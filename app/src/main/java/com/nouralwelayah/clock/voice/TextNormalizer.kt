package com.nouralwelayah.clock.voice

import java.util.Locale

/**
 * Deterministic Bilingual (Arabic & English) Text Normalizer for the Nour Alwelayah Offline Voice Assistant.
 *
 * Strictly mirrors `compile_kb.py` normalization rules so runtime STT transcripts and user queries
 * match the pre-compiled `intent_patterns` and `intent_patterns_fts` tables 1-to-1.
 */
object TextNormalizer {

    // Arabic Unicode block detection (\u0600-\u06FF)
    private val ARABIC_CHAR_RE = Regex("[\\u0600-\\u06FF]")

    // Arabic diacritics (tashkeel \u0617-\u061A, \u064B-\u0652, superscript alef \u0670) and tatweel (\u0640)
    private val ARABIC_DIACRITICS_RE = Regex("[\\u0617-\\u061A\\u064B-\\u0652\\u0670\\u0640]")

    // Arabic punctuation inside \u0600-\u06FF (comma ، \u060C, semicolon ؛ \u061B, question mark ؟ \u061F, full stop ۔ \u06D4, percent/stars \u066A-\u066D)
    private val ARABIC_PUNCTUATION_RE = Regex("[\\u060C\\u061B\\u061F\\u06D4\\u066A-\\u066D]")

    // Non-word characters for Arabic normalization (preserves Arabic \u0600-\u06FF, ASCII digits, ASCII letters, and whitespace)
    private val NON_WORD_AR_RE = Regex("[^\\u0600-\\u06FF0-9a-zA-Z\\s]")

    // Non-word characters for English normalization (preserves lowercase ASCII letters, digits, and whitespace)
    private val NON_WORD_EN_RE = Regex("[^a-z0-9\\s]")

    // Collapses contiguous whitespace into a single space
    private val MULTI_SPACE_RE = Regex("\\s+")

    /**
     * Exact English transliteration normalization map from `compile_kb.py`.
     */
    val EN_TRANSLITERATIONS: Map<String, String> = mapOf(
        "hussein" to "husayn",
        "hussain" to "husayn",
        "hossein" to "husayn",
        "adhan" to "azan",
        "athaan" to "azan",
        "azaan" to "azan",
        "kumail" to "kumayl",
        "ziyarah" to "ziyarat",
        "ashoora" to "ashura",
        "sajdah" to "sujud",
        "salat" to "salah",
        "marjaa" to "marja",
        "wudhu" to "wudu",
        "wudoo" to "wudu",
    )

    /**
     * Detects whether [text] is Arabic (`"ar"`) or English (`"en"`).
     *
     * @return `"ar"` if any character in the Arabic Unicode range `\u0600-\u06FF` is present, else `"en"`.
     */
    fun detectLanguage(text: String): String {
        if (text.isEmpty()) return "en"
        return if (ARABIC_CHAR_RE.containsMatchIn(text)) "ar" else "en"
    }

    /**
     * Normalizes Arabic text in parity with `compile_kb.py:normalize_ar` plus STT punctuation hygiene:
     * 1. Strips tashkeel/diacritics and tatweel (`[\u0617-\u061A\u064B-\u0652\u0670\u0640]`).
     * 2. Normalizes Alef variants (`أ`, `إ`, `آ`, `ٱ` -> `ا`).
     * 3. Normalizes Teh Marbuta (`ة` -> `ه`), Alef Maksura / Farsi Yeh (`ى`, `ی` -> `ي`), and Farsi Kaf (`ک` -> `ك`).
     * 4. Strips Arabic punctuation (`،`, `؛`, `؟`) and replaces `[^\u0600-\u06FF0-9a-zA-Z\s]` with a space.
     * 5. Collapses contiguous whitespace into a single space and trims.
     */
    fun normalizeAr(text: String): String {
        if (text.isBlank()) return ""
        var out = ARABIC_DIACRITICS_RE.replace(text, "")
        out = out
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace('ی', 'ي')
            .replace('ک', 'ك')
        out = ARABIC_PUNCTUATION_RE.replace(out, " ")
        out = NON_WORD_AR_RE.replace(out, " ")
        return MULTI_SPACE_RE.replace(out, " ").trim()
    }

    /**
     * Normalizes English text in exact parity with `compile_kb.py:normalize_en`:
     * 1. Lowercases with `Locale.ROOT`.
     * 2. Replaces `[^a-z0-9\s]` with a space.
     * 3. Splits into tokens and maps each token through [EN_TRANSLITERATIONS].
     * 4. Joins tokens with a single space and trims.
     */
    fun normalizeEn(text: String): String {
        if (text.isBlank()) return ""
        val lowered = text.lowercase(Locale.ROOT)
        val cleaned = NON_WORD_EN_RE.replace(lowered, " ")
        val tokens = MULTI_SPACE_RE.split(cleaned.trim()).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { tok -> EN_TRANSLITERATIONS[tok] ?: tok }
    }

    /**
     * Normalizes [text] using [normalizeAr] if [lang] is `"ar"`, else [normalizeEn].
     */
    fun normalize(text: String, lang: String = detectLanguage(text)): String =
        if (lang == "ar") normalizeAr(text) else normalizeEn(text)
}
