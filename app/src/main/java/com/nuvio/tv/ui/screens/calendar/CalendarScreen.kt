package com.nuvio.tv.ui.screens.calendar

import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.xmplayer.XmpCalendarReminders
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val DAY_CHIPS = 3

/**
 * XMPlayer's calendar, in Nuvio (MD/NUVIO_CALENDAR_SPEC.md N1-N6) — the XMPlayer TV calendar's page drawn in Nuvio's theme:
 * a CALENDAR tab (month grid of day cells with kind-coloured chips beside the selected day's agenda; legend Movies / TV /
 * DVR / Events toggles kinds; + Event; ‹ › Today and CHANNEL UP/DOWN change month) and a RELEASES tab (Streaming / In
 * theaters / TV, by date / popularity / rating, synopsis on focus). OK on a release opens Nuvio's own title page; hold (or
 * MENU) gives its bell; a DVR recording plays; an own event edits.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    onOpenTitle: (itemId: String, itemType: String, season: Int?, episode: Int?) -> Unit,
    onPlayUrl: (url: String, title: String) -> Unit,
) {
    val s by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val tabFocus = remember { listOf(FocusRequester(), FocusRequester()) }
    var bellFor by remember { mutableStateOf<CalEntry?>(null) }
    var actionsFor by remember { mutableStateOf<CalEntry?>(null) }
    var formFor by remember { mutableStateOf<Pair<String?, CalEntry?>?>(null) }
    var dvrFor by remember { mutableStateOf<CalEntry?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val openEntry: (CalEntry) -> Unit = { e ->
        when (e.kind) {
            "release" -> scope.launch {
                val t = viewModel.openTarget(e)
                onOpenTitle(t.itemId, if (e.mediaType == "series") "series" else "movie", t.season, t.episode)
            }
            "dvr" -> dvrFor = e
            "custom" -> formFor = null to e
        }
    }
    val holdEntry: (CalEntry) -> Unit = { e -> if (e.kind == "dvr") dvrFor = e else actionsFor = e }

    Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(start = 24.dp, end = 32.dp, top = 20.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusGroup()) {
            Pill(stringResource(R.string.xmp_cal_tab_calendar), tab == 0, Modifier.focusRequester(tabFocus[0])) { tab = 0 }
            Pill(stringResource(R.string.xmp_cal_tab_releases), tab == 1, Modifier.focusRequester(tabFocus[1])) { tab = 1 }
            Spacer(Modifier.width(16.dp))
            noteFor(s)?.let { Text(stringResource(it), color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.height(12.dp))
        when {
            s.loading && s.releases.isEmpty() && s.recordings.isEmpty() && s.items.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.xmp_cal_loading), color = NuvioTheme.colors.TextSecondary, fontSize = 16.sp)
                }
            tab == 0 -> CalendarTab(viewModel, s, onOpen = openEntry, onHold = holdEntry, onNewEvent = { day -> formFor = day to null })
            else -> ReleasesTab(s, onOpen = openEntry, onHold = holdEntry)
        }
    }

    bellFor?.let { e -> BellDialog(e, s, onDismiss = { bellFor = null }) { lead -> bellFor = null; viewModel.setBell(e, lead) } }
    actionsFor?.let { e ->
        ActionsDialog(e, onDismiss = { actionsFor = null },
            onOpen = { actionsFor = null; openEntry(e) },
            onBell = { actionsFor = null; bellFor = e },
            onEdit = { actionsFor = null; formFor = null to e },
            onDelete = { actionsFor = null; viewModel.deleteEvent(e) })
    }
    formFor?.let { (day, entry) ->
        EventDialog(day, entry, s.canWrite, onDismiss = { formFor = null },
            onDelete = entry?.let { { formFor = null; viewModel.deleteEvent(it) } }) { title, start, allDay, weekly, lead ->
            formFor = null
            viewModel.saveEvent(entry, title, start, allDay, weekly, lead)
        }
    }
    dvrFor?.let { e ->
        DvrDialog(e, onDismiss = { dvrFor = null }) {
            dvrFor = null
            val id = e.recording?.id ?: return@DvrDialog
            scope.launch {
                val url = viewModel.recordingUrl(id)
                if (url != null) onPlayUrl(url, e.title)
                else android.widget.Toast.makeText(context, context.getString(R.string.xmp_cal_recording_unavailable), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    s.message?.let {
        LaunchedEffect(it) {
            android.widget.Toast.makeText(context, context.getString(R.string.xmp_cal_write_failed), android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
}

private fun noteFor(s: CalendarViewModel.UiState): Int? = when {
    s.releasesFailed && s.releases.isEmpty() -> R.string.xmp_cal_releases_failed
    !s.connected -> R.string.xmp_cal_not_connected
    s.itemsStatus == XmpCalendarReminders.Status.NEEDS_RECONNECT -> R.string.xmp_cal_needs_reconnect
    s.itemsStatus == XmpCalendarReminders.Status.UNAVAILABLE -> R.string.xmp_cal_unavailable
    !s.canWrite -> R.string.xmp_cal_read_only
    else -> null
}

// ── CALENDAR TAB ─────────────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarTab(
    viewModel: CalendarViewModel,
    s: CalendarViewModel.UiState,
    onOpen: (CalEntry) -> Unit,
    onHold: (CalEntry) -> Unit,
    onNewEvent: (String) -> Unit,
) {
    val context = LocalContext.current
    val clock24 = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val prefs = remember { context.getSharedPreferences("xmp_calendar_ui", android.content.Context.MODE_PRIVATE) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()) }
    var anchor by remember { mutableStateOf(monthStart(Calendar.getInstance())) }
    var selectedKey by remember { mutableStateOf(dayKeyOf(Calendar.getInstance())) }
    val selectedFocus = remember { FocusRequester() }
    val agendaFocus = remember { FocusRequester() }
    val agendaList = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The 6-week grid's window (± a week of slack), grouped off the main thread.
    var byDay by remember { mutableStateOf<Map<String, List<CalEntry>>>(emptyMap()) }
    var chips by remember { mutableStateOf<Map<String, List<CalEntry>>>(emptyMap()) }
    LaunchedEffect(anchor.timeInMillis, s.releases, s.recordings, s.items, hidden) {
        val winStart = anchor.timeInMillis - 7 * DAY_MS
        val winEnd = (anchor.clone() as Calendar).apply { add(Calendar.MONTH, 1); add(Calendar.DATE, 7) }.timeInMillis
        val (d, c) = withContext(Dispatchers.Default) {
            val days = viewModel.window(s, winStart, winEnd, hidden)
            days to days.mapValues { (_, v) -> pickVariety(v, DAY_CHIPS) }
        }
        byDay = d; chips = c
        Log.i("XmpCalendar", "CAL| window ${dayKeyOf(anchor)} days=${d.size} entries=${d.values.sumOf { it.size }} hidden=$hidden")
    }
    // LAND ON THE SELECTED DAY when the page opens (XMPlayer: "I should land on the current day's square").
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { selectedFocus.requestFocus() } }
    LaunchedEffect(selectedKey) { runCatching { agendaList.scrollToItem(0) } }
    val enterAgenda: () -> Boolean = {
        if (byDay[selectedKey].isNullOrEmpty()) false else {
            scope.launch { runCatching { agendaList.scrollToItem(0) }; androidx.compose.runtime.withFrameNanos { }; runCatching { agendaFocus.requestFocus() } }
            true
        }
    }
    fun flip(months: Int) { anchor = (anchor.clone() as Calendar).apply { add(Calendar.MONTH, months) } }

    Column(Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (ev.key) {
            Key.ChannelUp, Key.PageUp -> { flip(-1); true }
            Key.ChannelDown, Key.PageDown -> { flip(1); true }
            else -> false
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("‹", false) { flip(-1) }
            Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(anchor.time), color = NuvioTheme.colors.TextPrimary,
                fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Pill("›", false) { flip(1) }
            Pill(stringResource(R.string.xmp_cal_today), false) {
                anchor = monthStart(Calendar.getInstance()); selectedKey = dayKeyOf(Calendar.getInstance())
            }
            Text(TimeZone.getDefault().getDisplayName(TimeZone.getDefault().inDaylightTime(Date()), TimeZone.LONG),
                color = NuvioTheme.colors.TextTertiary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (s.connected) Pill(stringResource(R.string.xmp_cal_add_event), false) { onNewEvent(selectedKey) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((k, label) in listOf("movie" to R.string.xmp_cal_movies, "tv" to R.string.xmp_cal_tv, "dvr" to R.string.xmp_cal_dvr, "custom" to R.string.xmp_cal_events)) {
                LegendChip(stringResource(label), KIND_COLORS.getValue(k), enabled = k !in hidden) {
                    hidden = if (k in hidden) hidden - k else hidden + k
                    prefs.edit().putStringSet("hidden", hidden).apply()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // ── month grid ──
            Column(Modifier.weight(0.64f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth()) {
                    val wd = Calendar.getInstance().apply { set(2026, 2, 1) }   // a Sunday
                    repeat(7) {
                        Text(SimpleDateFormat("EEE", Locale.getDefault()).format(wd.time), color = NuvioTheme.colors.TextTertiary,
                            fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
                        wd.add(Calendar.DATE, 1)
                    }
                }
                val firstDow = anchor.get(Calendar.DAY_OF_WEEK) - 1
                val cellStart = (anchor.clone() as Calendar).apply { add(Calendar.DATE, -firstDow) }
                val todayKey = dayKeyOf(Calendar.getInstance())
                for (week in 0 until 6) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (dow in 0 until 7) {
                            val d = (cellStart.clone() as Calendar).apply { add(Calendar.DATE, week * 7 + dow) }
                            val key = dayKeyOf(d)
                            DayCell(
                                day = d.get(Calendar.DAY_OF_MONTH), otherMonth = d.get(Calendar.MONTH) != anchor.get(Calendar.MONTH),
                                isToday = key == todayKey, selected = key == selectedKey,
                                chips = chips[key].orEmpty(), total = byDay[key]?.size ?: 0, clock24 = clock24,
                                onSelect = { selectedKey = key },
                                onOpen = { selectedKey = key; enterAgenda() },
                                onLongPress = { if (s.connected) onNewEvent(key) },
                                onRightKey = if (dow == 6) enterAgenda else null,
                                focusRequester = if (key == selectedKey) selectedFocus else null,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    if (week < 5) Spacer(Modifier.height(4.dp))
                }
            }
            // ── the selected day's agenda ──
            Column(Modifier.weight(0.36f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                .background(NuvioTheme.colors.BackgroundCard).padding(12.dp)) {
                val (y, m, dd) = selectedKey.split('-').map { it.toInt() }
                val sel = Calendar.getInstance().apply { set(y, m - 1, dd) }
                Text(SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(sel.time), color = NuvioTheme.colors.TextPrimary,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val rows = byDay[selectedKey].orEmpty()
                if (rows.isEmpty()) Text(stringResource(R.string.xmp_cal_nothing_scheduled), color = NuvioTheme.colors.TextTertiary, fontSize = 15.sp)
                else LazyColumn(state = agendaList, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(rows, key = { i, e -> "${e.kind}:${e.id}${e.tmdbId}${e.recording?.id.orEmpty()}${e.startUtc}#$i" }) { i, e ->
                        AgendaRow(e, clock24, if (i == 0) agendaFocus else null, onClick = { onOpen(e) }, onLongClick = { onHold(e) })
                    }
                }
            }
        }
    }
}

private fun monthStart(c: Calendar): Calendar = (c.clone() as Calendar).apply {
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayCell(
    day: Int, otherMonth: Boolean, isToday: Boolean, selected: Boolean, chips: List<CalEntry>, total: Int, clock24: Boolean,
    onSelect: () -> Unit, onOpen: () -> Unit, onLongPress: () -> Unit, onRightKey: (() -> Boolean)?,
    focusRequester: FocusRequester?, modifier: Modifier,
) {
    val accent = NuvioTheme.colors.FocusRing
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onOpen,
        onLongClick = onLongPress,
        shape = CardDefaults.shape(shape = shape),
        border = CardDefaults.border(
            border = if (isToday) Border(BorderStroke(1.dp, accent.copy(alpha = 0.6f)), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, accent), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.03f),
        colors = CardDefaults.colors(
            containerColor = if (selected) accent.copy(alpha = 0.16f) else NuvioTheme.colors.BackgroundCard.copy(alpha = if (otherMonth) 0.35f else 0.85f),
            focusedContainerColor = if (selected) accent.copy(alpha = 0.22f) else NuvioTheme.colors.FocusBackground),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    ev.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MENU -> { onLongPress(); true }
                    ev.key == Key.DirectionRight && onRightKey != null -> onRightKey()
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) onSelect() },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(day.toString(), color = if (isToday) accent else if (otherMonth) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
                fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium, lineHeight = 13.sp)
            for (e in chips) {
                val c = KIND_COLORS[e.kindKey] ?: NuvioTheme.colors.TextSecondary
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp).clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = 0.22f)).padding(horizontal = 3.dp)) {
                    if (!e.allDay) Text(chipTime(e.startUtc, clock24), color = c, fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
                    Text(chipLabel(e), color = NuvioTheme.colors.TextPrimary, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 10.sp)
                }
            }
            if (total > chips.size) Text(stringResource(R.string.xmp_cal_x_more, total - chips.size), color = NuvioTheme.colors.TextTertiary, fontSize = 8.sp, lineHeight = 10.sp)
        }
    }
}

@Composable
private fun subLine(e: CalEntry, clock24: Boolean): String {
    val time = if (e.allDay) stringResource(R.string.xmp_cal_all_day)
    else SimpleDateFormat(if (clock24) "H:mm" else "h:mm a", Locale.getDefault()).format(Date(e.startUtc))
    return when (e.kind) {
        "release" -> listOfNotNull(time,
            when (e.sub) { "theaters" -> stringResource(R.string.xmp_cal_in_theaters); "digital" -> stringResource(R.string.xmp_cal_digital); else -> stringResource(R.string.xmp_cal_airs) },
            e.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
        "dvr" -> listOfNotNull(time,
            when {
                e.isRecording -> stringResource(R.string.xmp_cal_recording)
                e.isRecorded -> e.recording?.durationMs?.takeIf { it > 0 }?.let { stringResource(R.string.xmp_cal_recorded_min, (it / 60_000).toInt()) }
                    ?: stringResource(R.string.xmp_cal_recorded)
                else -> stringResource(R.string.xmp_cal_scheduled)
            },
            e.recording?.channelName?.ifBlank { null }).joinToString(" · ")
        "custom" -> listOfNotNull(time, if (e.weekly) stringResource(R.string.xmp_cal_weekly) else null).joinToString(" · ")
        else -> time
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AgendaRow(e: CalEntry, clock24: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val accent = NuvioTheme.colors.FocusRing
    val c = KIND_COLORS[e.kindKey] ?: NuvioTheme.colors.TextSecondary
    val shape = RoundedCornerShape(8.dp)
    Card(
        onClick = onClick, onLongClick = onLongClick,
        shape = CardDefaults.shape(shape = shape),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, accent), shape = shape)),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = NuvioTheme.colors.FocusBackground),
        modifier = Modifier.fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MENU) { onLongClick(); true } else false
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(c))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(chipLabel(e), color = NuvioTheme.colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                Text(subLine(e, clock24), color = if (e.isRecording) accent else NuvioTheme.colors.TextSecondary, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.bellRef != null || (e.kind == "custom" && e.reminderLeadMs != null)) {
                val armed = e.reminderLeadMs != null
                Box(Modifier.size(32.dp).clip(CircleShape).background(if (armed) accent.copy(alpha = 0.25f) else Color.Transparent),
                    contentAlignment = Alignment.Center) {
                    Icon(if (armed) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone,
                        contentDescription = stringResource(if (armed) R.string.xmp_cal_reminder else R.string.xmp_cal_set_reminder),
                        tint = if (armed) accent else NuvioTheme.colors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.FocusRing
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clip(RoundedCornerShape(14.dp))
            .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickableTv(onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (enabled) color else NuvioTheme.colors.TextTertiary))
        Text(label, color = if (enabled) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary, fontSize = 12.sp)
    }
}

// ── RELEASES TAB ─────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun ReleasesTab(s: CalendarViewModel.UiState, onOpen: (CalEntry) -> Unit, onHold: (CalEntry) -> Unit) {
    var lane by remember { mutableStateOf(RelLane.STREAMING) }
    var sort by remember { mutableStateOf(RelSort.DATE) }
    var cards by remember { mutableStateOf<List<CalEntry>>(emptyList()) }
    LaunchedEffect(s.releases, s.items, lane, sort) {
        // XMPlayer's window: a week back, sixty days on.
        val now = System.currentTimeMillis()
        cards = withContext(Dispatchers.Default) {
            releasesView(s.releaseEntries.filter { it.startUtc in (now - 7 * DAY_MS)..(now + 60 * DAY_MS) }, lane, sort)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.xmp_cal_streaming), lane == RelLane.STREAMING) { lane = RelLane.STREAMING }
            Pill(stringResource(R.string.xmp_cal_in_theaters), lane == RelLane.THEATERS) { lane = RelLane.THEATERS }
            Pill(stringResource(R.string.xmp_cal_tv), lane == RelLane.TV) { lane = RelLane.TV }
            Spacer(Modifier.weight(1f))
            Pill(stringResource(R.string.xmp_cal_sort_date), sort == RelSort.DATE) { sort = RelSort.DATE }
            Pill(stringResource(R.string.xmp_cal_sort_popular), sort == RelSort.POPULAR) { sort = RelSort.POPULAR }
            // theatrical titles are unrated before release
            if (lane != RelLane.THEATERS) Pill(stringResource(R.string.xmp_cal_sort_rating), sort == RelSort.RATING) { sort = RelSort.RATING }
        }
        Spacer(Modifier.height(12.dp))
        if (cards.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.xmp_cal_no_releases), color = NuvioTheme.colors.TextSecondary, fontSize = 15.sp)
        } else LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(cards, key = { "${it.sub}:${it.tmdbId}:${it.startUtc}" }) { e -> ReleasePoster(e, onOpen, onHold) }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ReleasePoster(e: CalEntry, onOpen: (CalEntry) -> Unit, onHold: (CalEntry) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.FocusRing
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(
            onClick = { onOpen(e) }, onLongClick = { onHold(e) },
            shape = CardDefaults.shape(shape = shape),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, accent), shape = shape)),
            colors = CardDefaults.colors(containerColor = NuvioTheme.colors.BackgroundCard),
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_MENU) { onHold(e); true } else false
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                if (e.poster.isNotBlank()) AsyncImage(model = tmdbImage(e.poster), contentDescription = e.title, modifier = Modifier.fillMaxSize())
                else Text(e.title, color = NuvioTheme.colors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                e.rating?.takeIf { it > 0 }?.let {
                    Text("%.1f".format(it), color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp))
                }
                if (e.reminderLeadMs != null) Icon(Icons.Rounded.Notifications, contentDescription = null, tint = accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp))
            }
        }
        Text(e.title, color = NuvioTheme.colors.TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = if (e.mediaType == "series" && e.episodeTitle.isNotBlank()) e.episodeTitle
        else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(e.startUtc))
        Text(sub, color = NuvioTheme.colors.TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (focused && e.overview.isNotBlank()) Text(e.overview, color = NuvioTheme.colors.TextTertiary, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ── shared bits ──────────────────────────────────────────────────────────────────────────────────────────────

@Composable
internal fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.FocusRing
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard)
            .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(20.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickableTv(onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(label, color = NuvioTheme.colors.TextPrimary, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/** One focus target that OK activates (clickable brings its own focus node). */
internal fun Modifier.clickableTv(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
