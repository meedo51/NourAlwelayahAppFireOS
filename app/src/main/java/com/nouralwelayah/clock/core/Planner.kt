package com.nouralwelayah.clock.core

import com.nouralwelayah.clock.data.Alarm
import com.nouralwelayah.clock.data.AppSettings
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Prayer times (minutes after midnight, -1 = none) and Hijri date of one day. */
data class DayTimes(val date: LocalDate, val minutes: IntArray, val hijri: HijriDate) {
    operator fun get(p: Prayer) = minutes[p.ordinal]
}

/** An azan or an alarm at an exact moment. */
data class Event(
    val atMillis: Long,
    val isAzan: Boolean,
    val azan: AzanPrayer? = null,      // azan events
    val alarmId: Long = -1,            // alarm events
    val prayerMinute: Int = -1,        // azan: the prayer time (the event may start azanLeadSec earlier)
)

object Planner {
    /** Like recalc() in scheduler.cpp: UTC offset of that day at noon, method or custom angles. */
    fun day(s: AppSettings, date: LocalDate, zone: ZoneId = s.zone()): DayTimes {
        val noon = ZonedDateTime.of(date, LocalTime.NOON, zone)
        val tz = noon.offset.totalSeconds / 3600.0
        val m = PrayerCalc.compute(
            date.year, date.monthValue, date.dayOfMonth, s.lat, s.lon, tz,
            s.effFajrAngle, s.effMaghribAngle, s.offsetMin.toIntArray(),
        )
        return DayTimes(date, m, Hijri.fromGregorian(date.year, date.monthValue, date.dayOfMonth, s.hijriAdjust))
    }

    /** Minute of the day an alarm rings on that day, -1 = unknown (schedulerAlarmMinute()). */
    fun alarmMinute(a: Alarm, d: DayTimes): Int {
        if (a.relPrayer !in 0..2) return a.hour * 60 + a.minute
        val p = d.minutes[AzanPrayer.entries[a.relPrayer].prayer.ordinal]
        if (p < 0) return -1
        return ((p + a.relOffset) % 1440 + 1440) % 1440
    }

    /** Does the alarm ring on this day (date / weekdays / Ramadan rules of checkAlarms()). */
    fun alarmMatches(a: Alarm, d: DayTimes): Boolean {
        if (!a.enabled) return false
        if (a.useDate) return a.year == d.date.year && a.month == d.date.monthValue && a.day == d.date.dayOfMonth
        val wday = d.date.dayOfWeek.value % 7          // Sunday = 0, like tm_wday
        var match = if (a.days != 0) (a.days and (1 shl wday)) != 0 else true
        if (a.ramadan && d.hijri.month != 9) match = false
        return match
    }

    private fun at(d: DayTimes, minute: Int, zone: ZoneId, secOffset: Int = 0): Long =
        ZonedDateTime.of(d.date, LocalTime.of(minute / 60, minute % 60), zone)
            .plusSeconds(secOffset.toLong()).toInstant().toEpochMilli()

    /** All azans and alarms from `fromMillis` (exclusive) in the next 3 days, sorted. */
    fun upcoming(s: AppSettings, alarms: List<Alarm>, fromMillis: Long, days: Int = 3): List<Event> {
        val zone = s.zone()
        val today = java.time.Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val out = ArrayList<Event>()
        for (k in 0 until days) {
            val d = day(s, today.plusDays(k.toLong()), zone)
            for (az in AzanPrayer.entries) {
                val p = d.minutes[az.prayer.ordinal]
                if (p >= 0 && s.azanOn[az.ordinal]) out += Event(at(d, p, zone, -s.azanLeadSec), true, az, prayerMinute = p)
            }
            for (a in alarms) {
                if (!alarmMatches(a, d)) continue
                val m = alarmMinute(a, d)
                if (m >= 0) out += Event(at(d, m, zone), false, alarmId = a.id)
            }
        }
        return out.filter { it.atMillis > fromMillis }.sortedBy { it.atMillis }
    }

    /** Next of Fajr / Dhuhr / Maghrib after `now` (schedulerNextPrayer()). */
    fun nextPrayer(s: AppSettings, now: ZonedDateTime): Pair<Prayer, ZonedDateTime>? {
        for (k in 0..1) {
            val d = day(s, now.toLocalDate().plusDays(k.toLong()), now.zone)
            for (az in AzanPrayer.entries) {
                val p = d.minutes[az.prayer.ordinal]
                if (p < 0) continue
                val t = ZonedDateTime.of(d.date, LocalTime.of(p / 60, p % 60), now.zone)
                if (t.isAfter(now)) return az.prayer to t
            }
        }
        return null
    }
}

fun fmtMinutes(m: Int, use24h: Boolean): String {
    if (m < 0) return "--:--"
    val h = m / 60; val mm = m % 60
    return if (use24h) String.format(Locale.US, "%02d:%02d", h, mm)
    else String.format(Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, mm, if (h < 12) "AM" else "PM")
}
