package com.nouralwelayah.clock.voice

import com.nouralwelayah.clock.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Offline Regression Evaluation Suite for the Nour Alwelayah Bilingual Voice Retrieval Pipeline
 * and Local Phrase Memory Guardrails.
 *
 * Evaluates held-out bilingual test utterances — including realistic Whisper `tiny` Arabic
 * phonetic/diacritic/taa-marbuta/hamza drift and short 1–3 word English commands — against
 * all 122 published intents across 10 subcategories in `nour_alwelayah_appliance_commands_kb.csv`
 * using the production [TextNormalizer] and [KbRetrievalEngine] lexical scoring pipeline.
 */
class VoiceRetrievalEvalTest {

    @Test
    fun heldOutBilingualUtterances_achieveAtLeast90PercentRetrievalAccuracy() {
        val heldOutCases: List<Pair<String, String>> = listOf(
            // ---- 24 Arabic utterances across all 10 subcategories with Whisper phonetic/diacritic drift ----
            "متى صلاه الفجر؟" to "prayer_fajr_time",
            "مَتى أَذَانُ الْمَغْرِبِ" to "prayer_maghrib_time",
            "وقت الظهر كم" to "prayer_dhuhr_time",
            "مواعيد الصلاة اليوم" to "prayer_today_all",
            "اوقات الصلاة غدا" to "prayer_tomorrow_all",
            "كم باقي على الصلاة الجاية" to "prayer_next_time",
            "كم باقي على الافطار" to "prayer_countdown_maghrib",
            "اَلسَّاعَةُ كَم الآن؟" to "query_current_time",
            "كم التاريخ الهجري اليوم" to "query_hijri_date",
            "التاريخ الميلادي كم" to "query_gregorian_date",
            "كم شحن البطارية" to "query_battery_status",
            "إتجاه القبلة" to "query_qibla_direction",
            "ارفع الصوت" to "volume_up",
            "وطي الصوت" to "volume_down",
            "اكتم الصوت" to "volume_mute",
            "رجع الصوت" to "volume_unmute",
            "رجع المقطع" to "media_seek_backward",
            "قدم ثلاثين ثانية" to "media_seek_forward",
            "زود السطوع" to "display_brightness_up",
            "خفف الاضاءه" to "display_brightness_down",
            "الخلفية التالية" to "display_next_wallpaper",
            "افتح الاعدادات" to "nav_tab_settings",
            "شغل نقطة الاتصال" to "net_hotspot_start",
            "التعرف على الصوت بالعربية" to "sys_voice_mode_arabic",

            // ---- 24 Short 1–4 word English commands across all 10 subcategories ----
            "Fajr time?" to "prayer_fajr_time",
            "Maghrib time" to "prayer_maghrib_time",
            "Next prayer" to "prayer_next_time",
            "Today's prayer times" to "prayer_today_all",
            "Tomorrow's prayer times" to "prayer_tomorrow_all",
            "What time is it" to "query_current_time",
            "Hijri date?" to "query_hijri_date",
            "Battery level" to "query_battery_status",
            "Qibla direction" to "query_qibla_direction",
            "How many days until Ramadan" to "query_countdown_ramadan",
            "Louder" to "volume_up",
            "Lower volume" to "volume_down",
            "Mute" to "volume_mute",
            "Unmute" to "volume_unmute",
            "Brighter" to "display_brightness_up",
            "Lower brightness" to "display_brightness_down",
            "Next wallpaper" to "display_next_wallpaper",
            "Pause playback" to "media_pause",
            "Next track" to "media_next_track",
            "Fast forward 30 seconds" to "media_seek_forward",
            "Rewind 30 seconds" to "media_seek_backward",
            "Open settings" to "nav_tab_settings",
            "Start tablet hotspot" to "net_hotspot_start",
            "Voice recognition in English" to "sys_voice_mode_english",
        )

        assertTrue("Eval suite must contain at least 36 held-out utterances", heldOutCases.size >= 36)

        var passed = 0
        val failures = mutableListOf<String>()

        for ((utterance, expectedIntentId) in heldOutCases) {
            val ranked = evaluateAgainstKb(utterance)
            val top = ranked.firstOrNull()
            if (top != null && top.intentId == expectedIntentId && top.score >= KbRetrievalEngine.DEFAULT_MIN_SCORE_THRESHOLD) {
                passed++
            } else {
                val topDesc = top?.let { "${it.intentId} (score=${"%.3f".format(it.score)})" } ?: "NO_MATCH"
                val runnerUps = ranked.take(3).joinToString(", ") { "${it.intentId}:${"%.2f".format(it.score)}" }
                failures.add("Query='$utterance' | Expected=$expectedIntentId | Got=$topDesc | Top3=[$runnerUps]")
            }
        }

        val passRate = passed.toDouble() / heldOutCases.size.toDouble()
        assertTrue(
            "Retrieval eval pass rate ${(passRate * 100).toInt()}% ($passed/${heldOutCases.size}) " +
                "is below required 90% threshold.\nFailures:\n${failures.joinToString("\n")}",
            passRate >= 0.90,
        )
    }

