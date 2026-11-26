package com.nouralwelayah.clock

import com.nouralwelayah.clock.data.CityFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** cities.txt time zones, as the firmware's citiesResolveTz accepts them (POSIX rules: firmware only). */
class CityFileTest {
    @Test fun zones() {
        assertEquals("Asia/Riyadh", CityFile.zoneOf("Asia/Riyadh"))
        assertEquals("Asia/Riyadh", CityFile.zoneOf("Riyadh / Makkah (+3)"))
        assertEquals("+03:30", CityFile.zoneOf("+3:30"))
        assertEquals("+05:30", CityFile.zoneOf("5.5"))
        assertEquals("-05:00", CityFile.zoneOf("-5"))
        assertEquals("+03:00", CityFile.zoneOf("UTC+3"))
        assertNull(CityFile.zoneOf("EET-2EEST,M3.5.0/0,M10.5.0/0"))
        assertNull(CityFile.zoneOf("Mars/Olympus"))
    }
}

class ClockNameTest {
    @Test fun names() {
        val v = com.nouralwelayah.clock.data.ClockName::valid
        org.junit.Assert.assertTrue(v("") && v("Fire-Clock") && v("Living Room 2") && v("a.b_c"))
        org.junit.Assert.assertFalse(v(" x") || v("x ") || v("---") || v("Ä") || v("a;b") || v("x".repeat(21)))
    }
}
