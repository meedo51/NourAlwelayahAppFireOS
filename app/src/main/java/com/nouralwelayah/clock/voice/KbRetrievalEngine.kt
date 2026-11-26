package com.nouralwelayah.clock.voice

import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import java.util.Locale
import kotlin.math.abs

/**
 * 100% Offline, Deterministic, Retrieval-Based Bilingual (Arabic & English) Knowledge Base Engine.
 *
 * Resolution priority & guardrails:
 * 1. Exact canonical pattern match in `nour_kb.db` ALWAYS wins (a learned phrase can never
 *    hijack a core canonical command).
 * 2. Promoted local phrase memory from [VoiceLearningStore] (`hit_count >= 2` and unused <= 90 days).
 * 3. SQLite FTS5 (`intent_patterns_fts`) `bm25()` lexical retrieval + Arabic light-prefix &
 *    1-edit fuzzy Whisper-drift tolerance + optional [SemanticScorer] fusion.
 * 4. Returns top 2–3 runner-up [VoiceCorrectionSuggestion] chips when confidence is medium/low
 *    (`< HIGH_CONFIDENCE_THRESHOLD`) or when no intent meets [minScoreThreshold].
 */
class KbRetrievalEngine(
    private val dbManager: KbDatabaseManager,
    private val semanticScorer: SemanticScorer = NoOpSemanticScorer,
) {

    /**
     * Retrieves the highest-confidence deterministic [KbMatch] for [rawQuery], or `null` if the query
     * is blank or no intent meets [minScoreThreshold].
     */
    suspend fun retrieve(
        rawQuery: String,
        minScoreThreshold: Float = DEFAULT_MIN_SCORE_THRESHOLD,
        learningStore: VoiceLearningStore? = null,
    ): KbMatch? = retrieveWithCandidates(
        rawQuery = rawQuery,
        minScoreThreshold = minScoreThreshold,
        maxSuggestions = 0,
        learningStore = learningStore,
    ).bestMatch

    /**
     * Retrieves the best deterministic [KbMatch] along with top runner-up [VoiceCorrectionSuggestion]
     * candidates for one-tap correction capture when confidence is medium/low or unmatched.
     */
    suspend fun retrieveWithCandidates(
        rawQuery: String,
        minScoreThreshold: Float = DEFAULT_MIN_SCORE_THRESHOLD,
        maxSuggestions: Int = DEFAULT_MAX_SUGGESTIONS,
        learningStore: VoiceLearningStore? = null,
    ): KbRetrievalOutcome {
        if (rawQuery.isBlank()) {
            return KbRetrievalOutcome(bestMatch = null)
        }

        val detectedLang = TextNormalizer.detectLanguage(rawQuery)
        val normalizedQuery = TextNormalizer.normalize(rawQuery, detectedLang)
        if (normalizedQuery.isBlank()) {
            return KbRetrievalOutcome(
                bestMatch = null,
                normalizedQuery = "",
                detectedLang = detectedLang,
            )
        }

        val queryTokens = extractTokens(normalizedQuery)
        if (queryTokens.isEmpty()) {
            return KbRetrievalOutcome(
                bestMatch = null,
                normalizedQuery = normalizedQuery,
                detectedLang = detectedLang,
            )
        }

        val ftsMatchQuery = buildFtsMatchQuery(queryTokens)
        if (ftsMatchQuery.isBlank()) {
            return KbRetrievalOutcome(
                bestMatch = null,
                normalizedQuery = normalizedQuery,
                detectedLang = detectedLang,
            )
        }

        // Step 1: Collect exact canonical match + FTS5 candidates inside the DB mutex lock
        val queryResult = try {
            dbManager.withDatabase { db ->
                val exactNonKeyword = queryExactCanonicalMatch(db, detectedLang, normalizedQuery)
                if (exactNonKeyword != null) {
                    return@withDatabase QueryBatch(
                        exactCanonical = exactNonKeyword,
                        ftsRows = emptyList(),
                        fuzzyFallbackRows = emptyList(),
                    )
                }

                val ftsRows = queryFtsCandidates(
                    db = db,
                    normalizedQuery = normalizedQuery,
                    lang = detectedLang,
                    ftsMatchQuery = ftsMatchQuery,
                )
                QueryBatch(
                    exactCanonical = null,
                    ftsRows = ftsRows,
                    fuzzyFallbackRows = emptyList(),
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "FTS5 retrieval failed for query '$rawQuery'", e)
            return KbRetrievalOutcome(
                bestMatch = null,
                normalizedQuery = normalizedQuery,
                detectedLang = detectedLang,
            )
        }

        // Rule 1: Exact canonical match in nour_kb.db ALWAYS wins
        queryResult.exactCanonical?.let { exactRow ->
            val match = exactRow.toKbMatch(score = 1.0f, detectedLang = detectedLang)
            return KbRetrievalOutcome(
                bestMatch = match,
                suggestions = emptyList(),
                normalizedQuery = normalizedQuery,
                detectedLang = detectedLang,
                matchSource = KbRetrievalOutcome.MATCH_SOURCE_CANONICAL_EXACT,
                isLowConfidence = false,
            )
        }

        // Rule 2: Promoted local phrase memory (only when hit_count >= 2 and unused <= 90 days)
        val promotedIntentId = learningStore?.lookupPromotedIntent(normalizedQuery)
        if (!promotedIntentId.isNullOrBlank()) {
            val learnedMatch = findIntentById(promotedIntentId, detectedLang)
            if (learnedMatch != null) {
                learningStore.recordLearnedHit(normalizedQuery)
                return KbRetrievalOutcome(
                    bestMatch = learnedMatch.copy(score = 0.99f, matchedPattern = normalizedQuery),
                    suggestions = emptyList(),
                    normalizedQuery = normalizedQuery,
                    detectedLang = detectedLang,
                    matchSource = KbRetrievalOutcome.MATCH_SOURCE_LEARNED_PHRASE,
                    isLowConfidence = false,
                )
            }
        }

        // Rule 3: Score FTS5 candidates
        val queryTokenSet = queryTokens.toSet()
        var scoredRanked = scoreAndRankCandidates(
            rows = queryResult.ftsRows,
            normalizedQuery = normalizedQuery,
            queryTokenSet = queryTokenSet,
            detectedLang = detectedLang,
        )
        var usedFuzzyFallback = false

        // If FTS5 yielded no high-confidence hit or fewer than maxSuggestions candidates
        // (e.g., Whisper tiny phonetic/character drift causing an FTS5 token miss), run a fast
        // language-filtered lexical scan over intent_patterns (~450 rows per language, < 3ms).
        val topFtsScore = scoredRanked.firstOrNull()?.score ?: 0f
        if (topFtsScore < HIGH_CONFIDENCE_THRESHOLD || scoredRanked.size < maxSuggestions.coerceAtLeast(1)) {
            val allLangRows = runCatching {
                dbManager.withDatabase { db -> queryAllPatternsForLanguage(db, detectedLang) }
            }.getOrDefault(emptyList())

            if (allLangRows.isNotEmpty()) {
                val mergedRows = ArrayList<CandidatePatternRow>(queryResult.ftsRows.size + allLangRows.size)
                val seenIds = HashSet<Long>(queryResult.ftsRows.size + allLangRows.size)
                for (r in queryResult.ftsRows) {
                    if (seenIds.add(r.patternId)) mergedRows.add(r)
                }
                for (r in allLangRows) {
                    if (seenIds.add(r.patternId)) mergedRows.add(r)
                }
                val rescored = scoreAndRankCandidates(
                    rows = mergedRows,
                    normalizedQuery = normalizedQuery,
                    queryTokenSet = queryTokenSet,
                    detectedLang = detectedLang,
                )
                if ((rescored.firstOrNull()?.score ?: 0f) > topFtsScore || rescored.size > scoredRanked.size) {
                    scoredRanked = rescored
                    usedFuzzyFallback = true
                }
            }
        }

        val topMatch = scoredRanked.firstOrNull()?.takeIf { it.score >= minScoreThreshold }
        val isLowConfidence = topMatch == null || topMatch.score < HIGH_CONFIDENCE_THRESHOLD
        val matchSource = when {
            topMatch == null -> KbRetrievalOutcome.MATCH_SOURCE_NONE
            usedFuzzyFallback && topMatch.score > topFtsScore -> KbRetrievalOutcome.MATCH_SOURCE_FUZZY_LEXICAL
            else -> KbRetrievalOutcome.MATCH_SOURCE_FTS5
        }

        // Rule 4: Build top 2–3 runner-up correction suggestion chips when medium/low confidence or no match
        val suggestions = if (maxSuggestions > 0 && isLowConfidence) {
            val candidateMatches = scoredRanked
                .filter { candidate ->
                    candidate.score >= SUGGESTION_MIN_SCORE &&
                        (topMatch == null || candidate.intentId != topMatch.intentId)
                }
                .take(maxSuggestions)
            buildSuggestions(candidateMatches)
        } else {
            emptyList()
        }

        return KbRetrievalOutcome(
            bestMatch = topMatch,
            suggestions = suggestions,
            normalizedQuery = normalizedQuery,
            detectedLang = detectedLang,
            matchSource = matchSource,
            isLowConfidence = isLowConfidence,
        )
    }

    /**
     * Resolves a full [KbMatch] directly by `intent_id` from `nour_kb.db` (used when promoting
     * a learned phrase from [VoiceLearningStore] or dispatching a correction chip).
     */
    suspend fun findIntentById(
        intentId: String,
        detectedLang: String,
    ): KbMatch? {
        if (intentId.isBlank()) return null
        return runCatching {
            dbManager.withDatabase { db ->
                db.rawQuery(SQL_INTENT_BY_ID, arrayOf(intentId)).use { c ->
                    if (!c.moveToFirst()) return@use null
                    KbMatch(
                        intentId = c.getString(0).orEmpty(),
                        actionType = c.getString(1).orEmpty(),
                        commandPayload = if (c.isNull(2)) null else c.getString(2),
                        responseEn = c.getString(3).orEmpty(),
                        responseAr = c.getString(4).orEmpty(),
                        audioAssetAr = if (c.isNull(5)) null else c.getString(5),
                        audioAssetEn = if (c.isNull(6)) null else c.getString(6),
                        sourceRefEn = c.getString(7).orEmpty(),
                        sourceRefAr = c.getString(8).orEmpty(),
                        priority = if (c.isNull(9)) 2 else c.getInt(9),
                        score = 1.0f,
                        detectedLang = detectedLang,
                        category = c.getString(10).orEmpty(),
                        subcategory = c.getString(11).orEmpty(),
                        marjaScope = c.getString(12).orEmpty(),
                        matchedPattern = "",
                    )
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "findIntentById($intentId) failed", e)
        }.getOrNull()
    }

    private suspend fun buildSuggestions(
        candidates: List<KbMatch>,
    ): List<VoiceCorrectionSuggestion> {
        if (candidates.isEmpty()) return emptyList()
        val intentIds = candidates.map { it.intentId }.distinct()
        val labelsByIntent = runCatching {
            dbManager.withDatabase { db -> queryChipLabelsForIntents(db, intentIds) }
        }.getOrDefault(emptyMap())

        return candidates.map { match ->
            val pair = labelsByIntent[match.intentId]
            val enLabel = pair?.first?.takeIf { it.isNotBlank() }
                ?: formatFallbackChipLabel(match, isArabic = false)
            val arLabel = pair?.second?.takeIf { it.isNotBlank() }
                ?: formatFallbackChipLabel(match, isArabic = true)
            VoiceCorrectionSuggestion(
                intentId = match.intentId,
                labelEn = enLabel,
                labelAr = arLabel,
                score = match.score,
                match = match,
            )
        }
    }

    private fun queryChipLabelsForIntents(
        db: SQLiteDatabase,
        intentIds: List<String>,
    ): Map<String, Pair<String, String>> {
        if (intentIds.isEmpty()) return emptyMap()
        val placeholders = intentIds.joinToString(",") { "?" }
        val sql = """
            SELECT intent_id, lang, raw_text
            FROM intent_patterns
            WHERE is_keyword = 0 AND intent_id IN ($placeholders)
            ORDER BY LENGTH(raw_text) ASC, id ASC
        """.trimIndent()
        val enMap = HashMap<String, String>(intentIds.size)
        val arMap = HashMap<String, String>(intentIds.size)
        db.rawQuery(sql, intentIds.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0).orEmpty()
                val lang = c.getString(1).orEmpty()
                val raw = c.getString(2).orEmpty().trim()
                if (raw.isEmpty()) continue
                if (lang == "en" && !enMap.containsKey(id)) {
                    enMap[id] = capitalizeLabel(raw)
                } else if (lang == "ar" && !arMap.containsKey(id)) {
                    arMap[id] = raw
                }
            }
        }
        val out = HashMap<String, Pair<String, String>>(intentIds.size)
        for (id in intentIds) {
            out[id] = (enMap[id].orEmpty() to arMap[id].orEmpty())
        }
        return out
    }

    private suspend fun scoreAndRankCandidates(
        rows: List<CandidatePatternRow>,
        normalizedQuery: String,
        queryTokenSet: Set<String>,
        detectedLang: String,
    ): List<KbMatch> {
        if (rows.isEmpty()) return emptyList()
        val bestByIntent = LinkedHashMap<String, KbMatch>()
        val tokenBagByIntent = HashMap<String, MutableSet<String>>()
        val bestPatternRowByIntent = HashMap<String, CandidatePatternRow>()

        for (row in rows) {
            val rowTokens = extractTokens(row.normalizedPattern)
            if (rowTokens.isNotEmpty()) {
                tokenBagByIntent.getOrPut(row.intentId) { HashSet() }.addAll(rowTokens)
            }

            val lexicalScore = computeLexicalScore(
                normalizedQuery = normalizedQuery,
                queryTokens = queryTokenSet,
                patternText = row.normalizedPattern,
                isKeyword = row.isKeyword,
                rawBm25 = row.rawBm25,
            )
            if (lexicalScore <= 0f) continue

            val finalScore = if (semanticScorer.isEnabled && row.embeddingBlob != null && row.embeddingBlob.isNotEmpty()) {
                val semScore = runCatching {
                    semanticScorer.score(
                        normalizedQuery = normalizedQuery,
                        lang = detectedLang,
                        patternEmbeddingBlob = row.embeddingBlob,
                    )
                }.getOrNull()

                if (semScore != null) {
                    val w = semanticScorer.fusionWeight.coerceIn(0f, 1f)
                    ((1f - w) * lexicalScore + w * semScore.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                } else {
                    lexicalScore
                }
            } else {
                lexicalScore
            }

            val candidateMatch = row.toKbMatch(score = finalScore, detectedLang = detectedLang)
            val existing = bestByIntent[row.intentId]
            if (existing == null || finalScore > existing.score) {
                bestByIntent[row.intentId] = candidateMatch
                bestPatternRowByIntent[row.intentId] = row
            }
        }

        // Boost/evaluate multi-token intent coverage across an intent's patterns & keywords so
        // short recombined utterances (e.g. "Dim display", "Mute audio", "Lower volume",
        // "وقت الضهر كم") reliably beat single-keyword accidental overlaps.
        for ((intentId, match) in bestByIntent.entries.toList()) {
            val bag = tokenBagByIntent[intentId] ?: continue
            val bagScore = computeIntentBagScore(queryTokenSet, bag)
            if (bagScore > match.score) {
                bestByIntent[intentId] = match.copy(score = bagScore)
            }
        }

        return bestByIntent.values.sortedWith { a, b ->
            val scoreDiff = b.score.compareTo(a.score)
            if (abs(a.score - b.score) > SCORE_TIE_EPSILON && scoreDiff != 0) {
                scoreDiff
            } else {
                val prioDiff = a.priority.compareTo(b.priority)
                if (prioDiff != 0) prioDiff else scoreDiff
            }
        }
    }

    private fun queryExactCanonicalMatch(
        db: SQLiteDatabase,
        lang: String,
        normalizedQuery: String,
    ): CandidatePatternRow? {
        db.rawQuery(SQL_EXACT_CANONICAL_PATTERN, arrayOf(lang, normalizedQuery)).use { cursor ->
            if (cursor.moveToFirst()) {
                return readCandidateRow(cursor, defaultBm25 = -25.0)
            }
        }
        return null
    }

    private fun queryFtsCandidates(
        db: SQLiteDatabase,
        normalizedQuery: String,
        lang: String,
        ftsMatchQuery: String,
    ): List<CandidatePatternRow> {
        val results = ArrayList<CandidatePatternRow>(MAX_CANDIDATE_ROWS)
        val seenPatternIds = HashSet<Long>()

        // 1. Direct exact normalized match check (including keywords)
        db.rawQuery(SQL_EXACT_MATCH, arrayOf(lang, normalizedQuery)).use { cursor ->
            while (cursor.moveToNext()) {
                val row = readCandidateRow(cursor, defaultBm25 = -25.0)
                if (seenPatternIds.add(row.patternId)) {
                    results.add(row)
                }
            }
        }

        // 2. FTS5 BM25 match query for broader lexical & prefix retrieval
        db.rawQuery(
            SQL_FTS_BM25_MATCH,
            arrayOf(ftsMatchQuery, lang, MAX_CANDIDATE_ROWS.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = readCandidateRow(cursor, defaultBm25 = -1.0)
                if (seenPatternIds.add(row.patternId)) {
                    results.add(row)
                }
            }
        }

        return results
    }

    private fun queryAllPatternsForLanguage(
        db: SQLiteDatabase,
        lang: String,
    ): List<CandidatePatternRow> {
        val results = ArrayList<CandidatePatternRow>(512)
        db.rawQuery(SQL_ALL_PATTERNS_BY_LANG, arrayOf(lang)).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(readCandidateRow(cursor, defaultBm25 = -2.5))
            }
        }
        return results
    }

    private fun readCandidateRow(
        cursor: android.database.Cursor,
        defaultBm25: Double,
    ): CandidatePatternRow {
        val patternId = cursor.getLong(0)
        val intentId = cursor.getString(1).orEmpty()
        val normalizedPattern = cursor.getString(2).orEmpty()
        val isKeyword = cursor.getInt(3) != 0
        val embeddingBlob = if (cursor.isNull(4)) null else cursor.getBlob(4)
        val actionType = cursor.getString(5).orEmpty()
        val commandPayload = if (cursor.isNull(6)) null else cursor.getString(6)
        val responseEn = cursor.getString(7).orEmpty()
        val responseAr = cursor.getString(8).orEmpty()
        val audioAssetAr = if (cursor.isNull(9)) null else cursor.getString(9)
        val audioAssetEn = if (cursor.isNull(10)) null else cursor.getString(10)
        val sourceRefEn = cursor.getString(11).orEmpty()
        val sourceRefAr = cursor.getString(12).orEmpty()
        val priority = if (cursor.isNull(13)) 2 else cursor.getInt(13)
        val category = cursor.getString(14).orEmpty()
        val subcategory = cursor.getString(15).orEmpty()
        val marjaScope = cursor.getString(16).orEmpty()
        val rawBm25 = if (cursor.columnCount > 17 && !cursor.isNull(17)) {
            cursor.getDouble(17)
        } else {
            defaultBm25
        }

        return CandidatePatternRow(
            patternId = patternId,
            intentId = intentId,
            normalizedPattern = normalizedPattern,
            isKeyword = isKeyword,
            embeddingBlob = embeddingBlob,
            actionType = actionType,
            commandPayload = commandPayload,
            responseEn = responseEn,
            responseAr = responseAr,
            audioAssetAr = audioAssetAr,
            audioAssetEn = audioAssetEn,
            sourceRefEn = sourceRefEn,
            sourceRefAr = sourceRefAr,
            priority = priority,
            category = category,
            subcategory = subcategory,
            marjaScope = marjaScope,
            rawBm25 = rawBm25,
        )
    }

    private data class QueryBatch(
        val exactCanonical: CandidatePatternRow?,
        val ftsRows: List<CandidatePatternRow>,
        val fuzzyFallbackRows: List<CandidatePatternRow>,
    )

    private data class CandidatePatternRow(
        val patternId: Long,
        val intentId: String,
        val normalizedPattern: String,
        val isKeyword: Boolean,
        val embeddingBlob: ByteArray?,
        val actionType: String,
        val commandPayload: String?,
        val responseEn: String,
        val responseAr: String,
        val audioAssetAr: String?,
        val audioAssetEn: String?,
        val sourceRefEn: String,
        val sourceRefAr: String,
        val priority: Int,
        val category: String,
        val subcategory: String,
        val marjaScope: String,
        val rawBm25: Double,
    ) {
        fun toKbMatch(score: Float, detectedLang: String): KbMatch = KbMatch(
            intentId = intentId,
            actionType = actionType,
            commandPayload = commandPayload,
            responseEn = responseEn,
            responseAr = responseAr,
            audioAssetAr = audioAssetAr,
            audioAssetEn = audioAssetEn,
            sourceRefEn = sourceRefEn,
            sourceRefAr = sourceRefAr,
            priority = priority,
            score = score,
            detectedLang = detectedLang,
            category = category,
            subcategory = subcategory,
            marjaScope = marjaScope,
            matchedPattern = normalizedPattern,
        )
    }

    companion object {
        private const val TAG = "KbRetrievalEngine"
        const val DEFAULT_MIN_SCORE_THRESHOLD = 0.35f
        const val HIGH_CONFIDENCE_THRESHOLD = 0.80f
        const val SUGGESTION_MIN_SCORE = 0.16f
        const val DEFAULT_MAX_SUGGESTIONS = 3
        private const val MAX_CANDIDATE_ROWS = 40
        private const val SCORE_TIE_EPSILON = 0.015f

        // Common low-information conversational filler words excluded when calculating query token recall
        private val STOPWORDS_EN = setOf(
            "a", "an", "the", "is", "are", "to", "of", "in", "on", "for", "at", "by",
            "please", "can", "you", "me", "my", "i", "want", "tell", "about", "do", "does",
            "could", "would", "it", "this", "that"
        )

        private val STOPWORDS_AR = setOf(
            "من", "في", "على", "الى", "عن", "مع", "هل", "يا", "فضلك", "لو", "سمحت", "اريد", "لي",
            "ممكن", "الرجاء", "ابغى", "بدي"
        )

        private val FTS_UNSAFE_TOKEN_RE = Regex("[^\\u0600-\\u06FFa-zA-Z0-9]+")

        private const val SELECT_CANDIDATE_COLUMNS = """
            SELECT
                p.id,
                i.intent_id,
                p.normalized_text,
                p.is_keyword,
                p.embedding,
                i.action_type,
                i.command_payload,
                i.response_en,
                i.response_ar,
                i.audio_asset_ar,
                i.audio_asset_en,
                i.source_ref_en,
                i.source_ref_ar,
                i.priority,
                i.category,
                i.subcategory,
                i.marja_scope
        """

        private const val SQL_EXACT_CANONICAL_PATTERN = """
            $SELECT_CANDIDATE_COLUMNS,
                -25.0 AS bm25_score
            FROM intent_patterns p
            INNER JOIN intents i ON i.intent_id = p.intent_id
            WHERE p.lang = ? AND p.normalized_text = ? AND p.is_keyword = 0
            ORDER BY i.priority ASC
            LIMIT 1
        """

        private const val SQL_EXACT_MATCH = """
            $SELECT_CANDIDATE_COLUMNS,
                -25.0 AS bm25_score
            FROM intent_patterns p
            INNER JOIN intents i ON i.intent_id = p.intent_id
            WHERE p.lang = ? AND p.normalized_text = ?
            LIMIT 10
        """

        private const val SQL_FTS_BM25_MATCH = """
            $SELECT_CANDIDATE_COLUMNS,
                bm25(intent_patterns_fts) AS bm25_score
            FROM intent_patterns_fts
            INNER JOIN intent_patterns p ON p.id = intent_patterns_fts.rowid
            INNER JOIN intents i ON i.intent_id = p.intent_id
            WHERE intent_patterns_fts MATCH ? AND p.lang = ?
            ORDER BY bm25_score ASC, i.priority ASC
            LIMIT ?
        """

        private const val SQL_ALL_PATTERNS_BY_LANG = """
            $SELECT_CANDIDATE_COLUMNS,
                -2.5 AS bm25_score
            FROM intent_patterns p
            INNER JOIN intents i ON i.intent_id = p.intent_id
            WHERE p.lang = ?
        """

        private const val SQL_INTENT_BY_ID = """
            SELECT
                intent_id,
                action_type,
                command_payload,
                response_en,
                response_ar,
                audio_asset_ar,
                audio_asset_en,
                source_ref_en,
                source_ref_ar,
                priority,
                category,
                subcategory,
                marja_scope
            FROM intents
            WHERE intent_id = ?
            LIMIT 1
        """

        /**
         * Splits a normalized query string into clean alphanumeric / Arabic tokens.
         */
        fun extractTokens(normalizedText: String): List<String> {
            if (normalizedText.isBlank()) return emptyList()
            return normalizedText
                .split(' ')
                .map { FTS_UNSAFE_TOKEN_RE.replace(it.trim(), "") }
                .filter { it.isNotEmpty() }
        }

        /**
         * Sanitizes tokens and constructs a safe SQLite FTS5 `MATCH` expression.
         */
        fun buildFtsMatchQuery(tokens: List<String>): String {
            val sanitized = tokens
                .map { FTS_UNSAFE_TOKEN_RE.replace(it, "") }
                .filter { it.isNotEmpty() }
                .distinct()

            if (sanitized.isEmpty()) return ""

            val clauses = ArrayList<String>(sanitized.size * 3)
            for (token in sanitized) {
                clauses.add("\"$token\"")
                if (token.length >= 3) {
                    clauses.add("\"$token\"*")
                }
                val stemmed = stripArabicLightPrefix(token)
                if (stemmed != token && stemmed.length >= 3) {
                    clauses.add("\"$stemmed\"")
                    clauses.add("\"$stemmed\"*")
                }
            }
            return clauses.distinct().joinToString(" OR ")
        }

        /**
         * Convenience overload accepting a normalized query string and sanitizing it for FTS5 `MATCH`.
         */
        fun sanitizeFtsQuery(normalizedQuery: String): String =
            buildFtsMatchQuery(extractTokens(normalizedQuery))

        /**
         * Light Arabic prefix stripping (`ال`, `وال`, `بال`, `لل`, `فال`, `كال`) for resilient
         * token matching without mutating `TextNormalizer` canonical normalization.
         */
        fun stripArabicLightPrefix(token: String): String {
            if (token.length < 4 || TextNormalizer.detectLanguage(token) != "ar") return token
            val prefixes3 = arrayOf("وال", "بال", "فال", "كال")
            for (p in prefixes3) {
                if (token.startsWith(p) && token.length - p.length >= 3) {
                    return token.substring(p.length)
                }
            }
            if (token.startsWith("لل")) {
                return if (token.length == 4) token.substring(1) else token.substring(2)
            }
            if (token.startsWith("ال") && token.length >= 5) {
                return token.substring(2)
            }
            return token
        }

        /**
         * Returns `true` if [a] and [b] are within Levenshtein edit distance `<= 1`
         * (handles common Whisper `tiny` single-letter phonetic substitutions/omissions/insertions
         * such as `الضهر`/`الظهر`, `المغرف`/`المغرب`, `britness`/`brightness`).
         */
        fun isWithinOneEdit(a: String, b: String): Boolean {
            val lenA = a.length
            val lenB = b.length
            if (abs(lenA - lenB) > 1) return false
            if (a == b) return true
            if (lenA == lenB) {
                var diffs = 0
                for (i in 0 until lenA) {
                    if (a[i] != b[i]) {
                        diffs++
                        if (diffs > 1) return false
                    }
                }
                return diffs == 1
            }
            val shorter = if (lenA < lenB) a else b
            val longer = if (lenA < lenB) b else a
            var i = 0
            var j = 0
            var skipped = false
            while (i < shorter.length && j < longer.length) {
                if (shorter[i] == longer[j]) {
                    i++
                    j++
                } else {
                    if (skipped) return false
                    skipped = true
                    j++
                }
            }
            return true
        }

        private fun bestTokenMatchWeight(token: String, targetSet: Set<String>): Float {
            if (token in targetSet) return 1.0f

            val tokenStem = stripArabicLightPrefix(token)
            var best = 0f
            for (other in targetSet) {
                val otherStem = stripArabicLightPrefix(other)
                when {
                    tokenStem == otherStem -> {
                        best = maxOf(best, 0.94f)
                    }
                    tokenStem.length >= 3 && otherStem.length >= 3 &&
                        (otherStem.startsWith(tokenStem) || tokenStem.startsWith(otherStem)) -> {
                        best = maxOf(best, 0.80f)
                    }
                    tokenStem.length >= 4 && otherStem.length >= 4 &&
                        isWithinOneEdit(tokenStem, otherStem) -> {
                        best = maxOf(best, 0.74f)
                    }
                }
                if (best >= 0.94f) break
            }
            return best
        }

        /**
         * Computes an intent-level multi-token coverage score across all patterns/keywords belonging
         * to a single intent. Requires at least 2 meaningful query tokens matched to score strongly
         * so a single generic keyword never hijacks multi-word utterances.
         */
        fun computeIntentBagScore(
            queryTokens: Set<String>,
            intentTokenBag: Set<String>,
        ): Float {
            if (queryTokens.isEmpty() || intentTokenBag.isEmpty()) return 0f
            val meaningfulQuery = queryTokens
                .filterNot { it in STOPWORDS_EN || it in STOPWORDS_AR }
                .toSet()
                .ifEmpty { queryTokens }

            if (meaningfulQuery.size < 2) return 0f

            var weightedHits = 0f
            var distinctMatched = 0
            for (qTok in meaningfulQuery) {
                val w = bestTokenMatchWeight(qTok, intentTokenBag)
                if (w > 0f) {
                    weightedHits += w
                    distinctMatched++
                }
            }
            if (distinctMatched < 2) return 0f

            val coverage = (weightedHits / meaningfulQuery.size.toFloat()).coerceIn(0f, 1f)
            // Capped at 0.86f so exact/strong full utterance patterns (0.88–1.0f) still win ties
            return (0.86f * coverage).coerceIn(0f, 0.86f)
        }

        /**
         * Computes a normalized, deterministic lexical score in `[0.0f, 1.0f]` from:
         * - Exact normalized match boost (`1.00f` for full pattern, `0.90f` for exact keyword match).
         * - Phrase containment boost when a multi-token full pattern appears inside the user query.
         * - Informative token coverage with Arabic light-prefix stripping and 1-edit Whisper-drift tolerance.
         * - SQLite FTS5 `bm25()` saturation score (`abs(bm25) / (abs(bm25) + k)`).
         */
        fun computeLexicalScore(
            normalizedQuery: String,
            queryTokens: Set<String>,
            patternText: String,
            isKeyword: Boolean,
            rawBm25: Double,
        ): Float {
            if (normalizedQuery.isBlank() || patternText.isBlank()) return 0f

            // 1. Exact full match
            if (normalizedQuery == patternText) {
                return if (!isKeyword) 1.0f else 0.90f
            }

            val patternTokens = extractTokens(patternText).toSet()
            if (patternTokens.isEmpty() || queryTokens.isEmpty()) return 0f

            // Filter out pure stopwords only if non-stopword tokens remain
            val meaningfulQueryTokens = queryTokens
                .filterNot { it in STOPWORDS_EN || it in STOPWORDS_AR }
                .toSet()
                .ifEmpty { queryTokens }

            val meaningfulPatternTokens = patternTokens
                .filterNot { it in STOPWORDS_EN || it in STOPWORDS_AR }
                .toSet()
                .ifEmpty { patternTokens }

            // Compute weighted token recall & precision (exact, light-stem, prefix, 1-edit fuzzy)
            var matchedQueryCount = 0f
            for (qTok in meaningfulQueryTokens) {
                matchedQueryCount += bestTokenMatchWeight(qTok, meaningfulPatternTokens)
            }
            if (matchedQueryCount <= 0f) return 0f

            var matchedPatternCount = 0f
            for (pTok in meaningfulPatternTokens) {
                matchedPatternCount += bestTokenMatchWeight(pTok, meaningfulQueryTokens)
            }

            val queryCoverage = (matchedQueryCount / meaningfulQueryTokens.size.toFloat()).coerceIn(0f, 1f)
            val patternCoverage = (matchedPatternCount / meaningfulPatternTokens.size.toFloat()).coerceIn(0f, 1f)

            val tokenOverlapScore = if (queryCoverage + patternCoverage > 0f) {
                if (isKeyword && meaningfulPatternTokens.size == 1 && meaningfulQueryTokens.size >= 2) {
                    // Single keyword inside a multi-word utterance should reflect query coverage,
                    // not get inflated by 1/1 patternCoverage
                    (0.75f * queryCoverage + 0.25f * patternCoverage).coerceIn(0f, 1f)
                } else {
                    (0.45f * queryCoverage + 0.55f * patternCoverage).coerceIn(0f, 1f)
                }
            } else {
                0f
            }

            // SQLite FTS5 bm25() returns negative numbers where larger magnitude = higher relevance
            val absBm25 = abs(rawBm25).toFloat()
            val bm25Normalized = (absBm25 / (absBm25 + 3.5f)).coerceIn(0f, 1f)

            // Phrase containment bonus (multi-token patterns only; single-word keywords don't get containment bonus)
            val phraseContainmentBonus = when {
                !isKeyword && patternTokens.size >= 2 && normalizedQuery.contains(patternText) -> 0.22f
                !isKeyword && queryTokens.size >= 2 && patternText.contains(normalizedQuery) -> 0.16f
                isKeyword && patternTokens.size >= 2 && normalizedQuery.contains(patternText) -> 0.08f
                else -> 0f
            }

            // Single-token keywords have a modest ceiling so full patterns and multi-token intent bags win
            val keywordFactor = when {
                !isKeyword -> 1.0f
                patternTokens.size == 1 -> 0.60f
                else -> 0.80f
            }

            val combined = ((0.72f * tokenOverlapScore + 0.28f * bm25Normalized) * keywordFactor) + phraseContainmentBonus
            return combined.coerceIn(0f, 0.98f)
        }

        private fun capitalizeLabel(raw: String): String {
            if (raw.isEmpty()) return raw
            return raw.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
            }
        }

        private fun formatFallbackChipLabel(match: KbMatch, isArabic: Boolean): String {
            if (isArabic) {
                return match.responseAr.trim().removeSuffix(".").take(36)
            }
            val payload = match.commandPayload?.takeIf { it.isNotBlank() }
            if (payload != null) {
                return payload.lowercase(Locale.US)
                    .split('_')
                    .joinToString(" ") { w -> capitalizeLabel(w) }
            }
            return match.responseEn.trim().removeSuffix(".").take(36)
        }
    }
}
