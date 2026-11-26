package com.nouralwelayah.clock.ota

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.Folder
import com.nouralwelayah.clock.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unattended Over-The-Air (OTA) Firmware & Media Update Engine for Nour Alwelayah.
 *
 * Runs a resilient Coroutine polling loop that queries [com.nouralwelayah.clock.data.AppSettings.otaServerUrl]
 * once every 24 hours:
 * 1. Parses the JSON manifest containing `"latestVersionCode"`, `"apkUrl"`, and `"mediaPayloads"`.
 * 2. Streams updated Azan audio and background/wallpaper media payloads directly into
 *    `/storage/emulated/0/NourAlwelayah/<category>/` (and mirrors into active [AppFolders] directories
 *    so the clock immediately indexes new Azan/wallpaper assets).
 * 3. If `"latestVersionCode"` exceeds [BuildConfig.VERSION_CODE], streams the APK into
 *    [Context.getCacheDir] using [HttpURLConnection] and delegates silent Device Owner installation
 *    to [SilentInstaller.installApk].
 */
object OtaManager {
    private const val TAG = "OtaManager"
    private const val POLL_INTERVAL_MS = 24L * 60L * 60L * 1_000L // 24 hours
    private const val INITIAL_DELAY_MS = 15_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private const val APPLIANCE_STORAGE_DIR = "NourAlwelayah"

    data class MediaPayload(
        val fileName: String,
        val url: String,
        val category: String = "azan", // e.g., "azan", "wallpaper", "images", "alarms", "music", "videos"
        val sha256: String = "",
    )

