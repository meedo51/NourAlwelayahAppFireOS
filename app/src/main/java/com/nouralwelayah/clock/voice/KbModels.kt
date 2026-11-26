package com.nouralwelayah.clock.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Supported deterministic action types in `nour_kb.db` (`intents.action_type`).
 */
enum class KbActionType(val wireValue: String) {
    TTS_ONLY("tts_only"),
    PLAY_AUDIO("play_audio"),
    APP_COMMAND("app_command");

    companion object {
        fun fromWire(value: String?): KbActionType =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: TTS_ONLY
    }
}

/**
 * Metadata stored in the `kb_meta` table of `nour_kb.db`.
 */
data class KbMetadata(
    val kbVersion: String,
    val builtAtUtc: String = "",
    val intentCount: Int = 0,
    val patternCount: Int = 0,
    val embeddingModel: String = "none",
    val embeddingDim: Int = 0,
    val embeddingDtype: String = "none",
)

/**
 * Deterministic retrieval match returned by [KbRetrievalEngine.retrieve].
 */
data class KbMatch(
    val intentId: String,
    val actionType: String,
    val commandPayload: String?,
    val responseEn: String,
    val responseAr: String,
    val audioAssetAr: String?,
    val audioAssetEn: String?,
    val sourceRefEn: String,
    val sourceRefAr: String,
    val priority: Int,
    val score: Float,
    val detectedLang: String,
    val category: String = "",
    val subcategory: String = "",
    val marjaScope: String = "",
    val matchedPattern: String = "",
) {
    /** Typed enum representation of [actionType]. */
    val parsedActionType: KbActionType
        get() = KbActionType.fromWire(actionType)

    /** Returns the response text matching [detectedLang] (`"ar"` or `"en"`). */
    val localizedResponse: String
        get() = if (detectedLang == "ar") responseAr else responseEn

    /** Returns the source reference matching [detectedLang] (`"ar"` or `"en"`). */
    val localizedSourceRef: String
        get() = if (detectedLang == "ar") sourceRefAr else sourceRefEn

    /** Returns the audio asset path matching [detectedLang], with cross-language fallback if needed. */
    val localizedAudioAsset: String?
        get() = if (detectedLang == "ar") {
            audioAssetAr?.takeIf { it.isNotBlank() } ?: audioAssetEn?.takeIf { it.isNotBlank() }
        } else {
            audioAssetEn?.takeIf { it.isNotBlank() } ?: audioAssetAr?.takeIf { it.isNotBlank() }
        }
}

/**
 * Compact one-tap candidate intent chip displayed on the Voice Overlay when retrieval returns
 * a medium/low-confidence match or no match.
 */
data class VoiceCorrectionSuggestion(
    val intentId: String,
    val labelEn: String,
    val labelAr: String,
    val score: Float,
    val match: KbMatch,
) {
    fun localizedLabel(isArabic: Boolean): String =
        if (isArabic) labelAr.ifBlank { labelEn } else labelEn.ifBlank { labelAr }
}

/**
 * Rich retrieval outcome returned by [KbRetrievalEngine.retrieveWithCandidates], combining
 * canonical-first resolution, promoted learned phrases (`hit_count >= 2`), FTS5 ranking,
 * and runner-up correction suggestions.
 */
data class KbRetrievalOutcome(
    val bestMatch: KbMatch?,
    val suggestions: List<VoiceCorrectionSuggestion> = emptyList(),
    val normalizedQuery: String = "",
    val detectedLang: String = "en",
    val matchSource: String = MATCH_SOURCE_NONE,
    val isLowConfidence: Boolean = false,
) {
    companion object {
        const val MATCH_SOURCE_CANONICAL_EXACT = "canonical_exact"
        const val MATCH_SOURCE_LEARNED_PHRASE = "learned_phrase"
        const val MATCH_SOURCE_FTS5 = "fts5"
        const val MATCH_SOURCE_FUZZY_LEXICAL = "fuzzy_lexical"
        const val MATCH_SOURCE_NONE = "none"
    }
}

/**
 * Hook for optional ONNX `intfloat/multilingual-e5-small` float16 cosine similarity fusion.
 *
 * Defaults to [NoOpSemanticScorer] for 100% deterministic lexical FTS5 + BM25 retrieval when
 * embeddings are disabled or when no ONNX model is loaded.
 */
interface SemanticScorer {
    /**
     * Whether semantic embedding fusion is active.
     */
    val isEnabled: Boolean

    /**
     * Weight in `[0.0f, 1.0f]` assigned to the semantic score when fused with the lexical score.
     */
    val fusionWeight: Float
        get() = 0.35f

    /**
     * Computes cosine similarity in `[0.0f, 1.0f]` between [normalizedQuery] (in [lang]) and a row's
     * packed IEEE-754 float16 [patternEmbeddingBlob] from `intent_patterns.embedding`.
     *
     * @return similarity score in `[0.0f, 1.0f]`, or `null` if unavailable.
     */
    suspend fun score(
        normalizedQuery: String,
        lang: String,
        patternEmbeddingBlob: ByteArray,
    ): Float?

    companion object {
        /**
         * Unpacks a little-endian IEEE-754 float16 byte array (produced by Python `struct.pack('<Ne', ...)`)
         * into a standard 32-bit [FloatArray].
         */
        fun unpackFloat16(blob: ByteArray): FloatArray {
            val count = blob.size / 2
            val out = FloatArray(count)
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val halfBits = buf.short.toInt() and 0xFFFF
                out[i] = halfToFloat(halfBits)
            }
            return out
        }

        /**
         * Converts a 16-bit IEEE-754 half-precision bit pattern to a 32-bit IEEE-754 [Float].
         */
        fun halfToFloat(halfBits: Int): Float {
            val sign = (halfBits ushr 15) and 0x0001
            var exp = (halfBits ushr 10) and 0x001F
            var mant = halfBits and 0x03FF

            if (exp == 0) {
                if (mant == 0) {
                    return Float.fromBits(sign shl 31)
                }
                // Subnormal half-precision number
                while ((mant and 0x0400) == 0) {
                    mant = mant shl 1
                    exp -= 1
                }
                exp += 1
                mant = mant and 0x0400.inv()
            } else if (exp == 31) {
                // Inf or NaN
                val bits = (sign shl 31) or (0xFF shl 23) or (mant shl 13)
                return Float.fromBits(bits)
            }

            exp += (127 - 15)
            val bits = (sign shl 31) or (exp shl 23) or (mant shl 13)
            return Float.fromBits(bits)
        }

        /**
         * Computes normalized cosine similarity in `[0.0f, 1.0f]` between two vectors of equal dimension.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || a.size != b.size) return 0f
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                val va = a[i].toDouble()
                val vb = b[i].toDouble()
                dot += va * vb
                normA += va * va
                normB += vb * vb
            }
            val denom = sqrt(normA) * sqrt(normB)
            if (denom <= 1e-12) return 0f
            return (dot / denom).toFloat().coerceIn(0f, 1f)
        }
    }
}

/**
 * Default lexical-only [SemanticScorer] implementation (no ONNX overhead).
 */
object NoOpSemanticScorer : SemanticScorer {
    override val isEnabled: Boolean = false

    override suspend fun score(
        normalizedQuery: String,
        lang: String,
        patternEmbeddingBlob: ByteArray,
    ): Float? = null
}
