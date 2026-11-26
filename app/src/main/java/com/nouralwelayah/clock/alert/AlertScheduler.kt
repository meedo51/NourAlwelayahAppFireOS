package com.nouralwelayah.clock.alert

import com.nouralwelayah.clock.Sys
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nouralwelayah.clock.MainActivity
import com.nouralwelayah.clock.core.Event
import com.nouralwelayah.clock.core.Planner
import com.nouralwelayah.clock.data.Store

/**
 * Keeps exactly one exact alarm in AlarmManager: the next azan, alarm or snooze.
 * Called at start, after every alert, after settings / alarm changes, boot and time zone changes.
 */
object AlertScheduler {
    const val ACTION_FIRE = "com.nouralwelayah.clock.FIRE"
    const val EXTRA_KIND = "kind"            // KIND_AZAN / KIND_ALARM / KIND_SNOOZE
    const val EXTRA_AZAN = "azan"            // AzanPrayer ordinal
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_AT = "at"                // planned time (millis)
    const val EXTRA_PRAYER_MIN = "prayer_min"
    const val KIND_AZAN = 0
    const val KIND_ALARM = 1
    const val KIND_SNOOZE = 2

    /** Seconds an alert may be late and still ring (AZAN_MISSED_SEC in the firmware). */
    const val MISSED_SEC = 90

    data class Next(val atMillis: Long, val kind: Int, val event: Event?)

    fun nextAlert(context: Context, now: Long = System.currentTimeMillis()): Next? {
        Store.init(context)
        val ev = Planner.upcoming(Store.settings.value, Store.alarms.value, now).firstOrNull()
        val snooze = Store.snoozeUntil
        val sn = if (snooze > now) Next(snooze, KIND_SNOOZE, null) else null
        val evn = ev?.let { Next(it.atMillis, if (it.isAzan) KIND_AZAN else KIND_ALARM, it) }
        return listOfNotNull(sn, evn).minByOrNull { it.atMillis }
    }

    fun reschedule(context: Context) {
        val ctx = context.applicationContext
        val am = Sys.svc(ctx, AlarmManager::class.java)
        val next = nextAlert(ctx)
        val intent = Intent(ctx, AlertReceiver::class.java).setAction(ACTION_FIRE)
        if (next != null) {
            intent.putExtra(EXTRA_KIND, next.kind).putExtra(EXTRA_AT, next.atMillis)
            next.event?.let { e ->
                intent.putExtra(EXTRA_AZAN, e.azan?.ordinal ?: -1)
                intent.putExtra(EXTRA_ALARM_ID, e.alarmId)
                intent.putExtra(EXTRA_PRAYER_MIN, e.prayerMinute)
            }
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (next == null) { am.cancel(pi); Log.i(TAG, "nothing to schedule"); return }

        val show = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next.atMillis, show), pi)
        } else {
            // no exact-alarm permission: may be a few minutes late
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, pi)
        }
        Log.i(TAG, "next alert kind=${next.kind} at ${java.util.Date(next.atMillis)}")
    }

    private const val TAG = "AlertScheduler"
}
