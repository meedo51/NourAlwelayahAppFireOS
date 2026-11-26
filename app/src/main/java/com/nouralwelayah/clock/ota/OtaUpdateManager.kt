package com.nouralwelayah.clock.ota

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.alert.AlertState
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.media.MediaHub
import com.nouralwelayah.clock.voice.VoiceAssistantService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Self-hosted Versioning & OTA Update Engine for Nour Alwelayah Clock (`com.nouralwelayah.clock`).
 *
 * Automatically adapts to the tablet's provisioning mode:
 * - **Device Owner = true (`isDeviceOwner(context)`):** Performs a 100% silent update via
 *   [PackageInstaller.Session] and auto-relaunches `MainActivity` on `ACTION_MY_PACKAGE_REPLACED`.
 * - **Device Owner = false:** Seamlessly falls back to standard Android [FileProvider] +
 *   [Intent.ACTION_VIEW] (`application/vnd.android.package-archive`) install prompt.
 */
object OtaUpdateManager {
    private const val TAG = "OtaUpdateManager"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    private const val BUFFER_SIZE = 65_536 // 64 KB stream buffer
    private const val MAX_DOWNLOAD_RETRIES = 5
    private val RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 12_000L, 15_000L)
    private const val SAFETY_WAIT_INTERVAL_MS = 5_000L
    private const val MAX_SAFETY_WAIT_ATTEMPTS = 36 // Up to 3 minutes wait for Azan/Voice/Audio to finish
    private const val INSTALL_SESSION_NAME = "ota_package"
    private const val PENDING_INTENT_REQUEST_CODE = 10042

    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state: StateFlow<OtaState> = _state.asStateFlow()

    @Volatile
    private var lastCheckedManifest: OtaManifest? = null

    /**
     * Returns the current installed versionCode using [PackageInfoCompat.getLongVersionCode].
     */
    fun getCurrentVersionCode(context: Context): Long = runCatching {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(pkgInfo)
    }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

    /**
     * Returns the current installed versionName from `PackageManager` (falling back to [BuildConfig.VERSION_NAME]).
     */
    fun getCurrentVersionName(context: Context): String = runCatching {
        val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
    }.getOrDefault(BuildConfig.VERSION_NAME)

    /**
     * Auto-detects whether `com.nouralwelayah.clock` is provisioned as Device Owner.
     */
    fun isDeviceOwner(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
            ?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    /**
     * Reads the selected update channel (`"stable"` by default, or `"beta"`) from [Store.settings].
     */
    fun getSelectedChannel(context: Context): String {
        Store.init(context.applicationContext)
        return AppSettings.normalizeOtaChannel(Store.settings.value.otaChannel)
    }

    /**
     * Persists the selected update channel (`"stable"` or `"beta"`) into [Store.settings].
     */
    fun setSelectedChannel(context: Context, channel: String) {
        val appCtx = context.applicationContext
        Store.init(appCtx)
        val normalized = AppSettings.normalizeOtaChannel(channel)
        val current = Store.settings.value
        if (current.otaChannel != normalized) {
            Store.saveSettings(current.copy(otaChannel = normalized))
            _state.value = OtaState.Idle
        }
    }

    /**
     * Resolves the channel-specific `latest.json` URL (`<OTA_BASE_URL>/<channel>/latest.json`).
     */
    fun resolveManifestUrl(context: Context, channelOverride: String? = null): String {
        Store.init(context.applicationContext)
        val settings = Store.settings.value
        val channel = AppSettings.normalizeOtaChannel(channelOverride ?: settings.otaChannel)
        val rawBase = settings.otaServerUrl.trim().trimEnd('/').let { url ->
            if (url.isBlank() || url == "https://alwelayah.xus.me/ota") {
                BuildConfig.OTA_BASE_URL.trim().trimEnd('/')
            } else {
                url
            }
        }
        return when {
            rawBase.endsWith("/$channel/latest.json", ignoreCase = true) -> rawBase
            rawBase.endsWith(".json", ignoreCase = true) -> rawBase
            rawBase.endsWith("/$channel", ignoreCase = true) -> "$rawBase/latest.json"
            else -> "$rawBase/$channel/latest.json"
        }
    }

    /**
     * Pre-install safety check: returns `true` only when no Azan/alarm is ringing,
     * no Quran/media audio or YouTube playback is active, and no Voice Assistant session is running.
     */
    fun isSafeForSilentInstall(context: Context? = null): Boolean {
        if (AlertState.current.value != null) {
            Log.i(TAG, "isSafeForSilentInstall=false: Azan or Alarm is currently ringing")
            return false
        }
        if (VoiceAssistantService.isSessionActive()) {
            Log.i(TAG, "isSafeForSilentInstall=false: Voice Assistant session is active")
            return false
        }
        if (MediaHub.youTubePlaying.value || MediaHub.youTube.value != null) {
            Log.i(TAG, "isSafeForSilentInstall=false: MediaHub YouTube session is active")
            return false
        }
        val audioActive = runCatching {
            val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMusicActive == true
        }.getOrDefault(false)
        if (audioActive) {
            Log.i(TAG, "isSafeForSilentInstall=false: AudioManager reports active music/Quran playback")
            return false
        }
        return true
    }

    /**
     * Checks `<OTA_BASE_URL>/<channel>/latest.json` for a newer release.
     * Strictly compares `manifest.versionCode > getCurrentVersionCode(context)` as `Long`.
     */
    suspend fun checkForUpdates(
        context: Context,
        channelOverride: String? = null,
    ): OtaState = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        _state.value = OtaState.Checking

        val manifestUrl = resolveManifestUrl(appCtx, channelOverride)
        val currentCode = getCurrentVersionCode(appCtx)
        val currentName = getCurrentVersionName(appCtx)
        val selectedChannel = AppSettings.normalizeOtaChannel(channelOverride ?: getSelectedChannel(appCtx))

        Log.i(
            TAG,
            "Checking for OTA updates: url=$manifestUrl, channel=$selectedChannel, current=$currentName ($currentCode)"
        )

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "NourAlwelayah-Clock/$currentName ($currentCode)")
            }

            val httpCode = conn.responseCode
            if (httpCode !in 200..299) {
                val errState = OtaState.Error("HTTP $httpCode when fetching update manifest from $manifestUrl")
                Log.w(TAG, errState.message)
                _state.value = errState
                return@withContext errState
            }

            val rawJson = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val manifest = parseOtaManifest(rawJson, defaultChannel = selectedChannel)
            lastCheckedManifest = manifest

            val nextState = if (manifest.versionCode > currentCode) {
                Log.i(
                    TAG,
                    "OTA update available: v${manifest.versionName} (${manifest.versionCode}) > current v$currentName ($currentCode)"
                )
                OtaState.UpdateAvailable(manifest)
            } else {
                Log.i(
                    TAG,
                    "Appliance is up to date: current v$currentName ($currentCode) >= remote v${manifest.versionName} (${manifest.versionCode})"
                )
                OtaState.UpToDate(
                    currentVersionName = currentName,
                    currentVersionCode = currentCode,
                    manifestVersionName = manifest.versionName,
                    manifestVersionCode = manifest.versionCode,
                    channel = manifest.channel,
                )
            }
            _state.value = nextState
            return@withContext nextState
        } catch (e: Exception) {
            val errMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.w(TAG, "OTA update check failed: $errMsg", e)
            val errState = OtaState.Error("Unable to check for updates: $errMsg")
            _state.value = errState
            return@withContext errState
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Parses a JSON string into [OtaManifest].
     * Also supports legacy `"latestVersionCode"` keys as fallback so existing servers remain compatible.
     */
    fun parseOtaManifest(jsonString: String, defaultChannel: String = AppSettings.OTA_CHANNEL_STABLE): OtaManifest {
        val root = JSONObject(jsonString)
        val channel = AppSettings.normalizeOtaChannel(root.optString("channel", defaultChannel))
        val versionCode = when {
            root.has("versionCode") -> root.optLong("versionCode", 0L)
            root.has("latestVersionCode") -> root.optLong("latestVersionCode", 0L)
            else -> 0L
        }
        val versionName = root.optString("versionName", "").trim().ifBlank {
            if (versionCode > 0L) versionCode.toString() else "0.0.0"
        }
        val apkUrl = root.optString("apkUrl", "").trim()
        val sha256 = root.optString("sha256", "").trim().lowercase(Locale.ROOT)
        val mandatory = root.optBoolean("mandatory", false)
        val minSupportedVersionCode = root.optLong("minSupportedVersionCode", 0L)
        val releaseNotesEn = root.optString("releaseNotesEn", root.optString("releaseNotes", "")).trim()
        val releaseNotesAr = root.optString("releaseNotesAr", "").trim()

        if (versionCode <= 0L) {
            throw IllegalArgumentException("Invalid or missing versionCode in OTA manifest")
        }
        if (apkUrl.isBlank()) {
            throw IllegalArgumentException("Missing apkUrl in OTA manifest")
        }

        return OtaManifest(
            channel = channel,
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            mandatory = mandatory,
            minSupportedVersionCode = minSupportedVersionCode,
            releaseNotesEn = releaseNotesEn,
            releaseNotesAr = releaseNotesAr,
        )
    }

    /**
     * Streams the APK into a version-specific resumable partial file
     * (`cacheDir/ota/ota-v<versionCode>.apk.part`), supports HTTP `Range: bytes=` resumption
     * with up to [MAX_DOWNLOAD_RETRIES] automatic retries on transient BoringSSL / Wi-Fi drops,
     * verifies the SHA-256 digest, atomically renames `.part` to the final `.apk`,
     * and triggers either the silent Device Owner session commit or standard FileProvider install prompt.
     *
     * @param deferIfBusy When true (e.g., background unattended Device Owner update), waits until
     *                    [isSafeForSilentInstall] returns true before committing the silent session.
     */
    suspend fun downloadAndInstall(
        context: Context,
        manifest: OtaManifest,
        deferIfBusy: Boolean = false,
    ): OtaState = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val otaDir = File(appCtx.cacheDir, "ota").apply { mkdirs() }
        val partFile = OtaIntegrityHealthCheck.partFileFor(otaDir, manifest.versionCode)
        val sidecarFile = OtaIntegrityHealthCheck.sidecarFileFor(otaDir, manifest.versionCode)
        val apkFile = OtaIntegrityHealthCheck.apkFileFor(otaDir, manifest.versionCode)

        // Clean up any stale partial, sidecar, or APK files belonging to other version codes so disk space stays clean
        runCatching {
            otaDir.listFiles()?.forEach { file ->
                if (file.name != partFile.name && file.name != apkFile.name && file.name != sidecarFile.name) {
                    runCatching { file.delete() }
                }
            }
        }

        // If running in Standard (non-Device-Owner) mode on API 26+, check unknown app sources permission up front
        val deviceOwner = isDeviceOwner(appCtx)
        if (!deviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!appCtx.packageManager.canRequestPackageInstalls()) {
                runCatching {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appCtx.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appCtx.startActivity(settingsIntent)
                }.onFailure { e ->
                    Log.w(TAG, "Failed to open ACTION_MANAGE_UNKNOWN_APP_SOURCES: ${e.message}")
                }
                val errState = OtaState.Error(
                    "Please grant 'Install unknown apps' permission for Nour Alwelayah, then tap Download & Install again."
                )
                _state.value = errState
                return@withContext errState
            }
        }

        try {
            // Remove any unverified final APK before starting or resuming the .part download
            if (apkFile.exists()) {
                apkFile.delete()
            }

            // Enqueue DOWNLOAD_STARTED telemetry event
            OtaEventWorker.enqueue(
                context = appCtx,
                event = OtaTelemetryEvent.DOWNLOAD_STARTED,
                targetVersionCode = manifest.versionCode,
                targetVersionName = manifest.versionName,
                channelOverride = manifest.channel,
            )

            // Stream/resume into partFile with up to 5 automatic retries on SSL/socket/IO failures
            val downloadErr = downloadApkResumableWithRetries(
                appCtx = appCtx,
                manifest = manifest,
                partFile = partFile,
                sidecarFile = sidecarFile,
            )
            if (downloadErr != null) {
                OtaEventWorker.enqueue(
                    context = appCtx,
                    event = OtaTelemetryEvent.INSTALL_FAILED,
                    targetVersionCode = manifest.versionCode,
                    targetVersionName = manifest.versionName,
                    errorMessage = downloadErr.message,
                    channelOverride = manifest.channel,
                )
                _state.value = downloadErr
                return@withContext downloadErr
            }

            if (!partFile.isFile || partFile.length() <= 0L) {
                partFile.delete()
                sidecarFile.delete()
                val errState = OtaState.Error("Downloaded APK file is empty")
                OtaEventWorker.enqueue(
                    context = appCtx,
                    event = OtaTelemetryEvent.INSTALL_FAILED,
                    targetVersionCode = manifest.versionCode,
                    targetVersionName = manifest.versionName,
                    errorMessage = errState.message,
                    channelOverride = manifest.channel,
                )
                _state.value = errState
                return@withContext errState
            }

            // Transition to Verifying and verify SHA-256 digest on the completed .part file
            _state.value = OtaState.Verifying(manifest)
            val actualSha256 = computeSha256Hex(partFile)
            val expectedSha256 = manifest.sha256.trim()

            if (expectedSha256.isNotEmpty() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                Log.e(
                    TAG,
                    "SHA-256 checksum mismatch for ${partFile.name}: expected=$expectedSha256, actual=$actualSha256"
                )
                partFile.delete()
                sidecarFile.delete()
                apkFile.delete()
                val errState = OtaState.Error("SHA-256 checksum mismatch")
                OtaEventWorker.enqueue(
                    context = appCtx,
                    event = OtaTelemetryEvent.INSTALL_FAILED,
                    targetVersionCode = manifest.versionCode,
                    targetVersionName = manifest.versionName,
                    errorMessage = "SHA-256 checksum mismatch (expected=$expectedSha256, actual=$actualSha256)",
                    channelOverride = manifest.channel,
                )
                _state.value = errState
                return@withContext errState
            }

            // Record final verified SHA-256 and total size in sidecar before atomic rename to .apk
            OtaIntegrityHealthCheck.updateSidecarForPartFile(
                partFile = partFile,
                sidecarFile = sidecarFile,
                versionCode = manifest.versionCode,
                expectedFullSha256 = expectedSha256.ifEmpty { actualSha256 },
                expectedTotalBytes = partFile.length(),
            )

            // Atomic finalization: rename verified .part file to final .apk
            if (apkFile.exists()) {
                apkFile.delete()
            }
            val renamed = partFile.renameTo(apkFile)
            if (!renamed) {
                partFile.copyTo(apkFile, overwrite = true)
                partFile.delete()
            }

            Log.i(TAG, "Verified APK SHA-256 ($actualSha256) and finalized ${apkFile.name} for v${manifest.versionName} (${manifest.versionCode})")

            // Enqueue DOWNLOAD_VERIFIED telemetry event
            OtaEventWorker.enqueue(
                context = appCtx,
                event = OtaTelemetryEvent.DOWNLOAD_VERIFIED,
                targetVersionCode = manifest.versionCode,
                targetVersionName = manifest.versionName,
                channelOverride = manifest.channel,
            )

            // Dual-Mode Installer
            return@withContext if (deviceOwner) {
                if (deferIfBusy) {
                    var attempts = 0
                    while (!isSafeForSilentInstall(appCtx) && attempts < MAX_SAFETY_WAIT_ATTEMPTS) {
                        attempts++
                        Log.i(
                            TAG,
                            "Deferring silent Device Owner install until Azan/Media/Voice is idle (attempt $attempts/$MAX_SAFETY_WAIT_ATTEMPTS)"
                        )
                        delay(SAFETY_WAIT_INTERVAL_MS)
                    }
                    if (!isSafeForSilentInstall(appCtx)) {
                        val deferredErr = OtaState.Error(
                            "Silent update deferred because Azan, Media, or Voice Assistant is currently active."
                        )
                        _state.value = deferredErr
                        return@withContext deferredErr
                    }
                } else if (!isSafeForSilentInstall(appCtx)) {
                    Log.w(TAG, "Proceeding with technician-initiated silent install while audio/voice is active")
                }

                installSilentlyAsDeviceOwner(appCtx, apkFile, manifest)
            } else {
                installWithStandardPrompt(appCtx, apkFile, manifest)
            }
        } catch (e: Exception) {
            val errMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.e(TAG, "OTA downloadAndInstall failed: $errMsg", e)
            val errState = OtaState.Error("Update failed: $errMsg")
            OtaEventWorker.enqueue(
                context = appCtx,
                event = OtaTelemetryEvent.INSTALL_FAILED,
                targetVersionCode = manifest.versionCode,
                targetVersionName = manifest.versionName,
                errorMessage = errState.message,
                channelOverride = manifest.channel,
            )
            _state.value = errState
            return@withContext errState
        }
    }

    /**
     * Downloads [manifest.apkUrl] into [partFile] using HTTP/1.1 semantics, a 64 KB stream buffer,
     * `Range: bytes=<existingBytes>-` resumption, and up to [MAX_DOWNLOAD_RETRIES] automatic retries
     * with exponential backoff (`2s, 4s, 8s, 12s, 15s`) on [SSLException] (such as BoringSSL
     * `BAD_DECRYPT` / `DECRYPTION_FAILED_OR_BAD_RECORD_MAC`), [SocketTimeoutException],
     * [EOFException], and [IOException].
     *
     * @return `null` when the full payload has been written to [partFile], or an [OtaState.Error] if
     *         an unrecoverable HTTP error occurs or all retry attempts are exhausted.
     */
    private suspend fun downloadApkResumableWithRetries(
        appCtx: Context,
        manifest: OtaManifest,
        partFile: File,
        sidecarFile: File,
    ): OtaState.Error? {
        val userAgent = "NourAlwelayah-Clock/${getCurrentVersionName(appCtx)} (${getCurrentVersionCode(appCtx)})"
        var knownTotalBytes = OtaIntegrityMetadata.readFromFile(sidecarFile)?.expectedTotalBytes ?: -1L
        var retryAttempt = 0
        var handledRange416 = false
        val chunkSize = OtaIntegrityHealthCheck.DEFAULT_CHUNK_SIZE_BYTES

        // Verify any pre-existing partial file chunks before resuming
        if (partFile.isFile && partFile.length() > 0L) {
            partFile.parentFile?.let { dir ->
                OtaIntegrityHealthCheck.inspectDirectory(dir, getCurrentVersionCode(appCtx))
            }
        }

        while (true) {
            val existingBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L
            val initialPercent = if (knownTotalBytes > 0L) {
                ((existingBytes * 100L) / knownTotalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
            _state.value = OtaState.Downloading(
                manifest = manifest,
                progressPercent = initialPercent,
                downloadedBytes = existingBytes,
                totalBytes = knownTotalBytes,
            )

            var conn: HttpURLConnection? = null
            try {
                conn = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
                    readTimeout = DOWNLOAD_READ_TIMEOUT_MS
                    useCaches = false
                    instanceFollowRedirects = true
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("User-Agent", userAgent)
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                }

                val httpCode = conn.responseCode

                // Handle 416 Range Not Satisfiable: delete .part file and sidecar and retry once from byte 0
                if (httpCode == 416) {
                    Log.w(TAG, "Received HTTP 416 Range Not Satisfiable for ${partFile.name} (existingBytes=$existingBytes); resetting .part and restarting from 0")
                    partFile.delete()
                    sidecarFile.delete()
                    knownTotalBytes = -1L
                    if (!handledRange416) {
                        handledRange416 = true
                        continue
                    } else {
                        return OtaState.Error("APK download failed with HTTP 416 (Range Not Satisfiable)")
                    }
                }

                val isPartial = httpCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
                val isFullOk = httpCode in 200..299 && httpCode != HttpURLConnection.HTTP_PARTIAL

                if (!isPartial && !isFullOk) {
                    return OtaState.Error("APK download failed with HTTP $httpCode")
                }

                val appendMode: Boolean
                var downloadedBytes: Long
                val contentLength = conn.contentLengthLong
                val contentRangeHeader = conn.getHeaderField("Content-Range")
                val totalFromContentRange = parseTotalBytesFromContentRange(contentRangeHeader)

                if (isPartial) {
                    appendMode = true
                    downloadedBytes = existingBytes
                    knownTotalBytes = when {
                        totalFromContentRange > 0L -> totalFromContentRange
                        contentLength > 0L -> existingBytes + contentLength
                        else -> knownTotalBytes
                    }
                    Log.i(
                        TAG,
                        "Resuming OTA APK download (HTTP 206 Partial Content) from byte $existingBytes / $knownTotalBytes"
                    )
                } else {
                    // Server responded with 200 OK (or existingBytes == 0): truncate .part and sidecar and start from byte 0
                    appendMode = false
                    downloadedBytes = 0L
                    knownTotalBytes = if (contentLength > 0L) contentLength else -1L
                    sidecarFile.delete()
                    if (existingBytes > 0L) {
                        Log.i(TAG, "Server returned HTTP $httpCode (ignoring Range); restarting download from byte 0")
                    }
                }

                var lastEmittedPercent = -1
                var lastRecordedChunks = (downloadedBytes / chunkSize).toInt()

                conn.inputStream.use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val percent = if (knownTotalBytes > 0L) {
                                ((downloadedBytes * 100L) / knownTotalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastEmittedPercent || knownTotalBytes <= 0L) {
                                lastEmittedPercent = percent
                                _state.value = OtaState.Downloading(
                                    manifest = manifest,
                                    progressPercent = percent,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = knownTotalBytes,
                                )
                            }
                            // As each 8 MB chunk completes on disk, flush and record its SHA-256 in the sidecar
                            val completedChunksNow = (downloadedBytes / chunkSize).toInt()
                            if (completedChunksNow > lastRecordedChunks) {
                                output.flush()
                                output.fd.sync()
                                OtaIntegrityHealthCheck.updateSidecarForPartFile(
                                    partFile = partFile,
                                    sidecarFile = sidecarFile,
                                    versionCode = manifest.versionCode,
                                    expectedFullSha256 = manifest.sha256,
                                    expectedTotalBytes = knownTotalBytes,
                                    chunkSizeBytes = chunkSize,
                                )
                                lastRecordedChunks = completedChunksNow
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }

                // Update sidecar with all completed chunks after stream closes
                OtaIntegrityHealthCheck.updateSidecarForPartFile(
                    partFile = partFile,
                    sidecarFile = sidecarFile,
                    versionCode = manifest.versionCode,
                    expectedFullSha256 = manifest.sha256,
                    expectedTotalBytes = knownTotalBytes,
                    chunkSizeBytes = chunkSize,
                )

                // Verify that if the server advertised a known total length, all bytes actually arrived before EOF
                val finalSize = if (partFile.isFile) partFile.length() else 0L
                if (knownTotalBytes > 0L && finalSize < knownTotalBytes) {
                    throw EOFException(
                        "Premature EOF while streaming APK ($finalSize / $knownTotalBytes bytes downloaded)"
                    )
                }

                return null
            } catch (e: Exception) {
                // Persist chunk hashes for any complete chunks flushed prior to the network interruption
                runCatching {
                    if (partFile.isFile && partFile.length() > 0L) {
                        OtaIntegrityHealthCheck.updateSidecarForPartFile(
                            partFile = partFile,
                            sidecarFile = sidecarFile,
                            versionCode = manifest.versionCode,
                            expectedFullSha256 = manifest.sha256,
                            expectedTotalBytes = knownTotalBytes,
                            chunkSizeBytes = chunkSize,
                        )
                    }
                }

                val isTransientIo = e is SSLException ||
                    e is SocketTimeoutException ||
                    e is EOFException ||
                    e is IOException
                if (!isTransientIo) {
                    val errMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    return OtaState.Error("Update failed: $errMsg")
                }

                if (retryAttempt >= MAX_DOWNLOAD_RETRIES) {
                    val savedBytes = if (partFile.isFile) partFile.length() else 0L
                    val errMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    Log.e(
                        TAG,
                        "OTA APK download exhausted $MAX_DOWNLOAD_RETRIES retries at $savedBytes bytes: $errMsg",
                        e
                    )
                    return OtaState.Error(
                        "Download interrupted after $MAX_DOWNLOAD_RETRIES retries ($errMsg). Partial progress saved; tap Download & Install to resume."
                    )
                }

                val backoffMs = RETRY_BACKOFF_MS[retryAttempt.coerceIn(0, RETRY_BACKOFF_MS.lastIndex)]
                retryAttempt++
                val currentSaved = if (partFile.isFile) partFile.length() else 0L
                Log.w(
                    TAG,
                    "Transient network/SSL error during OTA download (${e.javaClass.simpleName}: ${e.message}) at byte $currentSaved. " +
                        "Retrying ($retryAttempt/$MAX_DOWNLOAD_RETRIES) in ${backoffMs}ms..."
                )
                delay(backoffMs)
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Parses the total byte size from an HTTP `Content-Range` header (e.g., `"bytes 1048576-2097151/875560960"` -> `875560960L`).
     */
    internal fun parseTotalBytesFromContentRange(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return -1L
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex + 1 >= contentRange.length) return -1L
        val totalPart = contentRange.substring(slashIndex + 1).trim()
        if (totalPart == "*") return -1L
        return totalPart.toLongOrNull() ?: -1L
    }

    /**
     * Mode A — Silent Device Owner Install (`isDeviceOwner(context) == true`):
     * Streams the verified APK into a [PackageInstaller.Session] and commits with [OtaInstallResultReceiver].
     */
    private fun installSilentlyAsDeviceOwner(
        context: Context,
        apkFile: File,
        manifest: OtaManifest,
    ): OtaState {
        _state.value = OtaState.Installing(manifest = manifest, silentMode = true)
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (apkFile.length() > 0L) {
                setSize(apkFile.length())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        var sessionId = -1
        var session: PackageInstaller.Session? = null
        return try {
            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            FileInputStream(apkFile).use { input ->
                session.openWrite(INSTALL_SESSION_NAME, 0, apkFile.length()).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                    session.fsync(out)
                }
            }

            val callbackIntent = Intent(context, OtaInstallResultReceiver::class.java).apply {
                action = OtaInstallResultReceiver.ACTION_OTA_INSTALL_RESULT
                setPackage(context.packageName)
                putExtra(OtaInstallResultReceiver.EXTRA_VERSION_CODE, manifest.versionCode)
                putExtra(OtaInstallResultReceiver.EXTRA_VERSION_NAME, manifest.versionName)
            }

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                PENDING_INTENT_REQUEST_CODE,
                callbackIntent,
                pendingFlags,
            )

            // Synchronously persist pending install state to Device-Protected Storage before Session.commit()
            OtaTelemetryClient.recordPendingInstallSync(
                context = context,
                targetVersionCode = manifest.versionCode,
                targetVersionName = manifest.versionName,
                channel = manifest.channel,
            )

            session.commit(pendingIntent.intentSender)
            Log.i(
                TAG,
                "Committed silent Device Owner PackageInstaller session #$sessionId for v${manifest.versionName} (${manifest.versionCode})"
            )
            _state.value
        } catch (e: Exception) {
            if (sessionId != -1) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
            val errMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.w(TAG, "Silent Device Owner install threw ($errMsg); attempting Fire OS standard package installer fallback", e)
            if (apkFile.isFile && apkFile.length() > 0L) {
                val fallbackState = installWithStandardPrompt(context, apkFile, manifest)
                if (fallbackState !is OtaState.Error) {
                    return fallbackState
                }
            }
            OtaTelemetryClient.clearPendingInstallSync(context)
            OtaEventWorker.enqueue(
                context = context,
                event = OtaTelemetryEvent.INSTALL_FAILED,
                targetVersionCode = manifest.versionCode,
                targetVersionName = manifest.versionName,
                errorMessage = "Silent install failed: $errMsg",
                channelOverride = manifest.channel,
            )
            val errState = OtaState.Error("Silent install failed: $errMsg")
            _state.value = errState
            errState
        } finally {
            runCatching { session?.close() }
        }
    }

    /**
     * Mode B — Standard Prompted Install (`isDeviceOwner(context) == false` or Fire OS fallback):
     * Launches standard Android/Fire OS package installer via [FileProvider] (`ACTION_INSTALL_PACKAGE`
     * with automatic fallback to [Intent.ACTION_VIEW]).
     */
    private fun installWithStandardPrompt(
        context: Context,
        apkFile: File,
        manifest: OtaManifest,
    ): OtaState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
            }
            val errState = OtaState.Error(
                "Please enable 'Allow from this source' in Android Settings, then tap Download & Install again."
            )
            _state.value = errState
            return errState
        }

        // Persist pending install target so post-install restart reports INSTALL_SUCCEEDED
        OtaTelemetryClient.recordPendingInstallSync(
            context = context,
            targetVersionCode = manifest.versionCode,
            targetVersionName = manifest.versionName,
            channel = manifest.channel,
        )

        _state.value = OtaState.Installing(manifest = manifest, silentMode = false)
        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
            @Suppress("DEPRECATION")
            val primaryInstallIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val launchedPrimary = runCatching {
                context.startActivity(primaryInstallIntent)
                true
            }.getOrDefault(false)

            if (!launchedPrimary) {
                val fallbackViewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(fallbackViewIntent)
            }
            Log.i(TAG, "Launched FileProvider package installer for ${apkFile.name} ($contentUri)")
            _state.value
        } catch (e: Exception) {
            OtaTelemetryClient.clearPendingInstallSync(context)
            val errMsg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Log.e(TAG, "Standard FileProvider install launch failed: $errMsg", e)
            OtaEventWorker.enqueue(
                context = context,
                event = OtaTelemetryEvent.INSTALL_FAILED,
                targetVersionCode = manifest.versionCode,
                targetVersionName = manifest.versionName,
                errorMessage = "Failed to launch installer: $errMsg",
                channelOverride = manifest.channel,
            )
            val errState = OtaState.Error("Failed to launch installer: $errMsg")
            _state.value = errState
            errState
        }
    }

    /**
     * Computes the lowercase hexadecimal SHA-256 digest of [file] using [MessageDigest].
     */
    fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    /**
     * Deletes any cached `.apk` files inside `cacheDir/ota/`.
     */
    fun cleanupOtaCacheDir(context: Context) {
        runCatching {
            val otaDir = File(context.cacheDir, "ota")
            if (otaDir.exists()) {
                otaDir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to clean cacheDir/ota: ${e.message}")
        }
    }

    /**
     * Updates the internal state from [OtaInstallResultReceiver] and dispatches
     * `INSTALL_SUCCEEDED` or `INSTALL_FAILED` telemetry via [OtaEventWorker].
     */
    internal fun onInstallResult(
        context: Context,
        success: Boolean,
        message: String?,
        targetVersionCode: Long? = null,
        targetVersionName: String? = null,
    ) {
        val pending = OtaTelemetryClient.readPendingInstallRecord(context)
        val resolvedTargetCode = targetVersionCode?.takeIf { it > 0L }
            ?: pending?.targetVersionCode
            ?: lastCheckedManifest?.versionCode
        val resolvedTargetName = targetVersionName?.takeIf { it.isNotBlank() }
            ?: pending?.targetVersionName
            ?: lastCheckedManifest?.versionName

        if (success) {
            OtaTelemetryClient.clearPendingInstallSync(context)
            OtaEventWorker.enqueue(
                context = context,
                event = OtaTelemetryEvent.INSTALL_SUCCEEDED,
                targetVersionCode = resolvedTargetCode ?: getCurrentVersionCode(context),
                targetVersionName = resolvedTargetName ?: getCurrentVersionName(context),
                previousVersionCode = pending?.previousVersionCode,
                previousVersionName = pending?.previousVersionName,
                channelOverride = pending?.channel,
            )
            cleanupOtaCacheDir(context)
            _state.value = OtaState.UpToDate(
                currentVersionName = getCurrentVersionName(context),
                currentVersionCode = getCurrentVersionCode(context),
            )
        } else {
            val errMessage = message?.takeIf { it.isNotBlank() } ?: "Package installation failed"
            OtaTelemetryClient.clearPendingInstallSync(context)
            OtaEventWorker.enqueue(
                context = context,
                event = OtaTelemetryEvent.INSTALL_FAILED,
                targetVersionCode = resolvedTargetCode,
                targetVersionName = resolvedTargetName,
                previousVersionCode = pending?.previousVersionCode,
                previousVersionName = pending?.previousVersionName,
                errorMessage = errMessage,
                channelOverride = pending?.channel,
            )
            _state.value = OtaState.Error(errMessage)
        }
    }
}
