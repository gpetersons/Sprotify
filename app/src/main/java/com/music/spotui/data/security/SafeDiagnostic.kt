package com.music.spotui.data.security

/** Diagnostics are status-only; never include raw response bodies or URLs. */
internal object SafeDiagnostic {
    fun httpStatus(status: Int): String = "HTTP ${status.coerceIn(100, 599)}"
}
