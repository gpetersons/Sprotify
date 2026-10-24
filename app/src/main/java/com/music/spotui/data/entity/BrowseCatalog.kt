package com.music.spotui.data.entity

/** Shared phone/car catalogue. Queries find playlists, not official Spotify category feeds. */
object BrowseCatalog {
    data class Category(val title: String, val color: Long, val query: String)
    val categories = listOf(
        Category("Music", 0xFFDC148C, "Top hits"),
        Category("Podcasts", 0xFF1E3264, "Podcast"),
        Category("Made For You", 0xFF8768A8, "Discover weekly"),
        Category("New Releases", 0xFFE8115B, "New releases"),
        Category("Pop", 0xFF8D67AB, "Pop"),
        Category("Hip-Hop", 0xFF477D95, "Hip hop"),
        Category("Rock", 0xFFE61E32, "Rock"),
        Category("Latin", 0xFFE1118C, "Latin"),
        Category("Country", 0xFFD84000, "Country"),
        Category("R&B", 0xFFBA5D07, "R&B"),
        Category("K-Pop", 0xFF148A08, "K-pop"),
        Category("Indie", 0xFF608108, "Indie"),
        Category("Dance/Electronic", 0xFF056952, "Electronic dance"),
        Category("Metal", 0xFF777777, "Metal"),
        Category("Chill", 0xFF1E3264, "Chill"),
        Category("Charts", 0xFF8C1932, "Top charts"),
        Category("Workout", 0xFF777777, "Workout"),
        Category("Jazz", 0xFF503750, "Jazz"),
    )
    // Podcasts are not supported by our music resolver; keyword searches are not personal mixes.
    val carCategories = categories.filterNot { it.title in setOf("Podcasts", "Made For You") }
}
