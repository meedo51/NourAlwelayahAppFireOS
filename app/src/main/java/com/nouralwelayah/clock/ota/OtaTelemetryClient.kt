package com.nouralwelayah.clock.ota

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Supported OTA Telemetry Event types reported to `https://manager.alwelayah.xus.me/api/v1/telemetry/ota`.
 */
object OtaTelemetryEvent {
    const val HEARTBEAT = "HEARTBEAT"
    const val DOWNLOAD_STARTED = "DOWNLOAD_STARTED"
    const val DOWNLOAD_VERIFIED = "DOWNLOAD_VERIFIED"
    const val INSTALL_SUCCEEDED = "INSTALL_SUCCEEDED"
    const val INSTALL_FAILED = "INSTALL_FAILED"
}

/**
 * Result of an HTTP telemetry report attempt.
 */
sealed class TelemetrySendResult {
    data object Sent : TelemetrySendResult()
    data object SkippedNoSecret : TelemetrySendResult()
    data class TransientFailure(val httpCode: Int = -1, val message: String) : TelemetrySendResult()
    data class PermanentFailure(val httpCode: Int = -1, val message: String) : TelemetrySendResult()
}

/**
 * Pending OTA installation metadata persisted via synchronous `SharedPreferences.Editor.commit()`
 * in Device-Protected Storage immediately prior to `PackageInstaller.Session.commit(...)`.
 */
data class PendingOtaInstallRecord(
    val targetVersionCode: Long,
    val targetVersionName: String,
    val previousVersionCode: Long,
    val previousVersionName: String,
    val channel: String,
    val recordedAtEpochMs: Long,
)

/**
 * HMAC-SHA256 OTA Device Telemetry Client for Nour Alwelayah (`com.nouralwelayah.clock`).
 *
 * Signs every payload as:
 *   `HMAC_SHA256(secret = BuildConfig.OTA_TELEMETRY_SECRET, message = "${timestamp}.${rawJsonBody}")`
 * and sends:
 *   - `POST https://manager.alwelayah.xus.me/api/v1/telemetry/ota`
 *   - `Content-Type: application/json; charset=utf-8`
 *   - `X-Nour-Timestamp: <timestamp>`
 *   - `X-Nour-Signature: <64-char lowercase hex HMAC-SHA256>`
 */
object OtaTelemetryClient {
    private const val TAG = "OtaTelemetryClient"
    const val DEFAULT_TELEMETRY_ENDPOINT = "https://manager.alwelayah.xus.me/api/v1/telemetry/ota"
    const val HEADER_TIMESTAMP = "X-Nour-Timestamp"
    const val HEADER_SIGNATURE = "X-Nour-Signature"
    private const val HMAC_SHA256_ALGORITHM = "HmacSHA256"

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 12_000

    private const val PREFS_PENDING_INSTALL = "nour_ota_pending_install_prefs"
    private const val KEY_PENDING_TARGET_VERSION_CODE = "pending_target_version_code"
    private const val KEY_PENDING_TARGET_VERSION_NAME = "pending_target_version_name"
    private const val KEY_PENDING_PREV_VERSION_CODE = "pending_prev_version_code"
    private const val KEY_PENDING_PREV_VERSION_NAME = "pending_prev_version_name"
    private const val KEY_PENDING_CHANNEL = "pending_channel"
    private const val KEY_PENDING_TIMESTAMP_MS = "pending_timestamp_ms"