    @Test
    fun resolvedArabicPatternCollision_disambiguatesVolumeUnmuteAndMediaSeekBackward() {
        val unmuteRanked = evaluateAgainstKb("رجع الصوت")
        assertNotNull(unmuteRanked.firstOrNull())
        assertEquals("volume_unmute", unmuteRanked.first().intentId)
        assertEquals(1.0f, unmuteRanked.first().score, 0.001f)

        val seekBackRanked = evaluateAgainstKb("رجع المقطع")
        assertNotNull(seekBackRanked.firstOrNull())
        assertEquals("media_seek_backward", seekBackRanked.first().intentId)
        assertEquals(1.0f, seekBackRanked.first().score, 0.001f)
    }

    @Test
    fun canonicalExactMatch_alwaysBeatsLearnedPhrase() {
        val now = 1_750_000_000_000L
        val learned = LearnedPhraseEntry(
            normalizedTranscript = "volume up",
            intentId = "volume_mute",
            hitCount = 10,
            lastUsedEpochMs = now,
            createdEpochMs = now - 10_000L,
        )

        val winner = VoiceLearningPolicy.resolvePriorityOverride(
            canonicalExactIntentId = "volume_up",
            learnedEntry = learned,
            nowMs = now,
        )

        assertNotNull(winner)
        assertEquals("volume_up", winner?.first)
        assertEquals(KbRetrievalOutcome.MATCH_SOURCE_CANONICAL_EXACT, winner?.second)
    }

    @Test
    fun learnedPhrase_requiresAtLeastTwoHitsToPromote() {
        val now = 1_750_000_000_000L
        val singleHit = LearnedPhraseEntry(
            normalizedTranscript = "dim lights now",
            intentId = "display_brightness_down",
            hitCount = 1,
            lastUsedEpochMs = now,
            createdEpochMs = now,
        )
        assertFalse(VoiceLearningPolicy.isEligibleForAutoPromotion(singleHit, now))
        assertNull(VoiceLearningPolicy.resolvePriorityOverride(null, singleHit, now))

        val twoHits = singleHit.copy(hitCount = 2)
        assertTrue(VoiceLearningPolicy.isEligibleForAutoPromotion(twoHits, now))
        val winner = VoiceLearningPolicy.resolvePriorityOverride(null, twoHits, now)
        assertNotNull(winner)
        assertEquals("display_brightness_down", winner?.first)
        assertEquals(KbRetrievalOutcome.MATCH_SOURCE_LEARNED_PHRASE, winner?.second)
    }

