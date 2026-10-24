package com.music.spotui.data.security

import java.net.URI

/** Main-frame destinations, not third-party subresources, allowed for account navigation. */
internal object WebNavigationPolicy {
    fun spotify(url: String?): Boolean = allowed(url, setOf("spotify.com", "www.spotify.com", "accounts.spotify.com", "open.spotify.com"))
    fun youtube(url: String?): Boolean = allowed(url, setOf("accounts.google.com", "consent.google.com", "music.youtube.com", "www.youtube.com"))
    fun spotifyPlayer(url: String?): Boolean = allowed(url, setOf("open.spotify.com"))
    private fun allowed(url: String?, hosts: Set<String>): Boolean = try {
        val uri = URI(url ?: return false)
        uri.scheme == "https" && uri.host in hosts && uri.userInfo == null
    } catch (_: Exception) { false }
}
