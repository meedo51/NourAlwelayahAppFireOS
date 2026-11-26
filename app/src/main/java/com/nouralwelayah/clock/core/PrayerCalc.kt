package com.nouralwelayah.clock.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Port of prayer_times.cpp from the ESP32-P4 firmware (V1_3). Same formulas, same rounding,
// so the app and the clocks show the same times.

/** Shown times. Maghrib = when the sun is maghribAngle degrees below the horizon. */
enum class Prayer(val label: String) { FAJR("Fajr"), SUNRISE("Sunrise"), DHUHR("Dhuhr"), SUNSET("Sunset"), MAGHRIB("Maghrib") }

/** Prayers that have an azan (also used for alarms "before / after prayer"). */
enum class AzanPrayer(val prayer: Prayer) { FAJR(Prayer.FAJR), DHUHR(Prayer.DHUHR), MAGHRIB(Prayer.MAGHRIB) }

const val MAGHRIB_SUNSET_ANGLE = 0.833
const val FAJR_ANGLE_MIN = 14.0
const val FAJR_ANGLE_MAX = 19.9
const val MAGHRIB_ANGLE_MIN = MAGHRIB_SUNSET_ANGLE
const val MAGHRIB_ANGLE_MAX = 9.9

data class CalcMethod(val name: String, val fajrAngle: Double, val maghribAngle: Double)

/** Keep the order: the settings store the index. New methods go at the end (same list as the firmware). */
val CALC_METHODS: List<CalcMethod> = listOf(
    CalcMethod("Muslim World League",        18.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("ISNA (North America)",       15.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Egyptian Authority",         19.5, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Umm al-Qura (Makkah)",       18.5, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Karachi",                    18.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Gulf Region",                19.5, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Kuwait",                     18.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Qatar",                      18.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Singapore / Malaysia",       20.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Turkey (Diyanet)",           18.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Dubai",                      18.2, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("France (UOIF 12)",           12.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Russia",                     16.0, MAGHRIB_SUNSET_ANGLE),
    CalcMethod("Tehran",                     17.7, 4.5),
    CalcMethod("Jafari (Shia Ithna Ashari)", 16.0, 4.0),
    CalcMethod("Bahrain (Prayer Times app)", 18.0, 4.0),   // com.smustafa.praytimes "Bahrain"
    CalcMethod("Al-Abbas Shrine (Hmomen)",   18.0, 4.0),   // Haqibat al-Mu'min "Ithna Ashari"
)

const val DEFAULT_METHOD = 16   // Al-Abbas Shrine (Hmomen)

fun isSunsetAngle(a: Double) = a <= MAGHRIB_SUNSET_ANGLE + 0.0005

/** "0.833" for the sunset angle, else one decimal ("4.0"). */
fun fmtAngle(a: Double): String =
    if (abs(a - MAGHRIB_SUNSET_ANGLE) < 0.0005) "0.833" else String.format(java.util.Locale.US, "%.1f", a)

object PrayerCalc {
    private fun dtr(d: Double) = d * Math.PI / 180.0
    private fun rtd(r: Double) = r * 180.0 / Math.PI
    private fun dsin(d: Double) = sin(dtr(d))
    private fun dcos(d: Double) = cos(dtr(d))
    private fun darcsin(x: Double) = rtd(asin(x))
    private fun darccos(x: Double) = rtd(acos(x))
    private fun darctan2(y: Double, x: Double) = rtd(atan2(y, x))
    private fun fixA(a: Double): Double { val r = a - 360.0 * floor(a / 360.0); return if (r < 0) r + 360.0 else r }
    private fun fixH(h: Double): Double { val r = h - 24.0 * floor(h / 24.0); return if (r < 0) r + 24.0 else r }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year; var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private class Sun(val decl: Double, val eqt: Double)

    private fun sunPos(jd: Double): Sun {
        val d = jd - 2451545.0
        val g = fixA(357.529 + 0.98560028 * d)
        val q = fixA(280.459 + 0.98564736 * d)
        val l = fixA(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        return Sun(darcsin(dsin(e) * dsin(l)), q / 15.0 - fixH(ra))
    }

    private fun midDay(jd: Double, t: Double) = fixH(12.0 - sunPos(jd + t).eqt)

    private fun sunAngleTime(jd: Double, lat: Double, angle: Double, t: Double, ccw: Boolean): Double {
        val decl = sunPos(jd + t).decl
        val noon = midDay(jd, t)
        val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
        if (x < -1.0 || x > 1.0) return Double.NaN
        val tt = darccos(x) / 15.0
        return noon + if (ccw) -tt else tt
    }

    /**
     * Minutes after local midnight for each [Prayer], -1 if not computable.
     * tzHours = UTC offset of that day (with daylight saving), offsets = per-prayer minute corrections.
     */
    fun compute(
        year: Int, month: Int, day: Int, lat: Double, lon: Double, tzHours: Double,
        fajrAngleIn: Double, maghribAngleIn: Double, offsets: IntArray? = null,
    ): IntArray {
        // the firmware keeps angles as float: round the same way so the times match the clocks
        val fajrAngle = fajrAngleIn.toFloat().toDouble()
        val maghribAngle = maghribAngleIn.toFloat().toDouble()
        val jd = julian(year, month, day) - lon / (15.0 * 24.0)
        val def = doubleArrayOf(5.0, 6.0, 12.0, 18.0, 18.0)
        val t = doubleArrayOf(5.0, 6.0, 12.0, 18.0, 18.0)
        repeat(2) {
            val p = DoubleArray(5) { i -> (if (t[i].isNaN()) def[i] else t[i]) / 24.0 }
            t[0] = sunAngleTime(jd, lat, fajrAngle, p[0], true)
            t[1] = sunAngleTime(jd, lat, 0.833, p[1], true)
            t[2] = midDay(jd, p[2])
            t[3] = sunAngleTime(jd, lat, 0.833, p[3], false)
            t[4] = sunAngleTime(jd, lat, maghribAngle, p[4], false)
        }
        val shift = tzHours - lon / 15.0
        for (i in t.indices) t[i] += shift

        // high-latitude correction for Fajr and Maghrib (angle-based)
        if (!t[1].isNaN() && !t[3].isNaN()) {
            val night = fixH(t[1] - t[3])
            val fp = fajrAngle / 60.0 * night
            if (t[0].isNaN() || fixH(t[1] - t[0]) > fp) t[0] = t[1] - fp
            val mp = maghribAngle / 60.0 * night
            if (t[4].isNaN() || fixH(t[4] - t[3]) > mp) t[4] = t[3] + mp
        }
        return IntArray(5) { i ->
            if (t[i].isNaN()) -1 else {
                var mm = floor(fixH(t[i]) * 60.0 + 0.5).toInt()
                if (offsets != null) mm += offsets[i]
                ((mm % 1440) + 1440) % 1440
            }
        }
    }
}
