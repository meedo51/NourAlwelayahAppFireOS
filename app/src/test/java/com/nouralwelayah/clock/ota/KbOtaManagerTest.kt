package com.nouralwelayah.clock.ota

import com.nouralwelayah.clock.voice.KbDatabaseManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Unit tests for [KbOtaManager] and [KbDatabaseManager] hot-swap pipeline:
 * 1. Schema version mismatch (`schemaVersion != SUPPORTED_KB_SCHEMA_VERSION`) is safely ignored.
 * 2. Corrupted SHA-256 (compressed or uncompressed) aborts before touching the active DB.
 * 3. Failed `PRAGMA integrity_check;` or missing FTS5/embeddings schema aborts before touching the active DB.
 * 4. Successful `.db.gz` swap updates `filesDir/kb/knowledge_base.db`, persists the new `kbVersion`,
 *    and deletes `staging.part`, `staging.db`, and `knowledge_base.bak`.
 * 5. Reopen failure during swap immediately rolls back `knowledge_base.bak` -> `knowledge_base.db`.
 */
class KbOtaManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun locateBundledAssetDb(): File {
        val candidates = listOf(
            File("src/main/assets/kb/nour_kb.db"),
            File("app/src/main/assets/kb/nour_kb.db"),
            File("../app/src/main/assets/kb/nour_kb.db"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
            ?: error("Could not locate bundled assets/kb/nour_kb.db for test")
    }

    private fun createModifiedSqliteDb(baseDb: File, destDb: File, newKbVersionValue: String) {
        baseDb.copyTo(destDb, overwrite = true)
        val pyScript = """
import sqlite3, sys
path, ver = sys.argv[1], sys.argv[2]
conn = sqlite3.connect(path)
conn.execute("UPDATE kb_meta SET value = ? WHERE key = 'kb_version'", (ver,))
conn.commit()
conn.close()
        """.trimIndent()
        val proc = ProcessBuilder("python3", "-c", pyScript, destDb.absolutePath, newKbVersionValue).start()
        val exitCode = proc.waitFor()
        assertEquals("Python sqlite3 update of kb_version must succeed", 0, exitCode)
    }

    private fun gzipFile(source: File, gzDest: File) {
        source.inputStream().use { input ->
            GZIPOutputStream(FileOutputStream(gzDest)).use { gzOut ->
                input.copyTo(gzOut)
            }
        }
    }

    @Test
    fun `schema version mismatch is safely ignored without downloading or modifying active DB`() {
        val kbDir = tempFolder.newFolder("kb_schema_mismatch")
        val activeDbFile = File(kbDir, KbDatabaseManager.ACTIVE_DB_FILENAME)
        val bundledDb = locateBundledAssetDb()
        bundledDb.copyTo(activeDbFile, overwrite = true)
        val originalActiveBytes = activeDbFile.readBytes()

        val dbManager = KbDatabaseManager(targetDbFile = activeDbFile)
        var downloadCalled = false
        var installedVersion = 1L

        val state = KbOtaManager.executeUpdatePipeline(
            kbDir = kbDir,
            dbManager = dbManager,
            installedKbVersion = installedVersion,
            fetchManifest = {
                KbManifest(
                    kbVersion = 5L,
                    schemaVersion = 99, // Unsupported schema version
                    downloadUrl = "https://alwelayah.xus.me/ota/kb/stable/kb-v5.db.gz",
                    sha256 = "a".repeat(64),
                    uncompressedSha256 = "b".repeat(64),
                    sizeBytes = 123_456L,
                    releaseNotes = "Incompatible schema v99",
                )
            },
            downloadPayload = { _, _, _ ->
                downloadCalled = true
                true
            },
            onVersionInstalled = { newVer, _ ->
                installedVersion = newVer
            },
        )

        assertTrue("Expected UpToDate when schema mismatches, got $state", state is KbOtaState.UpToDate)
        assertFalse("Downloader must NOT be invoked when schemaVersion is unsupported", downloadCalled)
        assertEquals("Installed version must remain 1", 1L, installedVersion)
        assertArrayEquals("Active knowledge_base.db must not be modified", originalActiveBytes, activeDbFile.readBytes())
    }

    @Test
    fun `corrupted SHA-256 aborts before touching the active DB and cleans up staging files`() {
        val kbDir = tempFolder.newFolder("kb_bad_sha")
        val activeDbFile = File(kbDir, KbDatabaseManager.ACTIVE_DB_FILENAME)
        val bundledDb = locateBundledAssetDb()
        bundledDb.copyTo(activeDbFile, overwrite = true)
        val originalActiveBytes = activeDbFile.readBytes()

        val candidateDb = File(tempFolder.root, "candidate_v2.db")
        createModifiedSqliteDb(bundledDb, candidateDb, "2")
        val candidateGz = File(tempFolder.root, "candidate_v2.db.gz")
        gzipFile(candidateDb, candidateGz)

        val dbManager = KbDatabaseManager(targetDbFile = activeDbFile)
        var installedVersion = 1L

        // Provide wrong sha256 in manifest
        val state = KbOtaManager.executeUpdatePipeline(
            kbDir = kbDir,
            dbManager = dbManager,
            installedKbVersion = installedVersion,
            fetchManifest = {
                KbManifest(
                    kbVersion = 2L,
                    schemaVersion = KbOtaManager.SUPPORTED_KB_SCHEMA_VERSION,
                    downloadUrl = "https://alwelayah.xus.me/ota/kb/stable/kb-v2.db.gz",
                    sha256 = "0".repeat(64), // Deliberate SHA-256 mismatch
                    uncompressedSha256 = KbOtaManager.computeSha256Hex(candidateDb),
                    sizeBytes = candidateGz.length(),
                    releaseNotes = "KB v2",
                )
            },
            downloadPayload = { _, partFile, onProgress ->
                candidateGz.copyTo(partFile, overwrite = true)
                onProgress(partFile.length(), partFile.length())
                true
            },
            onVersionInstalled = { newVer, _ ->
                installedVersion = newVer
            },
        )

        assertTrue("Expected Error on SHA-256 mismatch, got $state", state is KbOtaState.Error)
        assertEquals(1L, installedVersion)
        assertFalse("staging.part must be deleted on SHA-256 failure", File(kbDir, KbOtaManager.STAGING_PART_FILENAME).exists())
        assertFalse("staging.db must not exist on SHA-256 failure", File(kbDir, KbOtaManager.STAGING_DB_FILENAME).exists())
        assertArrayEquals("Active knowledge_base.db must remain untouched", originalActiveBytes, activeDbFile.readBytes())
    }

    @Test
    fun `failed PRAGMA integrity_check or missing FTS5 tables aborts before touching the active DB`() {
        val kbDir = tempFolder.newFolder("kb_corrupt_sqlite")
        val activeDbFile = File(kbDir, KbDatabaseManager.ACTIVE_DB_FILENAME)
        val bundledDb = locateBundledAssetDb()
        bundledDb.copyTo(activeDbFile, overwrite = true)
        val originalActiveBytes = activeDbFile.readBytes()

        // Case A: File with valid SHA-256 matching manifest, but corrupted SQLite structure
        val corruptDb = File(tempFolder.root, "corrupt_sqlite.db")
        val badBytes = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(4096) { 0x7F.toByte() }
        corruptDb.writeBytes(badBytes)
        val corruptGz = File(tempFolder.root, "corrupt_sqlite.db.gz")
        gzipFile(corruptDb, corruptGz)

        val dbManager = KbDatabaseManager(targetDbFile = activeDbFile)
        var installedVersion = 1L

        val stateCorrupt = KbOtaManager.executeUpdatePipeline(
            kbDir = kbDir,
            dbManager = dbManager,
            installedKbVersion = installedVersion,
            fetchManifest = {
                KbManifest(
                    kbVersion = 3L,
                    schemaVersion = KbOtaManager.SUPPORTED_KB_SCHEMA_VERSION,
                    downloadUrl = "https://alwelayah.xus.me/ota/kb/beta/kb-v3.db.gz",
                    sha256 = KbOtaManager.computeSha256Hex(corruptGz),
                    uncompressedSha256 = KbOtaManager.computeSha256Hex(corruptDb),
                    sizeBytes = corruptGz.length(),
                    releaseNotes = "Corrupted SQLite pages",
                )
            },
            downloadPayload = { _, partFile, _ ->
                corruptGz.copyTo(partFile, overwrite = true)
                true
            },
            onVersionInstalled = { newVer, _ ->
                installedVersion = newVer
            },
        )

        assertTrue("Expected Error when PRAGMA integrity_check fails, got $stateCorrupt", stateCorrupt is KbOtaState.Error)
        assertEquals(1L, installedVersion)
        assertFalse("staging.part must be cleaned up", File(kbDir, KbOtaManager.STAGING_PART_FILENAME).exists())
        assertFalse("staging.db must be cleaned up", File(kbDir, KbOtaManager.STAGING_DB_FILENAME).exists())
        assertArrayEquals("Active knowledge_base.db must remain untouched", originalActiveBytes, activeDbFile.readBytes())

        // Case B: Valid SQLite DB that passes PRAGMA integrity_check, but is missing intent_patterns_fts table
        val missingFtsDb = File(tempFolder.root, "missing_fts.db")
        bundledDb.copyTo(missingFtsDb, overwrite = true)
        val dropFtsProc = ProcessBuilder(
            "python3",
            "-c",
            "import sqlite3, sys; c=sqlite3.connect(sys.argv[1]); c.execute('DROP TABLE intent_patterns_fts'); c.commit(); c.close()",
            missingFtsDb.absolutePath,
        ).start()
        assertEquals(0, dropFtsProc.waitFor())

        val missingFtsGz = File(tempFolder.root, "missing_fts.db.gz")
        gzipFile(missingFtsDb, missingFtsGz)

        val stateMissingTable = KbOtaManager.executeUpdatePipeline(
            kbDir = kbDir,
            dbManager = dbManager,
            installedKbVersion = installedVersion,
            fetchManifest = {
                KbManifest(
                    kbVersion = 4L,
                    schemaVersion = KbOtaManager.SUPPORTED_KB_SCHEMA_VERSION,
                    downloadUrl = "https://alwelayah.xus.me/ota/kb/beta/kb-v4.db.gz",
                    sha256 = KbOtaManager.computeSha256Hex(missingFtsGz),
                    uncompressedSha256 = KbOtaManager.computeSha256Hex(missingFtsDb),
                    sizeBytes = missingFtsGz.length(),
                    releaseNotes = "Missing FTS5 table",
                )
            },
            downloadPayload = { _, partFile, _ ->
                missingFtsGz.copyTo(partFile, overwrite = true)
                true
            },
            onVersionInstalled = { newVer, _ ->
                installedVersion = newVer
            },
        )

        assertTrue("Expected Error when required FTS5 table is missing, got $stateMissingTable", stateMissingTable is KbOtaState.Error)
        assertEquals(1L, installedVersion)
        assertArrayEquals("Active knowledge_base.db must remain untouched", originalActiveBytes, activeDbFile.readBytes())
    }

    @Test
    fun `successful swap updates active DB and cleans up staging files`() {
        val kbDir = tempFolder.newFolder("kb_success_swap")
        val activeDbFile = File(kbDir, KbDatabaseManager.ACTIVE_DB_FILENAME)
        val bundledDb = locateBundledAssetDb()
        bundledDb.copyTo(activeDbFile, overwrite = true)

        // Build valid v2 KB SQLite DB and compress as .db.gz
        val candidateDb = File(tempFolder.root, "valid_v2.db")
        createModifiedSqliteDb(bundledDb, candidateDb, "2")
        val expectedNewBytes = candidateDb.readBytes()

        val candidateGz = File(tempFolder.root, "valid_v2.db.gz")
        gzipFile(candidateDb, candidateGz)

        val dbManager = KbDatabaseManager(targetDbFile = activeDbFile)
        var installedVersion = 1L
        var installedTimestamp = 0L

        val state = KbOtaManager.executeUpdatePipeline(
            kbDir = kbDir,
            dbManager = dbManager,
            installedKbVersion = installedVersion,
            fetchManifest = {
                KbManifest(
                    kbVersion = 2L,
                    schemaVersion = KbOtaManager.SUPPORTED_KB_SCHEMA_VERSION,
                    downloadUrl = "https://alwelayah.xus.me/ota/kb/stable/kb-v2.db.gz",
                    sha256 = KbOtaManager.computeSha256Hex(candidateGz),
                    uncompressedSha256 = KbOtaManager.computeSha256Hex(candidateDb),
                    sizeBytes = candidateGz.length(),
                    releaseNotes = "Added new bilingual intents",
                )
            },
            downloadPayload = { _, partFile, onProgress ->
                candidateGz.copyTo(partFile, overwrite = true)
                onProgress(partFile.length(), partFile.length())
                true
            },
            onVersionInstalled = { newVer, syncedAt ->
                installedVersion = newVer
                installedTimestamp = syncedAt
            },
        )

        assertTrue("Expected Updated state, got $state", state is KbOtaState.Updated)
        val updated = state as KbOtaState.Updated
        assertEquals(2L, updated.kbVersion)
        assertEquals(2L, installedVersion)
        assertTrue("Sync timestamp must be recorded", installedTimestamp > 0L)

        // Active DB must now match v2 DB bytes
        assertTrue("Active knowledge_base.db must exist", activeDbFile.isFile)
        assertArrayEquals("Active knowledge_base.db must contain new v2 DB", expectedNewBytes, activeDbFile.readBytes())

        // Cached metadata in KbDatabaseManager must reflect kb_version = "2"
        val liveMeta = dbManager.getMetadataSync()
        assertNotNull(liveMeta)
        assertEquals("2", liveMeta?.kbVersion)

        // All staging and backup files must be cleaned up
        assertFalse("staging.part must be deleted after swap", File(kbDir, KbOtaManager.STAGING_PART_FILENAME).exists())
        assertFalse("staging.db must be deleted after swap", File(kbDir, KbOtaManager.STAGING_DB_FILENAME).exists())
        assertFalse("knowledge_base.bak must be deleted after swap", File(kbDir, KbOtaManager.BACKUP_DB_FILENAME).exists())
    }

    @Test
    fun `atomic swap rolls back knowledge_base_bak when reopening new DB fails`() {
        val kbDir = tempFolder.newFolder("kb_rollback")
        val activeDbFile = File(kbDir, KbDatabaseManager.ACTIVE_DB_FILENAME)
        val bundledDb = locateBundledAssetDb()
        bundledDb.copyTo(activeDbFile, overwrite = true)
        val originalActiveBytes = activeDbFile.readBytes()

        val candidateDb = File(tempFolder.root, "valid_v3.db")
        createModifiedSqliteDb(bundledDb, candidateDb, "3")
        val candidateGz = File(tempFolder.root, "valid_v3.db.gz")
        gzipFile(candidateDb, candidateGz)

        val dbManager = KbDatabaseManager(targetDbFile = activeDbFile)
        var installedVersion = 1L

        val state = KbOtaManager.executeUpdatePipeline(
            kbDir = kbDir,
            dbManager = dbManager,
            installedKbVersion = installedVersion,
            fetchManifest = {
                KbManifest(
                    kbVersion = 3L,
                    schemaVersion = KbOtaManager.SUPPORTED_KB_SCHEMA_VERSION,
                    downloadUrl = "https://alwelayah.xus.me/ota/kb/stable/kb-v3.db.gz",
                    sha256 = KbOtaManager.computeSha256Hex(candidateGz),
                    uncompressedSha256 = KbOtaManager.computeSha256Hex(candidateDb),
                    sizeBytes = candidateGz.length(),
                    releaseNotes = "Simulated reopen error",
                )
            },
            downloadPayload = { _, partFile, _ ->
                candidateGz.copyTo(partFile, overwrite = true)
                true
            },
            onVersionInstalled = { newVer, _ ->
                installedVersion = newVer
            },
            simulateReopenFailureForTest = true,
        )

        assertTrue("Expected Error when reopen fails, got $state", state is KbOtaState.Error)
        assertEquals("Installed version must remain 1 after rollback", 1L, installedVersion)
        assertTrue("Active knowledge_base.db must be restored from backup", activeDbFile.isFile)
        assertArrayEquals("Restored knowledge_base.db must match original v1 bytes", originalActiveBytes, activeDbFile.readBytes())
        assertFalse("knowledge_base.bak must not remain after rollback restore", File(kbDir, KbOtaManager.BACKUP_DB_FILENAME).exists())
    }

    @Test
    fun `resolveKbManifestUrlFromBase and parseKbManifest format endpoints and fields accurately`() {
        assertEquals(
            "https://alwelayah.xus.me/ota/kb/stable/latest.json",
            KbOtaManager.resolveKbManifestUrlFromBase("https://alwelayah.xus.me/ota", "stable")
        )
        assertEquals(
            "https://alwelayah.xus.me/ota/kb/beta/latest.json",
            KbOtaManager.resolveKbManifestUrlFromBase("https://alwelayah.xus.me/ota/", "beta")
        )

        val rawJson = """
            {
              "kbVersion": 12,
              "schemaVersion": 1,
              "downloadUrl": "https://alwelayah.xus.me/ota/kb/stable/knowledge_base_v12.db.gz",
              "sha256": "${"a1".repeat(32)}",
              "uncompressedSha256": "${"b2".repeat(32)}",
              "sizeBytes": 4194304,
              "releaseNotes": "Expanded Ramadan & Qibla Arabic dialects"
            }
        """.trimIndent()

        val parsed = KbOtaManager.parseKbManifest(rawJson)
        assertEquals(12L, parsed.kbVersion)
        assertEquals(1, parsed.schemaVersion)
        assertEquals("https://alwelayah.xus.me/ota/kb/stable/knowledge_base_v12.db.gz", parsed.downloadUrl)
        assertEquals("a1".repeat(32), parsed.sha256)
        assertEquals("b2".repeat(32), parsed.uncompressedSha256)
        assertEquals(4194304L, parsed.sizeBytes)
        assertEquals("Expanded Ramadan & Qibla Arabic dialects", parsed.releaseNotes)
    }

    @Test
    fun `non-2xx HTTP responses and manifest fetch exceptions always exit Checking into Error state`() {
        val kbDir = tempFolder.newFolder("kb_http_error_exit")
        val activeDbFile = File(kbDir, KbDatabaseManager.ACTIVE_DB_FILENAME)
        val bundledDb = locateBundledAssetDb()
        bundledDb.copyTo(activeDbFile, overwrite = true)

        val dbManager = KbDatabaseManager(targetDbFile = activeDbFile)
        val observedStates = mutableListOf<KbOtaState>()

        val state404 = KbOtaManager.executeUpdatePipeline(
            kbDir = kbDir,
            dbManager = dbManager,
            installedKbVersion = 1L,
            channel = "stable",
            fetchManifest = {
                throw java.io.IOException("HTTP 404")
            },
            downloadPayload = { _, _, _ -> false },
            onStateChange = { newState ->
                observedStates.add(newState)
            },
        )

        assertTrue("First state must be Checking", observedStates.firstOrNull() is KbOtaState.Checking)
        assertTrue("Final returned state must be Error, got $state404", state404 is KbOtaState.Error)
        assertEquals("HTTP 404 — No KB published for stable", (state404 as KbOtaState.Error).message)
        assertEquals("Last emitted state must not remain Checking", state404, observedStates.lastOrNull())
    }

    @Test
    fun `validateStagingDatabaseFile runs full FTS5 PRAGMA integrity_check in read-write mode while validateDatabaseFile succeeds in read-only mode`() {
        val bundledDb = locateBundledAssetDb()
        val stagingCopy = File(tempFolder.root, "fts5_staging_check.db")
        bundledDb.copyTo(stagingCopy, overwrite = true)

        // 1. Pre-swap staging validation (OPEN_READWRITE + PRAGMA integrity_check on FTS5 table intent_patterns_fts)
        val stagingMeta = KbDatabaseManager.validateStagingDatabaseFile(stagingCopy)
        assertNotNull("Staging DB with FTS5 table must pass OPEN_READWRITE PRAGMA integrity_check", stagingMeta)
        assertTrue("Expected positive patternCount in FTS5 DB", (stagingMeta?.patternCount ?: 0) > 0)

        // 2. Installed DB validation (OPEN_READONLY + SELECT count(*) FROM kb_meta, intents, intent_patterns, intent_patterns_fts)
        val installedCopy = File(tempFolder.root, "fts5_installed_ro.db")
        bundledDb.copyTo(installedCopy, overwrite = true)
        installedCopy.setReadOnly()
        val roMeta = KbDatabaseManager.validateDatabaseFile(installedCopy, readWriteFullCheck = false)
        assertNotNull(
            "Read-only installed DB with FTS5 table must pass OPEN_READONLY SELECT count(*) checks without write error",
            roMeta
        )
        assertEquals(stagingMeta?.kbVersion, roMeta?.kbVersion)
    }
}


