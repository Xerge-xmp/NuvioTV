package com.nuvio.tv.data.xmplayer

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/**
 * EPISODE PICTURES FROM THE ATLAS (MD/NUVIO_EPISODE_IMAGES_SPEC.md). Where an add-on's episode image does not load
 * (Cinemeta's links for Detective Conan answer 404), the card shows TMDB's still of THAT episode, matched by XMPlayer's atlas
 * across numberings (air date + title: Cinemeta S34E34 = TMDB S1E1220). One request per show + season, kept for the
 * session; keyed by the add-on's episode id ("tt0131179:34:34"), so a card finds its own picture without plumbing.
 */
object XmpEpisodeStills {
    private val stills = MutableStateFlow<Map<String, String>>(emptyMap())
    val byEpisodeId: StateFlow<Map<String, String>> = stills.asStateFlow()
    private val asked = HashSet<String>()

    /** True the first time a show + season is asked for in this session. */
    @Synchronized
    fun claim(imdbId: String, season: Int): Boolean = asked.add("$imdbId:$season")

    @Synchronized
    fun release(imdbId: String, season: Int) { asked.remove("$imdbId:$season") }

    fun put(map: Map<String, String>) {
        if (map.isNotEmpty()) stills.update { it + map }
    }

    fun forEpisode(map: Map<String, String>, videoId: String?): String? = videoId?.let { map[it] }

    /**
     * THE SERIES' OWN PICTURE, by show id (owner 2026-11-11: "when an episode has no image, it should show the series'
     * card"): the last step of an episode card — the episode's image, else the atlas's still, else this.
     */
    private val art = MutableStateFlow<Map<String, String>>(emptyMap())
    val seriesArt: StateFlow<Map<String, String>> = art.asStateFlow()

    fun putSeriesArt(showId: String, url: String?) {
        if (showId.isBlank() || url.isNullOrBlank()) return
        if (art.value[showId] != url) art.update { it + (showId to url) }
    }

    /** The picture of the series an episode id ("tt0131179:34:35") belongs to. */
    fun seriesArtFor(map: Map<String, String>, videoId: String?): String? {
        val id = videoId ?: return null
        return map.entries.firstOrNull { (show, _) -> id == show || id.startsWith("$show:") }?.value
    }
}

@Serializable
internal data class XmpSeasonEpisode(
    val airDate: String? = null,
    val title: String? = null,
    val tmdb: XmpEpisodeNumber? = null,
    val imdb: XmpEpisodeNumber? = null,
    val still: String? = null,
)

@Serializable
internal data class XmpSeasonAnswer(
    val series: XmpEpisodeSeries? = null,
    val episodes: List<XmpSeasonEpisode> = emptyList(),
)
