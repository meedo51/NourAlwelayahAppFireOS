package com.nouralwelayah.clock.ota

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.util.Locale
import java.util.UUID

/**
 * Provides the appliance's stable hardware identifier in `NW-XXXXXXXX` format.
 *
 * Uses Device-Protected Storage (`context.createDeviceProtectedStorageContext()`) on Android 7.0+ (API 24+,
 * including Fire OS 7 / API 28 and Fire OS 8 / API 30) so the device identifier is accessible immediately
 * during Direct Boot before user unlock and shared consistently between:
 * - [OtaTelemetryClient] / [OtaHeartbeatWorker] / [OtaEventWorker]
 * - `AboutNourAlwelayahDialog` ("Device Number / رقم الجهاز")
 */
object DeviceIdProvider {
    private const val PREFS_NAME = "nour_device_identity_prefs"
    private const val KEY_DEVICE_ID = "appliance_device_id"
    private const val INVALID_ANDROID_ID = "9774d56d682e549c"

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * Resolves a Direct-Boot-safe [Context] backed by Device-Protected Storage on API 24+.
     */
    fun deviceProtectedContext(context: Context): Context {
        val appCtx = context.applicationContext ?: context
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appCtx.createDeviceProtectedStorageContext() ?: appCtx
        } else {
            appCtx
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return deviceProtectedContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the persistent `NW-XXXXXXXX` device identifier for this appliance.
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        cachedDeviceId?.let { if (isValidDeviceNumber(it)) return it }

        val prefs = runCatching { getPrefs(context) }.getOrNull()
        val stored = prefs?.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (isValidDeviceNumber(stored) && stored != "NW-00000000") {
            cachedDeviceId = stored
            return stored
        }

        val rawAndroidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().trim()

        val candidate = if (rawAndroidId.isNotBlank() && !rawAndroidId.equals(INVALID_ANDROID_ID, ignoreCase = true)) {
            formatDeviceNumber(rawAndroidId)
        } else {
            formatDeviceNumber(UUID.randomUUID().toString().replace("-", ""))
        }

        runCatching {
            prefs?.edit()?.putString(KEY_DEVICE_ID, candidate)?.commit()
        }
        cachedDeviceId = candidate
        return candidate
    }

    /**
     * Alias for [getDeviceId] used by UI components displaying the Device Number (`NW-XXXXXXXX`).
     */
    fun getDeviceNumber(context: Context): String = getDeviceId(context)

    /**
     * Pure deterministic formatter turning a raw Android ID or hex/alphanumeric string into `NW-XXXXXXXX`.
     */
    fun formatDeviceNumber(rawId: String): String {
        val cleaned = rawId.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        val suffix = when {
            cleaned.length >= 8 -> cleaned.takeLast(8)
            cleaned.isNotEmpty() -> cleaned.padStart(8, '0')
            else -> "00000000"
        }
        return "NW-$suffix"
    }

    fun isValidDeviceNumber(value: String): Boolean {
        return DEVICE_NUMBER_REGEX.matches(value.trim())
    }

    private val DEVICE_NUMBER_REGEX = Regex("^NW-[A-Z0-9]{8}$")
}
