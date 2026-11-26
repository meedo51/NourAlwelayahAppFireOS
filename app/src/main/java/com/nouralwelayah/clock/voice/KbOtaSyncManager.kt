package com.nouralwelayah.clock.voice

import android.content.Context
import android.util.Log
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.system.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * State emitted by [KbOtaSyncManager] during manual or automated OTA Knowledge Base (`nour_kb.db`) sync.
 */
sealed class KbSyncState {
    data object Idle : KbSyncState()
    data object Checking : KbSyncState()
    data class Downloading(val progress: Float) : KbSyncState()
    data object UpToDate : KbSyncState()
    data class Success(val newVersion: String) : KbSyncState()
    data class Error(val msg: String) : KbSyncState()
}

/**
 * Parsed OTA manifest describing a remote or locally staged `nour_kb.db` release.
 */
data class KbOtaManifest(
    val kbVersion: String,
    val downloadUrl: String,
    val sha256: String,
)

/**
 * OTA Knowledge Base Hot-Swap Client Manager for the Nour Alwelayah Appliance.
 *
 * Implements the deterministic, corruption-proof KB update workflow:
 * 1. Fetches the JSON manifest (`kb_version`, `download_url`, `sha256`) from the configured
 *    OTA server (`AppSettings.otaServerUrl`) or local staging folder (`/NourAlwelayah/config/`).
 * 2. Compares `kb_version` against [KbDatabaseManager.getInstalledVersion] using semantic version comparison.
 * 3. Streams the database payload to a temporary file while emitting [KbSyncState.Downloading] progress (`0.0f..1.0f`).
 * 4. Verifies the downloaded file's SHA-256 cryptographic digest against the manifest's `sha256` field.
 * 5. Atomically hot-swaps the verified file via [KbDatabaseManager.hotSwapDatabase], refreshing live metadata.
 */
