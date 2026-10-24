package com.music.spotui.ui.notification

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.music.spotui.MainActivity
import com.music.spotui.R
import com.music.spotui.audio.RadioPlaybackController
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.preferences.RadioSessions
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.SpotifyWebPlayer
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Hosts a [MediaSession] over the app's single ExoPlayer (owned by [SongPlayer]).
 * This is what surfaces the track in the system notification center / lock screen
 * and routes the notification's transport controls (play/pause/seek/next/prev)
 * back into playback. Next/previous are wired to the in-app queue because our
 * player only ever holds one resolved stream at a time (YouTube URLs are resolved
 * lazily per track), so we advance the queue ourselves rather than via a playlist.
 *
 * It is a [MediaLibraryService] (not just a session service) so Android Auto can
 * browse the library — Liked Songs, Downloads, playlists and albums — and start
 * playback from the car.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var currentSongState: CurrentSongState
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var radioPlayback: RadioPlaybackController

    private val startRadioCommand = SessionCommand("com.music.spotui.action.START_RADIO", android.os.Bundle.EMPTY)
    private val toggleFavoriteCommand = SessionCommand("com.music.spotui.action.TOGGLE_FAVORITE", android.os.Bundle.EMPTY)
    private val toggleShuffleCommand = SessionCommand("com.music.spotui.action.TOGGLE_SHUFFLE", android.os.Bundle.EMPTY)
    private val toggleRepeatCommand = SessionCommand("com.music.spotui.action.TOGGLE_REPEAT", android.os.Bundle.EMPTY)
    private val addToQueueCommand = SessionCommand("com.music.spotui.action.ADD_TO_QUEUE", android.os.Bundle.EMPTY)

    // The first action remains Radio so existing Android Auto layouts keep it
    // ahead of optional actions that may move into the overflow menu.
    private val carCommands by lazy {
        listOf(startRadioCommand, toggleFavoriteCommand, toggleShuffleCommand, toggleRepeatCommand)
    }
    private var lastCarControlState: CarNowPlayingControlsPolicy.State? = null
    private var carControlsObserver: kotlinx.coroutines.Job? = null
    private val likedSongsPrefs by lazy {
        getSharedPreferences("LikedSongs", android.content.Context.MODE_PRIVATE)
    }
    private val likedSongsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        serviceScope.launch { refreshCarControls() }
    }

    private fun selectedCarTrack(): SongsModel? =
        CarRadioActionPolicy.currentTrack(currentSongState.queue.value, currentSongState.songId.value)

    private fun carControlState(): CarNowPlayingControlsPolicy.State {
        val current = selectedCarTrack()
        return CarNowPlayingControlsPolicy.State(
            hasTrack = current != null,
            hasMultipleTracks = currentSongState.queue.value.size > 1,
            shuffleActive = currentSongState.shuffle.value,
            smartShuffleActive = currentSongState.smartShuffle.value,
            repeatActive = currentSongState.repeat.value,
            liked = current != null && isSongLiked(applicationContext, current.id.toString()),
        )
    }

    private fun carControlButtons(state: CarNowPlayingControlsPolicy.State): List<CommandButton> =
        listOf(
            CommandButton.Builder()
                .setDisplayName(CarNowPlayingControlsPolicy.radioLabel(state))
                .setIconResId(R.drawable.ic_car_radio)
                .setSessionCommand(startRadioCommand)
                .setEnabled(state.hasTrack)
                .build(),
            CommandButton.Builder()
                .setDisplayName(CarNowPlayingControlsPolicy.favoriteLabel(state))
                .setIconResId(if (state.liked) R.drawable.ic_car_favorite_added else R.drawable.ic_car_favorite_add)
                .setSessionCommand(toggleFavoriteCommand)
                .setEnabled(state.hasTrack)
                .build(),
            CommandButton.Builder()
                .setDisplayName(CarNowPlayingControlsPolicy.shuffleLabel(state))
                .setIconResId(when {
                    state.smartShuffleActive -> R.drawable.ic_car_shuffle_smart
                    state.shuffleActive -> R.drawable.ic_car_shuffle_active
                    else -> R.drawable.ic_car_shuffle
                })
                .setSessionCommand(toggleShuffleCommand)
                .setEnabled(state.hasMultipleTracks)
                .build(),
            CommandButton.Builder()
                .setDisplayName(CarNowPlayingControlsPolicy.repeatLabel(state))
                .setIconResId(if (state.repeatActive) R.drawable.ic_car_repeat_active else R.drawable.ic_car_repeat)
                .setSessionCommand(toggleRepeatCommand)
                .setEnabled(state.hasTrack)
                .build(),
        )

    private fun refreshCarControls() {
        val state = carControlState()
        // Replacing layout on every song would disrupt some Android Auto hosts.
        // Publish only if actual action state or availability changed.
        if (state == lastCarControlState) return
        lastCarControlState = state
        mediaSession?.setCustomLayout(carControlButtons(state))
    }

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Android Auto browse cache: mediaId → track, and mediaId → the list it was
    // browsed from (so playing a track queues its whole playlist/album).
    private val trackById = java.util.concurrent.ConcurrentHashMap<String, SongsModel>()
    private val queueByTrackId = java.util.concurrent.ConcurrentHashMap<String, List<SongsModel>>()
    private val browseById = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()
    private val collectionByTrackId = java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.PlaybackCollection>()
    private var carPlayRequest = 0L
    private data class PendingVoice(val songs: List<SongsModel>, val generation: Long, val songId: Int, val request: Long)
    private var pendingVoice: PendingVoice? = null
    private var carSearchJob: kotlinx.coroutines.Job? = null
    private var searchWantsPlay = false
    private val voiceCache = object : LinkedHashMap<String, List<SongsModel>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SongsModel>>?): Boolean {
            if (size <= 8 || eldest == null) return false
            val prefix = "song/" + CarBrowsePolicy.encode("voice/" + CarBrowsePolicy.encode(eldest.key)) + "/"
            trackById.keys.removeAll { it.startsWith(prefix) }
            queueByTrackId.keys.removeAll { it.startsWith(prefix) }
            return true
        }
    }
    private var recentRefreshJob: kotlinx.coroutines.Job? = null
    private var playingQueueObserver: kotlinx.coroutines.Job? = null
    private val recentStores by lazy {
        listOf("spotui_home_frequency_v1", "spotui_radio_sessions").map { getSharedPreferences(it, 0) }
    }
    private val recentListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        recentRefreshJob?.cancel()
        recentRefreshJob = serviceScope.launch {
            kotlinx.coroutines.delay(200L)
            val recentCount = recentCarEntries().size
            mediaSession?.notifyChildrenChanged(CarBrowsePolicy.RECENT, recentCount, null)
            mediaSession?.notifyChildrenChanged(CarBrowsePolicy.RECENTS, recentCount, null)
            mediaSession?.notifyChildrenChanged(CarBrowsePolicy.RECENT_RADIOS, recentRadioEntries().size, null)
        }
    }
    private val artworkExecutor = com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
        java.util.concurrent.Executors.newFixedThreadPool(2))
    private var queuePlayer: QueueSessionPlayer? = null
    private var webPlayer: WebMediaPlayer? = null
    private var showingWeb = false
    private var resumingNotification = false
    private var resumeWatch: kotlinx.coroutines.Job? = null

    /** The service can be restarted without the Activity ever being opened. */
    private fun restoreLastSessionIfNeeded(): Boolean {
        val current = currentSongState.queue.value.firstOrNull { it.id == currentSongState.songId.value }
        if (current != null) {
            if (SongPlayer.exoPlayer?.mediaItemCount == 0) {
                com.music.spotui.data.preferences.loadLastPlayback(this)?.let { (savedSong, position) ->
                    if (savedSong.id == current.id) SongPlayer.setRestorePoint(current.url, position)
                }
            }
            return true
        }
        val (song, position) = com.music.spotui.data.preferences.loadLastPlayback(this) ?: return false
        val saved = com.music.spotui.data.preferences.loadPlaybackQueue(this)
        currentSongState.restore(com.music.spotui.audio.PlaybackRestorePolicy.snapshot(song, saved))
        val index = currentSongState.queue.value.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        currentSongState.updateSongState(song.coverUri, song.title, song.singer, false, song.id, index, song.album)
        SongPlayer.setRestorePoint(song.url, position)
        return true
    }

    // A receiver starts an Android foreground service. Stream resolution can take
    // longer than its startup deadline, so show a real, bounded "Resuming" state
    // until Media3 can take over with the normal music notification.
    private fun showResumingNotification() {
        if (resumingNotification) return
        resumingNotification = true
        val channel = "uspot_playback_resume"
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
                android.app.NotificationChannel(channel, "Playback", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = androidx.core.app.NotificationCompat.Builder(this, channel)
            .setSmallIcon(com.music.spotui.R.drawable.ic_playing)
            .setContentTitle("Sprotify • Resuming playback")
            .setContentText("Restoring your last song…")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_TRANSPORT)
            .build()
        androidx.core.app.ServiceCompat.startForeground(this,
            androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification,
            if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        resumeWatch = serviceScope.launch {
            kotlinx.coroutines.delay(65_000L)
            if (resumingNotification) {
                SongPlayer.pause()
                clearResumingNotification()
                stopSelf()
            }
        }
    }

    private fun clearResumingNotification() {
        if (!resumingNotification) return
        resumingNotification = false
        resumeWatch?.cancel(); resumeWatch = null
        androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == VoiceSearchPolicy.PLAY_ACTION) {
            if (!SongPlayer.isPlaying()) showResumingNotification()
            val request = ++carPlayRequest
            pendingVoice = null
            serviceScope.launch {
                if (prepareVoice(voiceRequest(intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty(), intent.extras), request)) {
                    playPendingVoice()
                } else clearResumingNotification()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && SongPlayer.exoPlayer?.mediaItemCount == 0) {
            showResumingNotification()
            if (!restoreLastSessionIfNeeded()) {
                clearResumingNotification()
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (resumingNotification) {
            // Empty-player notifications must not remove the startup notification.
            if (session.player.mediaItemCount == 0 || session.player.playbackState == Player.STATE_IDLE) return
            resumingNotification = false
            resumeWatch?.cancel(); resumeWatch = null
            // Same notification ID: Media3 replaces the loading notification.
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onCreate() {
        super.onCreate()
        SongPlayer.ensureCreated(this)
        // Let the player advance the in-app queue itself during a crossfade.
        SongPlayer.bindState(currentSongState)
        // Restore metadata/queue before creating layouts, without starting audio or Activity.
        restoreLastSessionIfNeeded()
        val base = SongPlayer.exoPlayer ?: return

        // Tapping the notification opens the app (back on the Now Playing screen).
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        webPlayer = WebMediaPlayer(mainLooper, currentSongState) { forward -> advance(forward,manual=true) }

        val projection = QueueSessionPlayer(mainLooper, snapshot = {
            val current = selectedCarTrack()
            QueueSessionPlayer.Snapshot(currentSongState.queue.value, currentSongState.songId.value,
                playingSourceName(), currentSongState.playingState.value || SongPlayer.isResolvingAudio(),
                if (showingWeb) webPlayer?.currentMediaItem?.mediaId == current?.id?.toString()
                else SongPlayer.exoPlayer?.currentMediaItem?.mediaId == current?.url)
        }, onSelect = ::selectQueueSong, onSeek = SongPlayer::seekTo,
            onPlay = { if (it) SongPlayer.play() else SongPlayer.pause() }, onStop = SongPlayer::stop)
        queuePlayer = projection
        projection.bind(base)
        mediaSession = MediaLibrarySession.Builder(this, wrap(projection), LibraryCallback())
            .setSessionActivity(sessionActivity)
            // AA can reset queue scroll on periodic legacy playback-state updates.
            // Keep event-driven updates; workaround for androidx/media#2192.
            .setPeriodicPositionUpdateEnabled(false)
            .setBitmapLoader(androidx.media3.session.CacheBitmapLoader(
                androidx.media3.datasource.DataSourceBitmapLoader(artworkExecutor,
                    androidx.media3.datasource.DefaultDataSource.Factory(this,
                        androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setConnectTimeoutMs(4_000).setReadTimeoutMs(4_000)), null, 512)))
            .setCommandButtonsForMediaItems(listOf(
                CommandButton.Builder()
                    .setDisplayName("Ievietot rindā")
                    .setIconResId(R.drawable.ic_add)
                    .setSessionCommand(addToQueueCommand)
                    .build()
            ))
            .setCustomLayout(carControlButtons(carControlState()))
            .build()
        recentStores.forEach { it.registerOnSharedPreferenceChangeListener(recentListener) }
        likedSongsPrefs.registerOnSharedPreferenceChangeListener(likedSongsListener)
        carControlsObserver = serviceScope.launch {
            snapshotFlow { carControlState() }
                .distinctUntilChanged()
                .collect { refreshCarControls() }
        }
        // Browse children only change when queue content/current row changes, not on play/pause.
        playingQueueObserver = serviceScope.launch {
            snapshotFlow { Pair(currentSongState.queue.value, currentSongState.songId.value) }
                .distinctUntilChanged()
                .collect { (queue, currentId) ->
                    mediaSession?.notifyChildrenChanged(CarBrowsePolicy.PLAYING_QUEUE,
                        CarBrowsePolicy.queueFromCurrent(queue, currentId).size, null)
                    webPlayer?.refresh()
                    queuePlayer?.refresh()
                }
        }
        serviceScope.launch {
            snapshotFlow { Pair(playingSourceName(), currentSongState.playingState.value) }
                .distinctUntilChanged().collect { queuePlayer?.refresh() }
        }

        // Keep the session player stable when crossfade promotes another audio engine.
        SongPlayer.onPlayerSwapped = { newPlayer ->
            if (!showingWeb) queuePlayer?.bind(newPlayer)
        }

        currentSongState.onSmartShuffleRequested = { prepareSmartShuffle() }
        SongPlayer.onPlaybackRecovered = { streamRecovery.recovered() }
        SongPlayer.onRetryRequested = { streamRecovery.recovered() }
        SongPlayer.onPlaybackRequestChanged = {
            streamRecovery.requestChanged()
            serviceScope.launch { queuePlayer?.refresh() }
        }
        SongPlayer.onStreamFailed = { query ->
            val ticket = recoveryTicket()?.takeIf { it.query == query }
            if (ticket != null) streamRecovery.failed(ticket)
            else com.music.spotui.data.preferences.PlaybackDiagnostics.record(applicationContext, "Recovery ignored", "Stale query or inactive play intent")
        }
        SongPlayer.onTrackEnded = { onTrackEnded() }
        SongPlayer.onQueueTailApproaching = { if (currentSongState.collection.value?.type == "radio") fillRadioQueue(false) else if (currentSongState.smartShuffle.value) prepareSmartShuffle() }
        SongPlayer.onRadioQualified = { rotated -> fillRadioQueue(rotated) }
        SongPlayer.onPreloadedTrackStarted = { query -> onPreloadedTrackStarted(query) }

        // As the hidden web player streams, keep the notification in sync and swap
        // the session between the web player (during web playback) and the ExoPlayer.
        SpotifyWebPlayer.onStateChanged = {
            syncSessionPlayer()
            if (showingWeb) {
                webPlayer?.refresh()
                // Reflect the web player's real play/pause state into the in-app UI
                // so the on-screen icon matches after the notification's pause.
                currentSongState.updatePlayingState(SpotifyWebPlayer.isPlaying)
            }
        }
    }

    /** Point the media session at whichever engine is currently producing audio. */
    private fun syncSessionPlayer() {
        val wantWeb = SongPlayer.webPlaybackActive()
        if (wantWeb == showingWeb) return
        showingWeb = wantWeb
        queuePlayer?.bind(if (wantWeb) webPlayer ?: return else SongPlayer.exoPlayer ?: return)
    }

    private fun selectQueueSong(id: Int) {
        val queue = currentSongState.queue.value
        val index = queue.indexOfFirst { it.id == id }
        val song = queue.getOrNull(index) ?: return
        pendingVoice = null; carPlayRequest++
        carSearchJob?.cancel(); carSearchJob = null; searchWantsPlay = false
        currentSongState.updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, song.album)
        SongPlayer.playSong(song.url, applicationContext)
        queuePlayer?.refresh()
    }

    /** Transport policy over the coherent session projection, including radio tail advance. */
    private fun wrap(base: Player): ForwardingPlayer = object : ForwardingPlayer(base) {
        // Our callbacks resolve tracks through SongPlayer and intentionally return no stream URIs.
        // Media3 must not clear an already-prepared stream when it applies that empty result.
        override fun setMediaItems(items: MutableList<MediaItem>, resetPosition: Boolean) {
            if (items.isNotEmpty()) super.setMediaItems(items, resetPosition)
        }
        override fun setMediaItems(items: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (items.isNotEmpty()) super.setMediaItems(items, startIndex, startPositionMs)
        }
        override fun prepare() { if (pendingVoice == null && carSearchJob == null) super.prepare() }
        override fun seekToDefaultPosition() { if (pendingVoice == null && carSearchJob == null) super.seekToDefaultPosition() }
        override fun play() {
            if (carSearchJob != null) { searchWantsPlay = true; return }
            if (playPendingVoice()) return
            if (base.mediaItemCount == 0 || base.playbackState == Player.STATE_IDLE) {
                if (!restoreLastSessionIfNeeded()) { clearResumingNotification(); return }
                showResumingNotification()
            }
            SongPlayer.play()
        }

        override fun pause() {
            pendingVoice = null; carPlayRequest++
            carSearchJob?.cancel(); carSearchJob = null; searchWantsPlay = false
            SongPlayer.pause()
            clearResumingNotification()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) play() else pause()
        }

        override fun stop() {
            pendingVoice = null; carPlayRequest++
            carSearchJob?.cancel(); carSearchJob = null; searchWantsPlay = false
            SongPlayer.stop()
            clearResumingNotification()
        }

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(COMMAND_SEEK_FORWARD)
                .add(COMMAND_SEEK_BACK)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_FORWARD, COMMAND_SEEK_BACK -> true
            else -> super.isCommandAvailable(command)
        }

        // ForwardingPlayer hands listeners the base player's commands, so on every
        // track change the session dropped the next we add above: the notification
        // kept "previous" (ExoPlayer has it) but lost "next" (#89).
        private val wrappedListeners = HashMap<Player.Listener, Player.Listener>()
        private val self = this

        override fun addListener(listener: Player.Listener) {
            // A Proxy, not Kotlin `by` delegation: Player.Listener's methods are all Java
            // defaults, which `by` does not forward, so the session saw no events at all.
            val wrapped = java.lang.reflect.Proxy.newProxyInstance(
                Player.Listener::class.java.classLoader,
                arrayOf(Player.Listener::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "onAvailableCommandsChanged" -> listener.onAvailableCommandsChanged(self.availableCommands)
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "CommandsFixingListener($listener)"
                    else -> method.invoke(listener, *(args ?: emptyArray()))
                }
            } as Player.Listener
            wrappedListeners[listener] = wrapped
            super.addListener(wrapped)
        }

        override fun removeListener(listener: Player.Listener) {
            super.removeListener(wrappedListeners.remove(listener) ?: listener)
        }

        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = advance(forward = true,manual=true)
        override fun seekToNextMediaItem() = advance(forward = true,manual=true)
        override fun seekToPrevious() = advance(forward = false)
        override fun seekToPreviousMediaItem() = advance(forward = false)
    }

    /** Advance the in-app queue one step in the given direction and start it. */
    private fun advance(forward: Boolean, manual: Boolean = false) {
        if(forward && manual)SongPlayer.recordManualSkip(applicationContext)
        val queue = currentSongState.queue.value
        if (queue.isEmpty()) return
        val curId = currentSongState.songId.value
        val cur = queue.indexOfFirst { it.id == curId }
            .let { if (it >= 0) it else currentSongState.songIndex.value }
            .coerceIn(0, queue.size - 1)
        if (forward && cur == queue.lastIndex) {
            if (currentSongState.collection.value?.type == "radio") { onTrackEnded(); return }
            val first = currentSongState.nextCollectionLoop() ?: return
            currentSongState.updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, first.album)
            SongPlayer.playSong(first.url, applicationContext)
            if (currentSongState.smartShuffle.value) prepareSmartShuffle()
            return
        }
        val nextIdx = if (forward) {
            if (cur < queue.size - 1) cur + 1 else 0
        } else {
            com.music.spotui.audio.QueueOrderPolicy.previousIndex(cur)
        }
        val song = queue[nextIdx]
        currentSongState.updateSongState(
            song.coverUri, song.title, song.singer, true,
            song.id, nextIdx, song.album
        )
        SongPlayer.playSong(song.url, applicationContext)
    }

    private var smartJob: kotlinx.coroutines.Job? = null
    private fun prepareSmartShuffle() {
        if (!currentSongState.smartShuffle.value || smartJob?.isActive == true) return
        val generation = currentSongState.generation
        val collection = currentSongState.collection.value?.key
        val songs = currentSongState.queue.value.filterNot { currentSongState.isSmartSuggestion(it.id) }
        val seeds = listOfNotNull(songs.firstOrNull(), songs.getOrNull(songs.size / 2), songs.lastOrNull())
            .map { it.spotifyTrackId }.filter { it.isNotBlank() }.distinct().take(3)
        smartJob = serviceScope.launch {
            val suggestions = withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(18_000L) {
                    seeds.map { seed -> async {
                        try { repository.provideRecommendations(listOf(seed)) }
                        catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            emptyList<SongsModel>()
                        }
                    } }.map { it.await() }.flatten().filter { !com.music.spotui.data.preferences.RadioSkipMemory.avoids(applicationContext,it) }
                }.orEmpty()
            }
            if (generation == currentSongState.generation && collection == currentSongState.collection.value?.key)
                currentSongState.addSmartSuggestions(suggestions.filter { com.music.spotui.data.preferences.RadioTaste.allowed(applicationContext, it) })
        }
    }

    /**
     * A track played to its end (screen on or off): repeat it, play the next queue
     * item, or at the end of the queue continue into Spotify radio. With no radio,
     * stop instead of wrapping around, which replayed the same song on a single.
     */
    private fun onTrackEnded() {
        if (currentSongState.repeat.value) {
            SongPlayer.seekTo(0)
            SongPlayer.play()
            return
        }
        val queue = currentSongState.queue.value
        val cur = queue.indexOfFirst { it.id == currentSongState.songId.value }
            .let { if (it >= 0) it else currentSongState.songIndex.value.coerceIn(0, (queue.size - 1).coerceAtLeast(0)) }
        if (cur in 0 until queue.size - 1) {
            advance(forward = true)
            return
        }
        if (currentSongState.collection.value?.type != "radio") {
            val first = currentSongState.nextCollectionLoop() ?: return
            currentSongState.updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, first.album)
            SongPlayer.playSong(first.url, applicationContext)
            if (currentSongState.smartShuffle.value) prepareSmartShuffle()
            return
        }
        val tasteRevision=com.music.spotui.data.preferences.RadioTaste.revision.value
        val generation = currentSongState.generation
        val endedId = currentSongState.songId.value
        serviceScope.launch {
            val fresh = continuationFor(generation, endedId, queue).await()
                .filter { next -> currentSongState.queue.value.none { it.id == next.id } }
            if (tasteRevision!=com.music.spotui.data.preferences.RadioTaste.revision.value || generation != currentSongState.generation || endedId != currentSongState.songId.value) return@launch
            val liveQueue = currentSongState.queue.value
            val liveIndex = liveQueue.indexOfFirst { it.id == endedId }
            if (liveIndex in 0 until liveQueue.size - 1) { advance(forward = true); return@launch }
            if (fresh.isEmpty()) {
                currentSongState.updatePlayingState(false)
                return@launch
            }
            currentSongState.updateQueue(currentSongState.queue.value + if (currentSongState.activeRadioSeed != null) fresh.take(10) else fresh, preserveSession = true)
            advance(forward = true)
        }
    }

    private fun recoveryTicket(): com.music.spotui.audio.StreamRetryController.Ticket? {
        val query = currentSongState.queue.value.firstOrNull { it.id == currentSongState.songId.value }?.url
        return query?.takeIf { it.isNotBlank() && SongPlayer.recoveryAllowed && SongPlayer.isCurrentRequest(it) }?.let {
            com.music.spotui.audio.StreamRetryController.Ticket(it, SongPlayer.playbackRequestId)
        }
    }

    private val streamRecovery by lazy {
        com.music.spotui.audio.StreamRetryController(serviceScope, ::recoveryTicket, SongPlayer::isPlaying,
            retry = { ticket ->
                com.music.spotui.data.preferences.PlaybackDiagnostics.record(applicationContext, "Recovery retry", "10 second backoff")
                val position = SongPlayer.getCurrentPosition()
                if (position > 0L) SongPlayer.setRestorePoint(ticket.query, position)
                SongPlayer.invalidateResolvedStream(ticket.query)
                SongPlayer.playSong(ticket.query, applicationContext)
            },
            skip = {
                val queue = currentSongState.queue.value
                val index = queue.indexOfFirst { it.id == currentSongState.songId.value }
                when {
                    index < 0 -> false
                    index < queue.lastIndex -> {
                        com.music.spotui.data.preferences.PlaybackDiagnostics.record(applicationContext, "Recovery skip", "Trying next queued song")
                        com.music.spotui.ui.components.PlaybackFeedback.skipping()
                        advance(forward = true)
                        true
                    }
                    currentSongState.collection.value?.type == "radio" -> {
                        // The final radio song failed: request more suggestions while
                        // keeping the current car session/track visible.
                        fillRadioQueue(false)
                        false
                    }
                    else -> false
                }
            }, onWaiting = { com.music.spotui.ui.components.PlaybackFeedback.retrying() })
    }

    private var radioFillJob: kotlinx.coroutines.Job? = null
    private fun fillRadioQueue(rotate: Boolean) {
        val station = currentSongState.activeRadioSeed ?: return
        if (currentSongState.collection.value?.type != "radio") return
        val before = currentSongState.queue.value
        val index = before.indexOfFirst { it.id == currentSongState.songId.value }
        if (index < 0 || (!rotate && before.size - index - 1 >= 10)) return
        if (radioFillJob?.isActive == true) { if (!rotate) return else radioFillJob?.cancel() }
        val tasteRevision=com.music.spotui.data.preferences.RadioTaste.revision.value
        val generation = currentSongState.generation
        val seed = com.music.spotui.data.preferences.RadioSessions.recommendationSeed(applicationContext, station)
        radioFillJob = serviceScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                    try {
                        repository.provideRadioRecommendations(station, seed)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        emptyList<SongsModel>()
                    }
                }.orEmpty()
            }
            if (tasteRevision!=com.music.spotui.data.preferences.RadioTaste.revision.value || generation != currentSongState.generation || station != currentSongState.activeRadioSeed ||
                seed != com.music.spotui.data.preferences.RadioSessions.recommendationSeed(applicationContext, station)) return@launch
            val live = currentSongState.queue.value
            val current = live.firstOrNull { it.id == currentSongState.songId.value } ?: return@launch
            val next = com.music.spotui.data.recommendation.EvolvingRadioPolicy.fill(current, live, candidates, rotate, currentSongState.playedThroughId, currentSongState.shuffle.value)
            val nextCurrent = next.indexOfFirst { it.id == current.id }
            if (nextCurrent in 0 until next.lastIndex) {
                val ended = SongPlayer.hasEnded()
                // A failed final stream is usually paused/READY rather than
                // ENDED. If new Radio suggestions arrive while recovery waits,
                // go to the next track instead of retrying the failed one.
                val failedCurrent = streamRecovery.isRecovering &&
                    !SongPlayer.isPlaying() && SongPlayer.isCurrentRequest(current.url)
                currentSongState.replaceRadioQueue(next)
                // Prepare the next Radio track only while the current one is
                // still audible. When advancing immediately, avoid two competing
                // remote lookups for the same track.
                if (!ended && !failedCurrent) {
                    next.getOrNull(nextCurrent + 1)?.let {
                        SongPlayer.prefetch(it.url, applicationContext)
                    }
                }
                if (!currentSongState.repeat.value && (ended || failedCurrent)) {
                    if (failedCurrent) streamRecovery.recovered()
                    advance(forward = true)
                }
            }
        }
    }

    private var continuationKey: String? = null
    private var continuation: kotlinx.coroutines.Deferred<List<SongsModel>>? = null

    private fun continuationFor(generation: Long, songId: Int, queue: List<SongsModel>): kotlinx.coroutines.Deferred<List<SongsModel>> {
        val key = "$generation:$songId:${com.music.spotui.data.preferences.RadioTaste.revision.value}"
        continuation?.takeIf { continuationKey == key }?.let { return it }
        continuation?.cancel()
        continuationKey = key
        val station = currentSongState.activeRadioSeed
        val evolving = station?.let { com.music.spotui.data.preferences.RadioSessions.recommendationSeed(applicationContext, it) }
        val seeds = com.music.spotui.data.recommendation.RadioQueuePolicy.seeds(station, queue)
        return serviceScope.async(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                try {
                    if (station != null && evolving != null) repository.provideRadioRecommendations(station, evolving)
                    else repository.provideRecommendations(seeds)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    emptyList<SongsModel>()
                }
            }.orEmpty()
        }.also { continuation = it }
    }

    /** Radio/autoplay recommendations are requested while the tail still has audio. */
    private fun prepareQueueContinuation() {
        val tasteRevision=com.music.spotui.data.preferences.RadioTaste.revision.value
        val generation = currentSongState.generation
        val songId = currentSongState.songId.value
        val queue = currentSongState.queue.value
        serviceScope.launch {
            val fresh = continuationFor(generation, songId, queue).await()
            if (tasteRevision!=com.music.spotui.data.preferences.RadioTaste.revision.value || generation != currentSongState.generation || songId != currentSongState.songId.value) return@launch
            val live = currentSongState.queue.value
            val index = live.indexOfFirst { it.id == songId }
            if (index < 0 || index != live.lastIndex) return@launch
            val candidates = fresh.filter { next -> live.none { it.id == next.id } }
            val unique = if (currentSongState.activeRadioSeed != null) candidates.take(10) else candidates
            if (unique.isNotEmpty()) currentSongState.updateQueue(live + unique, preserveSession = true)
        }
    }

    /** The player moved on to the queued-ahead track by itself: show it as current. */
    private fun onPreloadedTrackStarted(query: String) {
        val queue = currentSongState.queue.value
        val idx = queue.indexOfFirst { it.url == query }
        if (idx < 0) {
            // Removed from the queue after it was queued ahead: play the real next one.
            advance(forward = true)
            return
        }
        val song = queue[idx]
        currentSongState.updateSongState(
            song.coverUri, song.title, song.singer, true, song.id, idx, song.album,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    // ── Android Auto browse tree ──────────────────────────────────────────

    private companion object {
        const val ROOT = "root"
        const val NODE_LIKED = "liked"
        const val NODE_DOWNLOADS = "downloads"
        const val NODE_PLAYLISTS = "playlists"
        const val NODE_ALBUMS = "albums"
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(startRadioCommand)
                        .add(toggleFavoriteCommand)
                        .add(toggleShuffleCommand)
                        .add(toggleRepeatCommand)
                        .add(addToQueueCommand)
                        .build()
                )
                // Inherit the session layout so subsequent global updates reach this controller.
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == addToQueueCommand.customAction) {
                val mediaId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)
                val queued = mediaId?.let(trackById::get)?.let(currentSongState::addToQueue) == true
                return Futures.immediateFuture(
                    SessionResult(if (queued) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_BAD_VALUE)
                )
            }
            val song = selectedCarTrack()
            val status = when (customCommand.customAction) {
                startRadioCommand.customAction -> {
                    if (song == null) SessionResult.RESULT_ERROR_BAD_VALUE
                    else if (currentSongState.collection.value?.type == "radio") {
                        // Tapping active Radio again must not restart the current track.
                        SessionResult.RESULT_SUCCESS
                    } else if (radioPlayback.startRadioFromSong(song, applicationContext, serviceScope)) {
                        SessionResult.RESULT_SUCCESS
                    } else SessionResult.RESULT_ERROR_BAD_VALUE
                }
                toggleFavoriteCommand.customAction -> {
                    if (song == null) SessionResult.RESULT_ERROR_BAD_VALUE
                    else {
                        val nowSaved = !isSongLiked(applicationContext, song.id.toString())
                        if (nowSaved) addLikedSongId(applicationContext, song.id.toString())
                        else removeLikedSongId(applicationContext, song.id.toString())
                        currentSongState.updateLikeState(nowSaved)
                        // Same local preference and Spotify sync path as the phone menu.
                        SpotifySync.setTrackSaved(applicationContext, song.spotifyTrackId, nowSaved)
                        SessionResult.RESULT_SUCCESS
                    }
                }
                toggleShuffleCommand.customAction -> {
                    if (currentSongState.queue.value.size < 2) SessionResult.RESULT_ERROR_BAD_VALUE
                    else {
                        // Reuse the phone's three-step OFF -> ON -> SMART -> OFF cycle.
                        // Radio and Downloads keep the existing two-state exception.
                        currentSongState.cycleShuffle()
                        SessionResult.RESULT_SUCCESS
                    }
                }
                toggleRepeatCommand.customAction -> {
                    if (song == null) SessionResult.RESULT_ERROR_BAD_VALUE
                    else {
                        currentSongState.updateRepeatState(!currentSongState.repeat.value)
                        SessionResult.RESULT_SUCCESS
                    }
                }
                else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
            }
            refreshCarControls()
            return Futures.immediateFuture(SessionResult(status))
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            restoreLastSessionIfNeeded()
            // Like car browsing below, the saved list is an in-app queue of
            // search queries, not stream URIs. Media3 then invokes wrap.play(),
            // which uses the original resolver and saved seek point. Do not hand
            // ExoPlayer an expired CDN URL or a made-up playable query URI.
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            )
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val suggested = params?.isSuggested == true || params?.isRecent == true
            val rootId = when {
                params?.isOffline == true -> NODE_DOWNLOADS
                suggested -> CarBrowsePolicy.SUGGESTED
                else -> ROOT
            }
            return Futures.immediateFuture(LibraryResult.ofItem(folder(rootId, "Sprotify"), LibraryParams.Builder()
                .setSuggested(params?.isSuggested == true).setRecent(params?.isRecent == true)
                .setOffline(params?.isOffline == true)
                .setExtras(browseStyleExtras().apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                LibraryResult.ofItemList(ImmutableList.copyOf(CarBrowsePolicy.page(childrenOf(parentId), page, pageSize)), params)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) { LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO) }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            val item = resolveCarTrack(mediaId)?.let { playable(it, mediaId) }
                ?: browseById[mediaId]
                ?: (listOf(CarBrowsePolicy.Entry(ROOT, "Sprotify")) + (carRootEntries() + CarBrowsePolicy.home(playingSourceName()) + CarBrowsePolicy.browse() + CarBrowsePolicy.library())).firstOrNull { it.id == mediaId }?.let(::carItem)
                ?: recentCarEntries().firstOrNull { it.id == mediaId }?.let(::carItem)
            item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
            val songs = voiceSongs(VoiceSearchPolicy.Request(query))
            session.notifySearchResultChanged(browser, query, songs.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val songs = voiceCache[query] ?: voiceSongs(VoiceSearchPolicy.Request(query))
            val registered = registerTracks("voice/" + CarBrowsePolicy.encode(query), songs)
            val selected = VoiceSearchPolicy.page(songs, page, pageSize).map { song ->
                registered.first { it.mediaId == CarBrowsePolicy.trackId("voice/" + CarBrowsePolicy.encode(query), song) }
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(selected), params)
        }

        // Preserve the collection behind the selected row, not just the track's ID.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requested = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            val request = ++carPlayRequest
            pendingVoice = null
            carSearchJob?.cancel(); carSearchJob = null; searchWantsPlay = false
            val query = requested?.requestMetadata?.searchQuery
            if (query != null) {
                // A remote search must not occupy Media3's serial player-command queue.
                carSearchJob = serviceScope.launch {
                    try {
                        if (prepareVoice(voiceRequest(query, requested.requestMetadata.extras), request) && searchWantsPlay) {
                            playPendingVoice()
                        }
                    } finally {
                        if (request == carPlayRequest) { carSearchJob = null; searchWantsPlay = false }
                    }
                }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET))
            }
            return future {
                val id = requested?.mediaId
                if (id != null && CarBrowsePolicy.radioStation(id) != null) {
                    playCarRadio(id, request)
                } else {
                    val song = id?.let { resolveCarTrack(it) }
                    if (song == null) throw IllegalArgumentException("Track is no longer available. Refresh the car library.")
                    if (request == carPlayRequest) {
                        // A selection from the active queue must preserve its
                        // Radio station and use the LATEST live queue, not a
                        // browse snapshot from before Spotify added more songs.
                        val fromActiveQueue = id?.startsWith(
                            "song/" + CarBrowsePolicy.encode(CarBrowsePolicy.PLAYING_QUEUE) + "/"
                        ) == true
                        val live = currentSongState.queue.value
                        val queue = if (fromActiveQueue) live else queueByTrackId[id] ?: listOf(song)
                        if (queue.none { it.id == song.id }) return@future MediaSession.MediaItemsWithStartPosition(
                            emptyList(), C.INDEX_UNSET, C.TIME_UNSET
                        )
                        if (!fromActiveQueue) currentSongState.updateQueue(queue)
                        if (!fromActiveQueue) collectionByTrackId[id]?.let { collection ->
                            currentSongState.collection.value = collection
                            com.music.spotui.data.preferences.HomeHiddenItems.beginReplay(applicationContext, collection.type, collection.id)
                        }
                        val idx = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        currentSongState.updateSongState(song.coverUri, song.title, song.singer, true, song.id, idx, song.album)
                        if (!fromActiveQueue || !SongPlayer.isCurrentRequest(song.url))
                            SongPlayer.playSong(song.url, applicationContext)
                    }
                }
                MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
            }
        }
    }

    private fun voiceRequest(raw: String, extras: android.os.Bundle?) = VoiceSearchPolicy.Request(raw,
        extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_TITLE).orEmpty(),
        extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST).orEmpty(),
        extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM).orEmpty())

    private suspend fun voiceSongs(request: VoiceSearchPolicy.Request): List<SongsModel> {
        if (request.query.isBlank()) return emptyList()
        val songs = withContext(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                    VoiceSearchPolicy.rank(request, lastSuccess(repository.searchSongs(request.query)).orEmpty())
                }.orEmpty()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) { emptyList() }
        }
        voiceCache[request.raw] = songs
        return songs
    }

    private suspend fun prepareVoice(query: VoiceSearchPolicy.Request, request: Long): Boolean {
        val generation = currentSongState.generation
        val currentId = currentSongState.songId.value
        val songs = if (query.query.isBlank()) emptyList() else voiceSongs(query).take(1)
        if (request != carPlayRequest || generation != currentSongState.generation || currentId != currentSongState.songId.value) return false
        if (query.query.isNotBlank() && songs.isEmpty()) {
            android.widget.Toast.makeText(applicationContext, "Couldn't find that song. Check your connection or Spotify sign-in.", android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        pendingVoice = PendingVoice(songs, generation, currentId, request)
        return true
    }

    private fun playPendingVoice(): Boolean {
        val pending = pendingVoice ?: return false
        pendingVoice = null
        if (pending.request != carPlayRequest || pending.generation != currentSongState.generation || pending.songId != currentSongState.songId.value) return true
        val song = pending.songs.firstOrNull()
        if (song == null) {
            if (restoreLastSessionIfNeeded()) SongPlayer.play() else clearResumingNotification()
            return true
        }
        currentSongState.updateQueue(listOf(song))
        currentSongState.updateSongState(song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
        SongPlayer.playSong(song.url, applicationContext)
        return true
    }

    private suspend fun recentCarEntries(): List<CarBrowsePolicy.Entry> = CarBrowsePolicy.recent(recentCarItems())

    private suspend fun recentRadioEntries(): List<CarBrowsePolicy.Entry> {
        val saved = withContext(Dispatchers.IO) { RadioSessions.get(applicationContext).map { HomeItem.Radio(it) } }
        return CarBrowsePolicy.recentRadios(saved + recentCarItems())
    }

    private fun playingSourceName(): String =
        CarPlaybackContextPolicy.title(
            currentSongState.collection.value, currentSongState.title.value
        )

    private fun carRootEntries() = CarBrowsePolicy.root()

    private suspend fun carHomeFeed(): HomeFeedModel = withContext(Dispatchers.IO) {
        // Prefer the phone's already-loaded feed or persisted account-scoped
        // snapshot instantly. Waiting for the remote refresh even with cached
        // content caused a long spinner on Android Auto browse.
        val cached = Api.HomeCache.home
            ?: com.music.spotui.data.preferences.HomeSnapshotCache.readFeed(applicationContext)
        if (cached != null) return@withContext cached
        kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            lastSuccess(repository.provideHomeFeed())
        } ?: HomeFeedModel()
    }

    private suspend fun carHomeItems(): List<MediaItem> {
        val feed = carHomeFeed()
        val ready = withContext(Dispatchers.IO) {
            com.music.spotui.data.recommendation.HomePresentationBuilder.build(applicationContext,
                feed, repository.cachedHomeDiscoveries(), com.music.spotui.data.api.ProfileCache.name)
        }
        return CarHomePolicy.entries(ready).map { (entry, group) ->
            carItem(entry).let { item ->
                item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                    .setExtras(android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle()).apply {
                        putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                            if (entry.artwork.isBlank()) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                            else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                    }).build()).build()
            }
        }
    }

    private suspend fun carBrowseItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        // Bound artwork work. No periodic children notifications that reset host scrolling.
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        com.music.spotui.data.entity.BrowseCatalog.carCategories.map { category ->
            async {
                val artwork = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                    gate.acquire()
                    try { com.music.spotui.data.api.BrowseTileImages.coverFor(applicationContext, category.query) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { "" }
                    finally { gate.release() }
                }.orEmpty()
                carItem(CarBrowsePolicy.Entry("category/${CarBrowsePolicy.encode(category.query)}",
                    category.title, artwork)).let { item ->
                    item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                        .setExtras(browseStyleExtras().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                        }).build()).build()
                }
            }
        }.map { it.await() }
    }

    private suspend fun continueCarTracks(): List<SongsModel> = withContext(Dispatchers.IO) {
        val active = currentSongState.queue.value.firstOrNull { it.id == currentSongState.songId.value }
        val recent = com.music.spotui.data.preferences.getListeningHistory(applicationContext)
            .asSequence()
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .sortedByDescending { it.ts }
            .take(50)
            .map { SongsModel(it.songId, it.title, it.album, it.singer, it.image,
                it.url, it.spotifyTrackId, durationMs = it.durationMs) }
            .toList()
        CarBrowsePolicy.continueTracks(active, recent)
    }

    private suspend fun recentCarItems(): List<com.music.spotui.data.entity.HomeItem> = withContext(Dispatchers.IO) {
        val radios = com.music.spotui.data.preferences.RadioSessions.get(applicationContext).map { com.music.spotui.data.entity.HomeItem.Radio(it) }
        // Filter before the display cap, so older stations are not crowded out by albums.
        com.music.spotui.data.preferences.HomeFrequency.recentlyPlayed(applicationContext, radios, true, true, Int.MAX_VALUE)
    }

    private suspend fun playCarRadio(id: String, request: Long) {
        val station = CarBrowsePolicy.radioStation(id) ?: return
        val generation = currentSongState.generation
        val currentId = currentSongState.songId.value
        val snapshot = withContext(Dispatchers.IO) {
            val context = applicationContext
            val seed = com.music.spotui.data.preferences.RadioSessions.get(context)
                .firstOrNull { it.spotifyTrackId.ifBlank { it.id.toString() } == station } ?: return@withContext null
            val saved = com.music.spotui.data.preferences.RadioMemory.queue(context, station)
            val sameMode = saved?.mode == com.music.spotui.data.preferences.RadioTaste.mode(context, station)
            val candidates = saved?.songs.orEmpty().filterIndexed { index, song ->
                index == 0 || (sameMode && com.music.spotui.data.preferences.RadioTaste.allowed(context, song))
            }
            val anchors = com.music.spotui.data.preferences.RadioSessions.anchors(context, station)
                .map { com.music.spotui.data.preferences.RadioSessions.resolvedAnchor(context, it) }
            Triple(seed, CarBrowsePolicy.radioSongs(seed, com.music.spotui.data.preferences.RadioSessions.lastTrack(context, station), anchors, candidates), saved?.shuffled == true)
        } ?: return
        if (request != carPlayRequest || generation != currentSongState.generation || currentId != currentSongState.songId.value) return
        com.music.spotui.data.preferences.HomeHiddenItems.beginReplay(applicationContext, "radio", station)
        if (currentSongState.activeRadioSeed == station && currentSongState.queue.value.any { it.id == currentSongState.songId.value } && (SongPlayer.isPrepared() || (SongPlayer.webPlaybackActive() && SongPlayer.getDuration() > 0L))) {
            SongPlayer.play()
            return
        }
        val (seed, songs, shuffled) = snapshot
        val first = songs.firstOrNull() ?: return
        com.music.spotui.data.preferences.RadioSessions.remember(applicationContext, seed)
        val collection = com.music.spotui.data.entity.PlaybackCollection("radio:$station", "radio", station,
            com.music.spotui.data.preferences.RadioSessions.stationName(seed), seed.coverUri, seed.singer)
        currentSongState.restore(com.music.spotui.data.preferences.PlaybackQueueSnapshot(songs, collection, station, shuffled, null, playedThroughId = first.id))
        currentSongState.updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, first.album)
        SongPlayer.playSong(first.url, applicationContext)
        fillRadioQueue(false)
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> carRootEntries().map(::carItem)
        parentId == CarBrowsePolicy.SUGGESTED -> {
            val songs = continueCarTracks()
            withContext(Dispatchers.IO) {
                getSharedPreferences("car_suggestions", 0).edit()
                    .putString("tracks", CarSuggestionStore.encode(songs)).apply()
            }
            registerTracks(parentId, songs)
        }
        parentId == CarBrowsePolicy.HOME -> carHomeItems()
        parentId == CarBrowsePolicy.BROWSE -> carBrowseItems()
        parentId.startsWith("category/") -> {
            val query = android.net.Uri.decode(parentId.removePrefix("category/"))
            if (com.music.spotui.data.entity.BrowseCatalog.carCategories.none { it.query == query }) emptyList()
            else lastSuccess(repository.provideCategoryPlaylists(query)).orEmpty().map {
                folder("playlist/${CarBrowsePolicy.encode(it.spotifyId)}", it.name, it.coverUri)
            }
        }
        parentId == CarBrowsePolicy.PLAYING_QUEUE -> {
            val currentId = currentSongState.songId.value
            registerTracks(parentId, CarBrowsePolicy.queueFromCurrent(currentSongState.queue.value, currentId))
                .map { item ->
                    val song = trackById[item.mediaId]
                    item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                        .setExtras(android.os.Bundle().apply {
                            putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
                                getString(if (song?.id == currentId) R.string.car_playing_now else R.string.car_up_next))
                        }).build()).build()
                }
        }
        parentId == CarBrowsePolicy.CONTINUE ->
            registerTracks(parentId, continueCarTracks())
        parentId == CarBrowsePolicy.FOR_YOU ->
            CarBrowsePolicy.recommendations(carHomeFeed(), mixesOnly = false).map(::carItem)
        parentId == CarBrowsePolicy.MIXES ->
            CarBrowsePolicy.recommendations(carHomeFeed(), mixesOnly = true).map(::carItem)
        parentId == CarBrowsePolicy.RECENT || parentId == CarBrowsePolicy.RECENTS -> recentCarEntries().map(::carItem)
        parentId == CarBrowsePolicy.RECENT_RADIOS -> recentRadioEntries().map(::carItem)
        parentId == CarBrowsePolicy.LIBRARY -> CarBrowsePolicy.library().map(::carItem)
        parentId == NODE_LIKED ->
            registerTracks(NODE_LIKED, lastSuccess(repository.provideLikedSongs()).orEmpty())
        parentId == NODE_DOWNLOADS ->
            registerTracks(
                NODE_DOWNLOADS,
                com.music.spotui.data.preferences.getDownloadedSongs(applicationContext),
            )
        parentId == CarBrowsePolicy.ARTISTS ->
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) { repository.provideFollowedArtists() }.orEmpty()
            }
                .filter { it.id.isNotBlank() }.map {
                    folder("artist-id/${CarBrowsePolicy.encode(it.id)}", it.name, it.coverUri)
                }
        parentId.startsWith("artist-id/") -> {
            val id = android.net.Uri.decode(parentId.removePrefix("artist-id/"))
            registerTracks(parentId, lastSuccess(repository.provideArtistOverview("", id))?.topTracks.orEmpty().map { it.song })
        }
        parentId == NODE_PLAYLISTS ->
            listOf(carItem(CarBrowsePolicy.Entry(NODE_LIKED, "Liked Songs")),
                carItem(CarBrowsePolicy.Entry(NODE_DOWNLOADS, "Downloads"))) + libraryEntries().filter {
                it.isPlaylist && it.spotifyId != Api.LIKED_SONGS_ID && it.spotifyId != Api.DOWNLOADS_ID
            }.map { folder("playlist/${it.spotifyId}", it.name, it.coverUri) }
        parentId == NODE_ALBUMS ->
            libraryEntries().filter { !it.isPlaylist }.map {
                folder(
                    "album/${android.net.Uri.encode(it.name)}/${android.net.Uri.encode(it.artists)}",
                    it.name,
                    it.coverUri,
                )
            }
        parentId.startsWith("artist/") -> {
            val artist = android.net.Uri.decode(parentId.removePrefix("artist/"))
            registerTracks(parentId, lastSuccess(repository.provideArtistSongs(artist)).orEmpty())
        }
        parentId.startsWith("playlist/") -> {
            val playlistId = android.net.Uri.decode(parentId.removePrefix("playlist/"))
            val songs = lastSuccess(repository.providePlaylistSongs(playlistId))
            registerTracks(parentId, songs.orEmpty())
        }
        parentId.startsWith("album/") -> {
            val parts = parentId.removePrefix("album/").split('/')
            val name = android.net.Uri.decode(parts.getOrElse(0) { "" })
            val artist = android.net.Uri.decode(parts.getOrElse(1) { "" })
            registerTracks(parentId, lastSuccess(repository.provideAlbumSongs(name, artist)).orEmpty())
        }
        else -> emptyList()
    }

    private suspend fun libraryEntries() =
        lastSuccess(repository.provideLibrary()).orEmpty()

    /** Remote flow collection never runs on the session/main thread and is bounded. */
    private suspend fun <T> lastSuccess(flow: Flow<Response<T>>): T? = withContext(Dispatchers.IO) {
        kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            try {
                var result: T? = null
                flow.collect { if (it is Response.Success) result = it.data }
                result
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (error: Exception) { null }
        }
    }

    private suspend fun resolveCarTrack(id: String): SongsModel? {
        trackById[id]?.let { return it }
        if (!id.startsWith("song/")) return null
        val songId = id.substringAfterLast('/').toIntOrNull() ?: return null
        currentSongState.queue.value.firstOrNull { it.id == songId }?.let { return it }
        val saved = withContext(Dispatchers.IO) {
            CarSuggestionStore.decode(getSharedPreferences("car_suggestions", 0).getString("tracks", "[]").orEmpty())
        }
        return (saved + continueCarTracks()).firstOrNull { it.id == songId }?.also { trackById[id] = it }
    }

    private fun registerTracks(parentId: String, songs: List<SongsModel>): List<MediaItem> {
        val queue = songs.distinctBy { it.id }
        val metadata = browseById[parentId]?.mediaMetadata
        val collection = when {
            parentId == NODE_LIKED -> com.music.spotui.data.entity.PlaybackCollection("playlist:liked-songs", "playlist", "liked-songs", "Liked Songs", queue.firstOrNull()?.coverUri.orEmpty())
            parentId == NODE_DOWNLOADS -> com.music.spotui.data.entity.PlaybackCollection("local:downloads", "playlist", "downloaded-offline", "Downloads", queue.firstOrNull()?.coverUri.orEmpty())
            parentId.startsWith("playlist/") -> {
                val id = parentId.removePrefix("playlist/")
                com.music.spotui.data.entity.PlaybackCollection("playlist:$id", "playlist", id, metadata?.title?.toString() ?: "Playlist", metadata?.artworkUri?.toString().orEmpty())
            }
            parentId.startsWith("album/") -> {
                val parts = parentId.removePrefix("album/").split('/')
                val name = android.net.Uri.decode(parts.getOrElse(0) { "" })
                val artist = android.net.Uri.decode(parts.getOrElse(1) { "" })
                com.music.spotui.data.entity.PlaybackCollection("album:$name|$artist", "album", "$name|$artist", name, queue.firstOrNull()?.coverUri.orEmpty(), artist)
            }
            else -> null
        }
        return queue.map { song ->
            val id = CarBrowsePolicy.trackId(parentId, song)
            trackById[id] = song
            queueByTrackId[id] = queue
            if (collection != null) collectionByTrackId[id] = collection
            playable(song, id)
        }
    }

    private fun browseStyleExtras() = android.os.Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    private fun carItem(entry: CarBrowsePolicy.Entry): MediaItem = MediaItem.Builder().setMediaId(entry.id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setSubtitle(entry.subtitle)
            .setIsBrowsable(!entry.playable).setIsPlayable(entry.playable)
            .setExtras(browseStyleExtras().apply {
                if (entry.id == CarBrowsePolicy.LIBRARY) {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM)
                }
                if (entry.id in listOf(NODE_PLAYLISTS, NODE_ALBUMS, CarBrowsePolicy.ARTISTS)) {
                    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM)
                }
            })
            .apply {
                val icon = when (entry.id) {
                    CarBrowsePolicy.HOME -> R.drawable.ic_home_filled
                    CarBrowsePolicy.RECENTS -> R.drawable.ic_car_recents
                    CarBrowsePolicy.BROWSE -> R.drawable.ic_view_grid
                    CarBrowsePolicy.LIBRARY -> R.drawable.ic_library_big
                    CarBrowsePolicy.PLAYING_QUEUE -> R.drawable.ic_car_queue
                    CarBrowsePolicy.CONTINUE -> R.drawable.ic_car_recents
                    CarBrowsePolicy.FOR_YOU -> R.drawable.ic_car_for_you
                    CarBrowsePolicy.MIXES -> R.drawable.ic_car_shuffle
                    CarBrowsePolicy.RECENT_RADIOS -> R.drawable.ic_car_radio
                    NODE_LIKED -> R.drawable.ic_car_favorite_added
                    NODE_DOWNLOADS -> R.drawable.ic_download
                    NODE_PLAYLISTS -> R.drawable.ic_car_playlist
                    NODE_ALBUMS -> R.drawable.ic_car_album
                    CarBrowsePolicy.ARTISTS -> R.drawable.ic_car_artist
                    else -> R.drawable.ic_car_playlist
                }
                if (entry.artwork.isNotBlank()) setArtworkUri(android.net.Uri.parse(entry.artwork))
                else setArtworkUri(android.net.Uri.parse("android.resource://$packageName/$icon"))
            }.build())
        .build().also { browseById[entry.id] = it }

    private fun folder(id: String, title: String, coverUri: String = ""): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setExtras(browseStyleExtras())
                    .apply { if (coverUri.isNotBlank()) setArtworkUri(android.net.Uri.parse(coverUri)) }
                    .build(),
            )
            .build().also { browseById[id] = it }

    private fun playable(song: SongsModel, id: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setDisplayTitle(song.title)
                    .setArtist(song.singer)
                    .setSubtitle(song.singer)
                    .setDescription(song.singer)
                    .setAlbumTitle(song.album)
                    .setSupportedCommands(listOf(addToQueueCommand.customAction))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .apply { if (song.coverUri.isNotBlank()) setArtworkUri(android.net.Uri.parse(song.coverUri)) }
                    .build(),
            )
            .build()

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try {
                f.set(block())
            } catch (e: Exception) {
                f.setException(e)
            }
        }
        f.addListener({ if (f.isCancelled) job.cancel() }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        return f
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep audible music running when the UI is swiped away, like a music player.
        if (!resumingNotification && !streamRecovery.isRecovering && !SongPlayer.isPlaying() && SongPlayer.exoPlayer?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        clearResumingNotification()
        recentStores.forEach { it.unregisterOnSharedPreferenceChangeListener(recentListener) }
        likedSongsPrefs.unregisterOnSharedPreferenceChangeListener(likedSongsListener)
        carControlsObserver?.cancel()
        carControlsObserver = null
        currentSongState.onSmartShuffleRequested = null
        serviceScope.cancel()
        SongPlayer.onPlayerSwapped = null
        SongPlayer.onStreamFailed = null
        currentSongState.onSmartShuffleRequested = { prepareSmartShuffle() }
        streamRecovery.recovered()
        SongPlayer.onPlaybackRequestChanged = null
        SongPlayer.onPlaybackRecovered = null
        SongPlayer.onRetryRequested = null
        SongPlayer.onTrackEnded = null
        SongPlayer.onQueueTailApproaching = null
        SongPlayer.onRadioQualified = null
        SongPlayer.onPreloadedTrackStarted = null
        SpotifyWebPlayer.onStateChanged = null
        mediaSession?.release()
        queuePlayer?.release()
        queuePlayer = null
        artworkExecutor.shutdownNow()
        webPlayer?.release()
        webPlayer = null
        mediaSession = null
        SongPlayer.release()
        super.onDestroy()
    }
}
