package com.nuvio.tv.ui.screens.calendar

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.xmplayer.XmpAuthStore
import com.nuvio.tv.data.xmplayer.XmpCalendarClient
import com.nuvio.tv.data.xmplayer.XmpCalendarItem
import com.nuvio.tv.data.xmplayer.XmpCalendarReminders
import com.nuvio.tv.data.xmplayer.XmpCatalogClient
import com.nuvio.tv.data.xmplayer.XmpRecording
import com.nuvio.tv.data.xmplayer.XmpReleaseRow
import com.nuvio.tv.data.xmplayer.XmpReleasesClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * XMPlayer's calendar in Nuvio (MD/NUVIO_CALENDAR_SPEC.md N2-N4): Releases (public dataset), the profile's DVR recordings
 * and its bells + own events, merged into one entry list the page paints; bells and events are written back to XMPlayer.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val releasesClient: XmpReleasesClient,
    private val calendar: XmpCalendarClient,
    private val catalog: XmpCatalogClient,
    private val authStore: XmpAuthStore,
    private val reminders: XmpCalendarReminders,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val releasesFailed: Boolean = false,
        val releases: List<XmpReleaseRow> = emptyList(),
        val overviews: Map<String, String> = emptyMap(),
        val recordings: List<XmpRecording> = emptyList(),
        val items: List<XmpCalendarItem> = emptyList(),
        val connected: Boolean = false,
        /** XMPlayer writes need Premium / Pro (a Free connection reads only). */
        val canWrite: Boolean = false,
        val itemsStatus: XmpCalendarReminders.Status = XmpCalendarReminders.Status.UNKNOWN,
        val message: String? = null,
    ) {
        /** Every release with its bell. */
        val releaseEntries: List<CalEntry> by lazy { releaseEntries(releases, overviews, releaseBells(items)) }
    }

    private val mutable = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            combine(reminders.items, reminders.status, authStore.state) { items, status, auth -> Triple(items, status, auth) }
                .collect { (items, status, auth) ->
                    mutable.update {
                        it.copy(items = items, itemsStatus = status, connected = auth.isAuthenticated,
                            canWrite = auth.isAuthenticated && auth.account?.write == true)
                    }
                }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            val rel = withContext(Dispatchers.IO) { runCatching { releasesClient.releases() } }
            rel.onFailure { Log.w(TAG, "CAL| releases failed: $it") }
            val recs = if (authStore.state.value.isAuthenticated) withContext(Dispatchers.IO) {
                runCatching { catalog.allRecordings() }.onFailure { Log.w(TAG, "CAL| recordings failed: $it") }.getOrDefault(emptyList())
            } else emptyList()
            mutable.update {
                it.copy(loading = false, releasesFailed = rel.isFailure,
                    releases = rel.getOrNull()?.rows ?: it.releases, overviews = rel.getOrNull()?.overviews ?: it.overviews,
                    recordings = recs)
            }
            Log.i(TAG, "CAL| page loaded releases=${mutable.value.releases.size} recordings=${recs.size}")
            reminders.refresh()
        }
    }

    /** The entries of [winStart, winEnd), grouped by day and in the agenda's order. */
    fun window(s: UiState, winStart: Long, winEnd: Long, hidden: Set<String>): Map<String, List<CalEntry>> {
        val all = s.releaseEntries.filter { it.startUtc in winStart until winEnd } +
            recordingEntries(s.recordings).filter { it.startUtc in winStart until winEnd } +
            eventEntries(s.items, winStart, winEnd)
        return all.filter { it.kindKey !in hidden }.groupBy { it.dayKey() }.mapValues { (_, v) -> v.sortedWith(ENTRY_ORDER) }
    }

    /** Arm (leadMin) or remove (null) the bell on a release. */
    fun setBell(e: CalEntry, leadMin: Int?) {
        val (kind, ref) = e.bellRef ?: return
        val profile = authStore.state.value.account?.profileId.orEmpty()
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    if (leadMin == null) calendar.removeReminder(kind, ref)
                    else calendar.setReminder(kind, ref, e.title, e.reminderStart(), leadMin * 60_000L)
                }
            }
            res.onSuccess {
                val id = "$kind:$ref:$profile"
                reminders.applyLocal { list ->
                    list.filterNot { it.id == id } + if (leadMin == null) emptyList() else listOf(
                        XmpCalendarItem(id = id, kind = kind, ref = ref, title = e.title, startAt = e.reminderStart(), leadMs = leadMin * 60_000L))
                }
                Log.i(TAG, "CAL| bell ${if (leadMin == null) "removed" else "set $leadMin min"} $kind:$ref")
                reminders.refresh()
            }.onFailure { err -> fail("bell", err) }
        }
    }

    fun saveEvent(existing: CalEntry?, title: String, startAt: Long, allDay: Boolean, weekly: Boolean, leadMin: Int?) {
        val profile = authStore.state.value.account?.profileId.orEmpty()
        viewModelScope.launch {
            val lead = (leadMin ?: 0) * 60_000L
            val res = runCatching {
                withContext(Dispatchers.IO) { calendar.saveEvent(existing?.id.orEmpty(), title, startAt, allDay, weekly, lead, leadMin != null) }
            }
            res.onSuccess { id ->
                val eid = id.ifBlank { "ev$startAt:$profile" }
                reminders.applyLocal { list ->
                    list.filterNot { it.id == eid } + XmpCalendarItem(id = eid, kind = "custom", title = title, startAt = startAt,
                        leadMs = lead, allDay = allDay, weekly = weekly, remind = leadMin != null)
                }
                Log.i(TAG, "CAL| event saved id=$eid '$title' allDay=$allDay weekly=$weekly lead=$leadMin")
                reminders.refresh()
            }.onFailure { err -> fail("event", err) }
        }
    }

    fun deleteEvent(e: CalEntry) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { calendar.deleteEvent(e.id) } }
                .onSuccess {
                    reminders.applyLocal { list -> list.filterNot { it.id == e.id } }
                    Log.i(TAG, "CAL| event deleted ${e.id}")
                    reminders.refresh()
                }.onFailure { err -> fail("delete", err) }
        }
    }

    /**
     * Where a release opens: a movie by its TMDB id; a series episode on its series page, focused on the episode that airs
     * that day IN THE ADD-ONS' NUMBERING (the atlas through XMPlayer), so the add-ons are asked for the right one.
     */
    suspend fun openTarget(e: CalEntry): com.nuvio.tv.data.xmplayer.XmpEpisodeTarget {
        val plain = com.nuvio.tv.data.xmplayer.XmpEpisodeTarget("tmdb:${e.tmdbId}", null, null)
        if (e.mediaType != "series" || !authStore.state.value.isAuthenticated) return plain
        return withContext(Dispatchers.IO) {
            runCatching { calendar.episodeOnDate(e.tmdbId, e.dayKey(), e.episodeTitle) }
                .onFailure { Log.w(TAG, "CAL| episode lookup failed: $it") }.getOrNull()
        } ?: plain
    }

    /** A finished recording's 6-hour link (dvr:read). */
    suspend fun recordingUrl(id: String): String? =
        withContext(Dispatchers.IO) { runCatching { catalog.recordingPlaylist(id) }.onFailure { Log.w(TAG, "CAL| recording link failed: $it") }.getOrNull() }

    fun clearMessage() = mutable.update { it.copy(message = null) }

    private fun fail(what: String, err: Throwable) {
        Log.w(TAG, "CAL| $what write failed: $err")
        mutable.update { it.copy(message = err.message ?: what) }
    }

    private companion object { const val TAG = "XmpCalendar" }
}
