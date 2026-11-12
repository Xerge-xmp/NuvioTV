package com.nuvio.tv.data.xmplayer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The profile's calendar BELLS and OWN EVENTS (XMPlayer's, MD/NUVIO_CALENDAR_SPEC.md N5), kept on the TV so they alert
 * at their time like XMPlayer's: one OS alarm for the next one; when it goes off, an in-app pop-out (the TV hides system
 * notifications) and a system notification. The list is XMPlayer's (`/v1/calendar`) — read at start, when the XMPlayer
 * connection changes, when the calendar page opens and after every change made here.
 */
@Singleton
class XmpCalendarReminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendar: XmpCalendarClient,
    private val authStore: XmpAuthStore,
) {
    enum class Status { UNKNOWN, OK, NOT_CONNECTED, NEEDS_RECONNECT, UNAVAILABLE }

    data class Fire(val id: String, val title: String, val kind: String, val ref: String, val startAt: Long, val leadMs: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lock = Mutex()

    private val mutableItems = MutableStateFlow<List<XmpCalendarItem>>(emptyList())
    val items: StateFlow<List<XmpCalendarItem>> = mutableItems.asStateFlow()
    private val mutableStatus = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    fun start() {
        instance = this
        mutableItems.value = stored()
        scope.launch {
            authStore.state.map { it.isAuthenticated to it.account?.profileId }.distinctUntilChanged().collect { (signedIn, _) ->
                if (!signedIn) {
                    mutableStatus.value = Status.NOT_CONNECTED
                    store(emptyList())
                    arm()
                } else refresh()
            }
        }
    }

    /** Re-read the profile's bells + events from XMPlayer, then re-arm. */
    suspend fun refresh() = lock.withLock {
        if (!authStore.state.value.isAuthenticated) { mutableStatus.value = Status.NOT_CONNECTED; return@withLock }
        runCatching { calendar.items() }
            .onSuccess {
                store(it); mutableStatus.value = Status.OK
                Log.i(TAG, "CAL| items=${it.size} bells=${it.count { i -> !i.isEvent }} events=${it.count { i -> i.isEvent }}")
            }
            .onFailure { e ->
                val code = e.xmpErrorCode()
                val status = (e as? XmpApiException)?.status
                mutableStatus.value = when {
                    code == "insufficient_scope" || status == 403 -> Status.NEEDS_RECONNECT
                    else -> Status.UNAVAILABLE
                }
                Log.w(TAG, "CAL| items read failed status=$status code=$code ($e)")
            }
        arm()
    }

    /** A write made here shows at once (the next read confirms it). */
    fun applyLocal(change: (List<XmpCalendarItem>) -> List<XmpCalendarItem>) {
        store(change(mutableItems.value))
        arm()
    }

    // ── firing ─────────────────────────────────────────────────────────────────────────────────────────────
    private val mutableFires = MutableSharedFlow<Fire>(extraBufferCapacity = 8)

    /** The pop-out's feed: each bell / event as it goes off (nothing when the app is not showing). */
    val fires: SharedFlow<Fire> = mutableFires.asSharedFlow()

    internal fun onAlarm() {
        val now = System.currentTimeMillis()
        val fired = prefs.getStringSet(KEY_FIRED, emptySet()).orEmpty().toMutableSet()
        for (item in mutableItems.value) {
            val at = fireAt(item, now) ?: continue
            if (at > now || at < now - GRACE_MS) continue
            val mark = "${item.id}@$at"
            if (!fired.add(mark)) continue
            surface(item, at)
        }
        // keep the fired marks of the last two weeks only
        prefs.edit().putStringSet(KEY_FIRED, fired.filter { (it.substringAfterLast('@').toLongOrNull() ?: 0) > now - 14 * DAY_MS }.toSet()).apply()
        arm()
        scope.launch { refresh() }
    }

    private fun surface(item: XmpCalendarItem, fireAt: Long) {
        val f = Fire(item.id, item.title, item.kind, item.ref, fireAt + item.leadMs, item.leadMs)
        mutableFires.tryEmit(f)
        Log.i(TAG, "CAL| FIRED '${item.title}' kind=${item.kind} lead=${item.leadMs}")
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.xmp_cal_reminders), NotificationManager.IMPORTANCE_HIGH))
            }
            val mins = (item.leadMs / 60_000L).toInt()
            val text = if (mins <= 0) context.getString(R.string.xmp_cal_starting_now) else context.getString(R.string.xmp_cal_starts_in_min, mins)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(item.title.ifBlank { context.getString(R.string.xmp_cal_reminder) })
                .setContentText(text)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(item.id.hashCode(), n)
        }.onFailure { Log.w(TAG, "CAL| notification not shown: $it") }
    }

    /** One alarm slot: the next unfired bell / event. */
    fun arm() {
        val now = System.currentTimeMillis()
        val fired = prefs.getStringSet(KEY_FIRED, emptySet()).orEmpty()
        val next = mutableItems.value.mapNotNull { item ->
            fireAt(item, now)?.takeIf { it > now && "${item.id}@$it" !in fired }
        }.minOrNull()
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, XmpCalendarAlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (next == null) { am.cancel(pi); return }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        Log.i(TAG, "CAL| armed next at=$next (in ${(next - now) / 1000}s)")
    }

    private fun store(list: List<XmpCalendarItem>) {
        mutableItems.value = list
        prefs.edit().putString(KEY_ITEMS, json.encodeToString(ListSerializer(XmpCalendarItem.serializer()), list)).apply()
    }

    private fun stored(): List<XmpCalendarItem> = runCatching {
        json.decodeFromString(ListSerializer(XmpCalendarItem.serializer()), prefs.getString(KEY_ITEMS, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "XmpCalendar"
        private const val PREFS = "xmp_calendar"
        private const val KEY_ITEMS = "items"
        private const val KEY_FIRED = "fired"
        private const val CHANNEL = "xmp_calendar"
        internal const val ACTION_FIRE = "com.nuvio.tv.xmplayer.CALENDAR_FIRE"
        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = 7 * DAY_MS
        private const val GRACE_MS = 10 * 60_000L

        @Volatile internal var instance: XmpCalendarReminders? = null

        /** When [item] next goes off (its next occurrence for a weekly event), or null when it never alerts. */
        fun fireAt(item: XmpCalendarItem, now: Long): Long? {
            if (!item.remind || item.startAt <= 0) return null
            var occ = item.startAt
            if (item.weekly) while (occ - item.leadMs < now - GRACE_MS) occ += WEEK_MS
            return occ - item.leadMs
        }
    }
}

/** The alarm's hand: fire what is due, re-arm. Boot / clock changes re-arm. */
class XmpCalendarAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val r = XmpCalendarReminders.instance ?: return
        when (intent?.action) {
            XmpCalendarReminders.ACTION_FIRE -> r.onAlarm()
            else -> r.arm()
        }
    }
}
