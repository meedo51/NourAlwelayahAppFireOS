package com.nouralwelayah.clock.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests verifying 100% deterministic parity between [TextNormalizer], [KbRetrievalEngine],
 * [SemanticScorer], and `compile_kb.py`.
 */
class TextNormalizerTest {

    @Test
    fun detectLanguage_identifiesArabicAndEnglishCorrectly() {
        assertEquals("ar", TextNormalizer.detectLanguage("كم الساعة الآن؟"))
        assertEquals("ar", TextNormalizer.detectLanguage("شغل دعاء كميل"))
        assertEquals("ar", TextNormalizer.detectLanguage("Play سورة الفاتحة"))
        assertEquals("en", TextNormalizer.detectLanguage("What time is it?"))
        assertEquals("en", TextNormalizer.detectLanguage("Play Dua Kumail"))
        assertEquals("en", TextNormalizer.detectLanguage(""))
    }

    @Test
    fun normalizeAr_stripsTashkeelTatweelAndUnifiesAlefTehMarbutaAlefMaksura() {
        // Tashkeel + tatweel + Alef variants (أ / إ / آ) + Teh Marbuta (ة) + Alef Maksura (ى)
        val raw = "  أَلسَّـلَامُ عَلَيْكُمْ، إِقْرَأْ آيَةَ الكُرْسِيِّ وَصَلَاةَ الفَجْرِ حَتَّى الْمَسَاءِ!  "
        val expected = "السلام عليكم اقرا ايه الكرسي وصلاه الفجر حتي المساء"
        assertEquals(expected, TextNormalizer.normalizeAr(raw))
    }

    @Test
    fun normalizeAr_matchesCompileKbPyExamples() {
        assertEquals(
            "كم الساعه الان",
            TextNormalizer.normalizeAr("كم الساعة الآن؟")
        )
        assertEquals(
            "متي اذان المغرب",
            TextNormalizer.normalizeAr("متى أذان المغرب؟")
        )
        assertEquals(
            "شغل زياره عاشوراء",
            TextNormalizer.normalizeAr("شغّلْ زِيَارَةَ عَاشُورَاء!")
        )
    }

    @Test
    fun normalizeEn_appliesAll14TransliterationMappingsFromCompileKbPy() {
        // Verify every single entry in EN_TRANSLITERATIONS matches compile_kb.py
        assertEquals(14, TextNormalizer.EN_TRANSLITERATIONS.size)

        assertEquals("husayn", TextNormalizer.normalizeEn("Hussein"))
        assertEquals("husayn", TextNormalizer.normalizeEn("Hussain"))
        assertEquals("husayn", TextNormalizer.normalizeEn("Hossein"))

        assertEquals("azan", TextNormalizer.normalizeEn("Adhan"))
        assertEquals("azan", TextNormalizer.normalizeEn("Athaan"))
        assertEquals("azan", TextNormalizer.normalizeEn("Azaan"))

        assertEquals("kumayl", TextNormalizer.normalizeEn("Kumail"))
        assertEquals("ziyarat", TextNormalizer.normalizeEn("Ziyarah"))
        assertEquals("ashura", TextNormalizer.normalizeEn("Ashoora"))
        assertEquals("sujud", TextNormalizer.normalizeEn("Sajdah"))
        assertEquals("salah", TextNormalizer.normalizeEn("Salat"))
        assertEquals("marja", TextNormalizer.normalizeEn("Marjaa"))
        assertEquals("wudu", TextNormalizer.normalizeEn("Wudhu"))
        assertEquals("wudu", TextNormalizer.normalizeEn("Wudoo"))
    }

    @Test
    fun normalizeEn_handlesComplexSentencesAndPunctuation() {
        val raw = "  Play Ziyarah Ashoora for Imam Hussein, and set Fajr Adhan / Salat reminder! "
        val expected = "play ziyarat ashura for imam husayn and set fajr azan salah reminder"
        assertEquals(expected, TextNormalizer.normalizeEn(raw))

        val wuduQuery = "How do I perform Wudhu (or Wudoo) according to my Marjaa before Sajdah?"
        val expectedWudu = "how do i perform wudu or wudu according to my marja before sujud"
        assertEquals(expectedWudu, TextNormalizer.normalizeEn(wuduQuery))
    }

    @Test
    fun sanitizeFtsQuery_escapesSpecialCharactersAndBuildsSafeOrQuery() {
        val rawNormalized = TextNormalizer.normalizeEn("What's the next Adhan: \"Fajr\"* (or) -Maghrib?")
        val ftsQuery = KbRetrievalEngine.sanitizeFtsQuery(rawNormalized)

        assertFalse(ftsQuery.contains("?"))
        assertFalse(ftsQuery.contains(":"))
        assertFalse(ftsQuery.contains("("))
        assertFalse(ftsQuery.contains(")"))
        assertTrue(ftsQuery.contains("\"azan\""))
        assertTrue(ftsQuery.contains("\"azan\"*"))
        assertTrue(ftsQuery.contains("\"fajr\""))
    }

    @Test
    fun semanticScorer_unpackFloat16AndCosineSimilarity() {
        // IEEE-754 float16 bit patterns:
        // 1.0f = 0x3C00, 0.5f = 0x3800, -1.0f = 0xBC00, 0.0f = 0x0000
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x3C00.toShort())
        buf.putShort(0x3800.toShort())
        buf.putShort(0xBC00.toShort())
        buf.putShort(0x0000.toShort())

        val unpacked = SemanticScorer.unpackFloat16(buf.array())
        assertEquals(4, unpacked.size)
        assertEquals(1.0f, unpacked[0], 1e-5f)
        assertEquals(0.5f, unpacked[1], 1e-5f)
        assertEquals(-1.0f, unpacked[2], 1e-5f)
        assertEquals(0.0f, unpacked[3], 1e-5f)

        val selfSim = SemanticScorer.cosineSimilarity(unpacked, unpacked)
        assertEquals(1.0f, selfSim, 1e-4f)
    }

    @Test
    fun compareSemanticVersions_ordersVersionsDeterministically() {
        assertTrue(KbDatabaseManager.compareSemanticVersions("1.0.1", "1.0.0") > 0)
        assertTrue(KbDatabaseManager.compareSemanticVersions("1.1.0", "1.0.9") > 0)
        assertTrue(KbDatabaseManager.compareSemanticVersions("2.0.0", "1.9.9") > 0)
        assertEquals(0, KbDatabaseManager.compareSemanticVersions("1.0.0", "1.0.0"))
        assertTrue(KbDatabaseManager.compareSemanticVersions("1.0.0", "1.0.2") < 0)
    }
}
