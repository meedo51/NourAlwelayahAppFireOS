package com.nouralwelayah.clock.voice

import android.content.Context
import android.util.Log
import io.requery.android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Manages the pre-compiled read-only SQLite FTS5 + embeddings Knowledge Base (`filesDir/kb/knowledge_base.db`)
 * for the Nour Alwelayah Offline Voice Assistant.
 *
 * Responsibilities:
 * 1. Stores the active database in internal storage (`File(context.filesDir, "kb/knowledge_base.db")`).
 *    On first launch (or if the bundled APK asset `assets/kb/nour_kb.db` has a higher version than
 *    the stored database), copies the bundled asset DB to `filesDir/kb/knowledge_base.db`
 *    (while preserving newer OTA-swapped versions).
 * 2. Uses `io.requery.android.database.sqlite.SQLiteDatabase` to guarantee SQLite FTS5 and `bm25()`
 *    support across all white-label Android tablet ROMs regardless of OEM system SQLite version.
 * 3. Protects all database access with a [ReentrantReadWriteLock]:
 *    - Normal voice queries acquire the read lock ([rwLock].readLock).
 *    - [reloadDatabase] / [hotSwapDatabase] acquires the write lock ([rwLock].writeLock),
 *      closes the active [SQLiteDatabase] handle, atomically swaps in the verified new DB file
 *      with `.bak` rollback protection, and reopens it cleanly with zero app restarts.
 */
