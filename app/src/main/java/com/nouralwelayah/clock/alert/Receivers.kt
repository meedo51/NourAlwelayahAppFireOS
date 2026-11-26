package com.nouralwelayah.clock.alert

import com.nouralwelayah.clock.Sys
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
// MODIFIED: Added Build and UserManager imports for Direct Boot (LOCKED_BOOT_COMPLETED) checks
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.data.Store
// MODIFIED: Imported KioskPolicy to re-apply kiosk lockdown on boot and app update
import com.nouralwelayah.clock.kiosk.KioskPolicy

/** The exact alarm went off: start the azan / alarm, then schedule the next one. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlertScheduler.ACTION_FIRE) return
        Store.init(context)
        val kind = intent.getIntExtra(AlertScheduler.EXTRA_KIND, -1)
        val at = intent.getLongExtra(AlertScheduler.EXTRA_AT, 0L)
        val lateSec = (System.currentTimeMillis() - at) / 1000
        val limit = if (kind == AlertScheduler.KIND_AZAN) AlertScheduler.MISSED_SEC else 5 * 60

        when {
            lateSec > limit -> Log.w("AlertReceiver", "kind $kind is ${lateSec}s late - skipped")
            kind == AlertScheduler.KIND_AZAN -> start(context, AlertService.azanIntent(
                context, intent.getIntExtra(AlertScheduler.EXTRA_AZAN, 0),
                intent.getIntExtra(AlertScheduler.EXTRA_PRAYER_MIN, -1), test = false))
            kind == AlertScheduler.KIND_ALARM -> {
                val id = intent.getLongExtra(AlertScheduler.EXTRA_ALARM_ID, -1L)
                disableIfOnce(id)
                start(context, AlertService.alarmIntent(context, id))
            }
            kind == AlertScheduler.KIND_SNOOZE -> {
                val id = Store.snoozeAlarmId
                Store.snoozeUntil = 0L
                start(context, AlertService.alarmIntent(context, id))
            }
        }
        AlertScheduler.reschedule(context)
    }

    private fun start(context: Context, i: Intent) = ContextCompat.startForegroundService(context, i)

    /** Like checkAlarms(): a date alarm or a "once" alarm switches itself off after ringing. */
    private fun disableIfOnce(id: Long) {
        val list = Store.alarms.value
        val a = list.firstOrNull { it.id == id } ?: return
        if (a.useDate || a.days == 0) Store.saveAlarms(list.map { if (it.id == id) it.copy(enabled = false) else it })
    }
}

/** Boot, locked boot, app update, clock or time zone change: re-apply kiosk policy and plan the next alert. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val unlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            um.isUserUnlocked
        } else {
            true
        }

        // MODIFIED: Handle BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, and MY_PACKAGE_REPLACED for Kiosk re-entry
        val isBootOrUpdate = action == Intent.ACTION_BOOT_COMPLETED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && action == Intent.ACTION_LOCKED_BOOT_COMPLETED) ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (isBootOrUpdate) {
            val shouldApplyKiosk = if (unlocked) {
                runCatching {
                    Store.init(context)
                    Store.settings.value.kioskMode || KioskPolicy.isKioskActive(context)
                }.getOrDefault(KioskPolicy.isKioskActive(context))
            } else {
                KioskPolicy.isKioskActive(context)
            }

            if (shouldApplyKiosk && KioskPolicy.isOwner(context)) {
                KioskPolicy.applyFullKioskPolicy(context)
            }

            if (unlocked) {
                com.nouralwelayah.clock.kiosk.WatchdogService.start(context)
            }

            if (KioskPolicy.isKioskActive(context) || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                Log.i("BootReceiver", "Kiosk/Update active on $action: launching MainActivity with NEW_TASK | CLEAR_TASK")
                val kioskLaunch = Intent(context, com.nouralwelayah.clock.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                runCatching { context.startActivity(kioskLaunch) }
                    .onFailure { Log.w("BootReceiver", "Kiosk startActivity failed on $action", it) }
            }
        }

        // MODIFIED: Preserve existing AlertScheduler.reschedule and wakeTablet once credential storage is unlocked
        if (unlocked) {
            AlertScheduler.reschedule(context)
            if (action == Intent.ACTION_BOOT_COMPLETED &&
                com.nouralwelayah.clock.isTablet(context) &&
                !KioskPolicy.isKioskActive(context)
            ) {
                wakeTablet(context)
            }
        }
    }

    /**
     * Tablet = wall clock: after a restart the remotes (Wi-Fi page, Bluetooth) start at once and the clock opens.
     * Android blocks a receiver (and an alarm) from opening a screen unless the app may "Display over other apps"
     * (Settings > permissions); without it, a silent full-screen notification (only if notifications are on).
     * MainActivity shows over the swipe lock screen and removes the notification (BOOT_NOTIFICATION).
     */
    private fun wakeTablet(c: Context) {
        Log.i("BootReceiver", "tablet started: remotes on, opening the clock")
        runCatching { com.nouralwelayah.clock.media.MediaRemoteServer.start(c) }
        val main = Intent(c, com.nouralwelayah.clock.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        com.nouralwelayah.clock.wakeScreen(c)      // the screen may have gone off on the lock screen meanwhile
        if ((android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c)) && runCatching { c.startActivity(main) }.isSuccess) return
        val nm = Sys.svc(c, android.app.NotificationManager::class.java)
        com.nouralwelayah.clock.ifChannels {
            nm.createNotificationChannel(android.app.NotificationChannel(WAKE_CHANNEL, "Open the clock after a restart",
                android.app.NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
        }
        val open = android.app.PendingIntent.getActivity(c, 7,
            Intent(c, com.nouralwelayah.clock.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val n = androidx.core.app.NotificationCompat.Builder(c, WAKE_CHANNEL)
            .setSmallIcon(com.nouralwelayah.clock.R.drawable.ic_notification)
            .setContentTitle("Nour Alwelayah")
            .setContentText("Opening the clock")
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { nm.notify(BOOT_NOTIFICATION, n) }.onFailure { Log.w("BootReceiver", "could not open the clock", it) }
    }

    companion object {
        const val BOOT_NOTIFICATION = 77
        private const val WAKE_CHANNEL = "boot_open"
    }
}
