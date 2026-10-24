package com.music.spotui.data.security
import org.junit.Assert.*
import org.junit.Test

/** Guard against printing secrets in the high-risk player/auth diagnostic paths. */
class SafeDiagnosticTest {
    @Test fun safeMessagesUseOnlyCodes() {
        assertEquals("HTTP 403", SafeDiagnostic.httpStatus(403))
        assertEquals("HTTP 500", SafeDiagnostic.httpStatus(500))
    }
}
