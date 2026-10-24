package com.music.spotui.ui.notification

import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SongsModel
import java.net.URLDecoder
import java.net.URLEncoder

/** Stable car browse IDs; collection history is independent of Home visibility preferences. */
object CarBrowsePolicy {
    const val SUGGESTED = "suggested-tracks"
    const val ARTISTS = "artists"
    const val HOME = "home"
    const val BROWSE = "browse"
    const val RECENTS = "recents"
    const val RECENT = "recent"
    const val RECENT_RADIOS = "recent-radios"
    const val LIBRARY = "library"
    const val PLAYING_QUEUE = "playing-queue"
    const val CONTINUE = "continue-listening"
    const val FOR_YOU = "for-you"
    const val MIXES = "made-for-you"
    data class Entry(val id: String, val title: String, val artwork: String = "", val subtitle: String = "", val playable: Boolean = false)
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun radioId(station: String) = "radio/" + encode(station)
    fun radioStation(id: String): String? = if (!id.startsWith("radio/")) null else
        runCatching { URLDecoder.decode(id.removePrefix("radio/"), "UTF-8").takeIf { it.isNotBlank() } }.getOrNull()
    fun trackId(parent: String, song: SongsModel) = "song/${encode(parent)}/${song.id}"
    /** Four stable, browsable tabs supported by the standard car media host. */
    fun root() = listOf(
        Entry(HOME, "Home"), Entry(RECENTS, "Recents"),
        Entry(BROWSE, "Browse"), Entry(LIBRARY, "Library"),
    )
    fun home(source: String) = listOf(
        Entry(PLAYING_QUEUE, "Playing queue", subtitle = source),
        Entry(CONTINUE, "Continue listening"), Entry(FOR_YOU, "Recommended for you"),
    )
    fun browse() = listOf(Entry(MIXES, "Made for you"), Entry(RECENT_RADIOS, "Your radios"))
    fun library() = listOf(Entry("playlists", "Playlists"), Entry("albums", "Albums"), Entry(ARTISTS, "Artists"))

    /** Match the actual resolved/preloaded query, never the collection display title. */
    fun albumForQuery(songs: List<SongsModel>, query: String, fallback: String): String =
        songs.firstOrNull { it.url == query }?.album ?: fallback

    /** Browse the current track and remaining queue, without changing playback order.
     * If the saved current ID is missing, show the whole queue as upcoming. */
    fun queueFromCurrent(songs: List<SongsModel>, currentId: Int): List<SongsModel> {
        val queue = songs.distinctBy { it.id }
        val index = queue.indexOfFirst { it.id == currentId }
        return queue.drop(index.coerceAtLeast(0))
    }
    /** Spotify supplies these as playlists, without a reliable Radio type flag.
     * Only radio-named playlist titles qualify; this does not assert Spotify ownership. */
    fun recentRadios(items: List<HomeItem>, limit: Int = 40): List<Entry> = recent(items.filter {
        it is HomeItem.Radio || (it is HomeItem.Playlist && isRadioPlaylist(it.name))
    }, limit).map { if (it.id.startsWith("playlist/")) it.copy(subtitle = "Spotify playlist") else it }
    fun isRadioPlaylist(name: String): Boolean = name.trim().let {
        it.equals("Radio", ignoreCase = true) || it.endsWith(" Radio", ignoreCase = true)
    }
    fun recent(items: List<HomeItem>, limit: Int = 40): List<Entry> = items.mapNotNull { item ->
        when (item) {
            is HomeItem.Radio -> Entry(radioId(item.seed.spotifyTrackId.ifBlank { item.seed.id.toString() }), item.name, item.imageUrl, "Sprotify Radio", true)
            is HomeItem.Playlist -> if (item.id.isBlank()) null else Entry(when (item.id) {
                "liked-songs" -> "liked"
                "downloads", "downloaded-offline" -> "downloads"
                else -> "playlist/${item.id}"
            }, item.name, item.imageUrl, "Playlist")
            is HomeItem.Album -> Entry("album/${encode(item.name)}/${encode(item.artists)}", item.name, item.imageUrl, "Album")
            else -> null
        }
    }.distinctBy { it.id }.take(limit.coerceIn(0, 40))

    /** Convert real Home feed collections to browseable Android Auto entries. */
    fun homeEntry(item: HomeItem): Entry? = when (item) {
        is HomeItem.Playlist -> if (item.id.isBlank()) null else Entry(
            when (item.id) {
                "liked-songs" -> "liked"
                "downloads", "downloaded-offline" -> "downloads"
                else -> "playlist/" + encode(item.id)
            }, item.name, item.imageUrl, item.subtitle.ifBlank { "Playlist" })
        is HomeItem.Album -> if (item.name.isBlank()) null else Entry(
            "album/" + encode(item.name) + "/" + encode(item.artists),
            item.name, item.imageUrl, item.artists.ifBlank { item.subtitle })
        is HomeItem.Artist -> if (item.name.isBlank()) null else Entry(
            if (item.id.isNotBlank()) "artist-id/" + encode(item.id) else "artist/" + encode(item.name),
            item.name, item.imageUrl, "Artist")
        is HomeItem.Radio -> Entry(
            radioId(item.seed.spotifyTrackId.ifBlank { item.seed.id.toString() }),
            item.name, item.imageUrl, "Sprotify Radio", true)
        is HomeItem.RecentTrack -> null
    }

    /** Same Spotify personalized feed as the phone, no fabricated content. */
    fun recommendations(feed: HomeFeedModel, mixesOnly: Boolean, limit: Int = 30): List<Entry> =
        feed.sections.asSequence()
            .filter { section ->
                !mixesOnly || section.title.contains("mix", true) ||
                    section.title.contains("made for you", true) ||
                    section.title.contains("uniquely yours", true)
            }
            .flatMap { it.items.asSequence() }
            .mapNotNull(::homeEntry)
            .distinctBy { it.id }
            .take(limit.coerceIn(0, 40))
            .toList()

    /** Live track first, then local recent playable tracks, avoiding duplicates. */
    fun continueTracks(active: SongsModel?, history: List<SongsModel>, limit: Int = 20): List<SongsModel> =
        (listOfNotNull(active) + history).filter { it.url.isNotBlank() }
            .distinctBy { it.spotifyTrackId.ifBlank { "local:" + it.id } }
            .take(limit.coerceIn(0, 40))

    /** Paged MediaLibrary results, including overflow-safe large page requests. */
    fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val offset = page.toLong() * pageSize
        if (offset >= items.size) return emptyList()
        return items.drop(offset.toInt()).take(pageSize)
    }

    /** Saved current + next ten first; otherwise start from last track or an original mix anchor. */
    fun radioSongs(seed: SongsModel, last: SongsModel?, anchors: List<SongsModel>, saved: List<SongsModel>): List<SongsModel> {
        val cached = saved.filter { it.url.isNotBlank() }.distinctBy { it.id }.take(11)
        if (cached.isNotEmpty()) return cached
        return listOfNotNull(last, anchors.firstOrNull(), seed).firstOrNull { it.url.isNotBlank() }?.let { listOf(it) }.orEmpty()
    }
}
