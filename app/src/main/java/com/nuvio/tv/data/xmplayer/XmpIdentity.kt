package com.nuvio.tv.data.xmplayer

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.LibraryEntryInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A movie or a series as Nuvio keys it. XMPlayer identifies every title by TMDB (`movie:603`, `tv:1396`) and carries the
 * other ids it knows; Nuvio keys by the catalog id — IMDb (`tt…`, Cinemeta) or `tmdb:…`. [contentId] is the one Nuvio
 * opens; [aliases] are every form either side may use, so a title matches whichever id a catalog gave it.
 */
@Serializable
data class XmpMedia(
    val type: String,                // "movie" | "series"
    val tmdb: String? = null,
    val imdb: String? = null,
    val tvdb: String? = null,
    val mal: String? = null,
    val anilist: String? = null,
    val title: String? = null,
    val year: String? = null,
    val poster: String? = null
) {
    /** True when XMPlayer gave the title an id a catalog can open. Media without one is never built ([media]). */
    val hasId: Boolean
        get() = listOf(imdb, tmdb, tvdb, mal, anilist).any { !it.isNullOrBlank() }

    val contentId: String
        get() = imdb?.takeIf(String::isNotBlank)
            ?: tmdb?.takeIf(String::isNotBlank)?.let { "tmdb:$it" }
            ?: tvdb?.takeIf(String::isNotBlank)?.let { "tvdb:$it" }
            ?: mal?.takeIf(String::isNotBlank)?.let { "mal:$it" }
            ?: anilist?.takeIf(String::isNotBlank)?.let { "anilist:$it" }
            ?: ""

    fun aliases(): Set<String> = buildSet {
        imdb?.takeIf(String::isNotBlank)?.let { add(it); add("imdb:$it") }
        tmdb?.takeIf(String::isNotBlank)?.let { add("tmdb:$it") }
        tvdb?.takeIf(String::isNotBlank)?.let { add("tvdb:$it") }
        mal?.takeIf(String::isNotBlank)?.let { add("mal:$it") }
        anilist?.takeIf(String::isNotBlank)?.let { add("anilist:$it") }
        if (hasId) add(contentId)
    }
}

/**
 * The movie or series an API item is about (for an episode: its series). Null for live channels, unknown types, and
 * titles XMPlayer has no id for (a title-only row has nothing a Nuvio catalog can open, so it stays out of Nuvio).
 */
internal fun XmpItem.media(poster: String? = null): XmpMedia? {
    val kind = when (type?.lowercase()) {
        "movie" -> "movie"
        "series", "tv", "episode" -> "series"
        else -> return null
    }
    return XmpMedia(
        type = kind,
        tmdb = ids?.tmdb ?: tmdb,
        imdb = ids?.imdb ?: imdb,
        tvdb = ids?.tvdb ?: tvdb,
        mal = ids?.mal,
        anilist = ids?.anilist,
        title = (if (type.equals("episode", ignoreCase = true)) seriesTitle ?: title else title ?: seriesTitle)?.trim()?.takeIf(String::isNotBlank),
        year = year?.takeIf(String::isNotBlank),
        poster = poster?.takeIf(String::isNotBlank)
    ).takeIf { it.hasId }
}

/** Nuvio's view of an id string (`tt…`, `tmdb:603`, `kitsu:…`) as XMPlayer item fields. */
private fun xmpIdFields(ids: TrackingExternalIds): List<Pair<String, String>> = buildList {
    ids.imdb?.takeIf { it.startsWith("tt", ignoreCase = true) }?.let { add("imdb" to it.lowercase()) }
    ids.tmdb?.let { add("tmdb" to it.toString()) }
    ids.tvdb?.takeIf(String::isNotBlank)?.let { add("tvdb" to it) }
    ids.mal?.let { add("mal" to it.toString()) }
    ids.anilist?.let { add("anilist" to it.toString()) }
    ids.kitsu?.let { add("kitsu" to it.toString()) }
}

/**
 * The API item for what Nuvio played or marked. An episode names its SERIES ids + season + episode (the API's rule);
 * an anime episode with no season (absolute numbering) goes by its MAL / AniList / Kitsu id + episode, which XMPlayer
 * translates to TMDB's numbering. Title and year always ride along: they are what the API matches when no id is known.
 */
internal fun TrackingMediaReference.toXmpItem(): JsonObject? {
    val idFields = xmpIdFields(ids)
    if (idFields.isEmpty() && title.isNullOrBlank()) return null
    val ep = episode
    return buildJsonObject {
        when {
            kind == TrackingMediaKind.MOVIE -> {
                put("type", "movie")
                idFields.forEach { (k, v) -> put(k, v) }
                title?.let { put("title", it) }
            }
            ep != null -> {
                put("type", "episode")
                val animeOnly = ep.season == null
                idFields.filter { !animeOnly || it.first in setOf("mal", "anilist", "kitsu") }
                    .ifEmpty { idFields }
                    .forEach { (k, v) -> put(k, v) }
                ep.season?.let { put("season", it) }
                put("episode", ep.number)
                title?.let { put("seriesTitle", it) }
            }
            else -> {
                put("type", "series")
                idFields.forEach { (k, v) -> put(k, v) }
                title?.let { put("title", it) }
            }
        }
        year?.let { put("year", it) }
    }
}

/** The API item for a title in Nuvio's library (a movie or a series). */
internal fun LibraryEntryInput.toXmpItem(): JsonObject {
    val parsed = parseTrackingExternalIds(itemId)
    val movie = itemType.equals("movie", ignoreCase = true)
    return buildJsonObject {
        put("type", if (movie) "movie" else "series")
        (imdbId ?: parsed.imdb)?.takeIf { it.startsWith("tt", ignoreCase = true) }?.let { put("imdb", it.lowercase()) }
        (tmdbId?.toLong() ?: parsed.tmdb)?.let { put("tmdb", it.toString()) }
        parsed.tvdb?.let { put("tvdb", it) }
        parsed.mal?.let { put("mal", it.toString()) }
        parsed.anilist?.let { put("anilist", it.toString()) }
        parsed.kitsu?.let { put("kitsu", it.toString()) }
        title.takeIf(String::isNotBlank)?.let { put("title", it) }
        year?.let { put("year", it) }
    }
}

/** Every id string Nuvio might use for this library input, to find it in XMPlayer's lists. */
internal fun LibraryEntryInput.aliases(): Set<String> = buildSet {
    add(itemId)
    val parsed = parseTrackingExternalIds(itemId)
    (imdbId ?: parsed.imdb)?.let { add(it); add("imdb:$it") }
    (tmdbId?.toLong() ?: parsed.tmdb)?.let { add("tmdb:$it") }
    parsed.tvdb?.let { add("tvdb:$it") }
    parsed.mal?.let { add("mal:$it") }
    parsed.anilist?.let { add("anilist:$it") }
}
