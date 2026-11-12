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
