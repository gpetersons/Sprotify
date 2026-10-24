package com.music.spotui.lossless

import org.junit.Assert.*
import org.junit.Test

class LosslessRegistryTest {
    @Test fun endpointsAreReviewableAndHttpsOnly() {
        assertEquals(listOf("https://tidal.anandserver.cfd"), LosslessRegistry.tidalEndpoints())
        assertEquals(listOf("https://amazon.anandserver.cfd"), LosslessRegistry.amazonEndpoints())
        (LosslessRegistry.tidalEndpoints() + LosslessRegistry.amazonEndpoints() +
            listOfNotNull(LosslessRegistry.communityDlUrl("tidal"), LosslessRegistry.communityDlUrl("amazon"), LosslessRegistry.communityVerifyUrl()))
            .forEach { assertTrue(it, LosslessRegistry.permitted(it)) }
        assertNull(LosslessRegistry.communityDlUrl("unknown"))
    }

    @Test fun unapprovedHostsAndTransportAreRejected() {
        listOf("http://tidal.anandserver.cfd/track", "https://evil.example/path",
            "https://tidal.anandserver.cfd.evil.example", "https://user@tidal.anandserver.cfd/",
            "https://tidal.anandserver.cfd:8443/", "javascript:alert(1)").forEach {
                assertFalse(it, LosslessRegistry.permitted(it))
            }
    }
}
