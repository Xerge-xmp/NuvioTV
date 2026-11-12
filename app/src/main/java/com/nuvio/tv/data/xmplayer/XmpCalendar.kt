package com.nuvio.tv.data.xmplayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * XMPlayer's CALENDAR, in Nuvio (XMPlayer edition): the same three lanes the XMPlayer apps show —
 *  - RELEASES: the one dataset every XMPlayer surface reads (`https://api.xmplayer.app/v2/calendar`, TMDB release / air
 *    dates built once a day for every client). Public: no XMPlayer connection needed.
 *  - DVR: the profile's Cloud DVR recordings (`/v1/dvr`, dvr:read — scheduled, recording, recorded).
 *  - BELLS + OWN EVENTS: the profile's calendar bells and own events, the same ones its XMPlayer apps show
 *    (`/v1/calendar`, calendar:read; written with `/v1/events` types reminder / calendarEvent, calendar:write).
 * XMPlayer supplies the data; Nuvio paints it and opens a release on its own title page.
 */
@Serializable
data class XmpReleaseRow(
    /** theaters | digital | tv */
    val kind: String = "",
    val tmdbId: String = "",
    /** movie | series */
    val mediaType: String = "",
    val title: String = "",
    /** UTC midnight of the release DATE (a date, not a moment). */
    val dateUtc: Long? = null,
    val rating: Double? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val popularity: Double? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val placeholder: Boolean = false,
)

@Serializable
internal data class XmpReleasesDataset(
    val ok: Boolean = false,
    val seq: Long = 0,
    val region: String = "",
    val rows: List<XmpReleaseRow> = emptyList(),
    val overviews: Map<String, String> = emptyMap(),
)

@Serializable
internal data class XmpReleasesManifest(val seq: Long = 0)

data class XmpReleases(val rows: List<XmpReleaseRow>, val overviews: Map<String, String>)

/** The Releases dataset. One read per rebuild (the manifest's seq gates it), kept in memory. */
class XmpReleasesClient(private val http: OkHttpClient = DEFAULT_HTTP) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cached: XmpReleases? = null
    private var cachedSeq = 0L
    private var cachedRegion = ""
    private var checkedAt = 0L

    suspend fun releases(): XmpReleases = lock.withLock {
        val region = region()
        val now = System.currentTimeMillis()
        val have = cached
        if (have != null && region == cachedRegion && now - checkedAt < RECHECK_MS) return@withLock have
        withContext(Dispatchers.IO) {
            val seq = runCatching { json.decodeFromString<XmpReleasesManifest>(get("$BASE/v2/calendar/manifest")).seq }.getOrDefault(0L)
            checkedAt = now
            if (have != null && region == cachedRegion && seq != 0L && seq == cachedSeq) return@withContext have
            val ds = json.decodeFromString<XmpReleasesDataset>(get("$BASE/v2/calendar?region=$region"))
            // An empty build never replaces rows we have.
            if (ds.rows.isEmpty() && have != null) return@withContext have
            val out = XmpReleases(ds.rows.filter { it.tmdbId.isNotBlank() && it.dateUtc != null && it.kind.isNotBlank() }, ds.overviews)
            Log.i(TAG, "CAL| releases region=$region seq=${ds.seq} rows=${out.rows.size} overviews=${out.overviews.size}")
            cached = out; cachedSeq = ds.seq; cachedRegion = region
            out
        }
    }

    private fun get(url: String): String {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    /** The dataset's regions (US/CA/GB/AU); anything else reads the US build — as the XMPlayer apps do. */
    private fun region(): String {
        val c = runCatching { Locale.getDefault().country.uppercase(Locale.US) }.getOrDefault("")
        return if (c in setOf("US", "CA", "GB", "AU")) c else "US"
    }

    companion object {
        private const val TAG = "XmpCalendar"
        private const val BASE = "https://api.xmplayer.app"
        private const val RECHECK_MS = 30 * 60_000L
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    }
}

/** A bell or an own event of the profile (spec §1 — the same item in every XMPlayer app). */
@Serializable
data class XmpCalendarItem(
    /** '<kind>:<ref>:<profile>' (bell) or 'ev<ms>:<profile>' (own event). */
    val id: String = "",
    /** spotlight | sports (bells) · custom (own event) · reminder (a guide reminder) */
    val kind: String = "",
    /** A bell's subject: '<theaters|digital|tv>:<tmdbId>' for a release. */
    val ref: String = "",
    val title: String = "",
    val startAt: Long = 0,
    val leadMs: Long = 0,
    val allDay: Boolean = false,
    val weekly: Boolean = false,
    val remind: Boolean = true,
) {
    val isEvent: Boolean get() = kind == "custom"
}

/** XMPlayer's episode identity (the atlas): one show's episode on an air date, in TMDB's and the add-ons' numbering. */
@Serializable
internal data class XmpEpisodeNumber(val season: Int? = null, val episode: Int? = null)

