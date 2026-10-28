package com.music.spotui.data.recommendation

import com.music.spotui.audio.TrackEndGate
import com.music.spotui.ui.theme.ThemeChoicePolicy
import org.junit.Assert.*
import org.junit.Test

class ThemeAndTrackEndTest {
    @Test fun deviceThemeWinsOverManualChoice() {
        for (manual in listOf(false, true)) {
            assertFalse(ThemeChoicePolicy.dark(true, manual, false))
            assertTrue(ThemeChoicePolicy.dark(true, manual, true))
        }
    }
    @Test fun manualThemeSurvivesDeviceChanges() {
        for (device in listOf(false, true)) {
            assertFalse(ThemeChoicePolicy.dark(false, false, device))
            assertTrue(ThemeChoicePolicy.dark(false, true, device))
        }
    }
    @Test fun listenerAndWatchdogOnlyAdvanceOnce() {
        val gate = TrackEndGate()
        assertTrue(gate.take(true, true, false))
        assertFalse(gate.take(true, true, false))
    }
    @Test fun failedCrossfadeCanStillAdvanceAfterOutgoingEnds() {
        val gate = TrackEndGate()
        assertFalse(gate.take(true, true, true))
        assertTrue(gate.take(true, true, false))
    }
    @Test fun clearingMediaOrBufferingDoesNotSkipASong() {
        val gate = TrackEndGate()
        assertFalse(gate.take(true, false, false))
        assertFalse(gate.take(false, true, false))
        assertTrue(gate.take(true, true, false))
    }
    @Test fun repeatAndNewTracksCanFinishAgain() {
        val gate = TrackEndGate()
        assertTrue(gate.take(true, true, false))
        gate.reset()
        assertTrue(gate.take(true, true, false))
    }
    @Test fun retainedEndedStreamCannotConsumeNewSongsDuringSlowLookup() {
        val gate = TrackEndGate()
        assertTrue(gate.take(true, true, false)) // A ends, select B.
        gate.beginRequest()
        repeat(40) { // Ten seconds of the existing 250 ms position watchdog.
            gate.reset() // Even a delayed READY event cannot attach the old stream.
            assertFalse(gate.take(true, true, false))
            assertFalse(gate.ownsMedia("A", "B"))
        }
        gate.streamInstalled()
        assertTrue(gate.ownsMedia("B", "B"))
        assertFalse(gate.ownsMedia("A", "B"))
        assertTrue(gate.take(true, true, false))
        assertFalse(gate.take(true, true, false))
    }
    @Test fun retryOfSameQueryMustInstallItsOwnStreamBeforeItCanEnd() {
        val gate = TrackEndGate()
        gate.beginRequest()
        assertFalse(gate.ownsMedia("A", "A"))
        assertFalse(gate.take(true, true, false))
        gate.streamInstalled()
        assertTrue(gate.ownsMedia("A", "A"))
        assertTrue(gate.take(true, true, false))
    }
}
