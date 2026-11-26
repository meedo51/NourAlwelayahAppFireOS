package com.nouralwelayah.clock

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.nouralwelayah.clock.alert.AlertScheduler
import com.nouralwelayah.clock.alert.AlertService
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.ota.KbOtaManager
import com.nouralwelayah.clock.ota.OtaHeartbeatWorker
import com.nouralwelayah.clock.ota.OtaIntegrityHealthCheck
import com.nouralwelayah.clock.ota.OtaManager
import java.util.concurrent.atomic.AtomicBoolean

class PrayerClockApp : Application() {
    private val unlockedInitDone = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Store.init uses Device Protected Storage automatically when UserManager.isUserUnlocked is false
        runCatching { Store.init(this) }
            .onFailure { Log.w(TAG, "Store.init during Direct Boot deferred: ${it.message}") }
        runCatching { AlertService.createChannels(this) }

        if (isUserUnlocked(this)) {
            completePostUnlockInitialization()
        } else {
            Log.i(TAG, "Device is in Direct Boot (user locked); deferring credential-storage init until ACTION_USER_UNLOCKED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val unlockReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                            runCatching { unregisterReceiver(this) }
                            completePostUnlockInitialization()
                        }
                    }
                }
                runCatching {
                    registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
                }
            }
        }
    }

    private fun isUserUnlocked(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            um?.isUserUnlocked ?: true
        } else {
            true
        }
    }

    private fun completePostUnlockInitialization() {
        if (!unlockedInitDone.compareAndSet(false, true)) return
        runCatching {
            Store.init(this)
            AppFolders.setup(this)          // PrayerClock/azan, music, ... (with storage access)
            MediaLibrary.init(this)
            AlertService.createChannels(this)
            AlertScheduler.reschedule(this)
            OtaIntegrityHealthCheck.runAsync(this)
            OtaManager.startPolling(this)
            KbOtaManager.startPeriodicChecks(this)
            OtaHeartbeatWorker.schedulePeriodicAndStartupHeartbeat(this)
        }.onFailure { e ->
            Log.e(TAG, "Post-unlock initialization error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "PrayerClockApp"
    }
}

