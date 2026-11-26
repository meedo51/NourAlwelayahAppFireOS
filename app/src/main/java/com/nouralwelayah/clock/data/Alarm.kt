package com.nouralwelayah.clock.data

import org.json.JSONObject

const val ALARM_REL_MAX = 180        // minutes before / after a prayer, like the firmware
const val ALARM_MAX_RING_SEC = 300
const val SNOOZE_MIN = 5

/** Same fields as `struct Alarm` in the firmware. */
data class Alarm(
    val id: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,                  // fixed time (relPrayer == -1)
    val days: Int = 0,                    // bit0 = Sunday ... bit6 = Saturday, 0 = once
    val useDate: Boolean = false,         // ring on one date only
    val year: Int = 2026,
    val month: Int = 1,
    val day: Int = 1,
    val ramadan: Boolean = false,         // only ring in Hijri month 9 (works with days)
    val relPrayer: Int = -1,              // -1 = fixed time, else 0 Fajr / 1 Dhuhr / 2 Maghrib
    val relOffset: Int = 0,               // minutes, negative = before the prayer
    val sound: String = "",               // content URI, "" = default alarm sound
    val label: String = "",               // up to 30 characters
    val volume: Int = 15,                 // 0 .. 21, this alarm's own volume
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("enabled", enabled); put("hour", hour); put("minute", minute); put("days", days)
        put("use_date", useDate); put("year", year); put("month", month); put("day", day)
        put("ramadan", ramadan); put("rel_prayer", relPrayer); put("rel_offset", relOffset)
        put("sound", sound); put("label", label); put("volume", volume)
    }

    companion object {
        val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        fun fromJson(o: JSONObject) = Alarm(
            id = o.optLong("id", System.nanoTime()), enabled = o.optBoolean("enabled", true),
            hour = o.optInt("hour", 7).coerceIn(0, 23), minute = o.optInt("minute", 0).coerceIn(0, 59),
            days = o.optInt("days", 0) and 0x7F, useDate = o.optBoolean("use_date", false),
            year = o.optInt("year", 2026), month = o.optInt("month", 1).coerceIn(1, 12), day = o.optInt("day", 1).coerceIn(1, 31),
            ramadan = o.optBoolean("ramadan", false), relPrayer = o.optInt("rel_prayer", -1).coerceIn(-1, 2),
            relOffset = o.optInt("rel_offset", 0).coerceIn(-ALARM_REL_MAX, ALARM_REL_MAX),
            sound = o.optString("sound", ""), label = o.optString("label", "").take(30),
            volume = o.optInt("volume", 15).coerceIn(0, 21),
        )
    }
}
