package com.music.spotui.data.preferences

import android.content.Context

private const val PREF = "Soulseek"

/**
 * This install's own Soulseek account, created on first use: the server registers
 * an unknown name on its first login. One account per install, because a name can
 * be online only once (a shared login kicks the other users off).
 */
fun soulseekCredentials(context: Context): Pair<String, String> {
    val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val user = p.getString("username", null)
    val pass = p.getString("password", null)
    if (!user.isNullOrBlank() && !pass.isNullOrBlank()) return user to pass
    val rnd = java.security.SecureRandom()
    fun token(n: Int) = (1..n).map { "abcdefghijklmnopqrstuvwxyz0123456789"[rnd.nextInt(36)] }.joinToString("")
    val created = "spotui-${token(10)}" to token(24)
    p.edit().putString("username", created.first).putString("password", created.second).apply()
    return created
}

/** Forget the account (e.g. its name was rejected) so the next use makes a new one. */
fun resetSoulseekCredentials(context: Context) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("username").remove("password").apply()
}

/** User switch for the Soulseek fallback (Settings). Off by default. */
fun isSoulseekEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", false)

fun setSoulseekEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    if (!enabled) com.music.spotui.soulseek.SoulseekClient.disconnect()
}