@Serializable
internal data class XmpEpisodeEntry(val imdb: XmpEpisodeNumber? = null, val tmdb: XmpEpisodeNumber? = null)

@Serializable
internal data class XmpEpisodeSeries(val tmdb: String? = null, val imdb: String? = null)

@Serializable
internal data class XmpEpisodeAnswer(
    val series: XmpEpisodeSeries? = null,
    val found: Boolean = false,
    val ambiguous: Boolean = false,
    val matchedBy: String? = null,
    val episode: XmpEpisodeEntry? = null,
)

/** Where a TV release opens: the series (by its IMDb id when known) and the episode in the add-ons' numbering. */
data class XmpEpisodeTarget(val itemId: String, val season: Int?, val episode: Int?)

@Serializable
internal data class XmpCalendarResponse(val items: List<XmpCalendarItem> = emptyList())

@Serializable
internal data class XmpCalendarWriteResult(val index: Int? = null, val ok: Boolean = false, val id: String? = null, val error: String? = null)

@Serializable
internal data class XmpCalendarWriteResponse(val results: List<XmpCalendarWriteResult> = emptyList())

class XmpCalendarClient(private val api: XmpApiClient, private val catalog: XmpCatalogClient) {

    /** The profile's bells + own events. */
    suspend fun items(): List<XmpCalendarItem> =
        api.decode<XmpCalendarResponse>(api.get("/v1/calendar").body).items.filter { it.id.isNotBlank() && it.startAt > 0 }

    /**
     * THE EPISODE THAT AIRS ON [airDate] ("yyyy-mm-dd"), IN THE ADD-ONS' NUMBERING. TMDB and IMDb/Cinemeta number some
     * shows differently (TMDB's Detective Conan S1E1220 is Cinemeta's 34:33), and the stream add-ons are asked by the
     * IMDb side. XMPlayer's atlas answers both for a show + air date (`/v1/episode`); [title] breaks a tie. Null when it
     * has no single answer — the caller then opens the series without picking an episode.
     */
    suspend fun episodeOnDate(tmdbId: String, airDate: String, title: String): XmpEpisodeTarget? {
        val q = buildMap { put("tmdb", tmdbId); put("date", airDate); if (title.isNotBlank()) put("title", title) }
        val a = api.decode<XmpEpisodeAnswer>(api.get("/v1/episode", q).body)
        val imdbShow = a.series?.imdb?.takeIf { it.startsWith("tt") }
        val n = a.episode?.imdb?.takeIf { a.found && !a.ambiguous }
        val s = n?.season?.takeIf { it >= 0 }
        val e = n?.episode?.takeIf { it > 0 }
        Log.i("XmpCalendar", "CAL| episode tv:$tmdbId $airDate -> show=${imdbShow ?: "-"} imdb=${s ?: "-"}:${e ?: "-"} (${a.matchedBy ?: if (a.ambiguous) "ambiguous" else "none"})")
        if (imdbShow == null) return null
        return XmpEpisodeTarget(imdbShow, if (e != null) s else null, e)
    }

    /** Every recording of the profile: scheduled, recording and recorded (the calendar shows all three). */
    suspend fun recordings(): List<XmpRecording> = catalog.allRecordings()

    suspend fun setReminder(kind: String, ref: String, title: String, startAt: Long, leadMs: Long) {
        write(buildJsonObject {
            put("type", "reminder"); put("kind", kind); put("ref", ref); put("title", title)
            put("startAt", startAt); put("leadMs", leadMs)
        })
    }

    suspend fun removeReminder(kind: String, ref: String) {
        write(buildJsonObject { put("type", "reminder"); put("kind", kind); put("ref", ref); put("remove", true) })
    }

    /** Create (blank [id]) or change an own event; answers its id. */
    suspend fun saveEvent(id: String, title: String, startAt: Long, allDay: Boolean, weekly: Boolean, leadMs: Long, remind: Boolean): String {
        val r = write(buildJsonObject {
            put("type", "calendarEvent")
            if (id.isNotBlank()) put("id", id)
            put("title", title); put("startAt", startAt); put("allDay", allDay); put("weekly", weekly)
            put("leadMs", leadMs); put("remind", remind)
        })
        return r.id?.takeIf { it.isNotBlank() } ?: id
    }

    suspend fun deleteEvent(id: String) {
        write(buildJsonObject { put("type", "calendarEvent"); put("id", id); put("delete", true) })
    }

    private suspend fun write(event: kotlinx.serialization.json.JsonObject): XmpCalendarWriteResult {
        val body = buildJsonObject { put("events", buildJsonArray { add(event) }) }.toString()
        val res = api.decode<XmpCalendarWriteResponse>(api.post("/v1/events", body).body).results.firstOrNull()
            ?: throw XmpDecodingException()
        if (!res.ok) throw XmpApiException(400, res.error ?: "calendar_write_failed")
        return res
    }
}
