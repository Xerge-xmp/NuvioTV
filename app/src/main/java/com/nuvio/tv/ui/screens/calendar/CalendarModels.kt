package com.nuvio.tv.ui.screens.calendar

import androidx.compose.ui.graphics.Color
import com.nuvio.tv.data.xmplayer.XmpCalendarItem
import com.nuvio.tv.data.xmplayer.XmpRecording
import com.nuvio.tv.data.xmplayer.XmpReleaseRow
import java.util.Calendar
import java.util.TimeZone

/**
 * One calendar entry — XMPlayer's calendar model (its ForkCalendar.Entry), the three lanes Nuvio shows:
 * a RELEASE (TMDB release / air date), a DVR recording, an OWN EVENT. Bells ride on releases (and events alert themselves).
 */
data class CalEntry(
    /** release | dvr | custom */
    val kind: String,
    val startUtc: Long,
    val allDay: Boolean,
    val title: String,
    /** The profile's bell on this entry (minutes-before × 60000), null = no bell. */
    val reminderLeadMs: Long? = null,
    // release
    val sub: String = "", val tmdbId: String = "", val mediaType: String = "", val rating: Double? = null,
    val season: Int? = null, val episode: Int? = null, val episodeTitle: String = "",
    val poster: String = "", val backdrop: String = "", val popularity: Double? = null, val overview: String = "",
    // own event
    val id: String = "", val weekly: Boolean = false, val remind: Boolean = true,
    // dvr
    val recording: XmpRecording? = null,
) {
    /** Legend key: movie / tv (releases by media type), dvr, custom. */
    val kindKey: String get() = when (kind) { "release" -> if (mediaType == "series") "tv" else "movie"; else -> kind }

    /** The bell's subject (XMPlayer ids: kind 'spotlight', ref '<sub>:<tmdbId>'). */
    val bellRef: Pair<String, String>? get() = if (kind == "release" && tmdbId.isNotBlank()) "spotlight" to "$sub:$tmdbId" else null

    /** A date-only entry's bell goes off at LOCAL 9:00 of that day — XMPlayer's rule. */
    fun reminderStart(): Long {
        if (!allDay) return startUtc
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = startUtc }
        return Calendar.getInstance().apply {
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Day bucket "yyyy-mm-dd": a release DATE buckets by its UTC date; everything else by the local day. */
    fun dayKey(): String {
        val c = if (allDay && kind == "release") Calendar.getInstance(TimeZone.getTimeZone("UTC")) else Calendar.getInstance()
        c.timeInMillis = startUtc
        return dayKeyOf(c)
    }

    val isRecording: Boolean get() = recording?.status == "recording"
    val isRecorded: Boolean get() = recording?.status == "completed"
}

internal const val DAY_MS = 86_400_000L
internal const val WEEK_MS = 7 * DAY_MS

fun dayKeyOf(c: Calendar): String =
    "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

/** XMPlayer's legend colours. */
internal val KIND_COLORS = mapOf(
    "movie" to Color(0xFF60A5FA), "tv" to Color(0xFF34D399), "dvr" to Color(0xFFEC4899), "custom" to Color(0xFFA78BFA),
)
internal val KIND_ORDER = listOf("movie", "tv", "dvr", "custom")

internal fun releaseEntries(rows: List<XmpReleaseRow>, overviews: Map<String, String>, bells: Map<String, Long>): List<CalEntry> =
    rows.map { r ->
        CalEntry(
            kind = "release", startUtc = r.dateUtc ?: 0L, allDay = true, title = r.title,
            reminderLeadMs = bells["${r.kind}:${r.tmdbId}"],
            sub = r.kind, tmdbId = r.tmdbId, mediaType = r.mediaType, rating = r.rating,
            season = r.season, episode = r.episode, episodeTitle = r.episodeTitle.orEmpty(),
            poster = r.poster.orEmpty(), backdrop = r.backdrop.orEmpty(), popularity = r.popularity,
            overview = overviews[r.tmdbId].orEmpty(),
        )
    }

internal fun recordingEntries(list: List<XmpRecording>): List<CalEntry> =
    list.mapNotNull { r ->
        // a canceled or failed schedule is not on the calendar (the API keeps hundreds of them)
        if (r.status?.lowercase() in setOf("canceled", "cancelled", "failed", "deleted")) return@mapNotNull null
        val start = r.startAt?.takeIf { it > 0 } ?: return@mapNotNull null
        CalEntry(kind = "dvr", startUtc = start, allDay = false, title = r.title?.ifBlank { null } ?: r.channelName.orEmpty(), recording = r)
    }

/** Own events in [winStart, winEnd); a weekly one appears every week of the window (XMPlayer's expansion). */
internal fun eventEntries(items: List<XmpCalendarItem>, winStart: Long, winEnd: Long): List<CalEntry> {
    val out = ArrayList<CalEntry>()
    for (it in items) {
        if (!it.isEvent) continue
        fun entry(at: Long) = CalEntry(kind = "custom", startUtc = at, allDay = it.allDay, title = it.title,
            reminderLeadMs = if (it.remind) it.leadMs else null, id = it.id, weekly = it.weekly, remind = it.remind)
        if (!it.weekly) { if (it.startAt in winStart until winEnd) out += entry(it.startAt); continue }
        var at = it.startAt
        if (at < winStart) at += ((winStart - at) / WEEK_MS) * WEEK_MS
        while (at < winEnd) { if (at >= winStart) out += entry(at); at += WEEK_MS }
    }
    return out
}

/** The bells on releases, by '<sub>:<tmdbId>'. */
internal fun releaseBells(items: List<XmpCalendarItem>): Map<String, Long> =
    items.filter { it.kind == "spotlight" && it.ref.isNotBlank() }.associate { it.ref to it.leadMs }

/** The agenda's order: all-day first, then by time (XMPlayer's default). */
internal val ENTRY_ORDER: Comparator<CalEntry> = compareBy({ !it.allDay }, { it.startUtc })

/** A day cell shows a VARIETY across kinds, not the first three (XMPlayer's pickVariety). */
internal fun pickVariety(entries: List<CalEntry>, max: Int): List<CalEntry> {
    if (entries.size <= max) return entries
    val lanes = LinkedHashMap<String, ArrayDeque<CalEntry>>()
    for (e in entries) lanes.getOrPut(e.kindKey) { ArrayDeque() }.addLast(e)
    val out = ArrayList<CalEntry>(max)
    while (out.size < max) {
        var took = false
        for (q in lanes.values) {
            if (q.isEmpty()) continue
            out += q.removeFirst(); took = true
            if (out.size == max) break
        }
        if (!took) break
    }
    return out
}

internal fun chipLabel(e: CalEntry): String =
    e.title + if (e.kind == "release" && e.mediaType == "series" && e.season != null && e.episode != null) " S${e.season}E${e.episode}" else ""

/** A chip's terse time ("6p", "10:30a"; 24-hour "18:00"). */
internal fun chipTime(ms: Long, clock24: Boolean): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val m = c.get(Calendar.MINUTE)
    if (clock24) return "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), m)
    val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    return (if (m == 0) "$h" else "%d:%02d".format(h, m)) + (if (c.get(Calendar.AM_PM) == Calendar.PM) "p" else "a")
}