    data class OtaManifest(
        val latestVersionCode: Int,
        val apkUrl: String,
        val mediaPayloads: List<MediaPayload>,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    /**
     * Starts the 24-hour unattended background OTA polling loop.
     * Safe to invoke multiple times; only one active polling job runs at any time.
     */
    @Synchronized
    fun startPolling(context: Context) {
        if (pollingJob?.isActive == true) return
        val appCtx = context.applicationContext
        pollingJob = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                runCatching {
                    checkForUpdatesNow(appCtx)
                }.onFailure { e ->
                    Log.w(TAG, "OTA polling cycle failed silently: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Started 24-hour unattended OTA polling loop (current versionCode=${BuildConfig.VERSION_CODE})")
    }

    /**
     * Executes a single OTA manifest check via [OtaUpdateManager], optional media payload sync,
     * and conditional silent Device Owner APK upgrade.
     */
    suspend fun checkForUpdatesNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        Store.init(appCtx)

        val state = OtaUpdateManager.checkForUpdates(appCtx)
        when (state) {
            is OtaState.UpToDate -> {
                return@withContext true
            }
            is OtaState.UpdateAvailable -> {
                if (OtaUpdateManager.isDeviceOwner(appCtx)) {
                    val installState = OtaUpdateManager.downloadAndInstall(
                        context = appCtx,
                        manifest = state.manifest,
                        deferIfBusy = true,
                    )
                    return@withContext installState is OtaState.Installing || installState is OtaState.UpToDate
                }
                // In standard (non-Device-Owner) mode, leave state as UpdateAvailable so AdminDashboard or voice feedback can prompt install
                return@withContext true
            }
            else -> {
                // Fallback for legacy /manifest.json endpoints with mediaPayloads
                val baseUrl = Store.settings.value.otaServerUrl.trim().trimEnd('/')
                if (baseUrl.isEmpty()) return@withContext false
                val manifestUrl = if (baseUrl.endsWith(".json", ignoreCase = true)) {
                    baseUrl
                } else {
                    "$baseUrl/manifest.json"
                }
                val manifestJson = fetchText(manifestUrl) ?: return@withContext false
                val manifest = parseManifest(manifestJson) ?: return@withContext false
                if (manifest.mediaPayloads.isNotEmpty()) {
                    syncMediaPayloads(appCtx, manifest.mediaPayloads)
                }
                val currentVersion = OtaUpdateManager.getCurrentVersionCode(appCtx)
                if (manifest.latestVersionCode.toLong() > currentVersion && manifest.apkUrl.isNotBlank()) {
                    val apkFile = File(appCtx.cacheDir, "ota/update_${manifest.latestVersionCode}.apk")
                    if (downloadToFile(manifest.apkUrl, apkFile) && apkFile.isFile && apkFile.length() > 0L) {
                        val committed = SilentInstaller.installApk(appCtx, apkFile)
                        apkFile.delete()
                        return@withContext committed
                    } else {
                        apkFile.delete()
                        return@withContext false
                    }
                }
                return@withContext true
            }
        }
    }

    /**
     * Parses the OTA JSON manifest payload containing `"latestVersionCode"`, `"apkUrl"`, and `"mediaPayloads"`.
     */
    fun parseManifest(jsonString: String): OtaManifest? = runCatching {
        val root = JSONObject(jsonString)
        val latestVersionCode = root.optInt("latestVersionCode", BuildConfig.VERSION_CODE)
        val apkUrl = root.optString("apkUrl", "").trim()
        val payloadsArr = root.optJSONArray("mediaPayloads")
        val payloads = buildList {
            if (payloadsArr != null) {
                for (i in 0 until payloadsArr.length()) {
                    val item = payloadsArr.optJSONObject(i)
                    if (item != null) {
                        val url = item.optString("url", item.optString("fileUrl", "")).trim()
                        val rawName = item.optString("fileName", item.optString("name", "")).trim()
                        val fileName = rawName.ifEmpty {
                            url.substringBefore('?').substringAfterLast('/')
                        }.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val category = item.optString("category", item.optString("type", inferCategory(fileName)))
                            .lowercase()
                            .trim()
                        val sha256 = item.optString("sha256", "").trim()
                        if (url.isNotEmpty() && fileName.isNotEmpty()) {
                            add(MediaPayload(fileName = fileName, url = url, category = category, sha256 = sha256))
                        }
                    } else {
                        // Also support plain URL strings inside mediaPayloads array
                        val urlStr = payloadsArr.optString(i, "").trim()
                        if (urlStr.isNotEmpty()) {
                            val fileName = urlStr.substringBefore('?').substringAfterLast('/')
                                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            if (fileName.isNotEmpty()) {
                                add(
                                    MediaPayload(
                                        fileName = fileName,
                                        url = urlStr,
                                        category = inferCategory(fileName),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        OtaManifest(
            latestVersionCode = latestVersionCode,
            apkUrl = apkUrl,
            mediaPayloads = payloads,
        )
    }.onFailure { e ->
        Log.e(TAG, "Failed to parse OTA manifest JSON: ${e.message}", e)
    }.getOrNull()

    /**
     * Streams each [MediaPayload] directly into `/storage/emulated/0/NourAlwelayah/<category>/`
     * (falling back to app storage if external storage access is not yet granted) and mirrors into
     * active [AppFolders] directories so Azan and background libraries refresh automatically.
     */
    private fun syncMediaPayloads(context: Context, payloads: List<MediaPayload>) {
        val externalRoot = File(Environment.getExternalStorageDirectory(), APPLIANCE_STORAGE_DIR)
        var anyUpdated = false

        for (payload in payloads) {
            runCatching {
                val folderEnum = mapCategoryToFolder(payload.category, payload.fileName)
                val subDirName = folderEnum.dir

                // Primary target: /storage/emulated/0/NourAlwelayah/<subDirName>/<fileName>
                val primaryDir = if (AppFolders.hasAccess(context)) {
                    File(externalRoot, subDirName).apply { mkdirs() }
                } else {
                    File(context.getExternalFilesDir(null) ?: context.filesDir, "$APPLIANCE_STORAGE_DIR/$subDirName")
                        .apply { mkdirs() }
                }

                val targetFile = File(primaryDir, payload.fileName)
                val tempFile = File(context.cacheDir, "ota_media_${payload.fileName}.tmp")

                if (downloadToFile(payload.url, tempFile) && tempFile.length() > 0L) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    // Also mirror into AppFolders.dir(context, folderEnum) so existing AzanLibrary / PictureLibrary
                    // immediately see the updated file without requiring manual file moves
                    val appFolderDir = AppFolders.dir(context, folderEnum)
                    if (appFolderDir.canonicalPath != primaryDir.canonicalPath) {
                        val mirrorFile = File(appFolderDir, payload.fileName)
                        tempFile.copyTo(mirrorFile, overwrite = true)
                    }
                    tempFile.delete()
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(targetFile.absolutePath),
                        null,
                        null
                    )
                    anyUpdated = true
                    Log.i(TAG, "Synced OTA media payload: ${targetFile.absolutePath}")
                } else {
                    tempFile.delete()
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to sync media payload ${payload.fileName}: ${e.message}")
            }
        }

        if (anyUpdated) {
            runCatching { AppFolders.refreshAll(context) }
        }
    }

    private fun inferCategory(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "bmp" -> "wallpaper"
            "mp4", "mkv", "webm", "mov" -> "videos"
            else -> "azan"
        }
    }

    private fun mapCategoryToFolder(category: String, fileName: String): Folder = when (category.lowercase()) {
        "azan", "adhan" -> Folder.AZAN
        "wallpaper", "background", "clock_bg" -> Folder.WALLPAPER
        "image", "images", "azan_bg" -> Folder.IMAGES
        "alarm", "alarms" -> Folder.ALARMS
        "music", "audio", "audios" -> Folder.AUDIOS
        "video", "videos" -> Folder.VIDEOS
        else -> if (inferCategory(fileName) == "wallpaper") Folder.WALLPAPER else Folder.AZAN
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
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "OTA manifest HTTP $code from $urlString")
                return null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Network error fetching OTA manifest from $urlString: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadToFile(urlString: String, destination: File): Boolean {
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
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Download HTTP $code from $urlString")
                return false
            }
            conn.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error streaming $urlString to ${destination.name}: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
