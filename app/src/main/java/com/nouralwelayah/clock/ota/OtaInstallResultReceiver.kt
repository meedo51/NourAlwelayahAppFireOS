package com.nouralwelayah.clock.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives asynchronous commit callbacks from [PackageInstaller.Session.commit] initiated by
 * [OtaUpdateManager].
 */
class OtaInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val pkgName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: context.packageName
        val targetVersionCode = intent.getLongExtra(EXTRA_VERSION_CODE, -1L).takeIf { it > 0L }
        val targetVersionName = intent.getStringExtra(EXTRA_VERSION_NAME)?.takeIf { it.isNotBlank() }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    Log.i(TAG, "OTA install requires user confirmation for $pkgName; launching confirmation Intent")
                    runCatching {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to launch STATUS_PENDING_USER_ACTION intent: ${e.message}", e)
                        OtaUpdateManager.onInstallResult(
                            context = context,
                            success = false,
                            message = "Unable to launch install confirmation prompt: ${e.message}",
                            targetVersionCode = targetVersionCode,
                            targetVersionName = targetVersionName,
                        )
                    }
                } else {
                    OtaUpdateManager.onInstallResult(
                        context = context,
                        success = false,
                        message = "Install requires user action, but confirmation intent was missing.",
                        targetVersionCode = targetVersionCode,
                        targetVersionName = targetVersionName,
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "OTA package installation succeeded for $pkgName")
                OtaUpdateManager.onInstallResult(
                    context = context,
                    success = true,
                    message = statusMessage,
                    targetVersionCode = targetVersionCode,
                    targetVersionName = targetVersionName,
                )
            }

            else -> {
                val errText = "OTA installation failed (status=$status): ${statusMessage ?: "Unknown error"}"
                Log.e(TAG, errText)
                OtaUpdateManager.onInstallResult(
                    context = context,
                    success = false,
                    message = errText,
                    targetVersionCode = targetVersionCode,
                    targetVersionName = targetVersionName,
                )
            }
        }
    }

    companion object {
        private const val TAG = "OtaInstallResultRcvr"
        const val ACTION_OTA_INSTALL_RESULT = "com.nouralwelayah.clock.action.OTA_INSTALL_RESULT"
        const val EXTRA_VERSION_CODE = "extra_version_code"
        const val EXTRA_VERSION_NAME = "extra_version_name"
    }
}