/** The Releases tab — XMPlayer's lanes (Streaming = digital, In theaters, TV grouped by show) and sorts. */
enum class RelLane { STREAMING, THEATERS, TV }
enum class RelSort { DATE, POPULAR, RATING }

internal fun releasesView(entries: List<CalEntry>, lane: RelLane, sort: RelSort): List<CalEntry> {
    val laneId = when (lane) { RelLane.STREAMING -> "digital"; RelLane.THEATERS -> "theaters"; RelLane.TV -> "tv" }
    val laneRows = entries.filter { it.kind == "release" && it.sub == laneId }
    val laned = if (lane != RelLane.TV) laneRows else {
        val now = System.currentTimeMillis()
        laneRows.groupBy { it.tmdbId.ifBlank { it.title } }.values.map { list ->
            val s = list.sortedBy { it.startUtc }
            s.firstOrNull { it.startUtc >= now - DAY_MS } ?: s.last()
        }
    }
    return when (sort) {
        RelSort.DATE -> laned.sortedBy { it.startUtc }
        RelSort.POPULAR -> laned.sortedByDescending { it.popularity ?: 0.0 }
        RelSort.RATING -> laned.sortedByDescending { it.rating ?: 0.0 }
    }
}

internal fun tmdbImage(path: String, size: String = "w342"): String =
    if (path.isBlank()) "" else if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/$size$path"
