package com.nouralwelayah.clock.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nouralwelayah.clock.MainActivity
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.Sys
import com.nouralwelayah.clock.ifChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Immortal Kiosk Watchdog Foreground Service.
 *
 * Continuously monitors [MainActivity.isForeground] every 3 seconds and immediately brings
 * [MainActivity] back to the front with [Intent.FLAG_ACTIVITY_NEW_TASK] and
 * [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT] if the appliance UI leaves the foreground.
 */
class WatchdogService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        promoteToForeground()
        startWatchdogLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        promoteToForeground()
        if (watchdogJob?.isActive != true) {
            startWatchdogLoop()
        }
        return START_STICKY
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val anyAppUiVisible = MainActivity.isForeground ||
                    MainActivity.isAdminSessionActive ||
                    com.nouralwelayah.clock.media.MediaActivity.isMediaForeground ||
                    com.nouralwelayah.clock.alert.AlertState.current.value != null
                if (!anyAppUiVisible && KioskPolicy.isKioskActive(this@WatchdogService)) {
                    runCatching {
                        val rescueIntent = Intent(this@WatchdogService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        startActivity(rescueIntent)
                    }.onFailure { e ->
                        Log.w(TAG, "Watchdog failed to reorder MainActivity to front: ${e.message}")
                    }
                }
            }
        }
    }

    private fun ensureChannel() {
        ifChannels {
            val nm = Sys.svc(this, NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nour Alwelayah Appliance Guard",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the Nour Alwelayah appliance active in kiosk mode"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildSilentNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.kiosk_initializing))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun promoteToForeground() {
        val notification = buildSilentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }.onFailure {
                runCatching { startForeground(NOTIFICATION_ID, notification) }
            }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "WatchdogService foreground timeout signaled (startId=$startId, fgsType=$fgsType); stopping cleanly.")
        watchdogJob?.cancel()
        stopSelf(startId)
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WatchdogService"
        private const val CHANNEL_ID = "nour_alwelayah_watchdog"
        private const val NOTIFICATION_ID = 9001
        private const val WATCHDOG_INTERVAL_MS = 3_000L

        fun start(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                androidx.core.content.ContextCompat.startForegroundService(
                    appCtx,
                    Intent(appCtx, WatchdogService::class.java)
                )
            }.onFailure { e ->
                Log.w(TAG, "Unable to start WatchdogService: ${e.message}")
            }
        }
    }
}
