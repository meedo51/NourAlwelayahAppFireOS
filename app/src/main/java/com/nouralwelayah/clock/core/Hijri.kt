package com.nouralwelayah.clock.core

// Port of hiri.cpp / UQCal.cpp from the firmware: Umm al-Qura table, tabular calendar outside it.

import android.content.Context
import com.nouralwelayah.clock.R
import java.util.Locale

data class HijriDate(val year: Int, val month: Int, val day: Int) {
    val monthName: String get() = Hijri.monthName(month)
    val monthNameAr: String get() = Hijri.monthNameAr(month)

    /** Formats the Hijri date in English ("15 Rajab 1446 AH") or Arabic ("15 رجب 1446 هـ"). */
    fun format(languageCode: String): String {
        val isAr = languageCode.trim().lowercase(Locale.ROOT).startsWith("ar")
        return if (isAr) {
            "$day $monthNameAr $year هـ"
        } else {
            "$day $monthName $year AH"
        }
    }

    /** Formats the Hijri date using localized string-array resources when a [Context] is provided. */
    fun format(context: Context, languageCode: String): String {
        val months = runCatching { context.resources.getStringArray(R.array.hijri_months) }.getOrNull()
        val resolvedMonth = if (months != null && month in 1..months.size) {
            months[month - 1]
        } else if (languageCode.trim().lowercase(Locale.ROOT).startsWith("ar")) {
            monthNameAr
        } else {
            monthName
        }
        val suffix = runCatching { context.getString(R.string.hijri_era_suffix) }.getOrDefault(
            if (languageCode.trim().lowercase(Locale.ROOT).startsWith("ar")) "هـ" else "AH"
        )
        return "$day $resolvedMonth $year $suffix"
    }

    override fun toString() = "$day $monthName $year AH"
}

object Hijri {
    private val MONTHS = arrayOf(
        "Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani", "Jumada al-Awwal", "Jumada al-Thani",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah",
    )

    private val MONTHS_AR = arrayOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
    )

    fun monthName(m: Int) = if (m in 1..12) MONTHS[m - 1] else "?"
    fun monthNameAr(m: Int) = if (m in 1..12) MONTHS_AR[m - 1] else "؟"

    /** Gregorian -> Julian day number. */
    fun jdn(y: Int, m: Int, d: Int): Long {
        val a = (14 - m) / 12
        val yy = (y + 4800 - a).toLong()
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }

    private fun tabular(j: Long): HijriDate {
        var l = j - 1948440 + 10632
        val n = (l - 1) / 10631
        l = l - 10631 * n + 354
        val k = ((10985 - l) / 5316) * ((50 * l) / 17719) + (l / 5670) * ((43 * l) / 15238)
        l = l - ((30 - k) / 15) * ((17719 * k) / 50) - (k / 16) * ((15238 * k) / 43) + 29
        val m = (24 * l) / 709
        return HijriDate((30 * n + k - 30).toInt(), m.toInt(), (l - (709 * m) / 24).toInt())
    }

    /** adjustDays (-2..+2) shifts the result for local moon sighting. The date changes at midnight. */
    fun fromGregorian(gy: Int, gm: Int, gd: Int, adjustDays: Int = 0): HijriDate {
        val j = jdn(gy, gm, gd) + adjustDays
        val mj = (j - 2400000).toInt()
        val tbl = UmmAlQuraData.MONTH_STARTS
        if (mj >= tbl.first() && mj < tbl.last()) {
            var i = 0
            while (i < tbl.size && tbl[i] <= mj) i++
            val iln = i + 16260
            val ii = (iln - 1) / 12
            return HijriDate(ii + 1, iln - 12 * ii, mj - tbl[i - 1] + 1)
        }
        return tabular(j)
    }
}