    @Test
    fun learnedPhrasePruning_enforces250EntryLruCapAnd90DayExpiry() {
        val now = 1_750_000_000_000L
        val ninetyOneDaysAgo = now - (91L * 24L * 60L * 60L * 1000L)

        val expiredEntry = LearnedPhraseEntry(
            normalizedTranscript = "old_expired_phrase",
            intentId = "volume_up",
            hitCount = 5,
            lastUsedEpochMs = ninetyOneDaysAgo,
            createdEpochMs = ninetyOneDaysAgo,
        )
        assertFalse(VoiceLearningPolicy.isEligibleForAutoPromotion(expiredEntry, now))

        // Build 280 fresh entries + 1 expired entry -> pruning must drop the expired entry
        // and retain only the 250 most-recently-used entries.
        val allEntries = buildList {
            add(expiredEntry)
            for (i in 1..280) {
                add(
                    LearnedPhraseEntry(
                        normalizedTranscript = "phrase_$i",
                        intentId = "volume_up",
                        hitCount = 2,
                        lastUsedEpochMs = now - ((280 - i) * 1_000L),
                        createdEpochMs = now - 10_000_000L,
                    )
                )
            }
        }

        val pruned = VoiceLearningPolicy.pruneEntries(allEntries, now)
        assertEquals(VoiceLearningPolicy.MAX_LEARNED_PHRASES, pruned.size)
        assertTrue(pruned.none { it.normalizedTranscript == "old_expired_phrase" })
        assertEquals("phrase_280", pruned.first().normalizedTranscript)
        assertEquals("phrase_31", pruned.last().normalizedTranscript)
    }

    @Test
    fun appSettings_voiceRecognitionModeMapsCorrectlyToWhisperLanguage() {
        assertEquals("", AppSettings(voiceRecognitionMode = AppSettings.VOICE_MODE_AUTO).whisperLanguageCode())
        assertEquals("ar", AppSettings(voiceRecognitionMode = AppSettings.VOICE_MODE_AR).whisperLanguageCode())
        assertEquals("en", AppSettings(voiceRecognitionMode = AppSettings.VOICE_MODE_EN).whisperLanguageCode())
        assertEquals("", AppSettings(voiceRecognitionMode = "invalid_value").validated().whisperLanguageCode())
    }

    @Test
    fun appSettings_wakeWordDefaultsAndSensitivityClamping() {
        val defaultSettings = AppSettings()
        assertFalse(defaultSettings.wakeWordEnabled)
        assertEquals(0.25f, defaultSettings.wakeWordSensitivity, 0.0001f)

        val tooLow = AppSettings(wakeWordEnabled = true, wakeWordSensitivity = 0.02f).validated()
        assertTrue(tooLow.wakeWordEnabled)
        assertEquals(AppSettings.MIN_WAKE_WORD_SENSITIVITY, tooLow.wakeWordSensitivity, 0.0001f)

        val tooHigh = AppSettings(wakeWordEnabled = true, wakeWordSensitivity = 0.95f).validated()
        assertEquals(AppSettings.MAX_WAKE_WORD_SENSITIVITY, tooHigh.wakeWordSensitivity, 0.0001f)
    }

