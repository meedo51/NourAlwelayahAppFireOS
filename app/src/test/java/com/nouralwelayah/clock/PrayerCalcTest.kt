package com.nouralwelayah.clock

import com.nouralwelayah.clock.core.CALC_METHODS
import com.nouralwelayah.clock.core.Hijri
import com.nouralwelayah.clock.core.PrayerCalc
import org.junit.Assert.assertEquals
import org.junit.Test

class PrayerCalcTest {
    // Safwa, 4 Nov 2026, "Al-Abbas Shrine (Hmomen)" (Fajr 18, Maghrib 4) - same as the firmware
    @Test fun safwa() {
        val m = CALC_METHODS[16]
        val t = PrayerCalc.compute(2026, 11, 4, 26.6497, 49.9553, 3.0, m.fajrAngle, m.maghribAngle)
        assertEquals(listOf(4 * 60 + 33, 5 * 60 + 51, 11 * 60 + 24, 16 * 60 + 56, 17 * 60 + 10), t.toList())
    }

    @Test fun hijri() {
        val h = Hijri.fromGregorian(2026, 11, 4)
        assertEquals(Triple(1448, 5, 24), Triple(h.year, h.month, h.day))
    }
}
