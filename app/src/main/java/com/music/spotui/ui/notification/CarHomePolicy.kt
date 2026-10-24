package com.music.spotui.ui.notification

import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.recommendation.HomePresentation

/** Collections directly on Home; history stays on Recents and native Queue stays on the player. */
object CarHomePolicy {
    fun entries(home: HomePresentation): List<Pair<CarBrowsePolicy.Entry, String>> {
        val seen = mutableSetOf<String>()
        val sections = listOf(HomeSection("Quick access", home.topTiles)) + home.sections
        return sections.filterNot { it.title.equals("Recently played", true) }
            .flatMap { section ->
                section.items.mapNotNull(CarBrowsePolicy::homeEntry)
                    .filter { it.id !in seen }.distinctBy { it.id }.take(8).onEach { seen.add(it.id) }
                    .map { it to section.title.ifBlank { "For you" } }
            }.take(40)
    }
}
