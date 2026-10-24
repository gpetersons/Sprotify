package com.music.spotui.data.api

import android.content.Context
import com.music.spotui.data.security.SecureSecrets

/**
 * Stores the logged-in Spotify `sp_dc` cookie used to mint web access tokens.
 *
 * Set it once (e.g. from a settings screen) with [setSpDc]. To get yours: log in
 * at open.spotify.com, open devtools → Application → Cookies → copy `sp_dc`.
 */
object SpotifySession {
    private const val PREFS = "spotify_session"
    private const val KEY_SP_DC = "sp_dc"

    // Optional compile-time default so the app has data on first launch.
    // Leave blank to require runtime configuration.
    private const val DEFAULT_SP_DC = ""

    fun spDc(context: Context): String {
        return SecureSecrets.read(context, PREFS, KEY_SP_DC)?.takeIf { it.isNotBlank() } ?: DEFAULT_SP_DC
    }

    fun setSpDc(context: Context, value: String) {
        if (spDc(context) != value.trim()) { com.music.spotui.data.preferences.CreatedPlaylists.newSession(context); context.getSharedPreferences("LibraryCache",0).edit().clear().apply(); com.metrolist.spotify.SpotifyWebSession.clear(); ProfileCache.clear(); Api.HomeCache.clear(); com.music.spotui.data.preferences.HomeSnapshotCache.clear(context) }
        SecureSecrets.write(context, PREFS, KEY_SP_DC, value.trim())
    }
}
