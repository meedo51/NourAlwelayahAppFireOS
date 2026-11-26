package com.nouralwelayah.clock.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nouralwelayah.clock.MainActivity

/**
 * Listens for [Intent.ACTION_MY_PACKAGE_REPLACED] after an OTA update completes:
 * 1. Cleans up any leftover APK files in `cacheDir/ota/`.
 * 2. Relaunches [MainActivity] with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` so the kiosk
 *    immediately resumes after a silent Device Owner update.
 */
class OtaPackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i(
            TAG,
            "ACTION_MY_PACKAGE_REPLACED received — reporting INSTALL_SUCCEEDED telemetry, cleaning cacheDir/ota/, and relaunching MainActivity"
        )
        runCatching {
            OtaTelemetryClient.verifyAndReportPendingInstallIfCompleted(context)
        }
        OtaUpdateManager.cleanupOtaCacheDir(context)

        runCatching {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launchIntent)
        }.onFailure { e ->
            Log.e(TAG, "Failed to relaunch MainActivity after package replacement: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "OtaPkgReplacedRcvr"
    }
}
