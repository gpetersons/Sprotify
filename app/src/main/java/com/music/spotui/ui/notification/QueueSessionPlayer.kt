package com.music.spotui.ui.notification

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.spotui.data.entity.SongsModel

/** A coherent session timeline over the logical queue. Audio remains owned by SongPlayer.
 * No unresolved query is ever installed into an ExoPlayer as a playable URI. */
@UnstableApi
class QueueSessionPlayer(
    looper: Looper,
    private val snapshot: () -> Snapshot,
    private val onSelect: (Int) -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onPlay: (Boolean) -> Unit,
    private val onStop: () -> Unit,
) : SimpleBasePlayer(looper) {
    data class Snapshot(
        val songs: List<SongsModel>, val currentId: Int, val source: String,
        val playIntent: Boolean, val engineMatches: Boolean,
        val awaitingStream: Boolean = false,
    )
    private var engine: Player? = null
    // Queue content must not change with position/buffering polls. Keep the first
    // resolved duration for a track instead of moving engine duration between rows.
    private val resolvedDurations = mutableMapOf<Pair<Int, String>, Long>()
    private var playlistKey: List<Pair<SongsModel, Long>> = emptyList()
    private var cachedPlaylist: List<MediaItemData> = emptyList()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
    }

    fun bind(player: Player) {
        if (engine === player) return
        engine?.removeListener(listener)
        engine = player
        player.addListener(listener)
        refresh()
    }

    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = snapshot()
        val songs = s.songs.distinctBy { it.id }
        val index = songs.indexOfFirst { it.id == s.currentId }.coerceAtLeast(0)
        val p = engine
        val matched = s.engineMatches && p != null && !s.awaitingStream
        val error = if (matched) p?.playerError else null
        val playback = when {
            songs.isEmpty() || error != null -> Player.STATE_IDLE
            matched -> p!!.playbackState
            s.playIntent -> Player.STATE_BUFFERING
            else -> Player.STATE_IDLE
        }
        val liveKeys = songs.map { it.id to it.url }.toSet()
        resolvedDurations.keys.retainAll(liveKeys)
        songs.getOrNull(index)?.let { song ->
            if (matched && p!!.duration > 0 && p.duration != C.TIME_UNSET) {
                resolvedDurations.putIfAbsent(song.id to song.url, p.duration)
            }
        }
        val key = songs.map { song -> song to (resolvedDurations[song.id to song.url]
            ?: song.durationMs.toLong().takeIf { it > 0 } ?: C.TIME_UNSET) }
        if (key != playlistKey) {
            cachedPlaylist = key.map { (song, duration) ->
                val metadata = MediaMetadata.Builder().setTitle(song.title).setDisplayTitle(song.title)
                    .setArtist(song.singer).setSubtitle(song.singer).setAlbumTitle(song.album)
                    .setIsPlayable(true).setIsBrowsable(false)
                    // A non-null URI also invalidates Media3 legacy's pending artwork callback
                    // when switching from remote artwork to a track with no cover.
                    .setArtworkUri(Uri.parse(song.coverUri.ifBlank {
                        "android.resource://${com.music.spotui.BuildConfig.APPLICATION_ID}/drawable/placeholder"
                    })).build()
                MediaItemData.Builder(song.id)
                    .setMediaItem(MediaItem.Builder().setMediaId(CarBrowsePolicy.trackId(CarBrowsePolicy.PLAYING_QUEUE, song))
                        .setMediaMetadata(metadata).build())
                    .setMediaMetadata(metadata).setIsSeekable(true)
                    .setDurationUs(if (duration == C.TIME_UNSET) C.TIME_UNSET else duration * 1000).build()
            }
            playlistKey = key
        }
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME
        ).build())
            .setPlaylist(cachedPlaylist).setCurrentMediaItemIndex(if (songs.isEmpty()) C.INDEX_UNSET else index)
            .setPlaylistMetadata(MediaMetadata.Builder().setTitle(s.source).build())
            .setPlaybackState(playback).setPlayerError(error)
            .setPlayWhenReady(if (matched) p!!.playWhenReady else s.playIntent,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(if (matched) p!!.playbackSuppressionReason else Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setIsLoading(playback == Player.STATE_BUFFERING || (playback == Player.STATE_READY && p?.isLoading == true))
            .setContentPositionMs(if (matched) p!!.currentPosition.coerceAtLeast(0) else 0L)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(if (matched) p!!.bufferedPosition.coerceAtLeast(0) else 0L))
            .setTotalBufferedDurationMs(PositionSupplier.getConstant(if (matched) p!!.totalBufferedDuration.coerceAtLeast(0) else 0L))
            .apply { if (p != null) { setPlaybackParameters(p.playbackParameters); setAudioAttributes(p.audioAttributes); setVolume(p.volume) } }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        onPlay(playWhenReady); return Futures.immediateVoidFuture()
    }
    override fun handlePrepare(): ListenableFuture<*> {
        if (snapshot().engineMatches) engine?.prepare()
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> { onStop(); return Futures.immediateVoidFuture() }
    override fun handleSetMediaItems(items: MutableList<MediaItem>, startIndex: Int, positionMs: Long): ListenableFuture<*> {
        // LibraryCallback has already selected/resolved the logical song.
        return Futures.immediateVoidFuture()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // Use the timeline the controller actually saw, then resolve by stable song ID.
        if (mediaItemIndex !in 0 until currentTimeline.windowCount) return Futures.immediateVoidFuture()
        val uid = currentTimeline.getWindow(mediaItemIndex, androidx.media3.common.Timeline.Window()).uid
        val songId = uid as? Int
        if (songId != null && songId != snapshot().currentId) onSelect(songId)
        else if (positionMs != C.TIME_UNSET && snapshot().engineMatches) onSeek(positionMs.coerceAtLeast(0))
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> {
        engine?.removeListener(listener); engine = null
        return Futures.immediateVoidFuture()
    }
}
