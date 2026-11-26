package com.nouralwelayah.clock.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.nouralwelayah.clock.MainActivity
import com.nouralwelayah.clock.kiosk.KioskPolicy
import com.nouralwelayah.clock.kiosk.WatchdogService
import java.io.File
import java.io.FileInputStream

/**
 * Device Owner / Kiosk Silent Package Installer for Nour Alwelayah.
 *
 * Uses Android's [PackageInstaller] session API to stream and commit a downloaded APK
 * without user confirmation prompts when the application is provisioned as Device Owner.
 * Upon commit completion, [OtaInstallReceiver] (alongside `BootReceiver`'s
 * `MY_PACKAGE_REPLACED` handler) automatically restarts [MainActivity] and [WatchdogService]
 * so the Phase 1 Splash Screen and Phase 2 Watchdog bring the new version online seamlessly.
 */
object SilentInstaller {
    private const val TAG = "SilentInstaller"
    const val ACTION_OTA_INSTALL_STATUS = "com.nouralwelayah.clock.action.OTA_INSTALL_STATUS"
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Streams [apkFile] into a new [PackageInstaller.Session] and commits it with a
     * [PendingIntent] targeting [OtaInstallReceiver].
     *
     * @return `true` if the session was created, written, and committed without throwing an exception.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            Log.e(TAG, "installApk aborted: APK file is missing or empty (${apkFile.absolutePath})")
            return false
        }

        val appCtx = context.applicationContext
        val packageInstaller = appCtx.packageManager.packageInstaller
        var sessionId = -1
        var session: PackageInstaller.Session? = null

        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appCtx.packageName)
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            FileInputStream(apkFile).use { input ->
                session.openWrite("nour_alwelayah_ota.apk", 0L, apkFile.length()).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    session.fsync(output)
                }
            }

            val callbackIntent = Intent(appCtx, OtaInstallReceiver::class.java).apply {
                action = ACTION_OTA_INSTALL_STATUS
                setPackage(appCtx.packageName)
            }

            // PackageInstaller mutates the callback Intent to attach STATUS / STATUS_MESSAGE extras
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

            val pendingIntent = PendingIntent.getBroadcast(
                appCtx,
                sessionId,
                callbackIntent,
                pendingFlags
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i(TAG, "Committed PackageInstaller session $sessionId for ${apkFile.name} (${apkFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Silent APK installation failed for ${apkFile.name}: ${e.message}", e)
            runCatching { session?.abandon() }
            if (sessionId != -1) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
            false
        }
    }
}

/**
 * Receives asynchronous status callbacks from [PackageInstaller.Session.commit].
 *
 * - On [PackageInstaller.STATUS_PENDING_USER_ACTION] (if the device is not yet provisioned as
 *   Device Owner), launches the confirmation intent automatically with `FLAG_ACTIVITY_NEW_TASK`.
 * - On [PackageInstaller.STATUS_SUCCESS], re-applies [KioskPolicy], starts [WatchdogService],
 *   and launches [MainActivity] with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` to
 *   bring the updated appliance version online immediately.
 */
class OtaInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SilentInstaller.ACTION_OTA_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.w(TAG, "OTA install requires user confirmation (device may not be Device Owner): $message")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(it) }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "OTA silent install succeeded! Restarting Nour Alwelayah appliance UI and Watchdog.")
                if (KioskPolicy.isOwner(context)) {
                    KioskPolicy.applyFullKioskPolicy(context)
                }
                WatchdogService.start(context)
                runCatching {
                    val restartIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                    }
                    context.startActivity(restartIntent)
                }.onFailure { e ->
                    Log.w(TAG, "Failed to auto-launch MainActivity after OTA success: ${e.message}")
                }
            }

            else -> {
                Log.e(TAG, "OTA package installation failed (status=$status): $message")
            }
        }
    }

    companion object {
        private const val TAG = "OtaInstallReceiver"
    }
}
