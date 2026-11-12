package com.nuvio.tv.ui.screens.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.xmplayer.XmpCalendarReminders
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * A bell or an own event going off, on screen (XMPlayer's reminder pop-out): the TV hides system notifications, so the
 * app shows a card at the bottom for ~9 s. A heads-up, never a modal — it takes no focus and never blocks the remote.
 */
@Composable
fun XmpReminderPopoutHost() {
    val reminders = XmpCalendarReminders.instance ?: return
    var current by remember { mutableStateOf<XmpCalendarReminders.Fire?>(null) }
    LaunchedEffect(reminders) { reminders.fires.collect { current = it } }
    LaunchedEffect(current) { if (current != null) { delay(9_000); current = null } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = current != null,
            enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
            val f = current
            val accent = NuvioTheme.colors.FocusRing
            Row(
                Modifier.padding(bottom = 28.dp).widthIn(min = 320.dp, max = 560.dp).clip(RoundedCornerShape(14.dp))
                    .background(NuvioTheme.colors.BackgroundElevated).border(2.dp, accent, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Rounded.Notifications, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                Column {
                    Text(stringResource(R.string.xmp_cal_caps_reminder), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                    Text(f?.title.orEmpty(), color = NuvioTheme.colors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    val mins = ((f?.leadMs ?: 0L) / 60_000L).toInt()
                    Text(if (mins <= 0) stringResource(R.string.xmp_cal_starting_now) else stringResource(R.string.xmp_cal_starts_in_min, mins),
                        color = NuvioTheme.colors.TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}
