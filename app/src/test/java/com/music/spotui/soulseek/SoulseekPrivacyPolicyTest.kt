package com.music.spotui.soulseek

import org.junit.Assert.*
import org.junit.Test

class SoulseekPrivacyPolicyTest {
    @Test fun p2pRequiresExplicitOptIn() {
        assertFalse(SoulseekPrivacyPolicy.enabled(false, true))
        assertFalse(SoulseekPrivacyPolicy.enabled(true, false))
        assertTrue(SoulseekPrivacyPolicy.enabled(true, true))
    }
}