    @Test
    fun kwsKeywordsAsset_isBomFreeAndEveryTokenExistsInTokensTxt() {
        val kwsDir = listOf(
            File("src/main/assets/voice_models/kws"),
            File("app/src/main/assets/voice_models/kws"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("KWS assets directory must exist", kwsDir)

        val tokensFile = File(kwsDir!!, "tokens.txt")
        val keywordsFile = File(kwsDir, "keywords.txt")
        assertTrue("tokens.txt must exist", tokensFile.isFile)
        assertTrue("keywords.txt must exist", keywordsFile.isFile)

        val rawBytes = keywordsFile.readBytes()
        assertFalse(
            "keywords.txt must NOT contain a UTF-8 BOM",
            rawBytes.size >= 3 &&
                rawBytes[0] == 0xEF.toByte() &&
                rawBytes[1] == 0xBB.toByte() &&
                rawBytes[2] == 0xBF.toByte()
        )
        assertFalse("keywords.txt must use Unix LF (\\n) line endings without CR (\\r)", rawBytes.contains('\r'.code.toByte()))

        val validTokens = tokensFile.readLines(Charsets.UTF_8)
            .map { it.removePrefix("\uFEFF").trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")).first() }
            .toSet()
        assertTrue("tokens.txt must contain BPE vocabulary tokens", validTokens.isNotEmpty())

        val keywordLines = keywordsFile.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("keywords.txt must contain phonetic variants for @Welayah", keywordLines.isNotEmpty())

        for ((idx, line) in keywordLines.withIndex()) {
            assertFalse("keywords.txt must not contain temporary @HelloTest", line.contains("@HelloTest", ignoreCase = true))
            assertTrue("Line ${idx + 1} must target @Welayah", line.contains("@Welayah"))
            val boundary = line.indexOfFirst { it == ':' || it == '#' || it == '@' }
            val tokenSection = (if (boundary >= 0) line.substring(0, boundary) else line).trim()
            val tokens = tokenSection.split(Regex("\\s+")).filter { it.isNotEmpty() }
            assertTrue("Line ${idx + 1} must have BPE tokens", tokens.isNotEmpty())
            val missing = tokens.filter { it !in validTokens }
            assertTrue("Line ${idx + 1} contains tokens not in tokens.txt: $missing", missing.isEmpty())
        }
    }

    private data class ScoredIntent(
        val intentId: String,
        val priority: Int,
        val score: Float,
    )

    private fun evaluateAgainstKb(rawQuery: String): List<ScoredIntent> {
        val lang = TextNormalizer.detectLanguage(rawQuery)
        val normalized = TextNormalizer.normalize(rawQuery, lang)
        val queryTokens = KbRetrievalEngine.extractTokens(normalized).toSet()

        // Exact canonical non-keyword check first (mirrors KbRetrievalEngine Rule 1)
        val exact = kbPatterns.firstOrNull { p ->
            p.lang == lang && !p.isKeyword && p.normalizedText == normalized
        }
        if (exact != null) {
            return listOf(ScoredIntent(exact.intentId, exact.priority, 1.0f))
        }

        val bestByIntent = LinkedHashMap<String, ScoredIntent>()
        val tokenBagByIntent = HashMap<String, MutableSet<String>>()
        for (p in kbPatterns) {
            if (p.lang != lang) continue
            val rowTokens = KbRetrievalEngine.extractTokens(p.normalizedText)
            if (rowTokens.isNotEmpty()) {
                tokenBagByIntent.getOrPut(p.intentId) { HashSet() }.addAll(rowTokens)
            }
            val score = KbRetrievalEngine.computeLexicalScore(
                normalizedQuery = normalized,
                queryTokens = queryTokens,
                patternText = p.normalizedText,
                isKeyword = p.isKeyword,
                rawBm25 = -3.0,
            )
            if (score <= 0f) continue
            val existing = bestByIntent[p.intentId]
            if (existing == null || score > existing.score) {
                bestByIntent[p.intentId] = ScoredIntent(p.intentId, p.priority, score)
            }
        }

        for ((intentId, scored) in bestByIntent.entries.toList()) {
            val bag = tokenBagByIntent[intentId] ?: continue
            val bagScore = KbRetrievalEngine.computeIntentBagScore(queryTokens, bag)
            if (bagScore > scored.score) {
                bestByIntent[intentId] = scored.copy(score = bagScore)
            }
        }

        return bestByIntent.values.sortedWith { a, b ->
            val scoreDiff = b.score.compareTo(a.score)
            if (abs(a.score - b.score) > 0.015f && scoreDiff != 0) {
                scoreDiff
            } else {
                val prioDiff = a.priority.compareTo(b.priority)
                if (prioDiff != 0) prioDiff else scoreDiff
            }
        }
    }

    companion object {
        private data class KbPatternRecord(
            val intentId: String,
            val lang: String,
            val normalizedText: String,
            val isKeyword: Boolean,
            val priority: Int,
        )

        private lateinit var kbPatterns: List<KbPatternRecord>

        @JvmStatic
        @BeforeClass
        fun loadKbCsv() {
            val csvText = loadCsvText().removePrefix("\uFEFF")
            val rows = parseCsv(csvText)
            assertTrue("KB CSV must have header and rows", rows.size > 100)

            val header = rows.first()
            val colIdx = header.withIndex().associate { (idx, name) -> name.removePrefix("\uFEFF").trim() to idx }
            fun col(row: List<String>, name: String): String =
                colIdx[name]?.let { row.getOrNull(it) }.orEmpty().trim()

            val records = ArrayList<KbPatternRecord>(1600)
            var publishedIntents = 0
            val subcategories = mutableSetOf<String>()
            for (row in rows.drop(1)) {
                if (row.size < 10) continue
                if (!col(row, "status").equals("published", ignoreCase = true)) continue
                val intentId = col(row, "intent_id")
                if (intentId.isBlank()) continue
                publishedIntents++
                subcategories.add(col(row, "subcategory"))
                val priority = col(row, "priority").toIntOrNull() ?: 2

                fun addPipeField(fieldName: String, lang: String, isKeyword: Boolean) {
                    val raw = col(row, fieldName)
                    if (raw.isEmpty()) return
                    raw.split('|').forEach { part ->
                        val norm = TextNormalizer.normalize(part, lang)
                        if (norm.isNotBlank()) {
                            records.add(KbPatternRecord(intentId, lang, norm, isKeyword, priority))
                        }
                    }
                }

                addPipeField("patterns_en", "en", isKeyword = false)
                addPipeField("keywords_en", "en", isKeyword = true)
                addPipeField("patterns_ar", "ar", isKeyword = false)
                addPipeField("keywords_ar", "ar", isKeyword = true)
            }

            assertEquals("Expected 122 published intents in KB CSV", 122, publishedIntents)
            assertEquals("Expected 10 subcategories in KB CSV", 10, subcategories.size)
            kbPatterns = records
        }

        private fun loadCsvText(): String {
            // 1. Workspace canonical & uploaded CSV files
            val candidatePaths = listOf(
                "scripts/nour_alwelayah_appliance_commands_kb.csv",
                "../scripts/nour_alwelayah_appliance_commands_kb.csv",
                "public/assets/aistudio/nour_alwelayah_appliance_commands_kb_29ff1b5a.csv",
                "../public/assets/aistudio/nour_alwelayah_appliance_commands_kb_29ff1b5a.csv",
                "public/assets/aistudio/nour_alwelayah_appliance_commands_kb_9073ab75.csv",
                "../public/assets/aistudio/nour_alwelayah_appliance_commands_kb_9073ab75.csv",
            )
            for (path in candidatePaths) {
                val f = File(path)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }

            // 2. Fallback classpath test resource
            VoiceRetrievalEvalTest::class.java
                .getResourceAsStream("/nour_alwelayah_appliance_commands_kb.csv")
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { return it.readText() }

            error("Could not locate nour_alwelayah_appliance_commands_kb.csv in workspace or test resources.")
        }

        /** Minimal RFC-4180 CSV parser handling quoted fields containing commas. */
        private fun parseCsv(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var currentRow = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (inQuotes) {
                    if (ch == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                } else {
                    when (ch) {
                        '"' -> inQuotes = true
                        ',' -> {
                            currentRow.add(field.toString())
                            field.setLength(0)
                        }
                        '\r' -> { /* ignore CR */ }
                        '\n' -> {
                            currentRow.add(field.toString())
                            field.setLength(0)
                            if (currentRow.any { it.isNotBlank() }) rows.add(currentRow)
                            currentRow = mutableListOf()
                        }
                        else -> field.append(ch)
                    }
                }
                i++
            }
            if (field.isNotEmpty() || currentRow.isNotEmpty()) {
                currentRow.add(field.toString())
                if (currentRow.any { it.isNotBlank() }) rows.add(currentRow)
            }
            return rows
        }
    }
}
