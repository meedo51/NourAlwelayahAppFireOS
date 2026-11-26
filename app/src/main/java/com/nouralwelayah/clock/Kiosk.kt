package com.nouralwelayah.clock

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log

/**
 * Activity-level Lock Task Mode controller.
 *
 * All [android.app.admin.DevicePolicyManager] calls live in [com.nouralwelayah.clock.kiosk.KioskPolicy].
 * This object strictly inspects [ActivityManager] Lock Task state and invokes
 * [Activity.startLockTask] / [Activity.stopLockTask] safely.
 */
object Kiosk {
    private const val TAG = "Kiosk"

    /**
     * Resolves the hosting [Activity] from any [Context] (including Compose [Dialog] [ContextWrapper]s).
     */
    fun findActivity(context: Context?): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return c as? Activity
    }

    /**
     * Returns the current Lock Task Mode state of the system:
     * - [ActivityManager.LOCK_TASK_MODE_NONE] (`0`)
     * - [ActivityManager.LOCK_TASK_MODE_LOCKED] (`1`)
     * - [ActivityManager.LOCK_TASK_MODE_PINNED] (`2`)
     */
    fun status(activity: Activity): Int = runCatching {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState
        } else {
            @Suppress("DEPRECATION")
            if (am.isInLockTaskMode) ActivityManager.LOCK_TASK_MODE_LOCKED else ActivityManager.LOCK_TASK_MODE_NONE
        }
    }.onFailure { Log.w(TAG, "status check failed", it) }
        .getOrDefault(ActivityManager.LOCK_TASK_MODE_NONE)

    /**
     * Enters Lock Task Mode on [activity] if not already in Lock Task Mode.
     */
    fun enter(activity: Activity) {
        val current = status(activity)
        if (current != ActivityManager.LOCK_TASK_MODE_NONE) {
            Log.d(TAG, "enter: already in Lock Task mode (state=$current)")
            return
        }
        runCatching {
            activity.startLockTask()
            Log.i(TAG, "enter: startLockTask() invoked on ${activity.localClassName}")
        }.onFailure {
            Log.w(TAG, "enter: startLockTask() failed", it)
        }
    }

    /**
     * Exits Lock Task Mode on [activity] if currently in Lock Task Mode.
     */
    fun exit(activity: Activity) {
        val current = status(activity)
        if (current == ActivityManager.LOCK_TASK_MODE_NONE) {
            Log.d(TAG, "exit: already out of Lock Task mode (state=NONE)")
            return
        }
        runCatching {
            activity.stopLockTask()
            Log.i(TAG, "exit: stopLockTask() invoked on ${activity.localClassName}")
        }.onFailure {
            Log.w(TAG, "exit: stopLockTask() failed", it)
        }
    }

    /**
     * Master Kiosk clear invoked from the Technician Admin Dashboard:
     * removes all DevicePolicyManager kiosk policies and exits Lock Task mode on the hosting [Activity].
     */
    fun clear(context: Context) {
        com.nouralwelayah.clock.kiosk.KioskPolicy.removeKioskPolicy(context)
        findActivity(context)?.let { exit(it) }
    }
}
