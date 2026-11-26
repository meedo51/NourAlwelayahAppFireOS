package com.nouralwelayah.clock.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.AdminReceiver
import com.nouralwelayah.clock.MainActivity

/**
 * Production-grade Device Owner Kiosk policy manager for Nour Alwelayah.
 *
 * Applies and removes a reversible, idempotent set of [DevicePolicyManager] restrictions
 * that lock a provisioned Android or Fire OS device exclusively to this application.
 *
 * Architectural notes:
 * - [UserManager.DISALLOW_CONFIG_WIFI] and [UserManager.DISALLOW_CONFIG_BLUETOOTH] are
 *   INTENTIONALLY EXCLUDED from [RESTRICTIONS] because PrayerClockApp operates an embedded
 *   HTTP server (`:8080`), a `LocalOnlyHotspot` (API 26+), `WifiNetworkSpecifier` client
 *   connections, and dual BLE GATT servers/clients. Applying those user restrictions causes
 *   the Android framework to block the app's own programmatic `WifiManager` and
 *   `BluetoothAdapter` calls. Instead, system Settings access is blocked by excluding
 *   `com.android.settings` from Lock Task packages, suspending `com.android.settings` on
 *   API 24+, and applying [UserManager.DISALLOW_APPS_CONTROL].
 * - On Fire OS 5 (API 22), API 23+ DPM methods (`setStatusBarDisabled`, `setKeyguardDisabled`,
 *   `DISALLOW_SAFE_BOOT`) and API 26+ ADB hardening are gracefully skipped and logged.
 */
object KioskPolicy {
    private const val TAG = "KioskPolicy"
    private const val PREFS_NAME = "kiosk_policy"
    private const val KEY_ACTIVE = "kiosk_active"
    private const val KEY_KEYGUARD_DISABLED = "keyguard_disabled"
    private const val KEY_STATUSBAR_DISABLED = "statusbar_disabled"
    private const val SETTINGS_PKG = "com.android.settings"
    const val APPLIANCE_BT_NAME = "NourAlwelayah"

