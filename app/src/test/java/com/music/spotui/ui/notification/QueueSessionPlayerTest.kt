package com.music.spotui.ui.notification

import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@UnstableApi
class QueueSessionPlayerTest {
    private fun song(id: Int) = SongsModel(id, "Song $id", "Album $id", "Artist", "https://example.com/$id.jpg", "query$id", durationMs = 200_000)
    private class Engine : SimpleBasePlayer(Looper.getMainLooper()) {
        private var value = State.Builder().setAvailableCommands(Player.Commands.EMPTY)
            .setPlaylist(listOf(MediaItemData.Builder("stream").setDurationUs(200_000_000).build()))
            .setCurrentMediaItemIndex(0).setContentPositionMs(12000)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY).build()
        override fun getState() = value
        fun progress(position: Long, duration: Long = 200_000, playing: Boolean = true) {
            value = value.buildUpon().setContentPositionMs(position)
                .setPlaylist(listOf(MediaItemData.Builder("stream").setDurationUs(duration * 1000).build()))
                .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            invalidateState()
        }
        fun suppress() {
            value = value.buildUpon().setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS).build()
            invalidateState()
        }
        fun end() {
            value = value.buildUpon().setPlaybackState(Player.STATE_ENDED).build()
            invalidateState()
        }
        fun fail() {
            value = value.buildUpon().setPlaybackState(Player.STATE_IDLE)
                .setPlayerError(PlaybackException("failure", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)).build()
            invalidateState()
        }
    }
    @Test fun fullQueuePublishesCoherentTimelineAndIndexedSelectionWithoutChangingEngine() {
        val songs = listOf(song(1), song(2), song(3))
        var state = QueueSessionPlayer.Snapshot(songs, 1, "Radio", true, true)
        var selected = -1
        val engine = Engine()
        val player = QueueSessionPlayer(Looper.getMainLooper(), { state }, { selected = it }, {}, {}, {})
        player.bind(engine)
        assertEquals(3, player.mediaItemCount)
        assertEquals("Radio", player.playlistMetadata.title)
        assertEquals(0, player.currentMediaItemIndex)
        player.seekToDefaultPosition(2)
        assertEquals(3, selected)
        assertEquals(1, engine.mediaItemCount)
        assertEquals("stream", engine.currentTimeline.getWindow(0, Timeline.Window()).uid)
        player.setMediaItems(emptyList())
        assertEquals(3, player.mediaItemCount)
        var countInEvent = 0
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                countInEvent = timeline.windowCount
                assertEquals(player.mediaItemCount, countInEvent)
            }
        })
        state = state.copy(songs = songs + song(4))
        player.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, countInEvent)
        assertEquals(1, engine.mediaItemCount)
        player.release()
    }
    @Test fun newSelectionImmediatelyReplacesOldArtworkAndPositionWhileResolving() {
        var state = QueueSessionPlayer.Snapshot(listOf(song(1), song(2)), 1, "Album", true, true)
        val player = QueueSessionPlayer(Looper.getMainLooper(), { state }, {}, {}, {}, {})
        player.bind(Engine())
        assertEquals("Song 1", player.mediaMetadata.title)
        state = state.copy(currentId = 2, engineMatches = false)
        player.refresh()
        assertEquals("Song 2", player.mediaMetadata.title)
        assertEquals("https://example.com/2.jpg", player.mediaMetadata.artworkUri.toString())
        assertEquals(0L, player.currentPosition)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertEquals(1, player.currentMediaItemIndex)
        state = state.copy(songs = listOf(song(1), song(2).copy(coverUri = "")))
        player.refresh()
        assertEquals("android.resource", player.mediaMetadata.artworkUri?.scheme)
        assertEquals("Song 2", player.mediaMetadata.title)
        player.release()
    }
    @Test fun engineSuppressionErrorsAndSwapsReachTheSessionWithoutReplacingQueue() {
        val state = QueueSessionPlayer.Snapshot(listOf(song(1), song(2)), 1, "Playlist", true, true)
        val player = QueueSessionPlayer(Looper.getMainLooper(), { state }, {}, {}, {}, {})
        val first = Engine()
        player.bind(first)
        first.suppress(); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(player.isPlaying)
        val second = Engine()
        player.bind(second)
        assertTrue(player.isPlaying)
        assertEquals(2, player.mediaItemCount)
        second.fail(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertNotNull(player.playerError)
        player.release()
    }
    @Test fun progressPauseAndDurationJitterDoNotRepublishQueueButEditsDo() {
        var state = QueueSessionPlayer.Snapshot(listOf(song(1), song(2)), 1, "Radio", true, true)
        val engine = Engine()
        val player = QueueSessionPlayer(Looper.getMainLooper(), { state }, {}, {}, {}, {})
        player.bind(engine)
        assertEquals(2, player.mediaItemCount)
        shadowOf(Looper.getMainLooper()).idle()
        var changes = 0
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { changes++ }
        })
        repeat(10) {
            engine.progress(13_000L + it * 1000, 200_001L + it, it % 2 == 0)
            shadowOf(Looper.getMainLooper()).idle()
            player.refresh()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(0, changes)
        assertEquals(200_000L, player.duration)
        state = state.copy(songs = listOf(song(2), song(1), song(3)))
        player.refresh(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, changes)
        assertEquals(1, player.currentMediaItemIndex)
        state = state.copy(songs = listOf(song(2), song(1).copy(title = "Corrected"), song(3)))
        player.refresh(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, changes)
        assertEquals("Corrected", player.mediaMetadata.title)
        player.release()
    }
    @Test fun resolvedDurationDoesNotRevertWhenSelectionChanges() {
        var state = QueueSessionPlayer.Snapshot(listOf(song(1), song(2)), 1, "Radio", true, true)
        val engine = Engine()
        engine.progress(12000, 205000)
        val player = QueueSessionPlayer(Looper.getMainLooper(), { state }, {}, {}, {}, {})
        player.bind(engine)
        val before = player.currentTimeline
        state = state.copy(currentId = 2, engineMatches = false)
        player.refresh()
        assertEquals(before, player.currentTimeline)
        assertEquals(1, player.currentMediaItemIndex)
        player.release()
    }

    @Test fun radioContinuationAndSameTrackRetryKeepNowPlayingInBuffering() {
        var state = QueueSessionPlayer.Snapshot(listOf(song(1), song(2)), 1, "Radio", true, true)
        val engine = Engine()
        val player = QueueSessionPlayer(Looper.getMainLooper(), { state }, {}, {}, {}, {})
        player.bind(engine)
        // The logical request is pending before the retained engine publishes its end/pause.
        state = state.copy(awaitingStream = true)
        engine.end()
        engine.progress(200_000, playing = false)
        player.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.playWhenReady)
        assertEquals("Song 1", player.mediaMetadata.title)
        assertEquals(2, player.mediaItemCount)
        assertEquals(0L, player.currentPosition)
        // A radio fill keeps the timeline available, then selects the next song.
        state = state.copy(songs = state.songs + song(3), currentId = 2, engineMatches = false)
        player.refresh()
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertEquals("Song 2", player.mediaMetadata.title)
        assertEquals(3, player.mediaItemCount)
        // Explicit pause must still remove play intent while a lookup is pending.
        state = state.copy(playIntent = false)
        player.refresh()
        assertFalse(player.playWhenReady)
        player.release()
    }
}