class KbOtaSyncManager(
    context: Context,
    private val dbManager: KbDatabaseManager,
) {
    private val appContext: Context = context.applicationContext
    private val syncMutex = Mutex()

    private val _syncState = MutableStateFlow<KbSyncState>(KbSyncState.Idle)
    val syncState: StateFlow<KbSyncState> = _syncState.asStateFlow()

    /**
     * Resets [syncState] back to [KbSyncState.Idle].
     */
    fun resetState() {
        _syncState.value = KbSyncState.Idle
    }

    /**
     * Runs a full manifest check -> version comparison -> streaming download -> SHA-256 verification ->
     * [KbDatabaseManager.hotSwapDatabase] cycle on [Dispatchers.IO].
     *
     * @param manifestUrlOverride Optional explicit manifest URL; if `null`, resolves from `Store.settings.value.otaServerUrl`.
     */
    suspend fun syncNow(manifestUrlOverride: String? = null): KbSyncState = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncState.value = KbSyncState.Checking
            try {
                // 1. Check if a locally staged OTA database exists in /NourAlwelayah/config/nour_kb.db
                val localStagedResult = tryHotSwapLocalStagedKb()
                if (localStagedResult != null) {
                    _syncState.value = localStagedResult
                    return@withLock localStagedResult
                }

                // 2. When no explicit manifestUrlOverride is passed, delegate to the dedicated channel-aware KbOtaManager
                if (manifestUrlOverride.isNullOrBlank()) {
                    val kbOtaState = com.nouralwelayah.clock.ota.KbOtaManager.checkAndUpdateNow(appContext)
                    val mapped = when (kbOtaState) {
                        is com.nouralwelayah.clock.ota.KbOtaState.Updated ->
                            KbSyncState.Success(kbOtaState.kbVersion.toString())
                        is com.nouralwelayah.clock.ota.KbOtaState.UpToDate ->
                            KbSyncState.UpToDate
                        is com.nouralwelayah.clock.ota.KbOtaState.Error ->
                            KbSyncState.Error(kbOtaState.message)
                        else -> KbSyncState.UpToDate
                    }
                    _syncState.value = mapped
                    return@withLock mapped
                }

                // 3. Explicit manifestUrlOverride fallback
                Store.init(appContext)
                val configuredBase = manifestUrlOverride.trim()

                val installedVersion = dbManager.getInstalledVersion()

                if (configuredBase.isEmpty()) {
                    val state = if (installedVersion != null) {
                        KbSyncState.UpToDate
                    } else {
                        KbSyncState.Error("Missing knowledge_base.db and no OTA server URL configured")
                    }
                    _syncState.value = state
                    return@withLock state
                }

                val manifestUrl = resolveManifestUrl(configuredBase)
                val manifestJson = fetchText(manifestUrl)
                if (manifestJson.isNullOrBlank()) {
                    val state = KbSyncState.Error("Unable to reach OTA manifest at $manifestUrl")
                    _syncState.value = state
                    return@withLock state
                }

                val manifest = parseManifest(manifestJson, configuredBase)
                if (manifest.kbVersion.isBlank()) {
                    val state = KbSyncState.Error("Invalid OTA manifest: missing kb_version")
                    _syncState.value = state
                    return@withLock state
                }
                if (manifest.downloadUrl.isBlank()) {
                    val state = KbSyncState.Error("Invalid OTA manifest: missing download_url")
                    _syncState.value = state
                    return@withLock state
                }
                if (manifest.sha256.isBlank() || !SHA256_HEX_REGEX.matches(manifest.sha256)) {
                    val state = KbSyncState.Error("Invalid OTA manifest: missing or malformed sha256 checksum")
                    _syncState.value = state
                    return@withLock state
                }

                // 3. Compare remote kb_version against installed kb_version
                if (installedVersion != null &&
                    KbDatabaseManager.compareSemanticVersions(manifest.kbVersion, installedVersion) <= 0
                ) {
                    Log.i(TAG, "KB already up to date (installed=$installedVersion, remote=${manifest.kbVersion})")
                    _syncState.value = KbSyncState.UpToDate
                    return@withLock KbSyncState.UpToDate
                }

                // 4. Download database to a temporary file with live progress reporting
                val tempFile = File(appContext.cacheDir, "ota_nour_kb_${System.currentTimeMillis()}.db.tmp")
                try {
                    _syncState.value = KbSyncState.Downloading(0f)
                    val downloaded = downloadFileWithProgress(manifest.downloadUrl, tempFile) { progress ->
                        _syncState.value = KbSyncState.Downloading(progress.coerceIn(0f, 1f))
                    }
                    if (!downloaded || !tempFile.isFile || tempFile.length() == 0L) {
                        val state = KbSyncState.Error("Failed to download KB from ${manifest.downloadUrl}")
                        _syncState.value = state
                        return@withLock state
                    }

                    // 5. Verify SHA-256 checksum before touching the live database
                    val actualSha256 = computeSha256Hex(tempFile)
                    if (!actualSha256.equals(manifest.sha256, ignoreCase = true)) {
                        Log.w(
                            TAG,
                            "KB OTA SHA-256 mismatch! expected=${manifest.sha256}, actual=$actualSha256"
                        )
                        val state = KbSyncState.Error("SHA-256 checksum verification failed")
                        _syncState.value = state
                        return@withLock state
                    }

                    // 6. Atomically hot-swap into KbDatabaseManager
                    val swapped = dbManager.hotSwapDatabase(tempFile)
                    if (!swapped) {
                        val state = KbSyncState.Error("Database schema/integrity validation rejected hot-swap")
                        _syncState.value = state
                        return@withLock state
                    }

                    val updatedVersion = dbManager.getInstalledVersion() ?: manifest.kbVersion
                    VoiceAssistantService.refreshMetadata(appContext)
                    val successState = KbSyncState.Success(updatedVersion)
                    _syncState.value = successState
                    return@withLock successState
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "OTA KB sync failed", e)
                val errState = KbSyncState.Error(e.message ?: "Unexpected OTA sync failure")
                _syncState.value = errState
                return@withLock errState
            }
        }
    }

    /**
     * Checks `/NourAlwelayah/config/nour_kb.db` (and optional `/NourAlwelayah/config/manifest.json` or
     * `nour_kb.sha256`) for a locally staged KB package pushed via USB/ADB or BLE.
     */
    private suspend fun tryHotSwapLocalStagedKb(): KbSyncState? {
        val stagedDb = FileSystemManager.validatePath(appContext, "config/nour_kb.db")
            ?.takeIf { it.isFile && it.length() > 0L }
            ?: return null

        val stagedManifest = FileSystemManager.validatePath(appContext, "config/manifest.json")
            ?.takeIf { it.isFile && it.length() > 0L }
        val stagedShaFile = FileSystemManager.validatePath(appContext, "config/nour_kb.sha256")
            ?.takeIf { it.isFile && it.length() > 0L }

        val expectedSha = when {
            stagedManifest != null -> runCatching {
                JSONObject(stagedManifest.readText()).optString("sha256", "").trim()
            }.getOrDefault("")
            stagedShaFile != null -> runCatching {
                stagedShaFile.readText().trim().substringBefore(' ').trim()
            }.getOrDefault("")
            else -> ""
        }

        if (expectedSha.isNotEmpty()) {
            val actualSha = computeSha256Hex(stagedDb)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return KbSyncState.Error("Staged config/nour_kb.db failed SHA-256 verification")
            }
        }

        val swapped = dbManager.hotSwapDatabase(stagedDb)
        return if (swapped) {
            runCatching { stagedDb.delete() }
            runCatching { stagedManifest?.delete() }
            runCatching { stagedShaFile?.delete() }
            val newVer = dbManager.getInstalledVersion() ?: "updated"
            VoiceAssistantService.refreshMetadata(appContext)
            KbSyncState.Success(newVer)
        } else {
            KbSyncState.Error("Staged config/nour_kb.db failed SQLite FTS5 validation")
        }
    }

    companion object {
        private const val TAG = "KbOtaSyncManager"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 30_000
        private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

        fun resolveManifestUrl(baseOrManifestUrl: String): String {
            val trimmed = baseOrManifestUrl.trim().trimEnd('/')
            return if (trimmed.endsWith(".json", ignoreCase = true)) {
                trimmed
            } else {
                "$trimmed/kb/manifest.json"
            }
        }

        fun parseManifest(jsonString: String, baseUrl: String): KbOtaManifest {
            val json = JSONObject(jsonString)
            val version = json.optString("kb_version", "").trim()
            val rawUrl = json.optString("download_url", "").trim()
                .ifEmpty { json.optString("db_url", "").trim() }
            val resolvedUrl = when {
                rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl
                rawUrl.isNotEmpty() && baseUrl.isNotBlank() -> {
                    val baseRoot = baseUrl.trim().trimEnd('/').removeSuffix("/kb/manifest.json")
                    "$baseRoot/${rawUrl.trimStart('/')}"
                }
                else -> rawUrl
            }
            val sha256 = json.optString("sha256", "").trim().lowercase(Locale.ROOT)
            return KbOtaManifest(
                kbVersion = version,
                downloadUrl = resolvedUrl,
                sha256 = sha256,
            )
        }

        /**
         * Computes the lowercase 64-character hexadecimal SHA-256 digest of [file].
         */
        fun computeSha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { input ->
                var read = input.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            val hashBytes = digest.digest()
            val sb = StringBuilder(hashBytes.size * 2)
            for (b in hashBytes) {
                sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }

        private fun fetchText(urlString: String): String? {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "NourAlwelayah-Appliance/${BuildConfig.VERSION_NAME}")
                }
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Throwable) {
                Log.w(TAG, "fetchText failed for $urlString: ${e.message}")
                null
            } finally {
                conn?.disconnect()
            }
        }

        private fun downloadFileWithProgress(
            urlString: String,
            destination: File,
            onProgress: (Float) -> Unit,
        ): Boolean {
            var conn: HttpURLConnection? = null
            return try {
                destination.parentFile?.mkdirs()
                conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "NourAlwelayah-Appliance/${BuildConfig.VERSION_NAME}")
                }
                if (conn.responseCode !in 200..299) return false
                val totalBytes = conn.contentLengthLong
                var downloadedBytes = 0L
                val buffer = ByteArray(8192)

                conn.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        var read = input.read(buffer)
                        while (read >= 0) {
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                if (totalBytes > 0L) {
                                    onProgress((downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                                } else {
                                    // Indeterminate stream: pulse smoothly toward 0.9f
                                    val approx = (downloadedBytes.toFloat() / (512 * 1024f)).coerceIn(0.05f, 0.90f)
                                    onProgress(approx)
                                }
                            }
                            read = input.read(buffer)
                        }
                        output.fd.sync()
                    }
                }
                onProgress(1f)
                true
            } catch (e: Throwable) {
                Log.w(TAG, "downloadFileWithProgress failed for $urlString: ${e.message}")
                false
            } finally {
                conn?.disconnect()
            }
        }
    }
}
