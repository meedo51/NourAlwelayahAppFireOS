package com.nouralwelayah.clock.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.Sys
import com.nouralwelayah.clock.alert.AlertScheduler
import com.nouralwelayah.clock.alert.AlertService
import com.nouralwelayah.clock.alert.AlertState
import com.nouralwelayah.clock.core.AzanPrayer
import com.nouralwelayah.clock.core.CALC_METHODS
import com.nouralwelayah.clock.core.Hijri
import com.nouralwelayah.clock.core.HijriDate
import com.nouralwelayah.clock.core.Planner
import com.nouralwelayah.clock.core.Prayer
import com.nouralwelayah.clock.core.fmtAngle
import com.nouralwelayah.clock.core.fmtMinutes
import com.nouralwelayah.clock.core.isSunsetAngle
import com.nouralwelayah.clock.data.Alarm
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Folder
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.media.MediaActivity
import com.nouralwelayah.clock.media.MediaHub
import com.nouralwelayah.clock.media.MediaRemoteServer
import com.nouralwelayah.clock.media.MediaService
import com.nouralwelayah.clock.ota.OtaManager
import com.nouralwelayah.clock.system.FileSystemManager
import com.nouralwelayah.clock.system.WifiController
import com.nouralwelayah.clock.ui.cityName
import com.nouralwelayah.clock.ui.getLocation
import com.nouralwelayah.clock.ui.listWallpaperFiles
import com.nouralwelayah.clock.ui.picturesChanged
import com.nouralwelayah.clock.web.Hotspot
import com.nouralwelayah.clock.web.WebRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Result of dispatching a voice command through [VoiceCommandDispatcher].
 */
sealed class DispatchResult {
    data class Success(
        val spokenResponse: String,
        val sourceRef: String,
        val intentId: String = "",
        val actionType: String = "",
        val commandPayload: String? = null,
        val score: Float = 1.0f,
    ) : DispatchResult()

    data object NoMatch : DispatchResult()

    data class Error(
        val message: String,
    ) : DispatchResult()
}

/**
 * Deterministic Offline Voice Command Dispatcher for the Nour Alwelayah Appliance.
 *
 * Routes all 122 matched [KbMatch] intents across 10 subcategories (`navigation`, `clock_queries`,
 * `prayer_times`, `azan_control`, `alarms`, `media_player`, `volume_audio`, `display`,
 * `connectivity`, `system_settings`) by [KbMatch.parsedActionType]:
 * - [KbActionType.APP_COMMAND]: Executes deterministic appliance actions against [Store],
 *   Media3 [MediaService], [P4Tablet] tab navigation via [MediaHub], [AlertService],
 *   [Planner], [Hijri], [WifiController], [MediaRemoteServer], [Hotspot], and hardware controllers.
 * - [KbActionType.PLAY_AUDIO]: Validates `audioAssetAr` / `audioAssetEn` via
 *   [FileSystemManager.validatePath] against directory traversal and starts playback on Media3.
 * - [KbActionType.TTS_ONLY]: Returns the localized `responseAr` or `responseEn` string with `sourceRef`.
 */