    /**
     * Computes lowercase 64-character hex HMAC-SHA256 over `"${timestamp}.${rawJsonBody}"`.
     */
    fun computeHmacSha256Signature(
        secret: String,
        timestamp: String,
        rawJsonBody: String,
    ): String {
        val signingInput = "$timestamp.$rawJsonBody"
        val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_SHA256_ALGORITHM)
        mac.init(keySpec)
        val digest = mac.doFinal(signingInput.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    /**
     * Builds the JSON payload string for an OTA telemetry event.
     */
    fun buildPayloadJson(
        deviceId: String,
        event: String,
        versionCode: Long,
        versionName: String,
        channel: String,
        kbVersion: Long,
        targetVersionCode: Long? = null,
        targetVersionName: String? = null,
        previousVersionCode: Long? = null,
        previousVersionName: String? = null,
        errorMessage: String? = null,
        isDeviceOwner: Boolean = false,
        timestampSec: Long = System.currentTimeMillis() / 1000L,
    ): String {
        val obj = JSONObject()
        obj.put("deviceId", deviceId)
        obj.put("event", event)
        obj.put("versionCode", versionCode)
        obj.put("versionName", versionName)
        obj.put("channel", AppSettings.normalizeOtaChannel(channel))
        obj.put("kbVersion", kbVersion)
        obj.put("isDeviceOwner", isDeviceOwner)
        obj.put("platform", "fireos")
        obj.put("sdkInt", Build.VERSION.SDK_INT)
        obj.put("manufacturer", Build.MANUFACTURER ?: "Amazon")
        obj.put("model", Build.MODEL ?: "FireTablet")
        obj.put("timestamp", timestampSec)

        if (targetVersionCode != null && targetVersionCode > 0L) {
            obj.put("targetVersionCode", targetVersionCode)
        }
        if (!targetVersionName.isNullOrBlank()) {
            obj.put("targetVersionName", targetVersionName)
        }
        if (previousVersionCode != null && previousVersionCode > 0L) {
            obj.put("previousVersionCode", previousVersionCode)
        }
        if (!previousVersionName.isNullOrBlank()) {
            obj.put("previousVersionName", previousVersionName)
        }
        if (!errorMessage.isNullOrBlank()) {
            obj.put("errorMessage", errorMessage)
        }
        return obj.toString()
    }

    /**
     * Synchronously sends a signed JSON telemetry event to [endpointUrl].
     * Safe to invoke from `WorkManager` (`OtaEventWorker`, `OtaHeartbeatWorker`) on `Dispatchers.IO`.
     */
    fun sendSignedTelemetrySync(
        rawJsonBody: String,
        timestamp: String = (System.currentTimeMillis() / 1000L).toString(),
        secret: String = BuildConfig.OTA_TELEMETRY_SECRET,
        endpointUrl: String = DEFAULT_TELEMETRY_ENDPOINT,
    ): TelemetrySendResult {
        val trimmedSecret = secret.trim()
        if (trimmedSecret.isEmpty()) {
            runCatching {
                Log.d(TAG, "OTA_TELEMETRY_SECRET is blank; skipping telemetry dispatch")
            }
            return TelemetrySendResult.SkippedNoSecret
        }

        val signature = runCatching {
            computeHmacSha256Signature(
                secret = trimmedSecret,
                timestamp = timestamp,
                rawJsonBody = rawJsonBody,
            )
        }.getOrElse { e ->
            return TelemetrySendResult.PermanentFailure(message = "HMAC calculation error: ${e.message}")
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty(HEADER_TIMESTAMP, timestamp)
                setRequestProperty(HEADER_SIGNATURE, signature)
                setRequestProperty(
                    "User-Agent",
                    "NourAlwelayah-Clock/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(rawJsonBody)
                writer.flush()
            }

            val code = conn.responseCode
            when {
                code in 200..299 -> {
                    runCatching { Log.i(TAG, "OTA telemetry POST succeeded (HTTP $code)") }
                    TelemetrySendResult.Sent
                }
                code == 408 || code == 429 || code in 500..599 -> {
                    runCatching { Log.w(TAG, "OTA telemetry transient HTTP $code from $endpointUrl") }
                    TelemetrySendResult.TransientFailure(httpCode = code, message = "HTTP $code")
                }
                else -> {
                    runCatching { Log.w(TAG, "OTA telemetry rejected with HTTP $code from $endpointUrl") }
                    TelemetrySendResult.PermanentFailure(httpCode = code, message = "HTTP $code")
                }
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            runCatching { Log.w(TAG, "OTA telemetry network error: $msg") }
            TelemetrySendResult.TransientFailure(message = msg)
        } finally {
            conn?.disconnect()
        }
    }

    private fun pendingInstallPrefs(context: Context): SharedPreferences {
        return DeviceIdProvider.deviceProtectedContext(context)
            .getSharedPreferences(PREFS_PENDING_INSTALL, Context.MODE_PRIVATE)
    }

    /**
     * Synchronously commits the pending target version to Device-Protected Storage right before
     * `PackageInstaller.Session.commit(...)` (or standard package installer launch) so it survives
     * process death or device reboot during upgrade.
     */
    fun recordPendingInstallSync(
        context: Context,
        targetVersionCode: Long,
        targetVersionName: String,
        channel: String? = null,
    ) {
        runCatching {
            val appCtx = context.applicationContext ?: context
            val currentCode = OtaUpdateManager.getCurrentVersionCode(appCtx)
            val currentName = OtaUpdateManager.getCurrentVersionName(appCtx)
            val resolvedChannel = channel ?: runCatching {
                Store.init(appCtx)
                AppSettings.normalizeOtaChannel(Store.settings.value.otaChannel)
            }.getOrDefault(AppSettings.OTA_CHANNEL_STABLE)

            // Must use synchronous .commit() before PackageInstaller.Session.commit() kills the process
            pendingInstallPrefs(appCtx).edit()
                .putLong(KEY_PENDING_TARGET_VERSION_CODE, targetVersionCode)
                .putString(KEY_PENDING_TARGET_VERSION_NAME, targetVersionName)
                .putLong(KEY_PENDING_PREV_VERSION_CODE, currentCode)
                .putString(KEY_PENDING_PREV_VERSION_NAME, currentName)
                .putString(KEY_PENDING_CHANNEL, resolvedChannel)
                .putLong(KEY_PENDING_TIMESTAMP_MS, System.currentTimeMillis())
                .commit()
        }.onFailure { e ->
            runCatching { Log.w(TAG, "Failed to persist pending install state: ${e.message}") }
        }
    }

    /**
     * Reads any pending OTA install record from Device-Protected Storage, or `null` if none exists.
     */
    fun readPendingInstallRecord(context: Context): PendingOtaInstallRecord? = runCatching {
        val prefs = pendingInstallPrefs(context)
        val targetCode = prefs.getLong(KEY_PENDING_TARGET_VERSION_CODE, -1L)
        if (targetCode <= 0L) return null
        PendingOtaInstallRecord(
            targetVersionCode = targetCode,
            targetVersionName = prefs.getString(KEY_PENDING_TARGET_VERSION_NAME, "") ?: "",
            previousVersionCode = prefs.getLong(KEY_PENDING_PREV_VERSION_CODE, 0L),
            previousVersionName = prefs.getString(KEY_PENDING_PREV_VERSION_NAME, "") ?: "",
            channel = prefs.getString(KEY_PENDING_CHANNEL, AppSettings.OTA_CHANNEL_STABLE)
                ?: AppSettings.OTA_CHANNEL_STABLE,
            recordedAtEpochMs = prefs.getLong(KEY_PENDING_TIMESTAMP_MS, 0L),
        )
    }.getOrNull()

    /**
     * Clears the pending install record from Device-Protected Storage using synchronous `.commit()`.
     */
    fun clearPendingInstallSync(context: Context) {
        runCatching {
            pendingInstallPrefs(context).edit().clear().commit()
        }
    }

    /**
     * Inspects Device-Protected Storage on startup or `ACTION_MY_PACKAGE_REPLACED`.
     * If `currentVersionCode == pending.targetVersionCode`, clears the record and enqueues
     * an `INSTALL_SUCCEEDED` telemetry event via [OtaEventWorker].
     */
    fun verifyAndReportPendingInstallIfCompleted(context: Context): Boolean {
        val appCtx = context.applicationContext ?: context
        val pending = readPendingInstallRecord(appCtx) ?: return false
        val currentCode = OtaUpdateManager.getCurrentVersionCode(appCtx)
        if (currentCode == pending.targetVersionCode || BuildConfig.VERSION_CODE.toLong() == pending.targetVersionCode) {
            clearPendingInstallSync(appCtx)
            OtaEventWorker.enqueue(
                context = appCtx,
                event = OtaTelemetryEvent.INSTALL_SUCCEEDED,
                targetVersionCode = pending.targetVersionCode,
                targetVersionName = pending.targetVersionName.ifBlank { OtaUpdateManager.getCurrentVersionName(appCtx) },
                previousVersionCode = pending.previousVersionCode,
                previousVersionName = pending.previousVersionName,
                channelOverride = pending.channel,
            )
            runCatching {
                Log.i(
                    TAG,
                    "Detected completed OTA upgrade to v${pending.targetVersionName} (${pending.targetVersionCode}); enqueued INSTALL_SUCCEEDED telemetry"
                )
            }
            return true
        }
        return false
    }
}
