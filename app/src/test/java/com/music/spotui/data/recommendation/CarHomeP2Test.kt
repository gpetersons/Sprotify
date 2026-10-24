package com.music.spotui.data.recommendation

import com.music.spotui.data.entity.*
import com.music.spotui.ui.notification.CarBrowsePolicy
import org.junit.Assert.*
import org.junit.Test

class CarHomeP2Test {
    private fun song(id: Int, spotify: String = "spotify-$id") =
        SongsModel(id, "Song $id", "Album", "Artist", "cover", "query$id", spotify)

    @Test fun recommendedCollectionsCanBeOpenedAndDeduplicated() {
        val feed = HomeFeedModel(sections = listOf(
            HomeSection("Jump back in", listOf(
                HomeItem.Album("A/B", "cover", "Artist", "C/D"),
                HomeItem.Playlist("Daily mix", "cover", "Playlist", "abc/1"),
                HomeItem.Artist("Röyksopp", "cover", "artist123"),
                HomeItem.Playlist("Daily mix duplicate", "cover", "", "abc/1"))),
            HomeSection("Fresh mixes", listOf(
                HomeItem.Playlist("Mix", "cover", "", "mix123")))
        ))
        val all = CarBrowsePolicy.recommendations(feed, mixesOnly = false)
        assertEquals(listOf("album/A%2FB/C%2FD", "playlist/abc%2F1",
            "artist-id/artist123", "playlist/mix123"), all.map { it.id })
        assertTrue(all.all { !it.playable })
        assertEquals(listOf("playlist/mix123"),
            CarBrowsePolicy.recommendations(feed, mixesOnly = true).map { it.id })
    }

    @Test fun blankAndUnsupportedItemsAreNotShownAsDeadLinks() {
        val feed = HomeFeedModel(sections = listOf(HomeSection("Home", listOf(
            HomeItem.RecentTrack(song(1)),
            HomeItem.Playlist("No id", "", "", ""),
            HomeItem.Artist("", ""),
            HomeItem.Album("", "", "", ""),
            HomeItem.Playlist("Liked", "", "", "liked-songs")))))
        assertEquals(listOf("liked"), CarBrowsePolicy.recommendations(feed, false).map { it.id })
        assertTrue(CarBrowsePolicy.recommendations(feed, false, limit = 0).isEmpty())
    }

    @Test fun recentSongsKeepActiveFirstAndDoNotDuplicateSpotifyId() {
        val active = song(10)
        val duplicate = song(11).copy(spotifyTrackId = active.spotifyTrackId)
        val tracks = CarBrowsePolicy.continueTracks(active, listOf(duplicate, song(12), song(13)))
        assertEquals(listOf(10, 12, 13), tracks.map { it.id })
        assertEquals(2, CarBrowsePolicy.continueTracks(active, tracks, limit = 2).size)
    }

    @Test fun browsePagesAreBoundedAndDoNotOverflow() {
        val list = (1..20).toList()
        assertEquals(listOf(1,2,3), CarBrowsePolicy.page(list, 0, 3))
        assertEquals(listOf(4,5,6), CarBrowsePolicy.page(list, 1, 3))
        assertEquals(listOf(19,20), CarBrowsePolicy.page(list, 3, 6))
        assertTrue(CarBrowsePolicy.page(list, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertTrue(CarBrowsePolicy.page(list, -1, 5).isEmpty())
        assertTrue(CarBrowsePolicy.page(list, 0, 0).isEmpty())
    }
}
