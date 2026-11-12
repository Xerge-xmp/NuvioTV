package com.nuvio.tv.ui.screens.calendar

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.data.xmplayer.XmpCalendarReminders
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DialogButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary),
    ) { Text(label) }
}

/** Why bells / events cannot be changed here right now, or null when they can. */
@Composable
private fun writeBlock(s: CalendarViewModel.UiState): String? = when {
    !s.connected -> stringResource(R.string.xmp_cal_not_connected)
    s.itemsStatus == XmpCalendarReminders.Status.NEEDS_RECONNECT -> stringResource(R.string.xmp_cal_needs_reconnect)
    !s.canWrite -> stringResource(R.string.xmp_cal_read_only)
    else -> null
}

/** A release's details (poster, episode, when, synopsis) — the head of its sheet. */
@Composable
internal fun ReleaseInfo(e: CalEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (e.poster.isNotBlank()) AsyncImage(model = tmdbImage(e.poster), contentDescription = e.title,
            modifier = Modifier.width(110.dp).height(165.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val ep = listOfNotNull(
                if (e.mediaType == "series" && e.season != null && e.episode != null) "S${e.season}E${e.episode}" else null,
                e.episodeTitle.ifBlank { null }).joinToString(" · ")
            if (ep.isNotBlank()) Text(ep, color = NuvioTheme.colors.TextPrimary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val date = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(e.startUtc))
            val kind = when (e.sub) { "theaters" -> stringResource(R.string.xmp_cal_in_theaters); "digital" -> stringResource(R.string.xmp_cal_digital); else -> stringResource(R.string.xmp_cal_airs) }
            Text(listOfNotNull(date, kind, e.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · "),
                color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp)
            if (e.overview.isNotBlank()) Text(e.overview, color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Hold on a release / an event: open, bell, edit, delete. */
@Composable
internal fun ActionsDialog(e: CalEntry, onDismiss: () -> Unit, onOpen: () -> Unit, onBell: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val first = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = chipLabel(e), width = if (e.kind == "release") 640.dp else 460.dp) {
        if (e.kind == "release") ReleaseInfo(e)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(if (e.kind == "custom") R.string.xmp_cal_edit_event else R.string.xmp_cal_open), primary = true,
                modifier = Modifier.fillMaxWidth().focusRequester(first)) { if (e.kind == "custom") onEdit() else onOpen() }
            if (e.bellRef != null) DialogButton(stringResource(if (e.reminderLeadMs != null) R.string.xmp_cal_reminder else R.string.xmp_cal_set_reminder),
                modifier = Modifier.fillMaxWidth(), onClick = onBell)
            if (e.kind == "custom") DialogButton(stringResource(R.string.xmp_cal_delete_event), modifier = Modifier.fillMaxWidth(), onClick = onDelete)
            DialogButton(stringResource(R.string.xmp_cal_close), modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** XMPlayer's bell choices: 5 / 15 / 60 minutes before, or remove. */
@Composable
internal fun BellDialog(e: CalEntry, s: CalendarViewModel.UiState, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val first = remember { FocusRequester() }
    NuvioDialog(onDismiss = onDismiss, title = chipLabel(e), width = 480.dp) {
        val blocked = writeBlock(s)
        val armed = e.reminderLeadMs != null
        when {
            blocked != null -> Text(blocked, color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
            armed -> {
                Text(stringResource(R.string.xmp_cal_reminder_set_min, (e.reminderLeadMs!! / 60_000).toInt()), color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
                DialogButton(stringResource(R.string.xmp_cal_remove_reminder), primary = true, modifier = Modifier.fillMaxWidth().focusRequester(first)) { onPick(null) }
            }
            e.reminderStart() > System.currentTimeMillis() -> {
                Text(stringResource(R.string.xmp_cal_remind_me), color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 60).forEachIndexed { i, m ->
                        DialogButton(stringResource(R.string.xmp_cal_min_before, m), modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { onPick(m) }
                    }
                }
            }
            else -> Text(stringResource(R.string.xmp_cal_already_started), color = NuvioTheme.colors.TextTertiary, fontSize = 14.sp)
        }
        DialogButton(stringResource(R.string.xmp_cal_close), modifier = Modifier.fillMaxWidth().then(
            if (blocked != null || (!armed && e.reminderStart() <= System.currentTimeMillis())) Modifier.focusRequester(first) else Modifier), onClick = onDismiss)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** A DVR recording: what, when, where — Play when there is something to play. */
@Composable
internal fun DvrDialog(e: CalEntry, onDismiss: () -> Unit, onPlay: () -> Unit) {
    val first = remember { FocusRequester() }
    val r = e.recording
    NuvioDialog(onDismiss = onDismiss, title = e.title, width = 520.dp) {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.startUtc))
        val state = when {
            e.isRecording -> stringResource(R.string.xmp_cal_recording)
            e.isRecorded -> stringResource(R.string.xmp_cal_recorded)
            else -> stringResource(R.string.xmp_cal_scheduled)
        }
        Text(listOfNotNull(time, state, r?.channelName?.ifBlank { null }).joinToString(" · "), color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp)
        if (e.isRecorded || e.isRecording) DialogButton(stringResource(R.string.xmp_cal_play), primary = true,
            modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = onPlay)
        DialogButton(stringResource(R.string.xmp_cal_close), modifier = Modifier.fillMaxWidth().then(
            if (!(e.isRecorded || e.isRecording)) Modifier.focusRequester(first) else Modifier), onClick = onDismiss)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** XMPlayer's own-event form: title, date, time or all day, weekly, remind (off / 5 / 15 / 60 min). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun EventDialog(
    dayKey: String?, entry: CalEntry?, canWrite: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)?,
    onSave: (title: String, startAt: Long, allDay: Boolean, weekly: Boolean, leadMin: Int?) -> Unit,
) {
    val init = remember {
        val c = Calendar.getInstance()
        if (entry != null) c.timeInMillis = entry.startUtc
        else if (dayKey != null) { val (y, m, d) = dayKey.split('-').map { it.toInt() }; c.set(y, m - 1, d, 20, 0, 0) }
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); c
    }
    var title by remember { mutableStateOf(entry?.title.orEmpty()) }
    var cal by remember { mutableStateOf(init) }
    var allDay by remember { mutableStateOf(entry?.allDay ?: false) }
    var weekly by remember { mutableStateOf(entry?.weekly ?: false) }
    var lead by remember { mutableStateOf(entry?.reminderLeadMs?.let { (it / 60_000).toInt() }) }
    var editing by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val dateFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun bump(field: Int, delta: Int) { cal = (cal.clone() as Calendar).apply { add(field, delta) } }

    NuvioDialog(onDismiss = onDismiss, title = stringResource(if (entry == null) R.string.xmp_cal_create_event else R.string.xmp_cal_edit_event), width = 620.dp) {
        if (!canWrite) Text(stringResource(R.string.xmp_cal_read_only), color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp)
        // Nuvio's own text entry: read-only until OK, then the system keyboard.
        androidx.compose.material3.OutlinedTextField(
            value = title, onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().focusRequester(titleFocus)
                .onFocusChanged { if (!it.isFocused) editing = false }
                .onPreviewKeyEvent { ev ->
                    val n = ev.nativeKeyEvent
                    if (n.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (n.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                            if (!editing) { editing = true; keyboard?.show() }
                            false
                        }
                        // While typing the field held the remote: DOWN / UP did nothing and BACK never closed the form. Now
                        // they end the typing and leave the field; BACK ends the typing (a second BACK closes the form).
                        // DOWN always leaves for the date row, by name: the one-line field swallows DOWN itself (it moves its
                        // cursor), and a focus SEARCH from it did not stick — measured: moved=true, focus back on the field.
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            editing = false; keyboard?.hide()
                            val ok = runCatching { dateFocus.requestFocus() }.isSuccess
                            android.util.Log.i("XmpCalendar", "CAL| title -> date row ok=$ok")
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                            if (editing) { editing = false; keyboard?.hide() }
                            true
                        }
                        AndroidKeyEvent.KEYCODE_BACK -> {
                            if (!editing) return@onPreviewKeyEvent false
                            editing = false; keyboard?.hide()
                            true
                        }
                        else -> false
                    }
                },
            readOnly = !editing, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { editing = false; keyboard?.hide() }),
            label = { androidx.compose.material3.Text(stringResource(R.string.xmp_cal_title)) },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = NuvioTheme.colors.TextPrimary, unfocusedTextColor = NuvioTheme.colors.TextPrimary,
                focusedContainerColor = NuvioTheme.colors.BackgroundCard, unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
                focusedBorderColor = NuvioTheme.colors.FocusRing, unfocusedBorderColor = NuvioTheme.colors.Border,
                focusedLabelColor = NuvioTheme.colors.TextSecondary, unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
                cursorColor = NuvioTheme.colors.FocusRing),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.xmp_cal_date), color = NuvioTheme.colors.TextTertiary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
            Pill("‹", false, Modifier.focusRequester(dateFocus)) { bump(Calendar.DATE, -1) }
            Text(SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault()).format(cal.time), color = NuvioTheme.colors.TextPrimary, fontSize = 15.sp, modifier = Modifier.width(180.dp))
            Pill("›", false) { bump(Calendar.DATE, 1) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.xmp_cal_time), color = NuvioTheme.colors.TextTertiary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
            if (!allDay) {
                Pill("‹", false) { bump(Calendar.MINUTE, -15) }
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(cal.time), color = NuvioTheme.colors.TextPrimary, fontSize = 15.sp, modifier = Modifier.width(110.dp))
                Pill("›", false) { bump(Calendar.MINUTE, 15) }
            } else Text(stringResource(R.string.xmp_cal_all_day), color = NuvioTheme.colors.TextSecondary, fontSize = 15.sp)
        }
        // All day + Weekly on their own row: beside the time they were crushed into a sliver (owner screenshot: "broken.").
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.width(56.dp))
            Pill(stringResource(R.string.xmp_cal_all_day), allDay) { allDay = !allDay }
            Pill(stringResource(R.string.xmp_cal_weekly), weekly) { weekly = !weekly }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.xmp_cal_remind), color = NuvioTheme.colors.TextTertiary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
            Pill(stringResource(R.string.xmp_cal_off), lead == null) { lead = null }
            for (m in listOf(5, 15, 60)) Pill(stringResource(R.string.xmp_cal_x_min, m), lead == m) { lead = m }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canWrite) DialogButton(stringResource(R.string.xmp_cal_save), primary = true) {
                if (title.isBlank()) return@DialogButton
                // All day = LOCAL 9:00 of that day (XMPlayer's rule: buckets by the local day, a sane alert time).
                val start = (cal.clone() as Calendar).apply { if (allDay) { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0) } }.timeInMillis
                onSave(title.trim(), start, allDay, weekly, lead)
            }
            if (canWrite && onDelete != null) DialogButton(stringResource(R.string.xmp_cal_delete_event), onClick = onDelete)
            DialogButton(stringResource(R.string.xmp_cal_cancel), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { titleFocus.requestFocus() } }
}
