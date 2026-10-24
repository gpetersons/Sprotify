package com.music.spotui.ui.notification

import com.music.spotui.data.entity.*
import com.music.spotui.data.recommendation.HomePresentation
import org.junit.Assert.*
import org.junit.Test

class CarHomePolicyTest {
    private fun playlist(id: String) = HomeItem.Playlist("Playlist $id", "https://example.com/$id.jpg", "Owner", id)
    @Test fun homeShowsCollectionsAndTitlesWithoutNavigationOrHistoryDuplicates() {
        val home = HomePresentation(listOf(playlist("a")), listOf(
            HomeSection("Recently played", listOf(playlist("history"))),
            HomeSection("Your mixes", listOf(playlist("a"), playlist("b"))),
            HomeSection("Artists", listOf(HomeItem.Artist("Artist", "cover", "artist-id")))
        ), true)
        val entries = CarHomePolicy.entries(home)
        assertEquals(listOf("playlist/a", "playlist/b", "artist-id/artist-id"), entries.map { it.first.id })
        assertEquals(listOf("Quick access", "Your mixes", "Artists"), entries.map { it.second })
        assertTrue(entries.all { it.first.artwork.isNotBlank() })
    }
    @Test fun browseUsesSharedMusicCategoriesWithoutPretendingKeywordSearchIsPersonalized() {
        assertTrue(BrowseCatalog.carCategories.all { it in BrowseCatalog.categories })
        assertTrue(BrowseCatalog.carCategories.any { it.title == "Rock" })
        assertTrue(BrowseCatalog.carCategories.none { it.title in setOf("Podcasts", "Made For You") })
        assertEquals(BrowseCatalog.carCategories.size, BrowseCatalog.carCategories.map { it.query }.toSet().size)
    }
    @Test fun emptyFeedDoesNotInventRecommendations() {
        assertTrue(CarHomePolicy.entries(HomePresentation(emptyList(), emptyList(), false)).isEmpty())
    }
}
