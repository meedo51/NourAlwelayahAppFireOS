package com.nouralwelayah.clock.ota

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.voice.KbDatabaseManager
import com.nouralwelayah.clock.voice.VoiceAssistantService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

/**
 * OTA Manifest model for Knowledge Base (`knowledge_base.db` / `.db.gz`) updates
 * fetched from `https://alwelayah.xus.me/ota/kb/<channel>/latest.json`.
 */
data class KbManifest(
    val kbVersion: Long,
    val schemaVersion: Int,
    val downloadUrl: String,
    val sha256: String,
    val uncompressedSha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)

/**
 * Live state exposed by [KbOtaManager] to `AdminDashboard.kt` and `AboutNourAlwelayahDialog.kt`.
 */
sealed class KbOtaState {
    data object Idle : KbOtaState()
    data object Checking : KbOtaState()
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Int,
        val manifest: KbManifest? = null,
    ) : KbOtaState()

    data class Verifying(val manifest: KbManifest? = null) : KbOtaState()
    data class Updated(
        val kbVersion: Long,
        val releaseNotes: String = "",
        val syncedAtEpochMs: Long = System.currentTimeMillis(),
    ) : KbOtaState()

    data class UpToDate(
        val kbVersion: Long,
        val syncedAtEpochMs: Long = 0L,
        val note: String = "",
    ) : KbOtaState()

    data class Error(val message: String) : KbOtaState()
}

/**
 * Dedicated, Zero-Restart Knowledge Base (KB) Over-The-Air Updater for `com.nouralwelayah.clock`.
 *
 * Updates the offline bilingual Voice Assistant's SQLite FTS5 + embeddings database
 * (`filesDir/kb/knowledge_base.db`) independently of the main APK:
 * 1. Fetches `https://alwelayah.xus.me/ota/kb/<channel>/latest.json` (`Cache-Control: no-cache`,
 *    `connectTimeout = 10_000`, `readTimeout = 10_000`).
 * 2. Explicitly maps non-2xx HTTP responses (e.g., `404`, `403`, `500`) to `KbOtaState.Error("HTTP <code>")`
 *    and guarantees state exit from `Checking` on every failure or cancellation path.
 * 3. Skips if `manifest.schemaVersion != SUPPORTED_KB_SCHEMA_VERSION` (logging a warning) or
 *    `manifest.kbVersion <= installedKbVersion`.
 * 4. Downloads `.db.gz` (or `.db`) to `filesDir/kb/staging.part` using HTTP/1.1 with automatic
 *    retry + HTTP `Range: bytes=` resume.
 * 5. Verifies `sha256` of `staging.part`.
 * 6. If `.gz`, decompresses to `filesDir/kb/staging.db` and verifies `uncompressedSha256`.
 * 7. Runs pre-swap SQLite sanity check on `staging.db`: opens read-only, runs `PRAGMA integrity_check;`
 *    (must return `"ok"`), and verifies required FTS5 + embeddings tables exist.
 * 8. Acquires [KbDatabaseManager]'s write lock, closes active DB, renames `knowledge_base.db` ->
 *    `knowledge_base.bak`, renames `staging.db` -> `knowledge_base.db`, and reopens (with automatic
 *    rollback to `knowledge_base.bak` on failure).
 * 9. Persists new `kbVersion` and sync timestamp in `SharedPreferences` and deletes `staging.part`
 *    and `knowledge_base.bak`.
 */
object KbOtaManager {
    private const val TAG = "KbOtaManager"
    const val SUPPORTED_KB_SCHEMA_VERSION: Int = 1
    const val DEFAULT_BUNDLED_KB_VERSION: Long = 1L

    const val STAGING_PART_FILENAME = "staging.part"
    const val STAGING_PART_VERSION_FILENAME = "staging.part.version"
    const val STAGING_DB_FILENAME = "staging.db"
    const val ACTIVE_DB_FILENAME = "knowledge_base.db"
    const val BACKUP_DB_FILENAME = "knowledge_base.bak"

    private const val PREFS_NAME = "nour_kb_ota_prefs"
    private const val KEY_KB_VERSION = "installed_kb_version"
    private const val KEY_LAST_SYNC_EPOCH_MS = "last_kb_sync_epoch_ms"