class VoiceCommandDispatcher(
    context: Context,
    private val retrievalEngine: KbRetrievalEngine? = null,
) {
    private val appContext: Context = context.applicationContext
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var previousVolumeFraction: Float = 0.7f

    @Volatile
    private var sleepTimerJob: Job? = null

    /**
     * Retrieves the highest-confidence [KbMatch] from [retrievalEngine] and dispatches it.
     */
    suspend fun queryAndDispatch(
        rawQuery: String,
        minScoreThreshold: Float = KbRetrievalEngine.DEFAULT_MIN_SCORE_THRESHOLD,
    ): DispatchResult {
        val engine = retrievalEngine
            ?: return DispatchResult.Error("Retrieval engine is not configured.")
        if (rawQuery.isBlank()) return DispatchResult.NoMatch
        val match = engine.retrieve(rawQuery, minScoreThreshold) ?: return DispatchResult.NoMatch
        return dispatch(match)
    }

    /**
     * Dispatches a resolved [KbMatch] deterministically. Returns [DispatchResult.NoMatch] if [match] is `null`.
     */
    suspend fun dispatch(match: KbMatch?): DispatchResult {
        if (match == null) return DispatchResult.NoMatch

        Store.init(appContext)
        val settings = Store.settings.value
        val isAr = match.detectedLang == "ar"
        val baseResponse = match.localizedResponse
        val sourceRef = match.localizedSourceRef

        return try {
            when (match.parsedActionType) {
                KbActionType.TTS_ONLY -> DispatchResult.Success(
                    spokenResponse = baseResponse,
                    sourceRef = sourceRef,
                    intentId = match.intentId,
                    actionType = match.actionType,
                    commandPayload = match.commandPayload,
                    score = match.score,
                )

                KbActionType.PLAY_AUDIO -> handlePlayAudio(match, isAr, baseResponse, sourceRef)

                KbActionType.APP_COMMAND -> handleAppCommand(match, settings, isAr, baseResponse, sourceRef)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error dispatching intent ${match.intentId} (${match.commandPayload})", e)
            DispatchResult.Error(
                message = if (isAr) {
                    "تعذر تنفيذ الأمر الصوتي: ${e.message ?: "خطأ غير معروف"}"
                } else {
                    "Failed to execute voice command: ${e.message ?: "unknown error"}"
                }
            )
        }
    }

    private suspend fun handlePlayAudio(
        match: KbMatch,
        isAr: Boolean,
        baseResponse: String,
        sourceRef: String,
    ): DispatchResult {
        val rawAssetPath = match.localizedAudioAsset?.trim().orEmpty()
        if (rawAssetPath.isEmpty()) {
            return DispatchResult.Error(
                if (isAr) "لم يتم تحديد ملف صوتي لهذا الأمر." else "No audio asset configured for this intent."
            )
        }

        val validatedFile = FileSystemManager.validatePath(appContext, rawAssetPath)
            ?: return DispatchResult.Error(
                if (isAr) "تم رفض مسار الملف الصوتي لدواعي الأمان." else "Rejected unsafe audio asset path."
            )

        val resolvedFile = when {
            validatedFile.exists() && validatedFile.isFile -> validatedFile
            else -> {
                val cleanName = rawAssetPath.substringAfterLast('/')
                val inAudios = FileSystemManager.validatePath(appContext, "audios/$cleanName")
                val inAzan = FileSystemManager.validatePath(appContext, "azan/$cleanName")
                when {
                    inAudios != null && inAudios.exists() && inAudios.isFile -> inAudios
                    inAzan != null && inAzan.exists() && inAzan.isFile -> inAzan
                    else -> null
                }
            }
        }

        if (resolvedFile == null) {
            return DispatchResult.Error(
                if (isAr) {
                    "الملف الصوتي غير موجود على الجهاز: ${validatedFile.name}"
                } else {
                    "Audio file not found on appliance storage: ${validatedFile.name}"
                }
            )
        }

        val played = playLocalFilesOnMedia3(listOf(resolvedFile), startIndex = 0)
        if (!played) {
            return DispatchResult.Error(
                if (isAr) "تعذر بدء مشغل الوسائط." else "Unable to start Media3 playback controller."
            )
        }

        return DispatchResult.Success(
            spokenResponse = baseResponse,
            sourceRef = sourceRef,
            intentId = match.intentId,
            actionType = match.actionType,
            commandPayload = match.commandPayload,
            score = match.score,
        )
    }

    private suspend fun handleAppCommand(
        match: KbMatch,
        s: AppSettings,
        isAr: Boolean,
        baseResponse: String,
        sourceRef: String,
    ): DispatchResult {
        val payload = match.commandPayload?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val now = ZonedDateTime.now(s.zone())
        val today = Planner.day(s, now.toLocalDate(), now.zone)

        val spoken: String = when {
            // =========================================================================
            // 1. NAVIGATION (12 intents)
            // =========================================================================
            payload == "NAV_TAB:CLOCK" || payload == "OPEN_TAB_HOME" ||
                payload == "DISPLAY_FULLSCREEN_CLOCK" || payload == "NAV_GO_BACK" -> {
                MediaHub.forceClockTab.value = true
                MediaHub.requestedTab.tryEmit("CLOCK")
                baseResponse
            }

            payload == "NAV_TAB:PRAYER" || payload == "OPEN_TAB_PRAYER" ||
                payload == "SET_PRAYER_CALC_METHOD" -> {
                MediaHub.requestedTab.tryEmit("PRAYER")
                baseResponse
            }

            payload == "NAV_TAB:ALARMS" || payload == "OPEN_TAB_ALARMS" ||
                payload == "ALARM_SET" || payload == "ALARM_DELETE" -> {
                MediaHub.requestedTab.tryEmit("ALARMS")
                baseResponse
            }

            payload == "NAV_TAB:PLAYER" || payload == "OPEN_TAB_PLAYER" -> {
                MediaHub.requestedTab.tryEmit("PLAYER")
                baseResponse
            }

            payload == "NAV_TAB:WIFI" || payload == "OPEN_WIFI_SETTINGS" ||
                payload == "BLE_PAIRING_MODE" -> {
                MediaHub.requestedTab.tryEmit("WIFI")
                baseResponse
            }

            payload == "NAV_TAB:SETTINGS" || payload == "OPEN_TAB_SETTINGS" ||
                payload == "OPEN_TAB_LOCATION" || payload == "OPEN_TAB_SYSTEM" ||
                payload == "SET_CITY" || payload == "DETECT_LOCATION" ||
                payload == "SET_HIJRI_OFFSET" || payload == "SET_VOLUME_LIMIT" ||
                payload == "DISPLAY_SET_INACTIVITY_TIMEOUT" -> {
                MediaHub.requestedTab.tryEmit("SETTINGS")
                baseResponse
            }

            payload == "NAV_PLAYER:AUDIOS" -> {
                MediaHub.requestedPlayerSection.value = "AUDIOS"
                MediaHub.requestedTab.tryEmit("PLAYER")
                baseResponse
            }

            payload == "NAV_PLAYER:VIDEOS" -> {
                MediaHub.requestedPlayerSection.value = "VIDEOS"
                MediaHub.requestedTab.tryEmit("PLAYER")
                baseResponse
            }

            payload == "NAV_PLAYER:YOUTUBE" -> {
                MediaHub.requestedPlayerSection.value = "YOUTUBE"
                MediaHub.requestedTab.tryEmit("PLAYER")
                baseResponse
            }

            payload == "NAV_MEDIA_FULLSCREEN" -> {
                MediaHub.fullScreen.value = true
                withContext(Dispatchers.Main) {
                    runCatching {
                        appContext.startActivity(
                            Intent(appContext, MediaActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
                baseResponse
            }

            payload == "NAV_WALLPAPER_GALLERY" -> {
                MediaHub.openWallpaperGallery.value = true
                MediaHub.requestedTab.tryEmit("SETTINGS")
                baseResponse
            }

            payload == "NAV_WIFI_MANAGER" -> {
                MediaHub.openWifiManager.value = true
                MediaHub.requestedTab.tryEmit("WIFI")
                baseResponse
            }

            // =========================================================================
            // 2. CLOCK QUERIES (14 intents)
            // =========================================================================
            payload == "QUERY_TIME" || payload == "GET_CURRENT_TIME" -> {
                val pattern = if (s.use24h) "HH:mm" else "h:mm a"
                val timeStr = now.format(DateTimeFormatter.ofPattern(pattern, if (isAr) Locale("ar") else Locale.US))
                if (isAr) {
                    "الساعة الآن في ${s.city} هي $timeStr."
                } else {
                    "The current time in ${s.city} is $timeStr."
                }
            }

            payload == "QUERY_GREGORIAN_DATE" || payload == "GET_GREGORIAN_DATE" -> {
                val gregFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", if (isAr) Locale("ar") else Locale.US)
                val gregStr = now.format(gregFmt)
                if (isAr) {
                    "التاريخ الميلادي اليوم هو $gregStr."
                } else {
                    "Today's Gregorian date is $gregStr."
                }
            }

            payload == "QUERY_HIJRI_DATE" || payload == "GET_HIJRI_DATE" -> {
                val hijriStr = today.hijri.format(appContext, if (isAr) "ar" else "en")
                if (isAr) {
                    "التاريخ الهجري اليوم هو $hijriStr."
                } else {
                    "Today's Hijri date is $hijriStr."
                }
            }

            payload == "QUERY_DATE_TIME" || payload == "GET_FULL_DATE" -> {
                val pattern = if (s.use24h) "HH:mm" else "h:mm a"
                val timeStr = now.format(DateTimeFormatter.ofPattern(pattern, if (isAr) Locale("ar") else Locale.US))
                val hijriStr = today.hijri.format(appContext, if (isAr) "ar" else "en")
                val gregFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", if (isAr) Locale("ar") else Locale.US)
                val gregStr = now.format(gregFmt)
                if (isAr) {
                    "الساعة الآن $timeStr. اليوم هو $gregStr، الموافق $hijriStr."
                } else {
                    "The time is $timeStr. Today is $gregStr, corresponding to $hijriStr."
                }
            }

            payload == "QUERY_BATTERY_STATUS" -> formatBatteryStatusResponse(isAr)

            payload == "QUERY_LOCATION" || payload == "GET_CURRENT_CITY" -> {
                val coords = String.format(Locale.US, "%.4f, %.4f", s.lat, s.lon)
                if (isAr) {
                    "الموقع المضبوط حالياً هو مدينة ${s.city} (الإحداثيات: $coords)."
                } else {
                    "The currently configured location is ${s.city} (coordinates: $coords)."
                }
            }

            payload == "QUERY_TIMEZONE" -> {
                val zone = s.zone()
                val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
                if (isAr) {
                    "المنطقة الزمنية المضبوطة للجهاز هي ${zone.id} (التوقيت العالمي UTC$offset)."
                } else {
                    "The active time zone is ${zone.id} (UTC$offset)."
                }
            }

            payload == "QUERY_CALC_METHOD" -> {
                val m = CALC_METHODS[s.methodSafe]
                val fajrStr = fmtAngle(s.effFajrAngle)
                val maghribStr = if (isSunsetAngle(s.effMaghribAngle)) {
                    if (isAr) "وقت الغروب" else "sunset"
                } else {
                    fmtAngle(s.effMaghribAngle)
                }
                if (isAr) {
                    "طريقة حساب المواقيت الحالية هي ${m.name} (زاوية الفجر $fajrStr، والمغرب $maghribStr)."
                } else {
                    "The active prayer calculation method is ${m.name} (Fajr angle $fajrStr, Maghrib $maghribStr)."
                }
            }

            payload == "QUERY_QIBLA" || payload == "GET_QIBLA_DIRECTION" -> {
                val bearing = computeQiblaBearingDegrees(s.lat, s.lon).roundToInt()
                val cardinal = bearingToCardinal(bearing, isAr)
                if (isAr) {
                    "اتجاه القبلة من ${s.city} هو $bearing درجة من الشمال ($cardinal)."
                } else {
                    "The Qibla direction from ${s.city} is $bearing° from North ($cardinal)."
                }
            }

            payload == "QUERY_TODAY_OCCASION" || payload == "GET_ISLAMIC_OCCASION" -> {
                val occasion = lookupShiaOccasion(today.hijri.month, today.hijri.day, isAr)
                val hijriStr = today.hijri.format(appContext, if (isAr) "ar" else "en")
                if (occasion != null) {
                    if (isAr) "مناسبة اليوم ($hijriStr): $occasion." else "Today's Islamic occasion ($hijriStr): $occasion."
                } else {
                    if (isAr) {
                        "اليوم هو $hijriStr، ولا توجد مناسبة دينية رئيسية مسجلة في هذا اليوم."
                    } else {
                        "Today is $hijriStr. There is no major Islamic occasion recorded for today."
                    }
                }
            }

            payload == "QUERY_NEXT_OCCASION" -> formatNextOccasionResponse(s, now.toLocalDate(), isAr)

            payload == "QUERY_MONTH_OCCASIONS" -> formatMonthOccasionsResponse(today.hijri, isAr)

            payload == "QUERY_COUNTDOWN:RAMADAN" -> formatRamadanCountdownResponse(s, now.toLocalDate(), today.hijri, isAr)

            payload == "QUERY_COUNTDOWN:ASHURA" -> formatAshuraCountdownResponse(s, now.toLocalDate(), isAr)

            // =========================================================================
            // 3. PRAYER TIMES (12 intents)
            // =========================================================================
            payload == "PRAYER_NEXT" || payload == "GET_NEXT_PRAYER_TIME" -> formatNextPrayerResponse(s, now, isAr)

            payload == "PRAYER_TODAY_ALL" || payload == "GET_TODAY_PRAYER_TIMES" -> {
                val fajr = fmtMinutes(today[Prayer.FAJR], s.use24h)
                val sunrise = fmtMinutes(today[Prayer.SUNRISE], s.use24h)
                val dhuhr = fmtMinutes(today[Prayer.DHUHR], s.use24h)
                val sunset = fmtMinutes(today[Prayer.SUNSET], s.use24h)
                val maghrib = fmtMinutes(today[Prayer.MAGHRIB], s.use24h)
                if (isAr) {
                    "أوقات الصلاة اليوم في ${s.city}: الفجر $fajr، الشروق $sunrise، الظهر $dhuhr، الغروب $sunset، والمغرب $maghrib."
                } else {
                    "Today's prayer times in ${s.city}: Fajr $fajr, Sunrise $sunrise, Dhuhr $dhuhr, Sunset $sunset, and Maghrib $maghrib."
                }
            }

            payload == "PRAYER_TOMORROW_ALL" -> {
                val tomorrow = Planner.day(s, now.toLocalDate().plusDays(1), now.zone)
                val fajr = fmtMinutes(tomorrow[Prayer.FAJR], s.use24h)
                val sunrise = fmtMinutes(tomorrow[Prayer.SUNRISE], s.use24h)
                val dhuhr = fmtMinutes(tomorrow[Prayer.DHUHR], s.use24h)
                val sunset = fmtMinutes(tomorrow[Prayer.SUNSET], s.use24h)
                val maghrib = fmtMinutes(tomorrow[Prayer.MAGHRIB], s.use24h)
                if (isAr) {
                    "أوقات الصلاة غداً في ${s.city}: الفجر $fajr، الشروق $sunrise، الظهر $dhuhr، الغروب $sunset، والمغرب $maghrib."
                } else {
                    "Tomorrow's prayer times in ${s.city}: Fajr $fajr, Sunrise $sunrise, Dhuhr $dhuhr, Sunset $sunset, and Maghrib $maghrib."
                }
            }

            payload == "PRAYER_TIME:FAJR" || payload == "GET_PRAYER_TIME_FAJR" -> {
                val t = fmtMinutes(today[Prayer.FAJR], s.use24h)
                if (isAr) "وقت صلاة الفجر اليوم في ${s.city} هو الساعة $t." else "Fajr prayer time today in ${s.city} is $t."
            }

            payload == "PRAYER_TIME:SUNRISE" || payload == "GET_PRAYER_TIME_SUNRISE" -> {
                val t = fmtMinutes(today[Prayer.SUNRISE], s.use24h)
                if (isAr) "وقت شروق الشمس اليوم في ${s.city} هو الساعة $t." else "Sunrise today in ${s.city} is at $t."
            }

            payload == "PRAYER_TIME:DHUHR" || payload == "GET_PRAYER_TIME_DHUHR" -> {
                val t = fmtMinutes(today[Prayer.DHUHR], s.use24h)
                if (isAr) "وقت صلاة الظهرين اليوم في ${s.city} هو الساعة $t." else "Dhuhr prayer time today in ${s.city} is $t."
            }

            payload == "PRAYER_TIME:SUNSET" -> {
                val t = fmtMinutes(today[Prayer.SUNSET], s.use24h)
                if (isAr) "وقت غروب الشمس اليوم في ${s.city} هو الساعة $t." else "Sunset today in ${s.city} is at $t."
            }

            payload == "PRAYER_TIME:MAGHRIB" || payload == "GET_PRAYER_TIME_MAGHRIB" ||
                payload == "GET_PRAYER_TIME_ISHA" -> {
                val t = fmtMinutes(today[Prayer.MAGHRIB], s.use24h)
                if (isAr) "وقت صلاة المغرب والعشاء اليوم في ${s.city} هو الساعة $t." else "Maghrib prayer time today in ${s.city} is $t."
            }

            payload == "PRAYER_TIME:MIDNIGHT" || payload == "GET_PRAYER_TIME_MIDNIGHT" -> {
                val sunsetMin = today[Prayer.SUNSET]
                val fajrMin = today[Prayer.FAJR]
                val midnightMin = if (sunsetMin >= 0 && fajrMin >= 0) {
                    val nightDuration = ((fajrMin + 1440) - sunsetMin) % 1440
                    (sunsetMin + nightDuration / 2) % 1440
                } else {
                    -1
                }
                val midStr = fmtMinutes(midnightMin, s.use24h)
                if (isAr) {
                    "وقت منتصف الليل الشرعي الليلة في ${s.city} هو الساعة $midStr."
                } else {
                    "Tonight's Islamic midnight (Muntasaf al-Layl) in ${s.city} is at $midStr."
                }
            }

            payload == "PRAYER_TIME:IMSAK" -> {
                val fajrMin = today[Prayer.FAJR]
                val imsakMin = if (fajrMin >= 0) (fajrMin - 10 + 1440) % 1440 else -1
                val imsakStr = fmtMinutes(imsakMin, s.use24h)
                val fajrStr = fmtMinutes(fajrMin, s.use24h)
                if (isAr) {
                    "وقت الإمساك اليوم في ${s.city} هو الساعة $imsakStr (قبل صلاة الفجر $fajrStr بعشر دقائق)."
                } else {
                    "Today's Imsak time in ${s.city} is $imsakStr (10 minutes before Fajr at $fajrStr)."
                }
            }

            payload == "PRAYER_COUNTDOWN:MAGHRIB" -> formatPrayerCountdownResponse(s, now, Prayer.MAGHRIB, isAr)

            payload == "PRAYER_COUNTDOWN:FAJR" -> formatPrayerCountdownResponse(s, now, Prayer.FAJR, isAr)

            // =========================================================================
            // 4. AZAN CONTROL (15 intents)
            // =========================================================================
            payload == "AZAN_SET_ALL:ON" || payload == "AZAN_ENABLE" -> {
                Store.saveSettings(s.copy(azanOn = listOf(true, true, true)))
                AlertScheduler.reschedule(appContext)
                baseResponse
            }

            payload == "AZAN_SET_ALL:OFF" || payload == "AZAN_DISABLE" -> {
                Store.saveSettings(s.copy(azanOn = listOf(false, false, false)))
                AlertScheduler.reschedule(appContext)
                baseResponse
            }

            payload.startsWith("AZAN_SET:") -> {
                val parts = payload.split(":")
                val prayerKey = parts.getOrNull(1).orEmpty()
                val enable = parts.getOrNull(2) == "ON"
                val idx = when (prayerKey) {
                    "FAJR" -> 0
                    "DHUHR" -> 1
                    "MAGHRIB" -> 2
                    else -> -1
                }
                if (idx in 0..2) {
                    val cur = (0..2).map { i -> s.azanOn.getOrElse(i) { true } }.toMutableList()
                    cur[idx] = enable
                    Store.saveSettings(s.copy(azanOn = cur))
                    AlertScheduler.reschedule(appContext)
                }
                baseResponse
            }

            payload.startsWith("AZAN_TEST:") || payload == "AZAN_PLAY_TEST" -> {
                val prayer = when (payload.substringAfter("AZAN_TEST:", "DHUHR")) {
                    "FAJR" -> AzanPrayer.FAJR
                    "MAGHRIB" -> AzanPrayer.MAGHRIB
                    else -> AzanPrayer.DHUHR
                }
                ContextCompat.startForegroundService(
                    appContext,
                    AlertService.azanIntent(appContext, prayer.ordinal, -1, test = true)
                )
                baseResponse
            }

            payload == "ALERT_STOP" || payload == "AZAN_STOP" || payload == "ALARM_STOP" -> {
                appContext.startService(AlertService.stopIntent(appContext))
                baseResponse
            }

            payload == "ALERT_TOGGLE_PAUSE" -> {
                AlertState.togglePause()
                baseResponse
            }

            payload == "AZAN_PICTURE:ON" -> {
                Store.saveSettings(s.copy(azanPicture = true))
                picturesChanged()
                baseResponse
            }

            payload == "AZAN_PICTURE:OFF" -> {
                Store.saveSettings(s.copy(azanPicture = false))
                picturesChanged()
                baseResponse
            }

            // =========================================================================
            // 5. ALARMS (10 intents)
            // =========================================================================
            payload == "ALARM_LIST_ACTIVE" || payload == "GET_ALARMS_LIST" -> {
                val activeAlarms = Store.alarms.value.filter { it.enabled }
                if (activeAlarms.isEmpty()) {
                    if (isAr) "لا توجد منبهات مفعّلة حالياً." else "There are currently no active alarms."
                } else {
                    val times = activeAlarms.take(5).joinToString(if (isAr) "، " else ", ") { a ->
                        val min = Planner.alarmMinute(a, today)
                        val tStr = fmtMinutes(min, s.use24h)
                        val lbl = a.label.trim()
                        if (lbl.isNotEmpty()) "$lbl ($tStr)" else tStr
                    }
                    if (isAr) {
                        "يوجد ${activeAlarms.size} منبه مفعّل: $times."
                    } else {
                        "You have ${activeAlarms.size} active alarm(s): $times."
                    }
                }
            }

            payload == "ALARM_COUNT_ENABLED" -> {
                val count = Store.alarms.value.count { it.enabled }
                if (isAr) {
                    "عدد المنبهات المفعّلة حالياً هو $count من أصل 40 منبهاً."
                } else {
                    "$count of 40 alarms are currently enabled."
                }
            }

            payload.startsWith("ALARM_SNOOZE") -> {
                appContext.startService(AlertService.snoozeIntent(appContext))
                baseResponse
            }

            payload == "ALARM_OPEN_EDITOR_NEW" -> {
                val alarms = Store.alarms.value
                val freeSlot = alarms.indexOfFirst { !it.enabled }.takeIf { it >= 0 }
                    ?: alarms.size.coerceAtMost(39)
                MediaHub.requestedAlarmEditorSlot.value = freeSlot
                MediaHub.requestedTab.tryEmit("ALARMS")
                baseResponse
            }

            payload == "ALARM_SET_ALL:ON" || payload == "ALARM_ENABLE" -> {
                val current = Store.alarms.value
                val updated = if (current.isEmpty()) {
                    listOf(Alarm(enabled = true, hour = 7, minute = 0, days = 0x7F, volume = 15))
                } else {
                    current.map { it.copy(enabled = true) }
                }
                Store.saveAlarms(updated)
                AlertScheduler.reschedule(appContext)
                baseResponse
            }

            payload == "ALARM_SET_ALL:OFF" || payload == "ALARM_DISABLE" -> {
                val updated = Store.alarms.value.map { it.copy(enabled = false) }
                Store.saveAlarms(updated)
                AlertScheduler.reschedule(appContext)
                baseResponse
            }

            payload == "ALARM_NEXT_SCHEDULED" -> {
                val alarms = Store.alarms.value
                val nextAlarm = Planner.upcoming(s, alarms, System.currentTimeMillis(), days = 7)
                    .firstOrNull { !it.isAzan }
                if (nextAlarm == null) {
                    if (isAr) "لا يوجد أي منبه قادم مجدول خلال الأيام السبعة القادمة." else "No upcoming alarms are scheduled for the next 7 days."
                } else {
                    val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextAlarm.atMillis), s.zone())
                    val minOfDay = zdt.hour * 60 + zdt.minute
                    val timeStr = fmtMinutes(minOfDay, s.use24h)
                    val diffMin = ((nextAlarm.atMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0L)
                    val hrs = diffMin / 60
                    val mins = diffMin % 60
                    val matchedAlarm = alarms.firstOrNull { it.id == nextAlarm.alarmId }
                    val label = matchedAlarm?.label?.takeIf { it.isNotBlank() } ?: if (isAr) "منبه" else "Alarm"
                    if (isAr) {
                        val rem = if (hrs > 0) "بعد $hrs ساعة و $mins دقيقة" else "بعد $mins دقيقة"
                        "المنبه القادم هو ($label) الساعة $timeStr ($rem)."
                    } else {
                        val rem = if (hrs > 0) "in ${hrs}h ${mins}m" else "in ${mins}m"
                        "The next scheduled alarm is $label at $timeStr ($rem)."
                    }
                }
            }

            payload == "ALARM_QUICK:FAJR" -> {
                val fajrLabel = if (isAr) "منبه الفجر" else "Fajr Alarm"
                val fajrAlarm = Alarm(
                    enabled = true,
                    relPrayer = 0, // Fajr
                    relOffset = 0, // At Fajr
                    days = 0x7F,   // Every day
                    volume = 18,
                    label = fajrLabel,
                )
                upsertQuickAlarm(fajrAlarm) { it.relPrayer == 0 && it.relOffset == 0 }
                MediaHub.requestedTab.tryEmit("ALARMS")
                val fajrTimeStr = fmtMinutes(today[Prayer.FAJR], s.use24h)
                if (isAr) {
                    "تم تفعيل منبه صلاة الفجر اليومي (يرن عند أذان الفجر الساعة $fajrTimeStr)."
                } else {
                    "Daily Fajr wake-up alarm enabled (rings at Fajr time, $fajrTimeStr)."
                }
            }

            payload == "ALARM_QUICK:SUHOOR" -> {
                val suhoorLabel = if (isAr) "منبه السحور" else "Suhoor Alarm"
                val suhoorAlarm = Alarm(
                    enabled = true,
                    relPrayer = 0,   // Fajr
                    relOffset = -45, // 45 minutes before Fajr
                    days = 0x7F,     // Every day
                    ramadan = false, // Active immediately whenever requested
                    volume = 18,
                    label = suhoorLabel,
                )
                upsertQuickAlarm(suhoorAlarm) { it.relPrayer == 0 && it.relOffset < 0 }
                MediaHub.requestedTab.tryEmit("ALARMS")
                val suhoorMin = Planner.alarmMinute(suhoorAlarm, today)
                val suhoorTimeStr = fmtMinutes(suhoorMin, s.use24h)
                if (isAr) {
                    "تم تفعيل منبه السحور قبل صلاة الفجر بـ 45 دقيقة (الساعة $suhoorTimeStr)."
                } else {
                    "Suhoor alarm enabled for 45 minutes before Fajr ($suhoorTimeStr)."
                }
            }

            // =========================================================================
            // 6. MEDIA PLAYER (14 intents)
            // =========================================================================
            payload == "MEDIA_PLAY" || payload == "MEDIA_RESUME" -> {
                resumeOrStartDefaultMedia()
                baseResponse
            }

            payload == "MEDIA_PAUSE" -> {
                withMediaController { it.pause() }
                MediaHub.youTubeCommands.tryEmit("pause")
                baseResponse
            }

            payload == "MEDIA_STOP" -> {
                sleepTimerJob?.cancel()
                sleepTimerJob = null
                withMediaController {
                    it.stop()
                    it.clearMediaItems()
                }
                MediaHub.youTube.value = null
                MediaHub.youTubePlaying.value = false
                MediaHub.closeScreen.tryEmit(Unit)
                baseResponse
            }

            payload == "MEDIA_NEXT" -> {
                withMediaController { it.seekToNextMediaItem() }
                baseResponse
            }

            payload == "MEDIA_PREV" || payload == "MEDIA_PREVIOUS" -> {
                withMediaController { it.seekToPrevious() }
                baseResponse
            }

            payload == "MEDIA_REPLAY" || payload == "MEDIA_RESTART" -> {
                withMediaController {
                    it.seekTo(0L)
                    it.play()
                }
                baseResponse
            }

            payload.startsWith("MEDIA_SEEK_FWD") -> {
                val seconds = payload.substringAfter(":", "30").toLongOrNull()?.coerceAtLeast(1L) ?: 30L
                withMediaController { controller ->
                    val dur = controller.duration
                    val nextPos = controller.currentPosition + seconds * 1_000L
                    controller.seekTo(if (dur > 0L) nextPos.coerceAtMost(dur) else nextPos.coerceAtLeast(0L))
                }
                baseResponse
            }

            payload.startsWith("MEDIA_SEEK_BWD") -> {
                val seconds = payload.substringAfter(":", "30").toLongOrNull()?.coerceAtLeast(1L) ?: 30L
                withMediaController { controller ->
                    val prevPos = (controller.currentPosition - seconds * 1_000L).coerceAtLeast(0L)
                    controller.seekTo(prevPos)
                }
                baseResponse
            }

            payload == "MEDIA_NOW_PLAYING" || payload == "GET_NOW_PLAYING" -> {
                val title = getCurrentlyPlayingTitle()
                if (title != null) {
                    if (isAr) "قيد التشغيل حالياً: $title." else "Currently playing: $title."
                } else {
                    if (isAr) "لا يوجد أي مقطع قيد التشغيل حالياً." else "Nothing is currently playing."
                }
            }

            payload == "MEDIA_PLAY_FOLDER:AUDIOS" || payload == "MEDIA_PLAY_AUDIOS" ||
                payload == "MEDIA_PLAY_QURAN" || payload == "MEDIA_PLAY_DUA" || payload == "MEDIA_PLAY_ZIYARAT" -> {
                val keyword = when (payload) {
                    "MEDIA_PLAY_QURAN" -> listOf("quran", "surah", "قران", "سورة", "تلاوة")
                    "MEDIA_PLAY_DUA" -> listOf("dua", "kumayl", "kumail", "tawassul", "faraj", "دعاء", "كميل", "التوسل", "الفرج")
                    "MEDIA_PLAY_ZIYARAT" -> listOf("ziyarat", "ziyarah", "ashura", "warith", "aminallah", "زيارة", "عاشوراء", "وارث")
                    else -> emptyList()
                }
                MediaHub.requestedPlayerSection.value = "AUDIOS"
                val startedTrack = playAudioFolderMatching(keyword)
                if (startedTrack != null) {
                    "$baseResponse (${startedTrack.nameWithoutExtension})"
                } else {
                    MediaHub.requestedTab.tryEmit("PLAYER")
                    baseResponse
                }
            }

            payload == "MEDIA_PLAY_FOLDER:VIDEOS" || payload == "MEDIA_PLAY_VIDEOS" -> {
                MediaHub.requestedPlayerSection.value = "VIDEOS"
                val videoDir = FileSystemManager.getSubdirectory(appContext, "videos")
                val videos = (videoDir.listFiles().orEmpty().toList() + AppFolders.dir(appContext, Folder.VIDEOS).listFiles().orEmpty().toList())
                    .filter { it.isFile && !it.name.startsWith(".") && AppFolders.isVideo(it) }
                    .distinctBy { it.name.lowercase(Locale.ROOT) }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                if (videos.isNotEmpty()) {
                    playLocalFilesOnMedia3(videos, 0)
                } else {
                    MediaHub.requestedTab.tryEmit("PLAYER")
                }
                baseResponse
            }

            payload == "MEDIA_YOUTUBE_ADD_DIALOG" -> {
                MediaHub.requestedPlayerSection.value = "YOUTUBE_ADD"
                MediaHub.requestedTab.tryEmit("PLAYER")
                baseResponse
            }

            payload == "MEDIA_RESCAN_LIBRARY" || payload == "REFRESH_MEDIA_LIBRARY" -> {
                backgroundScope.launch {
                    AppFolders.refreshAll(appContext)
                    MediaLibrary.rescan(appContext)
                    picturesChanged()
                }
                baseResponse
            }

            payload.startsWith("MEDIA_SLEEP_TIMER:") -> {
                val minutes = payload.substringAfter(":", "30").toLongOrNull()?.coerceIn(1L, 360L) ?: 30L
                scheduleMediaSleepTimer(minutes)
                baseResponse
            }

            // =========================================================================
            // 7. VOLUME & AUDIO (9 intents)
            // =========================================================================
            payload == "VOLUME_UP" -> {
                val newPct = adjustVolumeByDelta(+15, s)
                if (isAr) "تم رفع مستوى الصوت إلى $newPct بالمائة." else "Volume increased to $newPct%."
            }

            payload == "VOLUME_DOWN" -> {
                val newPct = adjustVolumeByDelta(-15, s)
                if (isAr) "تم خفض مستوى الصوت إلى $newPct بالمائة." else "Volume decreased to $newPct%."
            }

            payload == "VOLUME_MUTE" -> {
                val cur = MediaHub.volume.value
                if (cur > 0.02f) previousVolumeFraction = cur
                setApplianceVolumeFraction(0f, s)
                baseResponse
            }

            payload == "VOLUME_UNMUTE" -> {
                val restored = previousVolumeFraction.coerceIn(0.25f, 1.0f)
                val pct = setApplianceVolumeFraction(restored, s)
                if (isAr) "تم إلغاء كتم الصوت وضبطه على $pct بالمائة." else "Audio unmuted ($pct%)."
            }

            payload.startsWith("VOLUME_SET:") || payload == "VOLUME_SET_MAX" -> {
                val level21 = if (payload == "VOLUME_SET_MAX") {
                    21
                } else {
                    payload.substringAfter(":", "21").toIntOrNull()?.coerceIn(0, 21) ?: 21
                }
                val fraction = (level21 / 21f).coerceIn(0f, 1f)
                val pct = setApplianceVolumeFraction(fraction, s)
                val effectiveLevel = ((pct / 100f) * 21f).roundToInt().coerceIn(0, 21)
                if (isAr) {
                    "تم ضبط مستوى صوت المشغل على الدرجة $effectiveLevel من ٢١ ($pct بالمائة)."
                } else {
                    "Player volume set to $effectiveLevel of 21 ($pct%)."
                }
            }

            payload == "AZAN_VOLUME_UP" -> {
                val maxLevel = ((s.maxVolumeLimit.coerceIn(10, 100) / 100f) * 21f).roundToInt().coerceIn(1, 21)
                val nextVol = (s.azanVolume + 3).coerceIn(0, maxLevel)
                Store.saveSettings(s.copy(azanVolume = nextVol))
                if (AlertState.current.value != null) {
                    AlertState.setVolume(nextVol)
                }
                if (isAr) "تم رفع مستوى صوت الأذان إلى $nextVol من ٢١." else "Azan volume increased to $nextVol of 21."
            }

            payload == "AZAN_VOLUME_DOWN" -> {
                val nextVol = (s.azanVolume - 3).coerceIn(0, 21)
                Store.saveSettings(s.copy(azanVolume = nextVol))
                if (AlertState.current.value != null) {
                    AlertState.setVolume(nextVol)
                }
                if (isAr) "تم خفض مستوى صوت الأذان إلى $nextVol من ٢١." else "Azan volume decreased to $nextVol of 21."
            }

            // =========================================================================
            // 8. DISPLAY (12 intents)
            // =========================================================================
            payload == "DISPLAY_BRIGHTNESS_UP" -> {
                val cur = if (s.brightness < 0) 75 else s.brightness
                val next = (cur + 20).coerceIn(5, 100)
                Store.saveSettings(s.copy(brightness = next))
                if (isAr) "تم رفع سطوع الشاشة إلى $next بالمائة." else "Screen brightness increased to $next%."
            }

            payload == "DISPLAY_BRIGHTNESS_DOWN" -> {
                val cur = if (s.brightness < 0) 75 else s.brightness
                val next = (cur - 20).coerceIn(5, 100)
                Store.saveSettings(s.copy(brightness = next))
                if (isAr) "تم خفض سطوع الشاشة إلى $next بالمائة." else "Screen brightness decreased to $next%."
            }

            payload == "DISPLAY_BRIGHTNESS:AUTO" || payload == "DISPLAY_BRIGHTNESS_AUTO" -> {
                Store.saveSettings(s.copy(brightness = -1))
                baseResponse
            }

            payload.startsWith("DISPLAY_BRIGHTNESS:") || payload == "DISPLAY_BRIGHTNESS_MAX" ||
                payload == "DISPLAY_BRIGHTNESS_MIN" -> {
                val target = when (payload) {
                    "DISPLAY_BRIGHTNESS_MAX" -> 100
                    "DISPLAY_BRIGHTNESS_MIN" -> 10
                    else -> payload.substringAfter(":", "100").toIntOrNull()?.coerceIn(5, 100) ?: 100
                }
                Store.saveSettings(s.copy(brightness = target))
                baseResponse
            }

            payload == "DISPLAY_TOGGLE_THEME" -> {
                val wallpapers = listWallpaperFiles(appContext)
                val enablePic = !s.clockPicture
                val chosen = if (enablePic) s.selectedWallpaper.ifBlank { wallpapers.firstOrNull()?.name.orEmpty() } else ""
                Store.saveSettings(s.copy(clockPicture = enablePic && chosen.isNotBlank(), selectedWallpaper = chosen))
                picturesChanged()
                baseResponse
            }

            payload == "DISPLAY_NEXT_WALLPAPER" -> {
                val wallpapers = listWallpaperFiles(appContext)
                if (wallpapers.isNotEmpty()) {
                    val curIdx = wallpapers.indexOfFirst { it.name.equals(s.selectedWallpaper, ignoreCase = true) }
                    val nextWall = wallpapers[(curIdx + 1) % wallpapers.size]
                    Store.saveSettings(s.copy(clockPicture = true, selectedWallpaper = nextWall.name))
                    picturesChanged()
                    if (isAr) "تم تغيير الخلفية إلى ${nextWall.nameWithoutExtension}." else "Changed wallpaper to ${nextWall.nameWithoutExtension}."
                } else {
                    Store.saveSettings(s.copy(clockPicture = false, selectedWallpaper = ""))
                    picturesChanged()
                    baseResponse
                }
            }

            payload == "DISPLAY_WALLPAPER_DEFAULT" -> {
                Store.saveSettings(s.copy(clockPicture = false, selectedWallpaper = ""))
                picturesChanged()
                baseResponse
            }

            payload == "DISPLAY_CLOCK_FORMAT:24H" || payload == "SET_TIME_FORMAT_24H" -> {
                Store.saveSettings(s.copy(use24h = true))
                baseResponse
            }

            payload == "DISPLAY_CLOCK_FORMAT:12H" || payload == "SET_TIME_FORMAT_12H" -> {
                Store.saveSettings(s.copy(use24h = false))
                baseResponse
            }

            payload == "DISPLAY_KEEP_SCREEN_ON:TRUE" || payload == "DISPLAY_SCREEN_ON" -> {
                Store.saveSettings(s.copy(keepScreenOn = true))
                baseResponse
            }

            payload == "DISPLAY_KEEP_SCREEN_ON:FALSE" || payload == "DISPLAY_SCREEN_OFF" -> {
                Store.saveSettings(s.copy(keepScreenOn = false))
                baseResponse
            }

            // =========================================================================
            // 9. CONNECTIVITY (11 intents)
            // =========================================================================
            payload == "WIFI_STATUS" || payload == "GET_WIFI_STATUS" -> {
                val wifiState = WifiController.refreshStatus(appContext)
                if (wifiState.isConnected) {
                    val quality = when (wifiState.signalLevel) {
                        4 -> if (isAr) "ممتازة" else "excellent"
                        3 -> if (isAr) "جيدة" else "good"
                        2 -> if (isAr) "متوسطة" else "fair"
                        else -> if (isAr) "ضعيفة" else "weak"
                    }
                    if (isAr) {
                        "الواي فاي متصل بالشبكة ${wifiState.ssid} (جودة الإشارة: $quality)."
                    } else {
                        "Wi-Fi is connected to ${wifiState.ssid} ($quality signal)."
                    }
                } else {
                    if (isAr) "الجهاز غير متصل بشبكة واي فاي حالياً." else "Wi-Fi is currently disconnected."
                }
            }

            payload == "WIFI_SCAN" -> {
                MediaHub.openWifiManager.value = true
                MediaHub.requestedTab.tryEmit("WIFI")
                if (WifiController.hasScanPermission(appContext)) {
                    WifiController.startScan(appContext)
                }
                baseResponse
            }

            payload == "BLE_STATUS" || payload == "GET_BLE_STATUS" -> {
                MediaRemoteServer.syncBleSnapshot(appContext)
                val ble = MediaRemoteServer.bleState.value
                val count = ble.connectedClients.size
                if (isAr) {
                    "البلوتوث (${ble.advertisedName}) ${if (ble.isAdvertising) "نشط ويبث" else "في وضع الاستعداد"}، والأجهزة المتصلة حالياً: $count."
                } else {
                    "Bluetooth (${ble.advertisedName}) is ${if (ble.isAdvertising) "advertising" else "on standby"} with $count connected remote(s)."
                }
            }

            payload == "BLE_REFRESH" -> {
                MediaRemoteServer.refreshBle(appContext)
                baseResponse
            }

            payload == "WEB_REMOTE:ON" -> {
                Store.saveSettings(s.copy(webRemote = true))
                MediaRemoteServer.start(appContext)
                baseResponse
            }

            payload == "WEB_REMOTE:OFF" -> {
                Store.saveSettings(s.copy(webRemote = false))
                MediaRemoteServer.start(appContext)
                baseResponse
            }

            payload == "WEB_REMOTE_INFO" -> {
                MediaHub.requestedTab.tryEmit("WIFI")
                val url = WebRemote.address(appContext).removeSuffix("/")
                if (!s.webRemote) {
                    if (isAr) {
                        "خدمة التحكم عبر المتصفح متوقفة حالياً. يمكنك تفعيلها بقول: شغّل التحكم عبر المتصفح."
                    } else {
                        "Wi-Fi Web Remote is currently turned off. Say 'Enable web remote' to turn it on."
                    }
                } else if (url.isNotEmpty()) {
                    if (isAr) {
                        "عنوان التحكم عبر المتصفح هو $url على المنفذ ${WebRemote.PORT}، وتم فتح رمز الاستجابة السريعة على شاشة الواي فاي."
                    } else {
                        "The Wi-Fi Web Remote address is $url. The QR code is now displayed on the Wi-Fi tab."
                    }
                } else {
                    if (isAr) {
                        "التحكم عبر المتصفح مفعّل على المنفذ ${WebRemote.PORT}، يرجى الاتصال بشبكة الواي فاي أو تشغيل نقطة الاتصال."
                    } else {
                        "Wi-Fi Web Remote is enabled on port ${WebRemote.PORT}. Connect to Wi-Fi or start the appliance hotspot to view the URL."
                    }
                }
            }

            payload == "HOTSPOT:START" -> {
                MediaHub.requestedTab.tryEmit("WIFI")
                withContext(Dispatchers.Main) {
                    Hotspot.start(appContext)
                }
                baseResponse
            }

            payload == "HOTSPOT:STOP" -> {
                withContext(Dispatchers.Main) {
                    Hotspot.stop()
                }
                baseResponse
            }

            payload == "SETTINGS_GPS_SYNC" -> {
                com.nouralwelayah.clock.kiosk.KioskPolicy.tryAutoGrantAppPermissions(appContext)
                MediaHub.requestedSettingsAction.value = "GPS_SYNC"
                if (!Store.settings.value.childLockEnabled) {
                    MediaHub.requestedTab.tryEmit("SETTINGS")
                }
                withContext(Dispatchers.Main) {
                    com.nouralwelayah.clock.ui.resolveApplianceLocation(appContext) { res ->
                        val loc = res.location
                        if (loc != null) {
                            backgroundScope.launch {
                                val resolvedCity = cityName(appContext, loc)
                                    ?: res.city?.takeIf { it.isNotBlank() }
                                    ?: Store.settings.value.city
                                val current = Store.settings.value
                                val nextZone = res.timezone?.takeIf { it.isNotBlank() } ?: current.zoneId
                                Store.saveSettings(
                                    current.copy(
                                        city = resolvedCity.take(31),
                                        lat = loc.latitude,
                                        lon = loc.longitude,
                                        zoneId = nextZone,
                                        locationSet = true,
                                    )
                                )
                                AlertScheduler.reschedule(appContext)
                            }
                        }
                    }
                }
                baseResponse
            }

            payload == "SETTINGS_ONLINE_CITY" -> {
                MediaHub.requestedSettingsAction.value = "ONLINE_CITY"
                MediaHub.requestedTab.tryEmit("SETTINGS")
                baseResponse
            }

            // =========================================================================
            // 10. SYSTEM SETTINGS (13 intents)
            // =========================================================================
            payload == "SET_LANGUAGE:AR" || payload == "SET_LANGUAGE_AR" || payload == "LANGUAGE_AR" -> {
                Store.saveSettings(s.copy(appLanguage = "ar"))
                baseResponse
            }

            payload == "SET_LANGUAGE:EN" || payload == "SET_LANGUAGE_EN" || payload == "LANGUAGE_EN" -> {
                Store.saveSettings(s.copy(appLanguage = "en"))
                baseResponse
            }

            payload.startsWith("HIJRI_ADJUST:") -> {
                val rawDelta = payload.substringAfter("HIJRI_ADJUST:", "0").trim()
                val newAdjust = when (rawDelta) {
                    "+1", "1" -> (s.hijriAdjust + 1).coerceIn(-2, 2)
                    "-1" -> (s.hijriAdjust - 1).coerceIn(-2, 2)
                    "0" -> 0
                    else -> rawDelta.toIntOrNull()?.coerceIn(-2, 2) ?: 0
                }
                val updatedSettings = s.copy(hijriAdjust = newAdjust)
                Store.saveSettings(updatedSettings)
                AlertScheduler.reschedule(appContext)
                val updatedDay = Planner.day(updatedSettings, now.toLocalDate(), now.zone)
                val hijriStr = updatedDay.hijri.format(appContext, if (isAr) "ar" else "en")
                if (isAr) {
                    "$baseResponse التاريخ الهجري الآن هو $hijriStr."
                } else {
                    "$baseResponse Today's Hijri date is now $hijriStr."
                }
            }

            payload == "VOICE_MODE:AUTO" -> {
                Store.saveSettings(s.copy(voiceRecognitionMode = "auto"))
                baseResponse
            }

            payload == "VOICE_MODE:AR" -> {
                Store.saveSettings(s.copy(voiceRecognitionMode = "ar"))
                baseResponse
            }

            payload == "VOICE_MODE:EN" -> {
                Store.saveSettings(s.copy(voiceRecognitionMode = "en"))
                baseResponse
            }

            payload == "VOICE_CLEAR_LEARNED" -> {
                val cleared = VoiceAssistantService.getLearningStore(appContext).clearLearnedPhrases()
                if (isAr) {
                    "تم مسح ذاكرة العبارات الصوتية المتعلّمة محلياً ($cleared عبارة)."
                } else {
                    "Cleared locally learned voice phrases ($cleared phrase(s) removed)."
                }
            }

            payload == "VOICE_DIAGNOSTICS" -> {
                val meta = VoiceAssistantService.refreshMetadata(appContext)
                val modelStatus = VoiceAssistantService.getModelManager(appContext).verifyAndPrepareModels()
                val learnedCount = VoiceAssistantService.getLearningStore(appContext).refreshStats().learnedPhraseCount
                val modelsOk = modelStatus is VoiceReadinessState.Ready
                if (isAr) {
                    "فحص المساعد الصوتي: قاعدة المعرفة الإصدار ${meta?.kbVersion ?: "1.1.0"} (${meta?.intentCount ?: 122} أمراً)، النماذج الصوتية ${if (modelsOk) "جاهزة ١٠٠٪" else "تحتاج مراجعة"}، العبارات المتعلّمة: $learnedCount."
                } else {
                    "Voice diagnostics: Knowledge Base v${meta?.kbVersion ?: "1.1.0"} (${meta?.intentCount ?: 122} intents), Voice Models ${if (modelsOk) "Ready" else "Degraded"}, Learned phrases: $learnedCount."
                }
            }

            payload == "KB_OTA_SYNC" -> {
                backgroundScope.launch {
                    VoiceAssistantService.syncKnowledgeBase(appContext)
                }
                baseResponse
            }

            payload == "OTA_CHECK_UPDATE" || payload == "CHECK_OTA_UPDATE" -> {
                backgroundScope.launch {
                    OtaManager.checkForUpdatesNow(appContext)
                }
                baseResponse
            }

            payload == "VOICE_HELP" -> baseResponse

            else -> baseResponse
        }

        return DispatchResult.Success(
            spokenResponse = spoken,
            sourceRef = sourceRef,
            intentId = match.intentId,
            actionType = match.actionType,
            commandPayload = match.commandPayload,
            score = match.score,
        )
    }

    private fun formatBatteryStatusResponse(isAr: Boolean): String {
        return runCatching {
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val pct = if (level >= 0) ((level * 100.0) / scale).roundToInt().coerceIn(0, 100) else -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0

            if (pct < 0) {
                if (isAr) "الجهاز موصول بمصدر الطاقة ويعمل بشكل طبيعي." else "The appliance is connected to power and operating normally."
            } else if (isAr) {
                val chargeStr = if (isCharging) "الجهاز قيد الشحن / موصول بالكهرباء" else "يعمل على البطارية"
                "مستوى البطارية الحالي هو $pct بالمائة ($chargeStr)."
            } else {
                val chargeStr = if (isCharging) "charging / plugged in" else "on battery"
                "Battery level is $pct% ($chargeStr)."
            }
        }.getOrElse {
            if (isAr) "الجهاز موصول بمصدر الطاقة." else "The appliance is connected to power."
        }
    }

    private fun formatNextOccasionResponse(
        s: AppSettings,
        todayDate: LocalDate,
        isAr: Boolean,
    ): String {
        for (offset in 0..370) {
            val date = todayDate.plusDays(offset.toLong())
            val h = Hijri.fromGregorian(date.year, date.monthValue, date.dayOfMonth, s.hijriAdjust)
            val occ = lookupShiaOccasion(h.month, h.day, isAr)
            if (occ != null) {
                val hStr = h.format(appContext, if (isAr) "ar" else "en")
                return when (offset) {
                    0 -> if (isAr) "مناسبة اليوم ($hStr): $occ." else "Today's Islamic occasion ($hStr): $occ."
                    1 -> if (isAr) "المناسبة الإسلامية القادمة غداً ($hStr): $occ." else "Tomorrow's Islamic occasion ($hStr): $occ."
                    else -> if (isAr) {
                        "المناسبة الإسلامية القادمة هي $occ في $hStr (بعد $offset يوماً)."
                    } else {
                        "The next Islamic occasion is $occ on $hStr (in $offset days)."
                    }
                }
            }
        }
        return if (isAr) "لا توجد مناسبة مسجلة قريبة." else "No upcoming occasion found."
    }

    private fun formatMonthOccasionsResponse(currentHijri: HijriDate, isAr: Boolean): String {
        val monthName = if (isAr) currentHijri.monthNameAr else currentHijri.monthName
        val matches = SHIA_OCCASIONS.filter { it.month == currentHijri.month }
        if (matches.isEmpty()) {
            return if (isAr) {
                "لا توجد مناسبات رئيسية مسجلة في شهر $monthName."
            } else {
                "There are no major recorded occasions in the month of $monthName."
            }
        }
        val listStr = matches.joinToString(if (isAr) "، " else "; ") { item ->
            if (isAr) "${item.day} $monthName: ${item.titleAr}" else "${item.day} $monthName: ${item.titleEn}"
        }
        return if (isAr) {
            "مناسبات شهر $monthName: $listStr."
        } else {
            "Occasions in $monthName: $listStr."
        }
    }

    private fun formatRamadanCountdownResponse(
        s: AppSettings,
        todayDate: LocalDate,
        currentHijri: HijriDate,
        isAr: Boolean,
    ): String {
        if (currentHijri.month == 9) {
            return if (isAr) {
                "نحن الآن في شهر رمضان المبارك (اليوم ${currentHijri.day} رمضان ${currentHijri.year} هـ)."
            } else {
                "We are currently in the blessed month of Ramadan (day ${currentHijri.day} of Ramadan ${currentHijri.year} AH)."
            }
        }
        val daysToRamadan = findDaysUntilHijriDate(todayDate, s.hijriAdjust, targetMonth = 9, targetDay = 1)
        return if (daysToRamadan != null) {
            if (isAr) {
                "متبقي $daysToRamadan يوماً على حلول شهر رمضان المبارك."
            } else {
                "There are $daysToRamadan days remaining until the blessed month of Ramadan."
            }
        } else {
            if (isAr) "تعذر حساب الأيام المتبقية لشهر رمضان." else "Unable to compute the countdown to Ramadan."
        }
    }

    private fun formatAshuraCountdownResponse(
        s: AppSettings,
        todayDate: LocalDate,
        isAr: Boolean,
    ): String {
        val daysToMuharram = findDaysUntilHijriDate(todayDate, s.hijriAdjust, targetMonth = 1, targetDay = 1)
        val daysToAshura = findDaysUntilHijriDate(todayDate, s.hijriAdjust, targetMonth = 1, targetDay = 10)
        val daysToArbaeen = findDaysUntilHijriDate(todayDate, s.hijriAdjust, targetMonth = 2, targetDay = 20)

        return if (isAr) {
            val parts = mutableListOf<String>()
            if (daysToMuharram != null) {
                parts.add(if (daysToMuharram == 0) "اليوم هو غرة شهر محرم" else "غرة محرم بعد $daysToMuharram يوماً")
            }
            if (daysToAshura != null) {
                parts.add(if (daysToAshura == 0) "اليوم هو يوم عاشوراء" else "يوم عاشوراء (١٠ محرم) بعد $daysToAshura يوماً")
            }
            if (daysToArbaeen != null) {
                parts.add(if (daysToArbaeen == 0) "اليوم هو أربعينية الإمام الحسين (ع)" else "زيارة الأربعين (٢٠ صفر) بعد $daysToArbaeen يوماً")
            }
            "المتبقي على محرم وعاشوراء: ${parts.joinToString("، ")}."
        } else {
            val parts = mutableListOf<String>()
            if (daysToMuharram != null) {
                parts.add(if (daysToMuharram == 0) "1st of Muharram is today" else "1st of Muharram in $daysToMuharram days")
            }
            if (daysToAshura != null) {
                parts.add(if (daysToAshura == 0) "Day of Ashura is today" else "Ashura (10 Muharram) in $daysToAshura days")
            }
            if (daysToArbaeen != null) {
                parts.add(if (daysToArbaeen == 0) "Arba'een is today" else "Arba'een (20 Safar) in $daysToArbaeen days")
            }
            "Countdown: ${parts.joinToString(", ")}."
        }
    }

    private fun findDaysUntilHijriDate(
        todayDate: LocalDate,
        hijriAdjust: Int,
        targetMonth: Int,
        targetDay: Int,
    ): Int? {
        for (offset in 0..380) {
            val d = todayDate.plusDays(offset.toLong())
            val h = Hijri.fromGregorian(d.year, d.monthValue, d.dayOfMonth, hijriAdjust)
            if (h.month == targetMonth && h.day == targetDay) {
                return offset
            }
        }
        return null
    }

    private fun formatPrayerCountdownResponse(
        s: AppSettings,
        now: ZonedDateTime,
        targetPrayer: Prayer,
        isAr: Boolean,
    ): String {
        val todayPlan = Planner.day(s, now.toLocalDate(), now.zone)
        val todayMin = todayPlan[targetPrayer]
        val todayAt = if (todayMin in 0 until 1440) {
            ZonedDateTime.of(todayPlan.date, LocalTime.of(todayMin / 60, todayMin % 60), now.zone)
        } else {
            null
        }
        val targetTime = if (todayAt != null && todayAt.isAfter(now)) {
            todayAt
        } else {
            val tomorrowPlan = Planner.day(s, now.toLocalDate().plusDays(1), now.zone)
            val tomMin = tomorrowPlan[targetPrayer]
            if (tomMin in 0 until 1440) {
                ZonedDateTime.of(tomorrowPlan.date, LocalTime.of(tomMin / 60, tomMin % 60), now.zone)
            } else {
                null
            }
        } ?: return if (isAr) "تعذر حساب الوقت المتبقي." else "Unable to compute time remaining."

        val diffMin = ((targetTime.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()) / 60_000L)
            .coerceAtLeast(0L)
        val hrs = diffMin / 60
        val mins = diffMin % 60
        val timeFormatted = fmtMinutes(targetTime.hour * 60 + targetTime.minute, s.use24h)
        val pName = when (targetPrayer) {
            Prayer.FAJR -> if (isAr) "صلاة الفجر" else "Fajr prayer"
            Prayer.MAGHRIB -> if (isAr) "أذان المغرب (الإفطار)" else "Maghrib Azan (Iftar)"
            Prayer.DHUHR -> if (isAr) "صلاة الظهر" else "Dhuhr prayer"
            Prayer.SUNRISE -> if (isAr) "شروق الشمس" else "Sunrise"
            Prayer.SUNSET -> if (isAr) "غروب الشمس" else "Sunset"
        }

        return if (isAr) {
            val rem = when {
                hrs > 0 && mins > 0 -> "$hrs ساعة و $mins دقيقة"
                hrs > 0 -> "$hrs ساعة"
                else -> "$mins دقيقة"
            }
            "متبقي على $pName في ${s.city} $rem (الساعة $timeFormatted)."
        } else {
            val rem = when {
                hrs > 0 && mins > 0 -> "${hrs}h ${mins}m"
                hrs > 0 -> "${hrs}h"
                else -> "${mins}m"
            }
            "$rem remaining until $pName in ${s.city} (at $timeFormatted)."
        }
    }

    private fun upsertQuickAlarm(newAlarm: Alarm, predicate: (Alarm) -> Boolean) {
        val current = Store.alarms.value.toMutableList()
        val existingIdx = current.indexOfFirst(predicate)
        if (existingIdx >= 0) {
            current[existingIdx] = newAlarm
        } else if (current.size < 40) {
            current.add(newAlarm)
        } else {
            val disabledIdx = current.indexOfFirst { !it.enabled }.takeIf { it >= 0 } ?: 0
            current[disabledIdx] = newAlarm
        }
        Store.saveAlarms(current)
        AlertScheduler.reschedule(appContext)
    }

    private fun scheduleMediaSleepTimer(minutes: Long) {
        sleepTimerJob?.cancel()
        sleepTimerJob = backgroundScope.launch {
            delay(minutes * 60_000L)
            withMediaController {
                it.stop()
                it.clearMediaItems()
            }
            MediaHub.youTube.value = null
            MediaHub.youTubePlaying.value = false
            MediaHub.closeScreen.tryEmit(Unit)
        }
    }

    private fun formatNextPrayerResponse(
        s: AppSettings,
        now: ZonedDateTime,
        isAr: Boolean,
    ): String {
        val next = Planner.nextPrayer(s, now) ?: return if (isAr) {
            "تعذر حساب وقت الصلاة القادمة."
        } else {
            "Unable to compute the next prayer time."
        }
        val (prayer, prayerTime) = next
        val prayerMin = prayerTime.hour * 60 + prayerTime.minute
        val timeFormatted = fmtMinutes(prayerMin, s.use24h)
        val diffMinutes = ((prayerTime.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()) / 60_000L)
            .coerceAtLeast(0L)
        val hoursRem = diffMinutes / 60
        val minsRem = diffMinutes % 60

        val prayerName = when (prayer) {
            Prayer.FAJR -> if (isAr) "الفجر" else "Fajr"
            Prayer.SUNRISE -> if (isAr) "الشروق" else "Sunrise"
            Prayer.DHUHR -> if (isAr) "الظهر" else "Dhuhr"
            Prayer.SUNSET -> if (isAr) "الغروب" else "Sunset"
            Prayer.MAGHRIB -> if (isAr) "المغرب" else "Maghrib"
        }

        return if (isAr) {
            val remText = when {
                hoursRem > 0 && minsRem > 0 -> "بعد $hoursRem ساعة و $minsRem دقيقة"
                hoursRem > 0 -> "بعد $hoursRem ساعة"
                else -> "بعد $minsRem دقيقة"
            }
            "الصلاة القادمة في ${s.city} هي صلاة $prayerName الساعة $timeFormatted ($remText)."
        } else {
            val remText = when {
                hoursRem > 0 && minsRem > 0 -> "in ${hoursRem}h ${minsRem}m"
                hoursRem > 0 -> "in ${hoursRem}h"
                else -> "in ${minsRem}m"
            }
            "The next prayer in ${s.city} is $prayerName at $timeFormatted ($remText)."
        }
    }

    private fun adjustVolumeByDelta(deltaPercent: Int, s: AppSettings): Int {
        val currentPct = getCurrentVolumePercent()
        val targetPct = (currentPct + deltaPercent).coerceIn(0, s.maxVolumeLimit)
        val fraction = if (s.maxVolumeLimit > 0) {
            (targetPct.toFloat() / 100f).coerceIn(0f, 1f)
        } else {
            0f
        }
        return setApplianceVolumeFraction(fraction, s)
    }

    private fun getCurrentVolumePercent(): Int {
        return runCatching {
            val am = Sys.svc(appContext, AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
            ((cur * 100.0) / max).roundToInt().coerceIn(0, 100)
        }.getOrDefault((MediaHub.volume.value * 100f).roundToInt().coerceIn(0, 100))
    }

    private fun setApplianceVolumeFraction(fraction: Float, s: AppSettings): Int {
        val maxAllowedFraction = (s.maxVolumeLimit.coerceIn(0, 100) / 100f)
        val effectiveFraction = fraction.coerceIn(0f, maxAllowedFraction)
        MediaHub.volume.value = effectiveFraction

        if (AlertState.current.value != null) {
            AlertState.setVolume((effectiveFraction * 21f).roundToInt().coerceIn(0, 21))
        }

        runCatching {
            val am = Sys.svc(appContext, AudioManager::class.java)
            val streamMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetVol = (streamMax * effectiveFraction).roundToInt().coerceIn(0, streamMax)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        }

        backgroundScope.launch {
            withMediaController { controller ->
                controller.volume = effectiveFraction
            }
        }

        return (effectiveFraction * 100f).roundToInt().coerceIn(0, 100)
    }

    private suspend fun resumeOrStartDefaultMedia() {
        val resumed = withMediaController { controller ->
            if (controller.mediaItemCount > 0) {
                controller.play()
                true
            } else {
                false
            }
        } ?: false

        if (!resumed) {
            playAudioFolderMatching(emptyList())
        }
    }

    private suspend fun playAudioFolderMatching(keywords: List<String>): File? {
        val audioDir = FileSystemManager.getSubdirectory(appContext, "audios")
        val fallbackDir = AppFolders.dir(appContext, Folder.AUDIOS)
        val allAudioFiles = (audioDir.walkTopDown().toList() + fallbackDir.walkTopDown().toList())
            .filter { it.isFile && !it.name.startsWith(".") && AppFolders.isAudio(it) }
            .distinctBy { it.name.lowercase(Locale.ROOT) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

        if (allAudioFiles.isEmpty()) return null

        val matchIndex = if (keywords.isEmpty()) {
            0
        } else {
            allAudioFiles.indexOfFirst { file ->
                val lower = file.nameWithoutExtension.lowercase(Locale.ROOT)
                keywords.any { kw -> lower.contains(kw.lowercase(Locale.ROOT)) }
            }.takeIf { it >= 0 } ?: 0
        }

        val played = playLocalFilesOnMedia3(allAudioFiles, matchIndex)
        return if (played) allAudioFiles[matchIndex] else null
    }

    private suspend fun playLocalFilesOnMedia3(files: List<File>, startIndex: Int): Boolean {
        if (files.isEmpty()) return false
        return withMediaController { controller ->
            MediaHub.youTube.value = null
            val mediaItems = files.map { f ->
                val uri = Uri.fromFile(f)
                MediaItem.Builder()
                    .setMediaId(uri.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(f.nameWithoutExtension)
                            .build()
                    )
                    .build()
            }
            controller.setMediaItems(mediaItems, startIndex.coerceIn(0, mediaItems.lastIndex), 0L)
            controller.prepare()
            controller.play()
            true
        } ?: false
    }

    private suspend fun getCurrentlyPlayingTitle(): String? {
        MediaHub.youTube.value?.let { yt ->
            if (MediaHub.youTubePlaying.value) return yt.title
        }
        AlertState.current.value?.let { alert ->
            return alert.title
        }
        return withMediaController { controller ->
            if (controller.isPlaying || controller.mediaItemCount > 0) {
                controller.currentMediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    }

    private suspend fun <T> withMediaController(block: (MediaController) -> T): T? =
        withContext(Dispatchers.Main) {
            val future = MediaController.Builder(
                appContext,
                SessionToken(appContext, ComponentName(appContext, MediaService::class.java))
            ).buildAsync()

            try {
                val controller = suspendCancellableCoroutine<MediaController?> { cont ->
                    future.addListener(
                        {
                            val c = runCatching { future.get() }.getOrNull()
                            if (cont.isActive) cont.resume(c)
                        },
                        ContextCompat.getMainExecutor(appContext)
                    )
                }
                controller?.let(block)
            } catch (e: Throwable) {
                Log.w(TAG, "MediaController action failed", e)
                null
            } finally {
                MediaController.releaseFuture(future)
            }
        }

    private data class ShiaOccasionEntry(
        val month: Int,
        val day: Int,
        val titleAr: String,
        val titleEn: String,
    )

    companion object {
        private const val TAG = "VoiceCommandDispatcher"
        private const val KAABA_LAT = 21.422487
        private const val KAABA_LON = 39.826206

        private val SHIA_OCCASIONS = listOf(
            ShiaOccasionEntry(1, 1, "رأس السنة الهجرية", "Islamic New Year"),
            ShiaOccasionEntry(1, 9, "تاسوعاء الإمام الحسين (ع)", "Tasu'a of Imam Husayn (AS)"),
            ShiaOccasionEntry(1, 10, "يوم عاشوراء - استشهاد الإمام الحسين (ع)", "Day of Ashura — Martyrdom of Imam Husayn (AS)"),
            ShiaOccasionEntry(1, 25, "استشهاد الإمام زين العابدين علي بن الحسين (ع)", "Martyrdom of Imam Zayn al-Abidin (AS)"),
            ShiaOccasionEntry(2, 20, "أربعينية الإمام الحسين (ع)", "Arba'een of Imam Husayn (AS)"),
            ShiaOccasionEntry(2, 28, "رحيل النبي الأكرم محمد (ص) واستشهاد الإمام الحسن المجتبى (ع)", "Passing of Prophet Muhammad (PBUH) & Martyrdom of Imam Hasan (AS)"),
            ShiaOccasionEntry(2, 30, "استشهاد الإمام علي بن موسى الرضا (ع)", "Martyrdom of Imam Ali al-Ridha (AS)"),
            ShiaOccasionEntry(3, 8, "استشهاد الإمام الحسن العسكري (ع)", "Martyrdom of Imam Hasan al-Askari (AS)"),
            ShiaOccasionEntry(3, 17, "المولد النبوي الشريف ومولد الإمام جعفر الصادق (ع)", "Birth of Prophet Muhammad (PBUH) & Imam Ja'far al-Sadiq (AS)"),
            ShiaOccasionEntry(5, 5, "مولد السيدة زينب الكبرى (ع)", "Birth of Lady Zaynab (SA)"),
            ShiaOccasionEntry(6, 3, "استشهاد السيدة فاطمة الزهراء (ع)", "Martyrdom of Lady Fatimah al-Zahra (SA)"),
            ShiaOccasionEntry(6, 20, "مولد السيدة فاطمة الزهراء (ع)", "Birth of Lady Fatimah al-Zahra (SA)"),
            ShiaOccasionEntry(7, 1, "مولد الإمام محمد الباقر (ع)", "Birth of Imam Muhammad al-Baqir (AS)"),
            ShiaOccasionEntry(7, 13, "مولد أمير المؤمنين الإمام علي بن أبي طالب (ع)", "Birth of Imam Ali ibn Abi Talib (AS)"),
            ShiaOccasionEntry(7, 25, "استشهاد الإمام موسى الكاظم (ع)", "Martyrdom of Imam Musa al-Kadhim (AS)"),
            ShiaOccasionEntry(7, 27, "يوم المبعث النبوي الشريف", "Day of Mab'ath"),
            ShiaOccasionEntry(8, 3, "مولد الإمام الحسين (ع)", "Birth of Imam Husayn (AS)"),
            ShiaOccasionEntry(8, 4, "مولد أبي الفضل العباس (ع)", "Birth of Abu al-Fadl al-Abbas (AS)"),
            ShiaOccasionEntry(8, 5, "مولد الإمام زين العابدين (ع)", "Birth of Imam Zayn al-Abidin (AS)"),
            ShiaOccasionEntry(8, 15, "مولد الإمام المهدي المنتظر (عجل الله تعالى فرجه)", "Birth of Imam al-Mahdi (AJTF)"),
            ShiaOccasionEntry(9, 15, "مولد الإمام الحسن المجتبى (ع)", "Birth of Imam Hasan al-Mujtaba (AS)"),
            ShiaOccasionEntry(9, 21, "استشهاد أمير المؤمنين الإمام علي (ع)", "Martyrdom of Imam Ali (AS)"),
            ShiaOccasionEntry(9, 23, "ليلة القدر المباركة", "Blessed Night of al-Qadr"),
            ShiaOccasionEntry(10, 1, "عيد الفطر المبارك", "Eid al-Fitr"),
            ShiaOccasionEntry(10, 25, "استشهاد الإمام جعفر الصادق (ع)", "Martyrdom of Imam Ja'far al-Sadiq (AS)"),
            ShiaOccasionEntry(11, 11, "مولد الإمام علي بن موسى الرضا (ع)", "Birth of Imam Ali al-Ridha (AS)"),
            ShiaOccasionEntry(11, 29, "استشهاد الإمام محمد الجواد (ع)", "Martyrdom of Imam Muhammad al-Jawad (AS)"),
            ShiaOccasionEntry(12, 7, "استشهاد الإمام محمد الباقر (ع)", "Martyrdom of Imam Muhammad al-Baqir (AS)"),
            ShiaOccasionEntry(12, 9, "يوم عرفة", "Day of Arafah"),
            ShiaOccasionEntry(12, 10, "عيد الأضحى المبارك", "Eid al-Adha"),
            ShiaOccasionEntry(12, 18, "عيد الغدير الأغر", "Eid al-Ghadir"),
            ShiaOccasionEntry(12, 24, "يوم المباهلة", "Day of Mubahalah"),
        )

        /**
         * Computes initial great-circle Qibla bearing in degrees `[0, 360)` from `(lat, lon)` to the Holy Kaaba.
         */
        fun computeQiblaBearingDegrees(lat: Double, lon: Double): Double {
            val phi1 = Math.toRadians(lat)
            val phi2 = Math.toRadians(KAABA_LAT)
            val deltaLon = Math.toRadians(KAABA_LON - lon)
            val y = sin(deltaLon) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLon)
            val theta = Math.toDegrees(atan2(y, x))
            return (theta + 360.0) % 360.0
        }

        private fun bearingToCardinal(deg: Int, isAr: Boolean): String {
            val idx = (((deg + 22.5) % 360) / 45.0).toInt().coerceIn(0, 7)
            val en = arrayOf("North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest")
            val ar = arrayOf("الشمال", "الشمال الشرقي", "الشرق", "الجنوب الشرقي", "الجنوب", "الجنوب الغربي", "الغرب", "الشمال الغربي")
            return if (isAr) ar[idx] else en[idx]
        }

        private fun lookupShiaOccasion(hijriMonth: Int, hijriDay: Int, isAr: Boolean): String? {
            val entry = SHIA_OCCASIONS.firstOrNull { it.month == hijriMonth && it.day == hijriDay } ?: return null
            return if (isAr) entry.titleAr else entry.titleEn
        }
    }
}