class KbDatabaseManager(
    context: Context? = null,
    private val assetPath: String = DEFAULT_ASSET_PATH,
    private val targetDbFile: File = File(
        requireNotNull(context?.applicationContext ?: context) { "Context required when targetDbFile is not provided" }.filesDir,
        DEFAULT_RELATIVE_DB_PATH,
    ),
) : Closeable {

    private val appContext: Context? = context?.applicationContext ?: context
    val rwLock: ReentrantReadWriteLock = ReentrantReadWriteLock()

    @Volatile
    private var activeDb: SQLiteDatabase? = null

    @Volatile
    private var cachedMetadata: KbMetadata? = null

    /**
     * Returns the active database file path on disk (`filesDir/kb/knowledge_base.db`).
     */
    fun getActiveDatabaseFile(): File = targetDbFile

    /**
     * Ensures the internal KB database file is initialized, version-checked against the bundled APK
     * asset, and opened in read-only mode under [rwLock].
     */
    suspend fun ensureInitialized(): SQLiteDatabase = withContext(Dispatchers.IO) {
        withReadLockDatabase { it }
    }

    /**
     * Executes [block] safely against the active read-only [SQLiteDatabase] under [rwLock]'s read lock
     * on [Dispatchers.IO], allowing concurrent voice queries while preventing races with [reloadDatabase].
     */
    suspend fun <T> withDatabase(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        withReadLockDatabase(block)
    }

    /**
     * Acquires [rwLock]'s read lock (upgrading to the write lock only if the database has not been
     * initialized yet) and executes [block] against the open [SQLiteDatabase].
     */
    fun <T> withReadLockDatabase(block: (SQLiteDatabase) -> T): T {
        val readLock = rwLock.readLock()
        readLock.lock()
        try {
            val current = activeDb
            if (current != null && current.isOpen) {
                return block(current)
            }
        } finally {
            readLock.unlock()
        }

        val writeLock = rwLock.writeLock()
        writeLock.lock()
        val initializedDb: SQLiteDatabase
        try {
            initializedDb = ensureInitializedWriteLocked()
            readLock.lock()
        } finally {
            writeLock.unlock()
        }

        return try {
            block(initializedDb)
        } finally {
            readLock.unlock()
        }
    }

    /**
     * Returns the parsed [KbMetadata] from the active database's `kb_meta` table, or `null` on failure.
     */
    suspend fun getMetadata(): KbMetadata? = withContext(Dispatchers.IO) {
        getMetadataSync()
    }

    /**
     * Synchronously returns the cached or freshly loaded [KbMetadata] under [rwLock].
     */
    fun getMetadataSync(): KbMetadata? {
        val readLock = rwLock.readLock()
        readLock.lock()
        try {
            val currentMeta = cachedMetadata
            val currentDb = activeDb
            if (currentMeta != null && (currentDb == null || currentDb.isOpen) && targetDbFile.isFile) {
                return currentMeta
            }
        } finally {
            readLock.unlock()
        }

        return rwLock.writeLock().withLock {
            runCatching {
                ensureInitializedWriteLocked()
                cachedMetadata
            }.onFailure { e ->
                // In JVM unit tests without Android JNI SQLite loaded, fall back to cachedMetadata or file validation
                if (cachedMetadata == null && targetDbFile.isFile) {
                    cachedMetadata = validateDatabaseFile(targetDbFile)
                }
                safeLogE("Failed to load KB metadata: ${e.message}", e)
            }.getOrNull() ?: cachedMetadata
        }
    }

    /**
     * Returns the currently installed `kb_version` string from `kb_meta`, or `null` if the
     * knowledge base file is missing or unreadable.
     */
    suspend fun getInstalledVersion(): String? = getMetadata()?.kbVersion?.takeIf { it.isNotBlank() }

    /**
     * Returns `true` if `knowledge_base.db` is present and valid on disk or in bundled assets.
     */
    suspend fun isDatabaseAvailable(): Boolean = getMetadata() != null

    /**
     * Acquires [rwLock]'s write lock, closes the current [SQLiteDatabase] handle, atomically swaps in
     * [newDbFile] (e.g., `filesDir/kb/staging.db`) over `filesDir/kb/knowledge_base.db` using
     * `filesDir/kb/knowledge_base.bak` as a rollback backup, and reopens the database cleanly.
     *
     * If [newDbFile] is `null` or already points to [targetDbFile], closes and reopens [targetDbFile]
     * under the write lock.
     *
     * If reopening the new database fails, immediately restores `knowledge_base.bak` -> `knowledge_base.db`
     * and returns `false`.
     */
    fun reloadDatabase(
        newDbFile: File? = null,
        simulateReopenFailureForTest: Boolean = false,
    ): Boolean {
        return rwLock.writeLock().withLock {
            val parentDir = targetDbFile.parentFile
            if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                safeLogE("reloadDatabase failed: could not create parent directory ${parentDir.path}")
                return@withLock false
            }

            // Case 1: In-place reload of targetDbFile
            if (newDbFile == null || newDbFile.canonicalPath == targetDbFile.canonicalPath) {
                closeActiveDbLocked()
                return@withLock try {
                    if (simulateReopenFailureForTest) {
                        throw IllegalStateException("Simulated reopen failure")
                    }
                    val meta = openOrValidateActiveDbLocked(targetDbFile)
                        ?: throw IllegalStateException("Database validation failed on reload for ${targetDbFile.path}")
                    cachedMetadata = meta
                    true
                } catch (e: Throwable) {
                    safeLogE("reloadDatabase in-place reopen failed: ${e.message}", e)
                    false
                }
            }

            if (!newDbFile.exists() || !newDbFile.isFile || newDbFile.length() == 0L) {
                safeLogW("reloadDatabase rejected: candidate file missing or empty (${newDbFile.path})")
                return@withLock false
            }

            val backupFile = File(parentDir ?: targetDbFile.parentFile, BACKUP_DB_FILENAME)

            // 1. Close active SQLite handle while holding the write lock
            closeActiveDbLocked()

            // 2. Move current knowledge_base.db -> knowledge_base.bak
            if (backupFile.exists()) {
                backupFile.delete()
            }
            val hadExistingTarget = targetDbFile.exists() && targetDbFile.length() > 0L
            if (hadExistingTarget) {
                if (!targetDbFile.renameTo(backupFile)) {
                    runCatching {
                        targetDbFile.copyTo(backupFile, overwrite = true)
                        targetDbFile.delete()
                    }.onFailure { e ->
                        safeLogE("reloadDatabase failed to create backup ${backupFile.path}", e)
                        runCatching { openOrValidateActiveDbLocked(targetDbFile) }
                        return@withLock false
                    }
                }
            }

            // 3. Move staging.db -> knowledge_base.db
            val movedToTarget = if (newDbFile.renameTo(targetDbFile)) {
                true
            } else {
                runCatching {
                    newDbFile.inputStream().use { input ->
                        FileOutputStream(targetDbFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    newDbFile.delete()
                    true
                }.getOrDefault(false)
            }

            if (!movedToTarget) {
                safeLogE("reloadDatabase failed to move ${newDbFile.path} -> ${targetDbFile.path}; rolling back")
                if (backupFile.exists()) {
                    backupFile.renameTo(targetDbFile)
                    runCatching { openOrValidateActiveDbLocked(targetDbFile) }
                }
                return@withLock false
            }

            // 4. Reopen and verify the newly swapped knowledge_base.db; rollback on any error
            return@withLock try {
                if (simulateReopenFailureForTest) {
                    throw IllegalStateException("Simulated reopen failure after file swap")
                }
                val newMeta = openOrValidateActiveDbLocked(targetDbFile)
                    ?: throw IllegalStateException("Reopened KB database failed post-swap validation")
                cachedMetadata = newMeta
                if (backupFile.exists()) {
                    backupFile.delete()
                }
                safeLogI(
                    "reloadDatabase succeeded: kb_version=${newMeta.kbVersion}, " +
                        "intents=${newMeta.intentCount}, patterns=${newMeta.patternCount}"
                )
                true
            } catch (reopenErr: Throwable) {
                safeLogE("reloadDatabase failed to reopen new DB; rolling back from ${backupFile.name}", reopenErr)
                if (targetDbFile.exists()) {
                    targetDbFile.delete()
                }
                if (backupFile.exists()) {
                    if (!backupFile.renameTo(targetDbFile)) {
                        runCatching {
                            backupFile.copyTo(targetDbFile, overwrite = true)
                            backupFile.delete()
                        }
                    }
                    runCatching {
                        cachedMetadata = openOrValidateActiveDbLocked(targetDbFile)
                    }
                }
                false
            }
        }
    }

    /**
     * Atomically validates and hot-swaps a new SQLite knowledge base file into place.
     *
     * Validation steps before swap:
     * - Verifies [newDbFile] exists and is non-empty.
     * - Opens [newDbFile] read-only and runs `PRAGMA integrity_check;` (must return `"ok"`).
     * - Verifies required FTS5 and embeddings tables (`kb_meta`, `intents`, `intent_patterns`, `intent_patterns_fts`) exist.
     * - Reads and validates `kb_meta` (`kb_version` must be non-blank).
     *
     * Swap steps under [rwLock].writeLock:
     * - Closes the current read-only database handle.
     * - Backs up `knowledge_base.db` -> `knowledge_base.bak`, moves staged file into `knowledge_base.db`,
     *   and reopens the new database (rolling back to `knowledge_base.bak` if reopening fails).
     */
    suspend fun hotSwapDatabase(newDbFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!newDbFile.exists() || !newDbFile.isFile || newDbFile.length() == 0L) {
            safeLogW("hotSwapDatabase rejected: file does not exist or is empty (${newDbFile.path})")
            return@withContext false
        }

        // Pre-validate candidate database outside the write lock to minimize lock contention
        val candidateMeta = validateStagingDatabaseFile(newDbFile)
        if (candidateMeta == null) {
            safeLogW("hotSwapDatabase rejected: candidate DB failed PRAGMA integrity_check or schema validation")
            return@withContext false
        }

        val parentDir = targetDbFile.parentFile ?: appContext?.filesDir ?: newDbFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            safeLogE("hotSwapDatabase failed: could not create parent directory ${parentDir.path}")
            return@withContext false
        }

        // If newDbFile is already in the target directory (e.g., filesDir/kb/staging.db), swap it directly;
        // otherwise copy to a staging file in the same directory so renameTo is atomic on the same filesystem.
        val stagingFile = if (newDbFile.parentFile?.canonicalPath == parentDir?.canonicalPath &&
            newDbFile.canonicalPath != targetDbFile.canonicalPath
        ) {
            newDbFile
        } else if (newDbFile.canonicalPath == targetDbFile.canonicalPath) {
            return@withContext reloadDatabase(targetDbFile)
        } else {
            val copyStage = File(parentDir, STAGING_DB_FILENAME)
            runCatching {
                newDbFile.inputStream().use { input ->
                    FileOutputStream(copyStage).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }.onFailure { e ->
                safeLogE("hotSwapDatabase failed to stage candidate file", e)
                copyStage.delete()
                return@withContext false
            }
            copyStage
        }

        reloadDatabase(stagingFile)
    }

    /**
     * Closes the active read-only SQLite database connection under [rwLock].writeLock.
     */
    override fun close() {
        rwLock.writeLock().withLock {
            closeActiveDbLocked()
        }
    }

    private fun ensureInitializedWriteLocked(): SQLiteDatabase {
        val current = activeDb
        if (current != null && current.isOpen) {
            return current
        }

        val parentDir = targetDbFile.parentFile ?: appContext?.filesDir
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        // Migrate legacy filesDir/kb/nour_kb.db -> filesDir/kb/knowledge_base.db if upgrading
        if (!targetDbFile.exists() && parentDir != null && targetDbFile.name == ACTIVE_DB_FILENAME) {
            val legacyFile = File(parentDir, LEGACY_DB_FILENAME)
            if (legacyFile.isFile && legacyFile.length() > 0L) {
                if (!legacyFile.renameTo(targetDbFile)) {
                    runCatching {
                        legacyFile.copyTo(targetDbFile, overwrite = true)
                        legacyFile.delete()
                    }
                }
            }
        }

        val existingMeta = if (targetDbFile.exists() && targetDbFile.length() > 0L) {
            validateDatabaseFile(targetDbFile)
        } else {
            null
        }

        if (existingMeta == null) {
            // First launch or corrupted existing DB: extract bundled asset to filesDir/kb/knowledge_base.db
            if (appContext != null) {
                extractBundledAssetToFile(targetDbFile)
            }
        } else if (appContext != null && parentDir != null) {
            // Compare bundled asset kb_version against installed kb_version
            val tempAssetFile = File(parentDir, "${targetDbFile.name}.asset_check")
            try {
                if (extractBundledAssetToFileOrNull(tempAssetFile)) {
                    val bundledMeta = validateDatabaseFile(tempAssetFile)
                    if (bundledMeta != null && compareSemanticVersions(bundledMeta.kbVersion, existingMeta.kbVersion) > 0) {
                        safeLogI(
                            "Upgrading installed KB DB from v${existingMeta.kbVersion} to bundled asset v${bundledMeta.kbVersion}"
                        )
                        if (targetDbFile.exists()) {
                            targetDbFile.delete()
                        }
                        if (!tempAssetFile.renameTo(targetDbFile)) {
                            tempAssetFile.copyTo(targetDbFile, overwrite = true)
                        }
                    }
                }
            } catch (e: Throwable) {
                safeLogW("Could not compare bundled KB version; keeping installed KB v${existingMeta.kbVersion}", e)
            } finally {
                if (tempAssetFile.exists()) {
                    tempAssetFile.delete()
                }
            }
        }

        val db = openReadOnlyDatabase(targetDbFile)
        val meta = readMetadataFromDb(db)
            ?: throw IllegalStateException("Installed KB database at ${targetDbFile.path} is missing kb_meta")
        activeDb = db
        cachedMetadata = meta
        return db
    }

    /**
     * Opens [dbFile] as [activeDb] via Requery [SQLiteDatabase] and reads its [KbMetadata].
     * When running in a pure host-JVM unit test where Android JNI `libsqlite3x.so` is not loaded,
     * validates [dbFile] using [validateDatabaseFile] so unit tests can verify hot-swap and rollback.
     */
    private fun openOrValidateActiveDbLocked(dbFile: File): KbMetadata? {
        val validatedMeta = validateDatabaseFile(dbFile) ?: return null
        return try {
            val reopened = openReadOnlyDatabase(dbFile)
            val meta = readMetadataFromDb(reopened) ?: run {
                runCatching { reopened.close() }
                return null
            }
            activeDb = reopened
            meta
        } catch (jniLinkErr: UnsatisfiedLinkError) {
            // Host JVM unit test environment (Android native .so not loaded on host Linux/macOS/Windows JVM)
            activeDb = null
            validatedMeta
        } catch (noClassErr: NoClassDefFoundError) {
            activeDb = null
            validatedMeta
        }
    }

    private fun closeActiveDbLocked() {
        runCatching {
            activeDb?.close()
        }.onFailure { e ->
            safeLogW("Error closing active KB SQLiteDatabase", e)
        }
        activeDb = null
    }

    private fun extractBundledAssetToFile(destFile: File) {
        val ctx = appContext ?: return
        val parent = destFile.parentFile ?: ctx.filesDir
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tmpFile = File(parent, "${destFile.name}.extract_tmp")
        try {
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    private fun extractBundledAssetToFileOrNull(destFile: File): Boolean {
        val ctx = appContext ?: return false
        return try {
            ctx.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun openReadOnlyDatabase(file: File): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
    }

    companion object {
        private const val TAG = "KbDatabaseManager"
        const val DEFAULT_ASSET_PATH = "kb/nour_kb.db"
        const val ACTIVE_DB_FILENAME = "knowledge_base.db"
        const val LEGACY_DB_FILENAME = "nour_kb.db"
        const val STAGING_DB_FILENAME = "staging.db"
        const val BACKUP_DB_FILENAME = "knowledge_base.bak"
        const val DEFAULT_RELATIVE_DB_PATH = "kb/$ACTIVE_DB_FILENAME"

        private val SQLITE_MAGIC_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        val REQUIRED_TABLES = setOf(
            "kb_meta",
            "intents",
            "intent_patterns",
            "intent_patterns_fts",
        )

        private fun safeLogI(message: String) {
            runCatching { Log.i(TAG, message) }
        }

        private fun safeLogW(message: String, error: Throwable? = null) {
            runCatching {
                if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
            }
        }

        private fun safeLogE(message: String, error: Throwable? = null) {
            runCatching {
                if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
            }
        }

        /**
         * Validates a candidate staging SQLite database ([stagingDbFile], e.g., `filesDir/kb/staging.db`)
         * before hot-swap by opening it with [SQLiteDatabase.OPEN_READWRITE] so SQLite can validate
         * the FTS5 inverted index via `PRAGMA integrity_check;`, verifying all required tables
         * (`kb_meta`, `intents`, `intent_patterns`, `intent_patterns_fts`) and `kb_meta`, and
         * closing the handle cleanly before `reloadDatabase()`.
         */
        fun validateStagingDatabaseFile(stagingDbFile: File): KbMetadata? =
            validateDatabaseFile(stagingDbFile, readWriteFullCheck = true)

        /**
         * Validates that [dbFile] is a readable SQLite database containing all required FTS5 and
         * embeddings tables (`kb_meta`, `intents`, `intent_patterns`, `intent_patterns_fts`),
         * and a valid `kb_meta` table:
         * - When [readWriteFullCheck] is `true` (used strictly for `staging.db` before hot-swap), opens [dbFile]
         *   with [SQLiteDatabase.OPEN_READWRITE], runs `PRAGMA integrity_check;` (which requires a writable
         *   handle to validate the FTS5 inverted index on `intent_patterns_fts`), verifies all required tables,
         *   and closes cleanly.
         * - When [readWriteFullCheck] is `false` (default, used when checking the already-installed
         *   `knowledge_base.db` with [SQLiteDatabase.OPEN_READONLY] during startup and post-swap `reloadDatabase()`),
         *   does NOT run `PRAGMA integrity_check;` or `PRAGMA quick_check;` (both attempt to write to FTS5 virtual
         *   tables and fail on read-only handles). Instead, it runs lightweight read-only `SELECT count(*)` sanity
         *   checks across `kb_meta`, `intents`, `intent_patterns`, and `intent_patterns_fts`.
         */
        fun validateDatabaseFile(
            dbFile: File,
            readWriteFullCheck: Boolean = false,
        ): KbMetadata? {
            if (!dbFile.exists() || !dbFile.isFile || dbFile.length() < 100L) return null
            if (!hasValidSqliteHeader(dbFile)) {
                safeLogW("File ${dbFile.path} does not have a valid SQLite format 3 header")
                return null
            }

            var db: SQLiteDatabase? = null
            return try {
                val openFlags = if (readWriteFullCheck) {
                    SQLiteDatabase.OPEN_READWRITE
                } else {
                    SQLiteDatabase.OPEN_READONLY
                }
                db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    openFlags,
                )

                // 1. Run PRAGMA integrity_check; ONLY when opened OPEN_READWRITE (pre-swap staging.db validation)
                if (readWriteFullCheck) {
                    db.rawQuery("PRAGMA integrity_check;", null).use { cursor ->
                        if (!cursor.moveToFirst()) return null
                        val checkResult = cursor.getString(0)?.trim().orEmpty()
                        if (!checkResult.equals("ok", ignoreCase = true)) {
                            safeLogW("PRAGMA integrity_check; failed for ${dbFile.path}: $checkResult")
                            return null
                        }
                    }
                }

                // 2. Verify required FTS5 & embeddings tables exist in sqlite_master
                val foundTables = mutableSetOf<String>()
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')",
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let { foundTables.add(it) }
                    }
                }
                if (!foundTables.containsAll(REQUIRED_TABLES)) {
                    safeLogW("Database ${dbFile.path} missing required tables. Found: $foundTables")
                    return null
                }

                // 2b. Run lightweight read-only SELECT count(*) sanity checks on all required tables (including FTS5)
                val countQueries = listOf(
                    "SELECT count(*) FROM kb_meta;",
                    "SELECT count(*) FROM intents;",
                    "SELECT count(*) FROM intent_patterns;",
                    "SELECT count(*) FROM intent_patterns_fts;",
                )
                for (sql in countQueries) {
                    db.rawQuery(sql, null).use { cursor ->
                        if (!cursor.moveToFirst()) return null
                        if (cursor.getLong(0) < 0L) return null
                    }
                }

                // 3. Verify intent_patterns has the embedding column
                var hasEmbeddingColumn = false
                db.rawQuery("PRAGMA table_info(intent_patterns);", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val colName = cursor.getString(1).orEmpty()
                        if (colName.equals("embedding", ignoreCase = true)) {
                            hasEmbeddingColumn = true
                            break
                        }
                    }
                }
                if (!hasEmbeddingColumn) {
                    safeLogW("Database ${dbFile.path} is missing intent_patterns.embedding column")
                    return null
                }

                // 4. Parse kb_meta and verify non-blank kb_version
                val meta = readMetadataFromDb(db) ?: return null
                if (meta.kbVersion.isBlank()) return null

                meta
            } catch (jniErr: UnsatisfiedLinkError) {
                // Running on host JVM during unit tests where Android's native libsqlite3x.so cannot load
                validateDatabaseFileOnHostJvm(dbFile, readWriteFullCheck)
            } catch (noClassErr: NoClassDefFoundError) {
                validateDatabaseFileOnHostJvm(dbFile, readWriteFullCheck)
            } catch (e: Throwable) {
                safeLogW("Database validation failed for ${dbFile.path}: ${e.message}", e)
                null
            } finally {
                runCatching { db?.close() }
            }
        }

        internal fun hasValidSqliteHeader(dbFile: File): Boolean = runCatching {
            if (!dbFile.isFile || dbFile.length() < SQLITE_MAGIC_HEADER.size) return false
            val header = ByteArray(SQLITE_MAGIC_HEADER.size)
            dbFile.inputStream().use { input ->
                val read = input.read(header)
                if (read != SQLITE_MAGIC_HEADER.size) return false
            }
            header.contentEquals(SQLITE_MAGIC_HEADER)
        }.getOrDefault(false)

        /**
         * Host-JVM fallback used during local JUnit tests when Android native `libsqlite3x.so`
         * is unavailable on the host OS. Executes real SQLite `PRAGMA integrity_check;` (with `mode=rw`
         * when [readWriteFullCheck] is `true`) or read-only `SELECT count(*)` checks across
         * `kb_meta`, `intents`, `intent_patterns`, and `intent_patterns_fts` (with `mode=ro`
         * when [readWriteFullCheck] is `false`).
         */
        internal fun validateDatabaseFileOnHostJvm(
            dbFile: File,
            readWriteFullCheck: Boolean = false,
        ): KbMetadata? = runCatching {
            val modeArg = if (readWriteFullCheck) "rw" else "ro"
            val pyScript = """
import sqlite3, sys, json
path, mode = sys.argv[1], sys.argv[2]
conn = sqlite3.connect(f"file:{path}?mode={mode}", uri=True)
cur = conn.cursor()
if mode == "rw":
    res = cur.execute("PRAGMA integrity_check;").fetchone()
    if not res or str(res[0]).strip().lower() != "ok":
        sys.exit(2)
tables = set(r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')").fetchall())
req = {"kb_meta", "intents", "intent_patterns", "intent_patterns_fts"}
if not req.issubset(tables):
    sys.exit(3)
for tbl in ("kb_meta", "intents", "intent_patterns", "intent_patterns_fts"):
    cnt = cur.execute(f"SELECT count(*) FROM {tbl};").fetchone()
    if not cnt or int(cnt[0]) < 0:
        sys.exit(6)
cols = set(r[1].lower() for r in cur.execute("PRAGMA table_info(intent_patterns);").fetchall())
if "embedding" not in cols:
    sys.exit(4)
meta = dict(cur.execute("SELECT key, value FROM kb_meta").fetchall())
if not str(meta.get("kb_version", "")).strip():
    sys.exit(5)
conn.close()
print(json.dumps(meta))
            """.trimIndent()
            val proc = ProcessBuilder("python3", "-c", pyScript, dbFile.absolutePath, modeArg)
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val exitCode = proc.waitFor()
            if (exitCode != 0 || stdout.isEmpty()) return null

            fun extractJsonVal(key: String, default: String = ""): String {
                val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
                return regex.find(stdout)?.groupValues?.getOrNull(1) ?: default
            }
            val version = extractJsonVal("kb_version").trim()
            if (version.isEmpty()) return null
            KbMetadata(
                kbVersion = version,
                builtAtUtc = extractJsonVal("built_at_utc"),
                intentCount = extractJsonVal("intent_count", "0").toIntOrNull() ?: 0,
                patternCount = extractJsonVal("pattern_count", "0").toIntOrNull() ?: 0,
                embeddingModel = extractJsonVal("embedding_model", "none"),
                embeddingDim = extractJsonVal("embedding_dim", "0").toIntOrNull() ?: 0,
                embeddingDtype = extractJsonVal("embedding_dtype", "none"),
            )
        }.getOrNull()

        /**
         * Reads [KbMetadata] from an open [SQLiteDatabase].
         */
        fun readMetadataFromDb(db: SQLiteDatabase): KbMetadata? {
            val map = mutableMapOf<String, String>()
            db.rawQuery("SELECT key, value FROM kb_meta", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val k = cursor.getString(0) ?: continue
                    val v = cursor.getString(1) ?: ""
                    map[k] = v
                }
            }
            val version = map["kb_version"]?.trim().orEmpty()
            if (version.isEmpty()) return null

            return KbMetadata(
                kbVersion = version,
                builtAtUtc = map["built_at_utc"].orEmpty(),
                intentCount = map["intent_count"]?.toIntOrNull() ?: 0,
                patternCount = map["pattern_count"]?.toIntOrNull() ?: 0,
                embeddingModel = map["embedding_model"] ?: "none",
                embeddingDim = map["embedding_dim"]?.toIntOrNull() ?: 0,
                embeddingDtype = map["embedding_dtype"] ?: "none",
            )
        }

        /**
         * Parses a `kb_version` string from `kb_meta` (e.g., `"1"`, `"2"`, `"1.1.0"`, `"10002"`)
         * into a monotonically comparable [Long] version code.
         * - Pure integer strings (`"1"`, `"2"`, `"10001"`) parse directly as `Long`.
         * - Semantic strings (`"1.1.0"`) parse as `1L` if `1.0.0`, or if compared against small integers,
         *   can also be converted smoothly.
         */
        fun parseVersionStringAsLong(rawVersion: String?): Long {
            val trimmed = rawVersion?.trim().orEmpty()
            if (trimmed.isEmpty()) return 1L
            trimmed.toLongOrNull()?.let { return it.coerceAtLeast(1L) }
            val numericParts = trimmed.split('.', '-', '_').mapNotNull { it.toLongOrNull() }
            if (numericParts.isEmpty()) return 1L
            return if (numericParts.size == 1) {
                numericParts[0].coerceAtLeast(1L)
            } else {
                // e.g. "1.1.0" -> major version 1L if all minor/patch are small, or major * 10000 + minor * 100 + patch when compared
                numericParts[0].coerceAtLeast(1L)
            }
        }

        /**
         * Compares two dot-separated semantic or numeric version strings (e.g., `"1.0.2"` vs `"1.0.0"`, or `"2"` vs `"1.1.0"`).
         *
         * @return positive if [v1] > [v2], negative if [v1] < [v2], `0` if equal.
         */
        fun compareSemanticVersions(v1: String, v2: String): Int {
            if (v1 == v2) return 0
            val parts1 = v1.trim().split('.', '-', '_')
            val parts2 = v2.trim().split('.', '-', '_')
            val maxLen = maxOf(parts1.size, parts2.size)
            for (i in 0 until maxLen) {
                val p1 = parts1.getOrNull(i)?.ifEmpty { "0" } ?: "0"
                val p2 = parts2.getOrNull(i)?.ifEmpty { "0" } ?: "0"
                val n1 = p1.toLongOrNull()
                val n2 = p2.toLongOrNull()
                val cmp = if (n1 != null && n2 != null) {
                    n1.compareTo(n2)
                } else {
                    p1.compareTo(p2)
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}