    private const val STARTUP_DELAY_MS = 10_000L // 10 seconds after app startup
    private const val PERIODIC_INTERVAL_MS = 6L * 60L * 60L * 1_000L // Every 6 hours
    const val MANIFEST_CONNECT_TIMEOUT_MS = 10_000
    const val MANIFEST_READ_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    private const val BUFFER_SIZE = 65_536 // 64 KB
    private const val MAX_DOWNLOAD_RETRIES = 5
    private val RETRY_BACKOFF_MS = longArrayOf(1_500L, 3_000L, 6_000L, 10_000L, 15_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isCheckInProgress = AtomicBoolean(false)
    private var periodicJob: Job? = null

    private val _state = MutableStateFlow<KbOtaState>(KbOtaState.Idle)
    val state: StateFlow<KbOtaState> = _state.asStateFlow()

    private val _installedKbVersionFlow = MutableStateFlow(DEFAULT_BUNDLED_KB_VERSION)
    val installedKbVersionFlow: StateFlow<Long> = _installedKbVersionFlow.asStateFlow()

    private val _lastSyncEpochMsFlow = MutableStateFlow(0L)
    val lastSyncEpochMsFlow: StateFlow<Long> = _lastSyncEpochMsFlow.asStateFlow()

    private fun safeLogD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

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

    private fun transitionState(newState: KbOtaState) {
        val prevState = _state.value
        _state.value = newState
        if (newState is KbOtaState.Error) {
            safeLogE("State transition: ${prevState.javaClass.simpleName} -> Error(${newState.message})")
        } else {
            safeLogD("State transition: ${prevState.javaClass.simpleName} -> $newState")
        }
    }

    /**
     * Returns a Direct-Boot-safe [Context] for `SharedPreferences`: if the user has not yet
     * unlocked the device on API 24+, uses `context.createDeviceProtectedStorageContext()`.
     */
    private fun resolvePrefsContext(context: Context): Context {
        val appCtx = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = appCtx.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um != null && !um.isUserUnlocked) {
                appCtx.createDeviceProtectedStorageContext()
            } else {
                appCtx
            }
        } else {
            appCtx
        }
    }

    /**
     * Resolves the currently installed numeric `kbVersion` (`Long`) from `SharedPreferences` and/or
     * the active `filesDir/kb/knowledge_base.db` `kb_meta` table.
     */
    fun getInstalledKbVersion(context: Context): Long {
        val appCtx = context.applicationContext
        val prefs = resolvePrefsContext(appCtx).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getLong(KEY_KB_VERSION, 0L)
        val storedSync = prefs.getLong(KEY_LAST_SYNC_EPOCH_MS, 0L)
        if (storedSync > 0L) {
            _lastSyncEpochMsFlow.value = storedSync
        }

        val dbMetaVersion = runCatching {
            val rawVer = VoiceAssistantService.getDatabaseManager(appCtx).getMetadataSync()?.kbVersion
            KbDatabaseManager.parseVersionStringAsLong(rawVer)
        }.getOrDefault(DEFAULT_BUNDLED_KB_VERSION)

        val resolved = maxOf(storedVersion, dbMetaVersion, DEFAULT_BUNDLED_KB_VERSION)
        if (resolved != storedVersion) {
            prefs.edit().putLong(KEY_KB_VERSION, resolved).apply()
        }
        _installedKbVersionFlow.value = resolved
        return resolved
    }

    /**
     * Returns the last successful KB OTA sync timestamp (epoch ms), or `0L` if never synced.
     */
    fun getLastSyncEpochMs(context: Context): Long {
        val appCtx = context.applicationContext
        val prefs = resolvePrefsContext(appCtx).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_SYNC_EPOCH_MS, 0L)
        _lastSyncEpochMsFlow.value = ts
        return ts
    }

    /**
     * Formats [epochMs] as `yyyy-MM-dd HH:mm` for UI display, or returns a localized fallback when `0L`.
     */
    fun formatSyncTime(epochMs: Long, isArabic: Boolean): String {
        if (epochMs <= 0L) {
            return if (isArabic) "مضمّنة / لم تتم المزامنة بعد" else "Bundled / Never synced"
        }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
        }.getOrDefault(if (isArabic) "تمت المزامنة" else "Synced")
    }

    /**
     * Persists the installed [kbVersion] and [syncedAtEpochMs] into `SharedPreferences`.
     */
    fun saveInstalledKbVersion(
        context: Context,
        kbVersion: Long,
        syncedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        val appCtx = context.applicationContext
        val prefs = resolvePrefsContext(appCtx).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_KB_VERSION, kbVersion)
            .putLong(KEY_LAST_SYNC_EPOCH_MS, syncedAtEpochMs)
            .apply()
        _installedKbVersionFlow.value = kbVersion
        _lastSyncEpochMsFlow.value = syncedAtEpochMs
    }

    /**
     * Resolves the channel-specific KB manifest URL:
     * `https://alwelayah.xus.me/ota/kb/<channel>/latest.json`
     */
    fun resolveKbManifestUrl(context: Context, channelOverride: String? = null): String {
        val appCtx = context.applicationContext
        Store.init(appCtx)
        val settings = Store.settings.value
        val channel = AppSettings.normalizeOtaChannel(channelOverride ?: settings.otaChannel)
        val rawBase = settings.otaServerUrl.trim().trimEnd('/').let { url ->
            if (url.isBlank()) BuildConfig.OTA_BASE_URL.trim().trimEnd('/') else url
        }
        return resolveKbManifestUrlFromBase(rawBase, channel)
    }

    fun resolveKbManifestUrlFromBase(rawBaseUrl: String, channel: String): String {
        val normalizedChannel = AppSettings.normalizeOtaChannel(channel)
        val base = rawBaseUrl.trim().trimEnd('/')
            .removeSuffix("/$normalizedChannel/latest.json")
            .removeSuffix("/stable/latest.json")
            .removeSuffix("/beta/latest.json")
            .removeSuffix("/latest.json")
            .trimEnd('/')
        return when {
            base.endsWith("/kb/$normalizedChannel", ignoreCase = true) -> "$base/latest.json"
            base.endsWith("/kb", ignoreCase = true) -> "$base/$normalizedChannel/latest.json"
            else -> "$base/kb/$normalizedChannel/latest.json"
        }
    }

    /**
     * Starts the silent, non-blocking background KB OTA updater (called once in `PrayerClockApp.onCreate()`):
     * - Runs an initial check after a short startup delay ([STARTUP_DELAY_MS])
     * - Repeats every 6 hours ([PERIODIC_INTERVAL_MS]) whenever Wi-Fi / network is connected.
     */
    @Synchronized
    fun startPeriodicChecks(context: Context) {
        val appCtx = context.applicationContext
        getInstalledKbVersion(appCtx)
        getLastSyncEpochMs(appCtx)

        if (periodicJob?.isActive == true) {
            safeLogD("startPeriodicChecks ignored: periodic KB OTA scheduler is already active")
            return
        }
        periodicJob = scope.launch {
            delay(STARTUP_DELAY_MS)
            while (isActive) {
                runCatching {
                    if (isWifiOrNetworkConnected(appCtx)) {
                        checkAndUpdateNow(appCtx, silentBackground = true)
                    } else {
                        safeLogD("Skipping background KB OTA check: Wi-Fi not connected")
                    }
                }.onFailure { e ->
                    safeLogE("Background KB OTA check threw unexpected exception: ${e.message}", e)
                }
                delay(PERIODIC_INTERVAL_MS)
            }
        }
        safeLogD("Started 6-hour background KB OTA update scheduler")
    }

    /**
     * Launches a manual KB update check on [KbOtaManager]'s dedicated [Dispatchers.IO] scope so
     * UI recomposition in `AdminDashboard.kt` cannot cancel an in-flight check.
     * If a check is already running, returns immediately without overwriting or freezing the UI state.
     */
    fun triggerManualCheck(context: Context, channelOverride: String? = null) {
        if (isCheckInProgress.get()) {
            safeLogD("triggerManualCheck ignored: KB OTA check is already in progress (currentState=${_state.value})")
            return
        }
        val appCtx = context.applicationContext
        scope.launch {
            checkForUpdates(appCtx, channelOverride)
        }
    }

    /**
     * Alias for [checkAndUpdateNow] for callers invoking `checkForUpdates(...)`.
     */
    suspend fun checkForUpdates(
        context: Context,
        channelOverride: String? = null,
    ): KbOtaState = checkAndUpdateNow(
        context = context,
        channelOverride = channelOverride,
        silentBackground = false,
    )

    /**
     * Checks `https://alwelayah.xus.me/ota/kb/<channel>/latest.json` on [Dispatchers.IO] and performs
     * the full download -> verify -> decompress -> SQLite sanity check -> atomic hot-swap workflow.
     *
     * Guarantees:
     * - Guarded by [isCheckInProgress] (`AtomicBoolean`) which is ALWAYS released in `finally`.
     * - If a check is already running, returns the current [_state] without overwriting or freezing UI state.
     * - Every failure path (including non-2xx HTTP status codes like `404`/`403`/`500`, timeouts,
     *   JSON parse failures, or exceptions) transitions [_state] to [KbOtaState.Error] so [_state]
     *   can never remain stuck in [KbOtaState.Checking].
     */
    suspend fun checkAndUpdateNow(
        context: Context,
        channelOverride: String? = null,
        silentBackground: Boolean = false,
    ): KbOtaState = withContext(Dispatchers.IO) {
        if (!isCheckInProgress.compareAndSet(false, true)) {
            val current = _state.value
            safeLogD("checkAndUpdateNow skipped: check already in progress (state=$current)")
            return@withContext current
        }

        try {
            val appCtx = context.applicationContext
            transitionState(KbOtaState.Checking)

            val kbDir = File(appCtx.filesDir, "kb").apply { mkdirs() }
            val dbManager = VoiceAssistantService.getDatabaseManager(appCtx)
            runCatching { dbManager.ensureInitialized() }.onFailure { e ->
                safeLogW("ensureInitialized warning prior to KB OTA check: ${e.message}", e)
            }

            val installedVersion = getInstalledKbVersion(appCtx)
            val lastSync = getLastSyncEpochMs(appCtx)
            Store.init(appCtx)
            val activeChannel = AppSettings.normalizeOtaChannel(channelOverride ?: Store.settings.value.otaChannel)
            val manifestUrl = resolveKbManifestUrl(appCtx, activeChannel)
            val baseRoot = manifestUrl.substringBefore("/kb/").ifBlank { BuildConfig.OTA_BASE_URL }

            safeLogD(
                "Starting KB OTA check: channel=$activeChannel, url=$manifestUrl, installedKbVersion=$installedVersion, silentBackground=$silentBackground"
            )

            val resultState = executeUpdatePipeline(
                kbDir = kbDir,
                dbManager = dbManager,
                installedKbVersion = installedVersion,
                lastSyncEpochMs = lastSync,
                channel = activeChannel,
                fetchManifest = {
                    val json = fetchManifestJson(manifestUrl, installedVersion, activeChannel)
                    parseKbManifest(json, baseRoot)
                },
                downloadPayload = { manifest, partFile, onProgress ->
                    downloadFileWithRangeResume(
                        urlString = manifest.downloadUrl,
                        partFile = partFile,
                        expectedTotalBytes = manifest.sizeBytes,
                        onProgress = onProgress,
                    )
                },
                onVersionInstalled = { newVersion, syncedAt ->
                    saveInstalledKbVersion(appCtx, newVersion, syncedAt)
                },
                onStateChange = { newState ->
                    transitionState(newState)
                },
            )

            if (resultState is KbOtaState.Updated) {
                runCatching { VoiceAssistantService.refreshMetadata(appCtx) }
            }
            if (_state.value != resultState) {
                transitionState(resultState)
            }
            resultState
        } catch (e: CancellationException) {
            safeLogE("KB OTA check cancelled", e)
            if (_state.value is KbOtaState.Checking ||
                _state.value is KbOtaState.Downloading ||
                _state.value is KbOtaState.Verifying
            ) {
                transitionState(KbOtaState.Error(e.message?.takeIf { it.isNotBlank() } ?: "Check failed"))
            }
            throw e
        } catch (t: Throwable) {
            val errMsg = formatKbOtaErrorMessage(
                rawMessage = t.message,
                channel = AppSettings.normalizeOtaChannel(channelOverride ?: Store.settings.value.otaChannel),
            )
            safeLogE("KB OTA check failed with exception: $errMsg", t)
            val errState = KbOtaState.Error(errMsg)
            transitionState(errState)
            errState
        } finally {
            isCheckInProgress.set(false)
            if (_state.value is KbOtaState.Checking) {
                transitionState(KbOtaState.Error("Check failed"))
            }
        }
    }

    internal fun formatKbOtaErrorMessage(rawMessage: String?, channel: String): String {
        val msg = rawMessage?.trim().orEmpty()
        if (msg.isEmpty()) return "Check failed"
        if (msg.equals("HTTP 404", ignoreCase = true)) {
            return "HTTP 404 — No KB published for $channel"
        }
        return msg
    }

    /**
     * Pure, deterministic 8-step KB OTA execution pipeline (directly testable in unit tests).
     */
    internal fun executeUpdatePipeline(
        kbDir: File,
        dbManager: KbDatabaseManager,
        installedKbVersion: Long,
        lastSyncEpochMs: Long = 0L,
        channel: String = AppSettings.OTA_CHANNEL_STABLE,
        fetchManifest: () -> KbManifest,
        downloadPayload: (KbManifest, File, (Long, Long) -> Unit) -> Boolean,
        onVersionInstalled: (Long, Long) -> Unit = { _, _ -> },
        onStateChange: (KbOtaState) -> Unit = {},
        simulateReopenFailureForTest: Boolean = false,
    ): KbOtaState {
        return try {
            if (!kbDir.exists()) {
                kbDir.mkdirs()
            }

            onStateChange(KbOtaState.Checking)

            // 1. Fetch latest.json manifest
            val manifest = try {
                fetchManifest()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = formatKbOtaErrorMessage(t.message, channel)
                val err = KbOtaState.Error(msg)
                safeLogE("KB manifest fetch failed: $msg", t)
                onStateChange(err)
                return err
            }

            safeLogD(
                "Parsed KB manifest: kbVersion=${manifest.kbVersion}, schemaVersion=${manifest.schemaVersion}, " +
                    "downloadUrl=${manifest.downloadUrl}, sizeBytes=${manifest.sizeBytes}"
            )

            // 2a. Check schemaVersion compatibility
            if (manifest.schemaVersion != SUPPORTED_KB_SCHEMA_VERSION) {
                safeLogW(
                    "Skipping KB OTA update: manifest.schemaVersion=${manifest.schemaVersion} != " +
                        "SUPPORTED_KB_SCHEMA_VERSION ($SUPPORTED_KB_SCHEMA_VERSION)"
                )
                val skipped = KbOtaState.UpToDate(
                    kbVersion = installedKbVersion,
                    syncedAtEpochMs = lastSyncEpochMs,
                    note = "Skipped unsupported schema v${manifest.schemaVersion} (supported: v$SUPPORTED_KB_SCHEMA_VERSION)",
                )
                onStateChange(skipped)
                return skipped
            }

            // 2b. Check kbVersion > installedKbVersion
            if (manifest.kbVersion <= installedKbVersion) {
                val syncedNow = System.currentTimeMillis()
                safeLogD(
                    "Knowledge Base is up to date (installed v$installedKbVersion >= remote v${manifest.kbVersion})"
                )
                val upToDate = KbOtaState.UpToDate(
                    kbVersion = installedKbVersion,
                    syncedAtEpochMs = if (lastSyncEpochMs > 0L) lastSyncEpochMs else syncedNow,
                )
                onStateChange(upToDate)
                return upToDate
            }

            if (manifest.downloadUrl.isBlank()) {
                val err = KbOtaState.Error("Invalid KB manifest: downloadUrl is empty")
                safeLogE(err.message)
                onStateChange(err)
                return err
            }
            if (!SHA256_HEX_REGEX.matches(manifest.sha256)) {
                val err = KbOtaState.Error("Invalid KB manifest: malformed sha256 checksum")
                safeLogE(err.message)
                onStateChange(err)
                return err
            }

            val stagingPartFile = File(kbDir, STAGING_PART_FILENAME)
            val stagingPartVerFile = File(kbDir, STAGING_PART_VERSION_FILENAME)
            val stagingDbFile = File(kbDir, STAGING_DB_FILENAME)
            val backupDbFile = File(kbDir, BACKUP_DB_FILENAME)

            // If an existing staging.part belongs to a different target kbVersion, discard it before resuming
            val trackedPartVersion = runCatching {
                if (stagingPartVerFile.isFile) stagingPartVerFile.readText(Charsets.UTF_8).trim().toLongOrNull() else null
            }.getOrNull()
            if (stagingPartFile.exists() && trackedPartVersion != null && trackedPartVersion != manifest.kbVersion) {
                stagingPartFile.delete()
            }
            runCatching { stagingPartVerFile.writeText(manifest.kbVersion.toString(), Charsets.UTF_8) }

            // 3. Download .db.gz (or .db) to filesDir/kb/staging.part with HTTP/1.1 + Range resume
            onStateChange(
                KbOtaState.Downloading(
                    downloadedBytes = if (stagingPartFile.isFile) stagingPartFile.length() else 0L,
                    totalBytes = manifest.sizeBytes,
                    progressPercent = 0,
                    manifest = manifest,
                )
            )

            val downloadedOk = try {
                downloadPayload(manifest, stagingPartFile) { downloadedBytes, totalBytes ->
                    val pct = if (totalBytes > 0L) {
                        ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    onStateChange(
                        KbOtaState.Downloading(
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            progressPercent = pct,
                            manifest = manifest,
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                safeLogE("KB payload download failed: ${e.message}", e)
                false
            }

            if (!downloadedOk || !stagingPartFile.isFile || stagingPartFile.length() <= 0L) {
                val err = KbOtaState.Error("Failed to download Knowledge Base payload from ${manifest.downloadUrl}")
                safeLogE(err.message)
                onStateChange(err)
                return err
            }

            onStateChange(KbOtaState.Verifying(manifest))

            // 4. Verify sha256 of the downloaded staging.part file
            val actualDownloadedSha = computeSha256Hex(stagingPartFile)
            if (!actualDownloadedSha.equals(manifest.sha256, ignoreCase = true)) {
                safeLogE(
                    "KB OTA downloaded file SHA-256 mismatch! expected=${manifest.sha256}, actual=$actualDownloadedSha; deleting staging.part"
                )
                stagingPartFile.delete()
                stagingPartVerFile.delete()
                stagingDbFile.delete()
                val err = KbOtaState.Error("KB download SHA-256 verification failed")
                onStateChange(err)
                return err
            }

            // 5. Decompress .gz (or copy raw .db) to filesDir/kb/staging.db and verify uncompressedSha256
            if (stagingDbFile.exists()) {
                stagingDbFile.delete()
            }
            val isGzip = isGzipFile(stagingPartFile) ||
                manifest.downloadUrl.substringBefore('?').endsWith(".gz", ignoreCase = true)

            val decompressedOk = try {
                if (isGzip) {
                    GZIPInputStream(FileInputStream(stagingPartFile).buffered(BUFFER_SIZE)).use { gzIn ->
                        FileOutputStream(stagingDbFile).use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = gzIn.read(buffer)
                                if (read < 0) break
                                if (read > 0) {
                                    out.write(buffer, 0, read)
                                }
                            }
                            out.flush()
                            out.fd.sync()
                        }
                    }
                } else {
                    FileInputStream(stagingPartFile).use { input ->
                        FileOutputStream(stagingDbFile).use { out ->
                            input.copyTo(out, BUFFER_SIZE)
                            out.flush()
                            out.fd.sync()
                        }
                    }
                }
                stagingDbFile.isFile && stagingDbFile.length() > 0L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                safeLogE("Failed to decompress/stage KB file to staging.db: ${e.message}", e)
                false
            }

            if (!decompressedOk) {
                stagingPartFile.delete()
                stagingPartVerFile.delete()
                stagingDbFile.delete()
                val err = KbOtaState.Error("Failed to decompress KB archive to staging.db")
                onStateChange(err)
                return err
            }

            val expectedUncompressedSha = manifest.uncompressedSha256.trim().ifEmpty {
                if (!isGzip) manifest.sha256.trim() else ""
            }
            if (expectedUncompressedSha.isNotEmpty()) {
                if (!SHA256_HEX_REGEX.matches(expectedUncompressedSha)) {
                    stagingPartFile.delete()
                    stagingPartVerFile.delete()
                    stagingDbFile.delete()
                    val err = KbOtaState.Error("Invalid uncompressedSha256 in KB manifest")
                    safeLogE(err.message)
                    onStateChange(err)
                    return err
                }
                val actualUncompressedSha = computeSha256Hex(stagingDbFile)
                if (!actualUncompressedSha.equals(expectedUncompressedSha, ignoreCase = true)) {
                    safeLogE(
                        "KB OTA uncompressedSha256 mismatch! expected=$expectedUncompressedSha, actual=$actualUncompressedSha"
                    )
                    stagingPartFile.delete()
                    stagingPartVerFile.delete()
                    stagingDbFile.delete()
                    val err = KbOtaState.Error("KB uncompressed SHA-256 verification failed")
                    onStateChange(err)
                    return err
                }
            }

            // 6. Pre-Swap SQLite Sanity Check: open staging.db with SQLiteDatabase.OPEN_READWRITE
            //    (required so PRAGMA integrity_check; can validate the FTS5 inverted index on intent_patterns_fts),
            //    verify required tables (kb_meta, intents, intent_patterns, intent_patterns_fts), and close cleanly before reloadDatabase().
            val validatedMeta = KbDatabaseManager.validateStagingDatabaseFile(stagingDbFile)
            if (validatedMeta == null) {
                safeLogE("Pre-swap SQLite sanity check (OPEN_READWRITE PRAGMA integrity_check / FTS5 tables) failed for ${stagingDbFile.path}")
                stagingPartFile.delete()
                stagingPartVerFile.delete()
                stagingDbFile.delete()
                val err = KbOtaState.Error("Pre-swap SQLite integrity_check or FTS5 schema verification failed")
                onStateChange(err)
                return err
            }

            // 7. Atomic Swap with Rollback under KbDatabaseManager's write lock
            val swapSuccess = dbManager.reloadDatabase(
                newDbFile = stagingDbFile,
                simulateReopenFailureForTest = simulateReopenFailureForTest,
            )
            if (!swapSuccess) {
                safeLogE("Atomic KB database swap failed; active database rolled back to previous version")
                stagingPartFile.delete()
                stagingPartVerFile.delete()
                stagingDbFile.delete()
                val err = KbOtaState.Error("Failed to hot-swap Knowledge Base; restored backup")
                onStateChange(err)
                return err
            }

            // 8. Save new kbVersion and timestamp, and clean up staging.part and knowledge_base.bak
            val syncedAt = System.currentTimeMillis()
            onVersionInstalled(manifest.kbVersion, syncedAt)
            runCatching { stagingPartFile.delete() }
            runCatching { stagingPartVerFile.delete() }
            runCatching { stagingDbFile.delete() }
            runCatching { backupDbFile.delete() }

            safeLogI("KB OTA update complete: active knowledge_base.db updated to v${manifest.kbVersion}")
            val updatedState = KbOtaState.Updated(
                kbVersion = manifest.kbVersion,
                releaseNotes = manifest.releaseNotes,
                syncedAtEpochMs = syncedAt,
            )
            onStateChange(updatedState)
            updatedState
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val err = KbOtaState.Error(formatKbOtaErrorMessage(t.message, channel))
            safeLogE("executeUpdatePipeline unexpected error: ${err.message}", t)
            onStateChange(err)
            err
        }
    }

    /**
     * Parses a JSON manifest from `https://alwelayah.xus.me/ota/kb/<channel>/latest.json` into [KbManifest].
     * Uses pure Kotlin parsing so it works identically in Android runtime and pure JVM unit tests.
     */
    fun parseKbManifest(
        jsonString: String,
        baseUrl: String = BuildConfig.OTA_BASE_URL,
    ): KbManifest {
        fun extractString(vararg keys: String): String {
            for (k in keys) {
                val regex = Regex(""""$k"\s*:\s*"((?:\\.|[^"\\])*)"""")
                val match = regex.find(jsonString)
                if (match != null) {
                    return match.groupValues[1]
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .trim()
                }
            }
            return ""
        }

        fun extractLong(defaultVal: Long, vararg keys: String): Long {
            for (k in keys) {
                val numRegex = Regex(""""$k"\s*:\s*(-?\d+)""")
                val numMatch = numRegex.find(jsonString)
                if (numMatch != null) {
                    numMatch.groupValues[1].toLongOrNull()?.let { return it }
                }
                val strVal = extractString(k)
                if (strVal.isNotEmpty()) {
                    strVal.toLongOrNull()?.let { return it }
                    return KbDatabaseManager.parseVersionStringAsLong(strVal)
                }
            }
            return defaultVal
        }

        val kbVersion = extractLong(0L, "kbVersion", "kb_version", "versionCode", "version")
        val schemaVersion = extractLong(SUPPORTED_KB_SCHEMA_VERSION.toLong(), "schemaVersion", "schema_version").toInt()
        val rawDownloadUrl = extractString("downloadUrl", "download_url", "dbUrl", "db_url", "url")
        val resolvedDownloadUrl = when {
            rawDownloadUrl.startsWith("http://", ignoreCase = true) ||
                rawDownloadUrl.startsWith("https://", ignoreCase = true) -> rawDownloadUrl
            rawDownloadUrl.isNotEmpty() && baseUrl.isNotBlank() -> {
                "${baseUrl.trim().trimEnd('/')}/${rawDownloadUrl.trimStart('/')}"
            }
            else -> rawDownloadUrl
        }
        val sha256 = extractString("sha256", "sha256Hex").lowercase(Locale.ROOT)
        val uncompressedSha256 = extractString("uncompressedSha256", "uncompressed_sha256").lowercase(Locale.ROOT)
        val sizeBytes = extractLong(-1L, "sizeBytes", "size_bytes", "size")
        val releaseNotes = extractString("releaseNotes", "release_notes", "notes")

        return KbManifest(
            kbVersion = kbVersion,
            schemaVersion = schemaVersion,
            downloadUrl = resolvedDownloadUrl,
            sha256 = sha256,
            uncompressedSha256 = uncompressedSha256,
            sizeBytes = sizeBytes,
            releaseNotes = releaseNotes,
        )
    }

    /**
     * Computes lowercase 64-character hexadecimal SHA-256 digest of [file].
     */
    fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    internal fun isGzipFile(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < 2L) return false
        FileInputStream(file).use { input ->
            val b0 = input.read()
            val b1 = input.read()
            b0 == 0x1F && b1 == 0x8B
        }
    }.getOrDefault(false)

    /**
     * Fetches the raw JSON manifest from [manifestUrl] with strict 10-second timeouts
     * (`connectTimeout = 10_000`, `readTimeout = 10_000`).
     *
     * Logs the exact URL and HTTP response code via `Log.d` / `Log.e`.
     * Throws an [IOException] with message `"HTTP 404 — No KB published for <channel>"` on HTTP 404
     * (or `"HTTP <code>"` on other non-2xx HTTP status codes) so the caller immediately transitions
     * to `KbOtaState.Error(...)`.
     */
    internal fun fetchManifestJson(
        manifestUrl: String,
        currentKbVersion: Long,
        channel: String = AppSettings.OTA_CHANNEL_STABLE,
    ): String {
        var conn: HttpURLConnection? = null
        try {
            safeLogD("Fetching KB OTA manifest from URL: $manifestUrl")
            conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = MANIFEST_CONNECT_TIMEOUT_MS
                readTimeout = MANIFEST_READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "keep-alive")
                setRequestProperty(
                    "User-Agent",
                    "NourAlwelayah-Clock/${BuildConfig.VERSION_NAME} (KB/$currentKbVersion)"
                )
            }
            val code = conn.responseCode
            safeLogD("KB OTA manifest HTTP response code: $code from $manifestUrl")
            if (code !in 200..299) {
                safeLogE("KB manifest HTTP $code from $manifestUrl")
                if (code == 404) {
                    throw IOException("HTTP 404 — No KB published for $channel")
                }
                throw IOException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.isBlank()) {
                safeLogE("KB OTA manifest response body is empty from $manifestUrl")
                throw IOException("Empty manifest response")
            }
            return body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            safeLogE("Error fetching KB manifest from $manifestUrl: ${e.message}", e)
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Downloads [urlString] into [partFile] using HTTP/1.1 with automatic retry and
     * HTTP `Range: bytes=<existingBytes>-` resume.
     */
    private fun downloadFileWithRangeResume(
        urlString: String,
        partFile: File,
        expectedTotalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        partFile.parentFile?.mkdirs()

        for (attempt in 0 until MAX_DOWNLOAD_RETRIES) {
            var conn: HttpURLConnection? = null
            try {
                val existingBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
                if (expectedTotalBytes > 0L && existingBytes == expectedTotalBytes) {
                    onProgress(existingBytes, expectedTotalBytes)
                    return true
                }
                if (expectedTotalBytes > 0L && existingBytes > expectedTotalBytes) {
                    partFile.delete()
                }

                val resumeOffset = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
                safeLogD(
                    "Downloading KB OTA payload (attempt ${attempt + 1}/$MAX_DOWNLOAD_RETRIES): " +
                        "url=$urlString, resumeOffset=$resumeOffset"
                )
                conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
                    readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                    useCaches = false
                    instanceFollowRedirects = true
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "NourAlwelayah-Clock/${BuildConfig.VERSION_NAME}")
                    if (resumeOffset > 0L) {
                        setRequestProperty("Range", "bytes=$resumeOffset-")
                    }
                }

                val responseCode = conn.responseCode
                safeLogD("KB OTA payload download HTTP response code: $responseCode from $urlString")
                if (responseCode == 416) {
                    // Range Not Satisfiable: delete staging.part and retry from 0
                    partFile.delete()
                    continue
                }
                if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    safeLogE("KB OTA payload download HTTP error: HTTP $responseCode from $urlString")
                    throw IOException("HTTP $responseCode")
                }

                val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0L
                val startOffset = if (isPartial) resumeOffset else 0L
                val contentLen = conn.contentLengthLong
                val totalBytes = when {
                    expectedTotalBytes > 0L -> expectedTotalBytes
                    contentLen > 0L -> startOffset + contentLen
                    else -> -1L
                }

                var downloadedBytes = startOffset
                onProgress(downloadedBytes, totalBytes)

                conn.inputStream.buffered(BUFFER_SIZE).use { input ->
                    FileOutputStream(partFile, isPartial).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                                onProgress(downloadedBytes, totalBytes)
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }

                if (totalBytes > 0L && downloadedBytes < totalBytes) {
                    throw EOFException("Incomplete KB stream: $downloadedBytes / $totalBytes bytes")
                }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val isRetryable = e is SSLException ||
                    e is SocketTimeoutException ||
                    e is EOFException ||
                    e is IOException
                val isLastAttempt = attempt >= MAX_DOWNLOAD_RETRIES - 1
                safeLogE(
                    "KB OTA download attempt ${attempt + 1}/$MAX_DOWNLOAD_RETRIES failed: ${e.message}",
                    e
                )
                if (!isRetryable || isLastAttempt) {
                    return false
                }
                val sleepMs = RETRY_BACKOFF_MS.getOrElse(attempt) { 15_000L }
                runCatching { Thread.sleep(sleepMs) }
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    /**
     * Checks whether the tablet currently has an active Wi-Fi or validated network connection.
     */
    fun isWifiOrNetworkConnected(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            info != null && info.isConnected
        }
    }.getOrDefault(false)

    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
}
