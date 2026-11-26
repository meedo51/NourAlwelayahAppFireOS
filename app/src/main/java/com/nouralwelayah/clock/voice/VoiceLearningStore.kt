package com.nouralwelayah.clock.voice

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.nouralwelayah.clock.system.FileSystemManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Text-only export formats supported by [VoiceLearningStore].
 */
enum class VoiceLogExportFormat(val extension: String) {
    JSON("json"),
    CSV("csv"),
}

/**
 * Snapshot of local phrase memory and privacy-first interaction logs for Admin UI diagnostics.
 */
data class VoiceLearningStats(
    val learnedPhraseCount: Int = 0,
    val promotedPhraseCount: Int = 0,
    val interactionLogCount: Int = 0,
    val maxLearnedPhrases: Int = VoiceLearningPolicy.MAX_LEARNED_PHRASES,
    val expiryDays: Long = VoiceLearningPolicy.EXPIRY_DAYS,
    val minHitsForPromotion: Int = VoiceLearningPolicy.MIN_HITS_FOR_PROMOTION,
)

/**
 * Pure-Kotlin representation of a learned phrase record for policy enforcement and unit testing.
 */
data class LearnedPhraseEntry(
    val normalizedTranscript: String,
    val intentId: String,
    val hitCount: Int,
    val lastUsedEpochMs: Long,
    val createdEpochMs: Long,
)

/**
 * Pure-Kotlin guardrails for local phrase memory so core safety invariants are deterministic
 * and independently unit-testable on the JVM without requiring Android SQLite bindings.
 */
object VoiceLearningPolicy {
    const val MIN_HITS_FOR_PROMOTION: Int = 2
    const val MAX_LEARNED_PHRASES: Int = 250
    const val EXPIRY_DAYS: Long = 90L
    const val EXPIRY_MS: Long = EXPIRY_DAYS * 24L * 60L * 60L * 1000L
    const val MAX_INTERACTION_LOGS: Int = 1000

    /**
     * Returns `true` if [entry] is eligible to auto-execute:
     * - Must have at least [MIN_HITS_FOR_PROMOTION] (2) confirmed hits.
     * - Must not be older than [EXPIRY_MS] (90 days) since [LearnedPhraseEntry.lastUsedEpochMs].
     */
    fun isEligibleForAutoPromotion(entry: LearnedPhraseEntry, nowMs: Long): Boolean {
        if (entry.hitCount < MIN_HITS_FOR_PROMOTION) return false
        if (nowMs - entry.lastUsedEpochMs > EXPIRY_MS) return false
        return entry.intentId.isNotBlank() && entry.normalizedTranscript.isNotBlank()
    }

    /**
     * Resolves intent priority deterministically:
     * 1. An exact canonical match from `nour_kb.db` ALWAYS wins (a learned phrase can never
     *    hijack a core canonical command).
     * 2. Otherwise, a promoted learned phrase (`hit_count >= 2` and not expired) wins.
     * 3. Otherwise, returns `null` so normal FTS5 retrieval / suggestion fallback runs.
     */
    fun resolvePriorityOverride(
        canonicalExactIntentId: String?,
        learnedEntry: LearnedPhraseEntry?,
        nowMs: Long,
    ): Pair<String, String>? {
        if (!canonicalExactIntentId.isNullOrBlank()) {
            return canonicalExactIntentId to KbRetrievalOutcome.MATCH_SOURCE_CANONICAL_EXACT
        }
        if (learnedEntry != null && isEligibleForAutoPromotion(learnedEntry, nowMs)) {
            return learnedEntry.intentId to KbRetrievalOutcome.MATCH_SOURCE_LEARNED_PHRASE
        }
        return null
    }

    /**
     * Applies expiration (> 90 days unused) and LRU eviction (max 250 entries ordered by
     * `lastUsedEpochMs DESC`) to a list of [LearnedPhraseEntry] records.
     */
    fun pruneEntries(
        entries: List<LearnedPhraseEntry>,
        nowMs: Long,
        maxEntries: Int = MAX_LEARNED_PHRASES,
        expiryMs: Long = EXPIRY_MS,
    ): List<LearnedPhraseEntry> {
        val cutoff = nowMs - expiryMs
        return entries
            .filter { it.lastUsedEpochMs >= cutoff }
            .sortedByDescending { it.lastUsedEpochMs }
            .take(maxEntries.coerceAtLeast(0))
    }
}

