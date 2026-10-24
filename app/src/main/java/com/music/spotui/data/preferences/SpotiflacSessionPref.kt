package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.security.SecureSecrets

/**
 * Stores the signed-session obtained from SpotiFLAC's gated backend (experimental
 * path 1a): the server-issued session_id + session_secret used to HMAC-sign the
 * community download requests. Captured by the WebView verification screen after
 * the user solves the Turnstile themselves.
 */
private const val PREF = "SpotiflacSession"

fun saveSpotiflacSession(
    context: Context,
    sessionId: String,
    sessionSecret: String,
    installId: String,
    expiresAt: String,
) {
    SecureSecrets.write(context, PREF, "session_id", sessionId)
    SecureSecrets.write(context, PREF, "session_secret", sessionSecret)
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putString("install_id", installId)
        .putString("expires_at", expiresAt)
        .apply()
}

data class SpotiflacSession(
    val sessionId: String,
    val sessionSecret: String,
    val installId: String,
    val expiresAt: String,
)

fun getSpotiflacSession(context: Context): SpotiflacSession? {
    val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val id = SecureSecrets.read(context, PREF, "session_id")?.takeIf { it.isNotBlank() } ?: return null
    val secret = SecureSecrets.read(context, PREF, "session_secret")?.takeIf { it.isNotBlank() } ?: return null
    return SpotiflacSession(
        sessionId = id,
        sessionSecret = secret,
        installId = p.getString("install_id", "").orEmpty(),
        expiresAt = p.getString("expires_at", "").orEmpty(),
    )
}

fun hasSpotiflacSession(context: Context): Boolean = getSpotiflacSession(context) != null

fun clearSpotiflacSession(context: Context) {
    SecureSecrets.write(context, PREF, "session_id", null)
    SecureSecrets.write(context, PREF, "session_secret", null)
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
}
