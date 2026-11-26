package com.nouralwelayah.clock.ota

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic 6-hour [CoroutineWorker] that reports appliance `HEARTBEAT` telemetry
 * (including `deviceId`, `versionCode`, `versionName`, `channel`, and active `kbVersion`)
 * to `https://manager.alwelayah.xus.me/api/v1/telemetry/ota` signed with HMAC-SHA256.
 *
 * Also checks [OtaTelemetryClient.verifyAndReportPendingInstallIfCompleted] on each heartbeat cycle
 * in case a package upgrade completed while offline.
 */
class OtaHeartbeatWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext

        // Check if a pending OTA upgrade completed prior to this heartbeat
        runCatching {
            OtaTelemetryClient.verifyAndReportPendingInstallIfCompleted(ctx)
        }

        val deviceId = DeviceIdProvider.getDeviceId(ctx)
        val versionCode = OtaUpdateManager.getCurrentVersionCode(ctx)
        val versionName = OtaUpdateManager.getCurrentVersionName(ctx)
        val channel = runCatching {
            Store.init(ctx)
            AppSettings.normalizeOtaChannel(Store.settings.value.otaChannel)
        }.getOrDefault(AppSettings.OTA_CHANNEL_STABLE)
        val kbVersion = runCatching { KbOtaManager.getInstalledKbVersion(ctx) }
            .getOrDefault(KbOtaManager.DEFAULT_BUNDLED_KB_VERSION)
        val isDeviceOwner = OtaUpdateManager.isDeviceOwner(ctx)

        val timestampSec = System.currentTimeMillis() / 1000L
        val payloadJson = OtaTelemetryClient.buildPayloadJson(
            deviceId = deviceId,
            event = OtaTelemetryEvent.HEARTBEAT,
            versionCode = versionCode,
            versionName = versionName,
            channel = channel,
            kbVersion = kbVersion,
            isDeviceOwner = isDeviceOwner,
            timestampSec = timestampSec,
        )

        when (
            OtaTelemetryClient.sendSignedTelemetrySync(
                rawJsonBody = payloadJson,
                timestamp = timestampSec.toString(),
            )
        ) {
            is TelemetrySendResult.Sent,
            is TelemetrySendResult.SkippedNoSecret -> Result.success()
            is TelemetrySendResult.TransientFailure -> {
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
            is TelemetrySendResult.PermanentFailure -> Result.success()
        }
    }

    companion object {
        private const val TAG = "OtaHeartbeatWorker"
        const val UNIQUE_WORK_NAME = "nour_ota_heartbeat_periodic_6h"
        private const val HEARTBEAT_INTERVAL_HOURS = 6L
        private const val BACKOFF_INITIAL_SECONDS = 60L

        /**
         * Schedules the 6-hour periodic [OtaHeartbeatWorker] in `WorkManager`, verifies any pending
         * post-upgrade `INSTALL_SUCCEEDED` record, and enqueues an immediate startup `HEARTBEAT`.
         */
        fun schedulePeriodicAndStartupHeartbeat(context: Context) {
            val appCtx = context.applicationContext ?: context

            // 1. Check if a pending OTA upgrade just finished (post-reboot / post-replace)
            runCatching {
                OtaTelemetryClient.verifyAndReportPendingInstallIfCompleted(appCtx)
            }

            // 2. Enqueue immediate startup HEARTBEAT via OtaEventWorker (NetworkType.CONNECTED)
            runCatching {
                OtaEventWorker.enqueue(
                    context = appCtx,
                    event = OtaTelemetryEvent.HEARTBEAT,
                )
            }

            // 3. Register 6-hour PeriodicWorkRequest
            runCatching {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val periodicRequest = PeriodicWorkRequestBuilder<OtaHeartbeatWorker>(
                    HEARTBEAT_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        BACKOFF_INITIAL_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    .addTag(UNIQUE_WORK_NAME)
                    .build()

                WorkManager.getInstance(appCtx).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest,
                )
                Log.i(TAG, "Scheduled 6-hour periodic OTA heartbeat worker ($UNIQUE_WORK_NAME)")
            }.onFailure { e ->
                Log.w(TAG, "Unable to schedule periodic OtaHeartbeatWorker: ${e.message}")
            }
        }
    }
}