/**
 * Privacy-First Local Phrase Memory & Text-Only Interaction Log Store (`voice_learning.db`).
 *
 * Security & privacy invariants:
 * - Stored strictly inside app-private internal storage (`context.filesDir/voice_learning/voice_learning.db`).
 * - Never synced automatically to any cloud endpoint.
 * - Never stores raw audio `.wav` / PCM files — text transcripts and intent IDs only.
 * - Enforces [VoiceLearningPolicy]:
 *   - Learned phrases only promote to auto-execute once `hit_count >= 2`.
 *   - Automatically expires rows unused for > 90 days.
 *   - Automatically caps `voice_learned_phrases` at 250 entries via LRU eviction.
 */
class VoiceLearningStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dbLock = Any()
    private val dbFile: File = File(File(appContext.filesDir, "voice_learning").apply { mkdirs() }, DB_NAME)
    private val dbHelper: LearningDbHelper = LearningDbHelper(appContext, dbFile.absolutePath)

    private val _stats = MutableStateFlow(VoiceLearningStats())
    val stats: StateFlow<VoiceLearningStats> = _stats.asStateFlow()

    init {
        refreshStatsSafe()
    }

    /**
     * Looks up a learned `intent_id` for [normalizedTranscript] that is eligible for auto-execution
     * (`hit_count >= 2` and not older than 90 days).
     */
    fun lookupPromotedIntent(
        normalizedTranscript: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val key = normalizedTranscript.trim()
        if (key.isEmpty()) return null
        return synchronized(dbLock) {
            runCatching {
                val db = dbHelper.writableDatabase
                pruneExpiredLocked(db, nowMs)
                val cutoff = nowMs - VoiceLearningPolicy.EXPIRY_MS
                db.rawQuery(
                    """
                    SELECT intent_id, hit_count, last_used_epoch_ms
                    FROM $TABLE_LEARNED_PHRASES
                    WHERE normalized_transcript = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(key),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) return@runCatching null
                    val intentId = cursor.getString(0).orEmpty()
                    val hitCount = cursor.getInt(1)
                    val lastUsed = cursor.getLong(2)
                    if (hitCount >= VoiceLearningPolicy.MIN_HITS_FOR_PROMOTION &&
                        lastUsed >= cutoff &&
                        intentId.isNotBlank()
                    ) {
                        intentId
                    } else {
                        null
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "lookupPromotedIntent failed", e)
            }.getOrNull()
        }
    }

    /**
     * Updates `hit_count` and `last_used_epoch_ms` when a promoted learned phrase is reused.
     */
    fun recordLearnedHit(
        normalizedTranscript: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = normalizedTranscript.trim()
        if (key.isEmpty()) return
        synchronized(dbLock) {
            runCatching {
                val db = dbHelper.writableDatabase
                db.execSQL(
                    """
                    UPDATE $TABLE_LEARNED_PHRASES
                    SET hit_count = hit_count + 1,
                        last_used_epoch_ms = ?
                    WHERE normalized_transcript = ?
                    """.trimIndent(),
                    arrayOf<Any>(nowMs, key),
                )
                refreshStatsLocked(db)
            }.onFailure { e ->
                Log.w(TAG, "recordLearnedHit failed", e)
            }
        }
    }

    /**
     * Records a user-confirmed correction (`normalizedTranscript -> intentId`) from a one-tap
     * suggestion chip.
     *
     * - If `normalizedTranscript` already maps to the same `intentId`, increments `hit_count`.
     * - If it previously mapped to a different `intentId`, resets `hit_count = 1` for the new
     *   `intentId` so a single accidental tap cannot immediately hijack the phrase.
     * - Automatically prunes expired rows (> 90 days) and enforces the 250-entry LRU cap.
     * - Logs the correction event in `voice_interaction_logs` (text-only).
     *
     * @return The updated `hit_count` for `(normalizedTranscript, intentId)`.
     */
    fun recordCorrection(
        normalizedTranscript: String,
        intentId: String,
        rawTranscript: String = normalizedTranscript,
        lang: String = TextNormalizer.detectLanguage(rawTranscript),
        originalMatchedIntentId: String? = null,
        originalScore: Float = 0f,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val key = normalizedTranscript.trim()
        val targetIntent = intentId.trim()
        if (key.isEmpty() || targetIntent.isEmpty()) return 0

        return synchronized(dbLock) {
            runCatching {
                val db = dbHelper.writableDatabase
                db.beginTransaction()
                var newHitCount = 1
                try {
                    var existingIntent: String? = null
                    var existingHits = 0
                    var existingCreated = nowMs

                    db.rawQuery(
                        """
                        SELECT intent_id, hit_count, created_epoch_ms
                        FROM $TABLE_LEARNED_PHRASES
                        WHERE normalized_transcript = ?
                        LIMIT 1
                        """.trimIndent(),
                        arrayOf(key),
                    ).use { c ->
                        if (c.moveToFirst()) {
                            existingIntent = c.getString(0)
                            existingHits = c.getInt(1)
                            existingCreated = c.getLong(2)
                        }
                    }

                    newHitCount = if (existingIntent == targetIntent) {
                        existingHits + 1
                    } else {
                        1
                    }

                    val cv = ContentValues().apply {
                        put("normalized_transcript", key)
                        put("intent_id", targetIntent)
                        put("hit_count", newHitCount)
                        put("last_used_epoch_ms", nowMs)
                        put("created_epoch_ms", if (existingIntent == targetIntent) existingCreated else nowMs)
                    }
                    db.insertWithOnConflict(TABLE_LEARNED_PHRASES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

                    insertLogLocked(
                        db = db,
                        timestampEpochMs = nowMs,
                        lang = lang,
                        rawTranscript = rawTranscript,
                        normalizedTranscript = key,
                        matchedIntentId = originalMatchedIntentId,
                        score = originalScore,
                        matchSource = "user_correction",
                        userCorrectedIntentId = targetIntent,
                    )

                    pruneExpiredAndEnforceCapLocked(db, nowMs)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                refreshStatsLocked(db)
                newHitCount
            }.onFailure { e ->
                Log.w(TAG, "recordCorrection failed", e)
            }.getOrDefault(0)
        }
    }

    /**
     * Logs a text-only voice interaction row into `voice_interaction_logs`.
     * Never stores audio files or PCM data.
     */
    fun logInteraction(
        lang: String,
        rawTranscript: String,
        normalizedTranscript: String,
        matchedIntentId: String?,
        score: Float,
        matchSource: String,
        userCorrectedIntentId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (rawTranscript.isBlank() && normalizedTranscript.isBlank()) return
        synchronized(dbLock) {
            runCatching {
                val db = dbHelper.writableDatabase
                insertLogLocked(
                    db = db,
                    timestampEpochMs = nowMs,
                    lang = lang.ifBlank { "en" },
                    rawTranscript = rawTranscript.trim(),
                    normalizedTranscript = normalizedTranscript.trim(),
                    matchedIntentId = matchedIntentId,
                    score = score,
                    matchSource = matchSource,
                    userCorrectedIntentId = userCorrectedIntentId,
                )
                enforceLogCapLocked(db)
                refreshStatsLocked(db)
            }.onFailure { e ->
                Log.w(TAG, "logInteraction failed", e)
            }
        }
    }

    /**
     * Deletes all learned phrase mappings in `voice_learned_phrases` and refreshes [stats].
     *
     * @return Number of deleted learned phrase rows.
     */
    fun clearLearnedPhrases(): Int {
        return synchronized(dbLock) {
            runCatching {
                val db = dbHelper.writableDatabase
                val deleted = db.delete(TABLE_LEARNED_PHRASES, null, null)
                refreshStatsLocked(db)
                deleted
            }.onFailure { e ->
                Log.w(TAG, "clearLearnedPhrases failed", e)
            }.getOrDefault(0)
        }
    }

    /**
     * Runs maintenance pruning (> 90 days expiry & 250-entry LRU cap) and refreshes [stats].
     */
    fun refreshStats(nowMs: Long = System.currentTimeMillis()): VoiceLearningStats {
        return synchronized(dbLock) {
            runCatching {
                val db = dbHelper.writableDatabase
                pruneExpiredAndEnforceCapLocked(db, nowMs)
                refreshStatsLocked(db)
            }.getOrElse { _stats.value }
        }
    }

    /**
     * Opt-in export of privacy-safe text-only interaction logs and learned phrase mappings
     * to JSON or CSV inside `/NourAlwelayah/config/` (never includes any audio files).
     */
    fun exportTextLogs(format: VoiceLogExportFormat): File {
        val exportDir = FileSystemManager.getSubdirectory(appContext, "config").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(exportDir, "voice_text_logs_${stamp}.${format.extension}")

        synchronized(dbLock) {
            val db = dbHelper.readableDatabase
            when (format) {
                VoiceLogExportFormat.JSON -> {
                    val root = JSONObject().apply {
                        put("exported_at_epoch_ms", System.currentTimeMillis())
                        put("privacy_notice", "Text transcripts and intent IDs only. No audio files stored.")
                        put("max_learned_phrases", VoiceLearningPolicy.MAX_LEARNED_PHRASES)
                        put("expiry_days", VoiceLearningPolicy.EXPIRY_DAYS)
                        put("min_hits_for_promotion", VoiceLearningPolicy.MIN_HITS_FOR_PROMOTION)
                    }

                    val phrasesArr = JSONArray()
                    db.rawQuery(
                        """
                        SELECT normalized_transcript, intent_id, hit_count, last_used_epoch_ms, created_epoch_ms
                        FROM $TABLE_LEARNED_PHRASES
                        ORDER BY last_used_epoch_ms DESC
                        """.trimIndent(),
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            phrasesArr.put(
                                JSONObject().apply {
                                    put("normalized_transcript", c.getString(0))
                                    put("intent_id", c.getString(1))
                                    put("hit_count", c.getInt(2))
                                    put("promoted", c.getInt(2) >= VoiceLearningPolicy.MIN_HITS_FOR_PROMOTION)
                                    put("last_used_epoch_ms", c.getLong(3))
                                    put("created_epoch_ms", c.getLong(4))
                                }
                            )
                        }
                    }
                    root.put("voice_learned_phrases", phrasesArr)

                    val logsArr = JSONArray()
                    db.rawQuery(
                        """
                        SELECT id, timestamp_epoch_ms, lang, raw_transcript, normalized_transcript,
                               matched_intent_id, score, match_source, user_corrected_intent_id
                        FROM $TABLE_INTERACTION_LOGS
                        ORDER BY id DESC
                        """.trimIndent(),
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            logsArr.put(
                                JSONObject().apply {
                                    put("id", c.getLong(0))
                                    put("timestamp_epoch_ms", c.getLong(1))
                                    put("lang", c.getString(2))
                                    put("raw_transcript", c.getString(3))
                                    put("normalized_transcript", c.getString(4))
                                    put("matched_intent_id", c.getString(5) ?: JSONObject.NULL)
                                    put("score", c.getDouble(6))
                                    put("match_source", c.getString(7))
                                    put("user_corrected_intent_id", c.getString(8) ?: JSONObject.NULL)
                                }
                            )
                        }
                    }
                    root.put("voice_interaction_logs", logsArr)
                    outFile.writeText(root.toString(2), Charsets.UTF_8)
                }

                VoiceLogExportFormat.CSV -> {
                    val sb = StringBuilder()
                    sb.appendLine("id,timestamp_epoch_ms,lang,raw_transcript,normalized_transcript,matched_intent_id,score,match_source,user_corrected_intent_id")
                    db.rawQuery(
                        """
                        SELECT id, timestamp_epoch_ms, lang, raw_transcript, normalized_transcript,
                               matched_intent_id, score, match_source, user_corrected_intent_id
                        FROM $TABLE_INTERACTION_LOGS
                        ORDER BY id DESC
                        """.trimIndent(),
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            sb.append(c.getLong(0)).append(',')
                            sb.append(c.getLong(1)).append(',')
                            sb.append(csvEscape(c.getString(2).orEmpty())).append(',')
                            sb.append(csvEscape(c.getString(3).orEmpty())).append(',')
                            sb.append(csvEscape(c.getString(4).orEmpty())).append(',')
                            sb.append(csvEscape(c.getString(5).orEmpty())).append(',')
                            sb.append(String.format(Locale.US, "%.4f", c.getDouble(6))).append(',')
                            sb.append(csvEscape(c.getString(7).orEmpty())).append(',')
                            sb.appendLine(csvEscape(c.getString(8).orEmpty()))
                        }
                    }
                    outFile.writeText(sb.toString(), Charsets.UTF_8)
                }
            }
        }
        return outFile
    }

    private fun insertLogLocked(
        db: SQLiteDatabase,
        timestampEpochMs: Long,
        lang: String,
        rawTranscript: String,
        normalizedTranscript: String,
        matchedIntentId: String?,
        score: Float,
        matchSource: String,
        userCorrectedIntentId: String?,
    ) {
        val cv = ContentValues().apply {
            put("timestamp_epoch_ms", timestampEpochMs)
            put("lang", lang)
            put("raw_transcript", rawTranscript)
            put("normalized_transcript", normalizedTranscript)
            put("matched_intent_id", matchedIntentId)
            put("score", score.toDouble())
            put("match_source", matchSource)
            put("user_corrected_intent_id", userCorrectedIntentId)
        }
        db.insert(TABLE_INTERACTION_LOGS, null, cv)
    }

    private fun pruneExpiredLocked(db: SQLiteDatabase, nowMs: Long) {
        val cutoff = nowMs - VoiceLearningPolicy.EXPIRY_MS
        db.delete(TABLE_LEARNED_PHRASES, "last_used_epoch_ms < ?", arrayOf(cutoff.toString()))
    }

    private fun pruneExpiredAndEnforceCapLocked(db: SQLiteDatabase, nowMs: Long) {
        pruneExpiredLocked(db, nowMs)
        // LRU eviction: keep only the newest MAX_LEARNED_PHRASES (250) rows ordered by last_used_epoch_ms DESC
        db.execSQL(
            """
            DELETE FROM $TABLE_LEARNED_PHRASES
            WHERE normalized_transcript NOT IN (
                SELECT normalized_transcript
                FROM $TABLE_LEARNED_PHRASES
                ORDER BY last_used_epoch_ms DESC
                LIMIT ${VoiceLearningPolicy.MAX_LEARNED_PHRASES}
            )
            """.trimIndent()
        )
    }

    private fun enforceLogCapLocked(db: SQLiteDatabase) {
        db.execSQL(
            """
            DELETE FROM $TABLE_INTERACTION_LOGS
            WHERE id NOT IN (
                SELECT id FROM $TABLE_INTERACTION_LOGS
                ORDER BY id DESC
                LIMIT ${VoiceLearningPolicy.MAX_INTERACTION_LOGS}
            )
            """.trimIndent()
        )
    }

    private fun refreshStatsSafe() {
        synchronized(dbLock) {
            runCatching {
                refreshStatsLocked(dbHelper.readableDatabase)
            }.onFailure { e ->
                Log.w(TAG, "refreshStatsSafe failed", e)
            }
        }
    }

    private fun refreshStatsLocked(db: SQLiteDatabase): VoiceLearningStats {
        val total = querySingleInt(db, "SELECT COUNT(*) FROM $TABLE_LEARNED_PHRASES")
        val promoted = querySingleInt(
            db,
            "SELECT COUNT(*) FROM $TABLE_LEARNED_PHRASES WHERE hit_count >= ${VoiceLearningPolicy.MIN_HITS_FOR_PROMOTION}"
        )
        val logs = querySingleInt(db, "SELECT COUNT(*) FROM $TABLE_INTERACTION_LOGS")
        val next = VoiceLearningStats(
            learnedPhraseCount = total,
            promotedPhraseCount = promoted,
            interactionLogCount = logs,
        )
        _stats.value = next
        return next
    }

    private fun querySingleInt(db: SQLiteDatabase, sql: String): Int {
        return db.rawQuery(sql, null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ')
        return "\"$escaped\""
    }

    private class LearningDbHelper(
        context: Context,
        dbPath: String,
    ) : SQLiteOpenHelper(context, dbPath, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_LEARNED_PHRASES (
                    normalized_transcript TEXT PRIMARY KEY,
                    intent_id TEXT NOT NULL,
                    hit_count INTEGER NOT NULL DEFAULT 1,
                    last_used_epoch_ms INTEGER NOT NULL,
                    created_epoch_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_learned_phrases_last_used
                ON $TABLE_LEARNED_PHRASES(last_used_epoch_ms DESC)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_INTERACTION_LOGS (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp_epoch_ms INTEGER NOT NULL,
                    lang TEXT NOT NULL,
                    raw_transcript TEXT NOT NULL,
                    normalized_transcript TEXT NOT NULL,
                    matched_intent_id TEXT,
                    score REAL NOT NULL,
                    match_source TEXT NOT NULL,
                    user_corrected_intent_id TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_interaction_logs_ts
                ON $TABLE_INTERACTION_LOGS(timestamp_epoch_ms DESC)
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onCreate(db)
        }
    }

    companion object {
        private const val TAG = "VoiceLearningStore"
        private const val DB_NAME = "voice_learning.db"
        private const val DB_VERSION = 1
        private const val TABLE_LEARNED_PHRASES = "voice_learned_phrases"
        private const val TABLE_INTERACTION_LOGS = "voice_interaction_logs"
    }
}