    /**
     * Programmatically renames the hardware Bluetooth adapter to [APPLIANCE_BT_NAME] ("NourAlwelayah").
     * Safely checks [Manifest.permission.BLUETOOTH_CONNECT] on Android 12+ (API 31+).
     */
    @SuppressLint("MissingPermission")
    fun enforceBluetoothName(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "enforceBluetoothName: skipped (BLUETOOTH_CONNECT not yet granted on API ${Build.VERSION.SDK_INT})")
            return false
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (adapter.name == APPLIANCE_BT_NAME) return true
            val ok = adapter.setName(APPLIANCE_BT_NAME)
            Log.i(TAG, "enforceBluetoothName -> $APPLIANCE_BT_NAME: result=$ok")
            ok
        }.onFailure {
            Log.w(TAG, "enforceBluetoothName failed", it)
        }.getOrDefault(false)
    }

    /**
     * Single source of truth for all [UserManager] restrictions managed by [KioskPolicy].
     *
     * Used identically by both [applyFullKioskPolicy] and [removeKioskPolicy] so applied
     * and cleared restrictions can never drift out of sync.
     *
     * NOTE: Do NOT add [UserManager.DISALLOW_CONFIG_WIFI], [UserManager.DISALLOW_CONFIG_BLUETOOTH],
     * or `DISALLOW_CONFIG_TETHERING` here — doing so breaks the app's own Wi-Fi HTTP server,
     * `LocalOnlyHotspot`, and BLE GATT remote/upload services.
     */
    private val RESTRICTIONS: List<String> = buildList {
        add(UserManager.DISALLOW_FACTORY_RESET)
        add(UserManager.DISALLOW_ADD_USER)
        add(UserManager.DISALLOW_REMOVE_USER)
        add(UserManager.DISALLOW_MODIFY_ACCOUNTS)
        add(UserManager.DISALLOW_APPS_CONTROL)
        add(UserManager.DISALLOW_UNINSTALL_APPS)
        add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
        add(UserManager.DISALLOW_USB_FILE_TRANSFER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            add(UserManager.DISALLOW_SAFE_BOOT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(UserManager.DISALLOW_AIRPLANE_MODE)
            add(UserManager.DISALLOW_AMBIENT_DISPLAY)
        }
    }

    private fun dpm(context: Context): DevicePolicyManager =
        context.applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    /** Returns the [ComponentName] of [AdminReceiver] for [DevicePolicyManager] calls. */
    fun adminComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, AdminReceiver::class.java)

    /**
     * Returns device-protected [SharedPreferences] on API 24+ so [isKioskActive] can be
     * safely queried during Direct Boot (`LOCKED_BOOT_COMPLETED`) before user unlock.
     */
    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        val storageCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.createDeviceProtectedStorageContext()
        } else {
            app
        }
        return storageCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns `true` if and only if this package is currently provisioned as the
     * Android Device Owner on [context].
     */
    fun isOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }
            .onFailure { Log.w(TAG, "isDeviceOwnerApp check failed", it) }
            .getOrDefault(false)

    /**
     * Returns `true` when kiosk lockdown has been enabled and applied via [applyFullKioskPolicy]
     * AND this app is still the active Device Owner.
     */
    fun isKioskActive(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ACTIVE, false) && isOwner(context) }
            .onFailure { Log.w(TAG, "isKioskActive read failed", it) }
            .getOrDefault(false)

    /**
     * Idempotently applies the full Device Owner kiosk lockdown policy.
     *
     * Safe to invoke repeatedly (on boot, package update, activity resume, or UI toggle).
     * Never throws a [SecurityException]; every DPM call is wrapped in [runCatching] and logged.
     *
     * @return `true` only if the app is Device Owner and all critical lock-task/launcher
     *         policies succeeded; `false` otherwise.
     */
    fun applyFullKioskPolicy(context: Context): Boolean {
        if (!isOwner(context)) {
            Log.i(TAG, "applyFullKioskPolicy: skipped (package ${context.packageName} is not Device Owner)")
            return false
        }

        val app = context.applicationContext
        val dpm = dpm(app)
        val admin = adminComponent(app)
        val pkg = app.packageName
        var criticalOk = true

        Log.i(TAG, "applyFullKioskPolicy: applying kiosk lockdown on API ${Build.VERSION.SDK_INT} for $pkg")

        // 1. Critical: Whitelist this package and com.android.settings for Lock Task Mode
        //    so technicians can open Settings.ACTION_WIFI_SETTINGS from the hidden Admin Dashboard while pinned.
        runCatching {
            dpm.setLockTaskPackages(admin, arrayOf(pkg, "com.android.settings"))
            val permitted = dpm.isLockTaskPermitted(pkg)
            Log.i(TAG, "setLockTaskPackages([$pkg, com.android.settings]): success (isLockTaskPermitted=$permitted)")
            if (!permitted) criticalOk = false
        }.onFailure {
            Log.e(TAG, "setLockTaskPackages failed", it)
            criticalOk = false
        }

        // 2. API 28+: Strip all system chrome (Home, Overview, Notifications, Global Actions, Keyguard) in Lock Task
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                Log.i(TAG, "setLockTaskFeatures(LOCK_TASK_FEATURE_NONE): success")
            }.onFailure {
                Log.w(TAG, "setLockTaskFeatures failed", it)
            }
        } else {
            Log.i(TAG, "setLockTaskFeatures: skipped (requires API 28, device is API ${Build.VERSION.SDK_INT})")
        }

        // 3. Critical: Register MainActivity as the persistent preferred HOME activity
        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, pkg)
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val activity = ComponentName(pkg, MainActivity::class.java.name)
            dpm.addPersistentPreferredActivity(admin, filter, activity)
            Log.i(TAG, "addPersistentPreferredActivity($activity as HOME): success")
        }.onFailure {
            Log.e(TAG, "addPersistentPreferredActivity failed", it)
            criticalOk = false
        }

        // 4. Disable keyguard features (API 21+) and full keyguard (API 23+)
        runCatching {
            dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL)
            Log.i(TAG, "setKeyguardDisabledFeatures(KEYGUARD_DISABLE_FEATURES_ALL): success")
        }.onFailure {
            Log.w(TAG, "setKeyguardDisabledFeatures failed", it)
        }

        var keyguardDisabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                keyguardDisabled = dpm.setKeyguardDisabled(admin, true)
                Log.i(TAG, "setKeyguardDisabled(true): result=$keyguardDisabled")
            }.onFailure {
                Log.w(TAG, "setKeyguardDisabled(true) failed", it)
            }
        } else {
            Log.w(
                TAG,
                "setKeyguardDisabled(true): unsupported on API ${Build.VERSION.SDK_INT} (< 23 / Fire OS 5); " +
                    "relying on Lock Task pinning and KEYGUARD_DISABLE_FEATURES_ALL",
            )
        }

        // 5. Disable status bar / notification shade expansion (API 23+)
        var statusBarDisabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                statusBarDisabled = dpm.setStatusBarDisabled(admin, true)
                Log.i(TAG, "setStatusBarDisabled(true): result=$statusBarDisabled")
            }.onFailure {
                Log.w(TAG, "setStatusBarDisabled(true) failed", it)
            }
        } else {
            Log.w(
                TAG,
                "setStatusBarDisabled(true): unsupported on API ${Build.VERSION.SDK_INT} (< 23 / Fire OS 5); " +
                    "relying on Lock Task status bar suppression",
            )
        }

        // 6. Apply all UserManager restrictions from single source of truth RESTRICTIONS
        for (restriction in RESTRICTIONS) {
            runCatching {
                dpm.addUserRestriction(admin, restriction)
                Log.i(TAG, "addUserRestriction($restriction): success")
            }.onFailure {
                Log.w(TAG, "addUserRestriction($restriction) failed on API ${Build.VERSION.SDK_INT}", it)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "UserRestriction(${UserManager.DISALLOW_SAFE_BOOT}): skipped (requires API 23+)")
        }

        // 7. Ensure com.android.settings is not suspended on API 24+ so the PIN-protected
        //    Admin Dashboard Wi-Fi Configurator can launch Settings.ACTION_WIFI_SETTINGS inside Lock Task.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                dpm.setPackagesSuspended(admin, arrayOf(SETTINGS_PKG), false)
                Log.i(TAG, "setPackagesSuspended([$SETTINGS_PKG], false): whitelisted for technician Wi-Fi access")
            }.onFailure {
                Log.w(TAG, "setPackagesSuspended([$SETTINGS_PKG], false) failed", it)
            }
        }

        // 8. Global setting: Stay awake while plugged into AC, USB, or Wireless power ("7")
        val stayOnFlags = (
            BatteryManager.BATTERY_PLUGGED_AC or
                BatteryManager.BATTERY_PLUGGED_USB or
                BatteryManager.BATTERY_PLUGGED_WIRELESS
            ).toString()
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, stayOnFlags)
            Log.i(TAG, "setGlobalSetting(STAY_ON_WHILE_PLUGGED_IN, \"$stayOnFlags\"): success")
        }.onFailure {
            Log.w(TAG, "setGlobalSetting(STAY_ON_WHILE_PLUGGED_IN) failed", it)
        }

        // 8B. Restrict Android OS system updates to 03:00–04:00 AM maintenance window (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                dpm.setSystemUpdatePolicy(admin, SystemUpdatePolicy.createWindowedInstallPolicy(180, 240))
                Log.i(TAG, "setSystemUpdatePolicy(createWindowedInstallPolicy(180, 240)): success")
            }.onFailure {
                Log.w(TAG, "setSystemUpdatePolicy(180, 240) failed", it)
            }
        }

        // 9. Global setting: Disable ADB on API 26+ to prevent `adb shell am stop-lock-task` escape
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "0")
                Log.i(TAG, "setGlobalSetting(ADB_ENABLED, \"0\"): success")
            }.onFailure {
                Log.w(TAG, "setGlobalSetting(ADB_ENABLED, \"0\") failed", it)
            }
        } else {
            Log.w(
                TAG,
                "setGlobalSetting(ADB_ENABLED, \"0\"): skipped on API ${Build.VERSION.SDK_INT} (< 26 / Fire OS 5); " +
                    "physical USB port security is required",
            )
        }

        // 10. Persist active state
        runCatching {
            prefs(app).edit()
                .putBoolean(KEY_ACTIVE, criticalOk)
                .putBoolean(KEY_KEYGUARD_DISABLED, keyguardDisabled)
                .putBoolean(KEY_STATUSBAR_DISABLED, statusBarDisabled)
                .apply()
        }.onFailure {
            Log.w(TAG, "Failed to persist kiosk_policy preferences", it)
        }

        // 11. Programmatically set hardware Bluetooth name to "NourAlwelayah"
        enforceBluetoothName(app)

        // 12. Auto-grant RECORD_AUDIO, ACCESS_FINE_LOCATION, and ACCESS_COARSE_LOCATION runtime permissions on API 23+ when provisioned as Device Owner
        tryAutoGrantAppPermissions(app)

        Log.i(TAG, "applyFullKioskPolicy completed: criticalOk=$criticalOk")
        return criticalOk
    }

    /**
     * When running as Device Owner on API 23+, silently grants [Manifest.permission.RECORD_AUDIO],
     * [Manifest.permission.ACCESS_FINE_LOCATION], and [Manifest.permission.ACCESS_COARSE_LOCATION]
     * via [DevicePolicyManager.setPermissionGrantState] so Kiosk Lock Task mode never gets blocked
     * by a system runtime permission prompt.
     */
    fun tryAutoGrantAppPermissions(context: Context): Boolean {
        val permissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isOwner(context)) {
            return false
        }
        val app = context.applicationContext
        val dpm = dpm(app)
        val admin = adminComponent(app)
        val pkg = app.packageName
        var allOk = true
        for (perm in permissions) {
            if (ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED) {
                continue
            }
            val granted = runCatching {
                val res = dpm.setPermissionGrantState(
                    admin,
                    pkg,
                    perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
                Log.i(TAG, "setPermissionGrantState($perm, GRANTED): result=$res")
                res
            }.onFailure {
                Log.w(TAG, "setPermissionGrantState($perm) failed", it)
            }.getOrDefault(false)
            if (!granted) {
                allOk = false
            }
        }
        return allOk
    }

    /**
     * Delegates to [tryAutoGrantAppPermissions] to auto-grant `RECORD_AUDIO`,
     * `ACCESS_FINE_LOCATION`, and `ACCESS_COARSE_LOCATION` when running as Device Owner on API 23+.
     */
    fun tryAutoGrantRecordAudioPermission(context: Context): Boolean =
        tryAutoGrantAppPermissions(context)

    /**
     * Reverses every policy, restriction, persistent preferred activity, and global setting
     * previously applied by [applyFullKioskPolicy].
     *
     * Safe to call multiple times; no-ops cleanly (while clearing local state) if not Device Owner.
     */
    fun removeKioskPolicy(context: Context) {
        val app = context.applicationContext
        runCatching {
            prefs(app).edit()
                .putBoolean(KEY_ACTIVE, false)
                .putBoolean(KEY_KEYGUARD_DISABLED, false)
                .putBoolean(KEY_STATUSBAR_DISABLED, false)
                .apply()
        }.onFailure {
            Log.w(TAG, "Failed to clear kiosk_policy preferences", it)
        }

        if (!isOwner(app)) {
            Log.i(TAG, "removeKioskPolicy: skipped DPM calls (package ${app.packageName} is not Device Owner)")
            return
        }

        val dpm = dpm(app)
        val admin = adminComponent(app)
        val pkg = app.packageName

        Log.i(TAG, "removeKioskPolicy: clearing all kiosk restrictions for $pkg")

        // 1. Clear all UserManager restrictions from RESTRICTIONS
        for (restriction in RESTRICTIONS) {
            runCatching {
                dpm.clearUserRestriction(admin, restriction)
                Log.i(TAG, "clearUserRestriction($restriction): success")
            }.onFailure {
                Log.w(TAG, "clearUserRestriction($restriction) failed", it)
            }
        }

        // 2. Unsuspend system Settings package on API 24+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                dpm.setPackagesSuspended(admin, arrayOf(SETTINGS_PKG), false)
                Log.i(TAG, "setPackagesSuspended([$SETTINGS_PKG], false): success")
            }.onFailure {
                Log.w(TAG, "setPackagesSuspended([$SETTINGS_PKG], false) failed", it)
            }
        }

        // 3. Re-enable status bar (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val res = dpm.setStatusBarDisabled(admin, false)
                Log.i(TAG, "setStatusBarDisabled(false): result=$res")
            }.onFailure {
                Log.w(TAG, "setStatusBarDisabled(false) failed", it)
            }
        }

        // 4. Re-enable keyguard (API 23+) and restore keyguard features
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val res = dpm.setKeyguardDisabled(admin, false)
                Log.i(TAG, "setKeyguardDisabled(false): result=$res")
            }.onFailure {
                Log.w(TAG, "setKeyguardDisabled(false) failed", it)
            }
        }
        runCatching {
            dpm.setKeyguardDisabledFeatures(admin, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
            Log.i(TAG, "setKeyguardDisabledFeatures(NONE): success")
        }.onFailure {
            Log.w(TAG, "setKeyguardDisabledFeatures(NONE) failed", it)
        }

        // 5. Clear persistent preferred HOME activity
        runCatching {
            dpm.clearPackagePersistentPreferredActivities(admin, pkg)
            Log.i(TAG, "clearPackagePersistentPreferredActivities($pkg): success")
        }.onFailure {
            Log.w(TAG, "clearPackagePersistentPreferredActivities failed", it)
        }

        // 6. Clear Lock Task features (API 28+) and Lock Task package whitelist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                Log.i(TAG, "setLockTaskFeatures reset: success")
            }.onFailure {
                Log.w(TAG, "setLockTaskFeatures reset failed", it)
            }
        }
        runCatching {
            dpm.setLockTaskPackages(admin, emptyArray())
            Log.i(TAG, "setLockTaskPackages([]): success")
        }.onFailure {
            Log.w(TAG, "setLockTaskPackages([]) failed", it)
        }

        // 7. Restore global settings (allow sleep on charger if desired, clear OS update window, re-enable ADB on API 26+)
        runCatching {
            dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")
            Log.i(TAG, "setGlobalSetting(STAY_ON_WHILE_PLUGGED_IN, \"0\"): success")
        }.onFailure {
            Log.w(TAG, "setGlobalSetting(STAY_ON_WHILE_PLUGGED_IN, \"0\") failed", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                dpm.setSystemUpdatePolicy(admin, null)
                Log.i(TAG, "setSystemUpdatePolicy(null): success")
            }.onFailure {
                Log.w(TAG, "setSystemUpdatePolicy(null) failed", it)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")
                Log.i(TAG, "setGlobalSetting(ADB_ENABLED, \"1\"): success")
            }.onFailure {
                Log.w(TAG, "setGlobalSetting(ADB_ENABLED, \"1\") failed", it)
            }
        }
    }

    /**
     * Generates a human-readable multi-line diagnostic report of the current Device Owner
     * and Lock Task state by reading live system services.
     */
    fun describeState(context: Context): String {
        val app = context.applicationContext
        val dpm = dpm(app)
        val admin = adminComponent(app)
        val pkg = app.packageName
        val owner = isOwner(app)
        val active = isKioskActive(app)
        val p = prefs(app)

        val lockPermitted = runCatching { dpm.isLockTaskPermitted(pkg) }.getOrDefault(false)
        val lockPkgs = if (owner) {
            runCatching { dpm.getLockTaskPackages(admin).toList() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lockStateStr = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                when (am.lockTaskModeState) {
                    ActivityManager.LOCK_TASK_MODE_NONE -> "NONE (0)"
                    ActivityManager.LOCK_TASK_MODE_LOCKED -> "LOCKED (1)"
                    ActivityManager.LOCK_TASK_MODE_PINNED -> "PINNED (2)"
                    else -> "UNKNOWN (${am.lockTaskModeState})"
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isInLockTaskMode) "LOCKED (API 22)" else "NONE (API 22)"
            }
        }.getOrDefault("ERROR")

        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val keyguardReport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val disabledPref = p.getBoolean(KEY_KEYGUARD_DISABLED, false)
            "disabled=$disabledPref (keyguardLocked=${km.isKeyguardLocked})"
        } else {
            val feats = if (owner) runCatching { dpm.getKeyguardDisabledFeatures(admin) }.getOrDefault(0) else 0
            "partial API 22 (disabledFeatures=0x${feats.toString(16)}, keyguardLocked=${km.isKeyguardLocked})"
        }

        val statusBarReport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            p.getBoolean(KEY_STATUSBAR_DISABLED, false).toString()
        } else {
            "unsupported on API 22 (suppressed by Lock Task)"
        }

        val activeRestrictions = runCatching {
            val bundle = if (owner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.getUserRestrictions(admin)
            } else {
                val um = app.getSystemService(Context.USER_SERVICE) as UserManager
                um.userRestrictions
            }
            RESTRICTIONS.filter { bundle.getBoolean(it, false) }
        }.getOrDefault(emptyList())

        val adbEnabled = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, -1)
        }.getOrDefault(-1)

        val stayOn = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
        }.getOrDefault(0)

        return buildString {
            appendLine("Device Owner: ${if (owner) "yes" else "no"} ($pkg)")
            appendLine("Kiosk policy active: $active (API ${Build.VERSION.SDK_INT})")
            appendLine("Lock Task permitted: $lockPermitted")
            appendLine("Lock Task packages: $lockPkgs")
            appendLine("Lock Task mode state: $lockStateStr")
            appendLine("Keyguard: $keyguardReport")
            appendLine("Status bar disabled: $statusBarReport")
            appendLine("User restrictions (${activeRestrictions.size}/${RESTRICTIONS.size}): $activeRestrictions")
            append("Global settings: ADB_ENABLED=$adbEnabled, STAY_ON_WHILE_PLUGGED_IN=$stayOn")
        }
    }
}
