package com.nouralwelayah.clock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.kiosk.KioskPolicy

/**
 * DeviceAdminReceiver for Device Owner provisioning and Lock Task lifecycle hooks.
 *
 * Provisioned once via ADB on an account-free device:
 * - `adb shell dpm set-device-owner com.nouralwelayah.clock/com.nouralwelayah.clock.AdminReceiver`
 */
class AdminReceiver : DeviceAdminReceiver() {

    /** Invoked when device admin / device owner status is enabled. */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "onEnabled: DeviceAdminReceiver enabled for ${context.packageName}")
        runCatching {
            Store.init(context)
            if (Store.settings.value.kioskMode && KioskPolicy.isOwner(context)) {
                KioskPolicy.applyFullKioskPolicy(context)
            }
        }.onFailure { Log.w(TAG, "onEnabled policy check failed", it) }
    }

    /** Invoked when device admin status is disabled. */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "onDisabled: DeviceAdminReceiver disabled for ${context.packageName}")
    }

    /**
     * Invoked when Device Owner / Profile provisioning completes.
     * Applies the full kiosk policy and launches [MainActivity].
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "onProfileProvisioningComplete: applying full kiosk policy and launching MainActivity")
        runCatching {
            Store.init(context)
            Store.saveSettings(Store.settings.value.copy(kioskMode = true))
            KioskPolicy.applyFullKioskPolicy(context)
            val launch = Intent(context.applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.applicationContext.startActivity(launch)
        }.onFailure { Log.e(TAG, "onProfileProvisioningComplete failed", it) }
    }

    /** Invoked when the system enters Lock Task Mode for [pkg]. */
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "onLockTaskModeEntering: pkg=$pkg")
    }

    /**
     * Invoked when the system exits Lock Task Mode.
     * If kiosk policy is still active, re-applies policy and schedules a 500 ms delayed
     * relaunch of [MainActivity] to defeat transient exits or external `stopLockTask` calls.
     */
    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        val active = KioskPolicy.isKioskActive(context)
        Log.w(TAG, "onLockTaskModeExiting: isKioskActive=$active")
        if (active) {
            val appCtx = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    if (KioskPolicy.isKioskActive(appCtx)) {
                        Log.i(TAG, "onLockTaskModeExiting: relaunching MainActivity to restore Lock Task")
                        KioskPolicy.applyFullKioskPolicy(appCtx)
                        val relaunch = Intent(appCtx, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        appCtx.startActivity(relaunch)
                    }
                }.onFailure { Log.e(TAG, "Delayed Lock Task relaunch failed", it) }
            }, RELAUNCH_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "AdminReceiver"
        private const val RELAUNCH_DELAY_MS = 500L
    }
}
