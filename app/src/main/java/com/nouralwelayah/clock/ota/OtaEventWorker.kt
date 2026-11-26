package com.nouralwelayah.clock.ota

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager [CoroutineWorker] that dispatches one-shot OTA lifecycle telemetry events
 * (`DOWNLOAD_STARTED`, `DOWNLOAD_VERIFIED`, `INSTALL_SUCCEEDED`, `INSTALL_FAILED`, `HEARTBEAT`)
 * to `https://manager.alwelayah.xus.me/api/v1/telemetry/ota` with `NetworkType.CONNECTED`
 * and `BackoffPolicy.EXPONENTIAL`.
 */
class OtaEventWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val event = inputData.getString(KEY_EVENT)?.trim().orEmpty()
        if (event.isEmpty()) {
            return@withContext Result.failure()
        }

        val deviceId = DeviceIdProvider.getDeviceId(ctx)
        val currentVersionCode = OtaUpdateManager.getCurrentVersionCode(ctx)
        val currentVersionName = OtaUpdateManager.getCurrentVersionName(ctx)
        val channel = inputData.getString(KEY_CHANNEL)?.takeIf { it.isNotBlank() } ?: runCatching {
            Store.init(ctx)
            AppSettings.normalizeOtaChannel(Store.settings.value.otaChannel)
        }.getOrDefault(AppSettings.OTA_CHANNEL_STABLE)
        val kbVersion = runCatching { KbOtaManager.getInstalledKbVersion(ctx) }
            .getOrDefault(KbOtaManager.DEFAULT_BUNDLED_KB_VERSION)
        val isDeviceOwner = OtaUpdateManager.isDeviceOwner(ctx)

        val targetVersionCode = inputData.getLong(KEY_TARGET_VERSION_CODE, -1L).takeIf { it > 0L }
        val targetVersionName = inputData.getString(KEY_TARGET_VERSION_NAME)?.takeIf { it.isNotBlank() }
        val prevVersionCode = inputData.getLong(KEY_PREV_VERSION_CODE, -1L).takeIf { it > 0L }
        val prevVersionName = inputData.getString(KEY_PREV_VERSION_NAME)?.takeIf { it.isNotBlank() }
        val errorMessage = inputData.getString(KEY_ERROR_MESSAGE)?.takeIf { it.isNotBlank() }

        val timestampSec = System.currentTimeMillis() / 1000L
        val payloadJson = OtaTelemetryClient.buildPayloadJson(
            deviceId = deviceId,
            event = event,
            versionCode = currentVersionCode,
            versionName = currentVersionName,
            channel = channel,
            kbVersion = kbVersion,
            targetVersionCode = targetVersionCode,
            targetVersionName = targetVersionName,
            previousVersionCode = prevVersionCode,
            previousVersionName = prevVersionName,
            errorMessage = errorMessage,
            isDeviceOwner = isDeviceOwner,
            timestampSec = timestampSec,
        )

        when (
            val sendRes = OtaTelemetryClient.sendSignedTelemetrySync(
                rawJsonBody = payloadJson,
                timestamp = timestampSec.toString(),
            )
        ) {
            is TelemetrySendResult.Sent,
            is TelemetrySendResult.SkippedNoSecret -> Result.success()
            is TelemetrySendResult.TransientFailure -> {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Log.w(TAG, "Exhausted $MAX_RETRY_ATTEMPTS telemetry retries for event=$event: ${sendRes.message}")
                    Result.failure()
                }
            }
            is TelemetrySendResult.PermanentFailure -> Result.failure()
        }
    }

    companion object {
        private const val TAG = "OtaEventWorker"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val BACKOFF_INITIAL_SECONDS = 30L

        const val KEY_EVENT = "ota_telemetry_event"
        const val KEY_CHANNEL = "ota_telemetry_channel"
        const val KEY_TARGET_VERSION_CODE = "ota_target_version_code"
        const val KEY_TARGET_VERSION_NAME = "ota_target_version_name"
        const val KEY_PREV_VERSION_CODE = "ota_prev_version_code"
        const val KEY_PREV_VERSION_NAME = "ota_prev_version_name"
        const val KEY_ERROR_MESSAGE = "ota_error_message"

        private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Enqueues a resilient one-time [OtaEventWorker] request with `NetworkType.CONNECTED` and
         * exponential backoff (`BackoffPolicy.EXPONENTIAL`).
         * Falls back to an immediate background coroutine dispatch if `WorkManager` is unavailable
         * (e.g., during early Direct Boot or unit tests).
         */
        fun enqueue(
            context: Context,
            event: String,
            targetVersionCode: Long? = null,
            targetVersionName: String? = null,
            previousVersionCode: Long? = null,
            previousVersionName: String? = null,
            errorMessage: String? = null,
            channelOverride: String? = null,
        ) {
            val appCtx = context.applicationContext ?: context
            val dataBuilder = Data.Builder()
                .putString(KEY_EVENT, event)
            if (!channelOverride.isNullOrBlank()) {
                dataBuilder.putString(KEY_CHANNEL, AppSettings.normalizeOtaChannel(channelOverride))
            }
            if (targetVersionCode != null && targetVersionCode > 0L) {
                dataBuilder.putLong(KEY_TARGET_VERSION_CODE, targetVersionCode)
            }
            if (!targetVersionName.isNullOrBlank()) {
                dataBuilder.putString(KEY_TARGET_VERSION_NAME, targetVersionName)
            }
            if (previousVersionCode != null && previousVersionCode > 0L) {
                dataBuilder.putLong(KEY_PREV_VERSION_CODE, previousVersionCode)
            }
            if (!previousVersionName.isNullOrBlank()) {
                dataBuilder.putString(KEY_PREV_VERSION_NAME, previousVersionName)
            }
            if (!errorMessage.isNullOrBlank()) {
                dataBuilder.putString(KEY_ERROR_MESSAGE, errorMessage.take(512))
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OtaEventWorker>()
                .setConstraints(constraints)
                .setInputData(dataBuilder.build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_INITIAL_SECONDS,
                    TimeUnit.SECONDS,
                )
                .addTag("ota_telemetry_$event")
                .build()

            val uniqueWorkName = "ota_event_${event}_${targetVersionCode ?: 0L}_${System.currentTimeMillis()}"
            runCatching {
                WorkManager.getInstance(appCtx)
                    .enqueueUniqueWork(uniqueWorkName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }.onFailure { e ->
                runCatching {
                    Log.w(TAG, "WorkManager enqueue fallback for event=$event: ${e.message}")
                }
                fallbackScope.launch {
                    runCatching {
                        val timestampSec = System.currentTimeMillis() / 1000L
                        val payloadJson = OtaTelemetryClient.buildPayloadJson(
                            deviceId = DeviceIdProvider.getDeviceId(appCtx),
                            event = event,
                            versionCode = OtaUpdateManager.getCurrentVersionCode(appCtx),
                            versionName = OtaUpdateManager.getCurrentVersionName(appCtx),
                            channel = channelOverride ?: AppSettings.OTA_CHANNEL_STABLE,
                            kbVersion = runCatching { KbOtaManager.getInstalledKbVersion(appCtx) }
                                .getOrDefault(KbOtaManager.DEFAULT_BUNDLED_KB_VERSION),
                            targetVersionCode = targetVersionCode,
                            targetVersionName = targetVersionName,
                            previousVersionCode = previousVersionCode,
                            previousVersionName = previousVersionName,
                            errorMessage = errorMessage,
                            isDeviceOwner = OtaUpdateManager.isDeviceOwner(appCtx),
                            timestampSec = timestampSec,
                        )
                        OtaTelemetryClient.sendSignedTelemetrySync(
                            rawJsonBody = payloadJson,
                            timestamp = timestampSec.toString(),
                        )
                    }
                }
            }
        }
    }
}
