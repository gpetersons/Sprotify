package com.music.spotui.data.security

import org.junit.Assert.*
import org.junit.Test

class SecretEnvelopeTest {
    @Test fun envelopeRoundTripsWithoutExposingPlaintext() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(32) { (it + 20).toByte() }
        val encoded = SecretEnvelope.pack(iv, ciphertext)
        assertFalse(encoded.contains("my-secret"))
        val opened = requireNotNull(SecretEnvelope.unpack(encoded))
        assertArrayEquals(iv, opened.first)
        assertArrayEquals(ciphertext, opened.second)
    }
    @Test fun invalidAndShortPayloadsAreRejected() {
        assertNull(SecretEnvelope.unpack("not_base64!"))
        assertNull(SecretEnvelope.unpack("YWJj"))
        assertNull(SecretEnvelope.unpack(""))
    }
}
