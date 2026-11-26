package com.nouralwelayah.clock.ota

/**
 * Remote JSON manifest hosted at `<OTA_BASE_URL>/<channel>/latest.json` (`channel` is `"stable"` or `"beta"`).
 */
data class OtaManifest(
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val mandatory: Boolean = false,
    val minSupportedVersionCode: Long = 0L,
    val releaseNotesEn: String = "",
    val releaseNotesAr: String = "",
)

/**
 * Sealed state representation for the self-hosted Nour Alwelayah OTA Update pipeline.
 */
sealed class OtaState {
    data object Idle : OtaState()
    data object Checking : OtaState()
    data class UpToDate(
        val currentVersionName: String,
        val currentVersionCode: Long,
        val manifestVersionName: String? = null,
        val manifestVersionCode: Long? = null,
        val channel: String? = null,
    ) : OtaState()
    data class UpdateAvailable(val manifest: OtaManifest) : OtaState()
    data class Downloading(
        val manifest: OtaManifest,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : OtaState()
    data class Verifying(val manifest: OtaManifest) : OtaState()
    data class Installing(
        val manifest: OtaManifest,
        val silentMode: Boolean,
    ) : OtaState()
    data class Error(val message: String) : OtaState()
}
