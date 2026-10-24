package com.music.spotui.data.security
import org.junit.Assert.*
import org.junit.Test
class WebNavigationPolicyTest {
    @Test fun spotifyAuthenticationHostsOnly() {
        assertTrue(WebNavigationPolicy.spotify("https://accounts.spotify.com/login"))
        assertTrue(WebNavigationPolicy.spotify("https://open.spotify.com/"))
        assertFalse(WebNavigationPolicy.spotify("https://accounts.spotify.com.evil.example/login"))
        assertFalse(WebNavigationPolicy.spotify("http://accounts.spotify.com/login"))
        assertFalse(WebNavigationPolicy.spotify("https://evil@accounts.spotify.com/login"))
    }
    @Test fun youtubeLoginOnlyOnGoogleAndMusicYoutube() {
        assertTrue(WebNavigationPolicy.youtube("https://accounts.google.com/ServiceLogin"))
        assertTrue(WebNavigationPolicy.youtube("https://music.youtube.com/"))
        assertFalse(WebNavigationPolicy.youtube("https://music.youtube.com.evil.example"))
        assertFalse(WebNavigationPolicy.youtube("javascript:alert(1)"))
    }
    @Test fun playerDoesNotRunInjectedScriptsOnForeignPages() {
        assertTrue(WebNavigationPolicy.spotifyPlayer("https://open.spotify.com/"))
        assertFalse(WebNavigationPolicy.spotifyPlayer("https://accounts.spotify.com/"))
        assertFalse(WebNavigationPolicy.spotifyPlayer(null))
    }
}
