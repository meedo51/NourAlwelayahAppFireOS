package com.nouralwelayah.clock.data

import android.content.Context
import com.nouralwelayah.clock.core.CALC_METHODS
import com.nouralwelayah.clock.core.FAJR_ANGLE_MAX
import com.nouralwelayah.clock.core.FAJR_ANGLE_MIN
import com.nouralwelayah.clock.core.MAGHRIB_ANGLE_MAX
import com.nouralwelayah.clock.core.MAGHRIB_ANGLE_MIN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/**
 * Same settings as the firmware (storage.h), minus the board-only ones (WiFi, remote, music...).
 * fajrAngle / maghribAngle: 0 = use the method's angles; custom angles are always set together.
 */
data class AppSettings(
    val city: String = "Makkah",
    val lat: Double = 21.4225,
    val lon: Double = 39.8262,
    val zoneId: String = "",               // "" = the phone's time zone
    val locationSet: Boolean = false,      // false until the user picks a location (first-run hint)
    val method: Int = 14,                  // Jafari, like the firmware
    val fajrAngle: Double = 0.0,
    val maghribAngle: Double = 0.0,
    val hijriAdjust: Int = 0,              // -2 .. +2
    val azanOn: List<Boolean> = listOf(true, true, true),  // Fajr, Dhuhr, Maghrib
    val azanSound: List<String> = listOf("", "", ""),      // AzanLibrary file per prayer, "" = azanDefault
    val azanDefault: String = "",          // AzanLibrary file used when a prayer has none, "" = phone alarm sound
    val azanVolume: Int = 15,              // 0 .. 21, like the firmware
    val azanLeadSec: Int = 0,              // start the azan this many seconds early (0 .. 500)
    val offsetMin: List<Int> = listOf(0, 0, 0, 0, 0),      // per prayer, -60 .. 60
    val use24h: Boolean = true,
    val keepScreenOn: Boolean = true,      // while the Clock screen is open
    val azanPicture: Boolean = true,       // tablet: a PictureLibrary azan picture behind the clock during the azan
    val clockPicture: Boolean = false,     // tablet: a PictureLibrary wallpaper behind the clock all the time
    val webRemote: Boolean = true,         // media player: Wi-Fi remote (the clock's PTclock page) on port 8080
    val webPin: String = "",               // Wi-Fi remote PIN, "" = none (like the firmware's remote PIN)
    val brightness: Int = -1,              // tablet: this app's screen brightness 5..100, -1 = the device's own
    val backToClockMin: Int = 5,           // tablet: another tab, untouched this long -> the Clock tab (0 = never)
    val youTubeKey: String = "",           // YouTube Data API v3 key for the YouTube search ("" = no search)
    val clockName: String = "",            // firmware [NAME]: the tablet's Bluetooth name, "" = the device's own
    val kioskMode: Boolean = false,        // tablet + device owner: pin the clock (Lock Task) to hide the lock screen
    val adminPin: String = "9200",         // default technician PIN to access the hidden Admin Dashboard
    val otaServerUrl: String = com.nouralwelayah.clock.BuildConfig.OTA_BASE_URL, // OTA update endpoint URL
    val otaChannel: String = OTA_CHANNEL_STABLE, // "stable" or "beta"
    val maxVolumeLimit: Int = 100,         // hardware/appliance master volume ceiling (0 .. 100)
    val appLanguage: String = "en",        // appliance UI language ("en" or "ar")
    val mediaTimeoutSec: Int = 30,         // customizable media player inactivity timeout in seconds (15, 30, 60, 120)
    val mediaStayAwake: Boolean = false,   // override media player inactivity timeout and keep screen awake
    val selectedWallpaper: String = "",    // selected wallpaper filename in /NourAlwelayah/wallpaper/ ("" = Default Solid Navy)
    val voiceAssistantEnabled: Boolean = true, // offline bilingual voice assistant enabled state
    val voiceRecognitionMode: String = VOICE_MODE_AUTO, // "auto" (Bilingual), "ar" (Arabic Priority), "en" (English Priority)
    val wakeWordEnabled: Boolean = false,  // hands-free wake word ("Welayah") via sherpa-onnx KeywordSpotter
    val wakeWordSensitivity: Float = DEFAULT_WAKE_WORD_SENSITIVITY, // keywordsThreshold (0.10f..0.60f; lower = more sensitive)
    val childLockEnabled: Boolean = false, // client-facing touchscreen child lock ("child_lock")
) {
    /** Appliance is permanently locked to the Media Player role. */
    fun isMediaPlayer(wideScreen: Boolean = true): Boolean = true

    val customAngles: Boolean get() = fajrAngle > 0
    val methodSafe: Int get() = method.coerceIn(0, CALC_METHODS.size - 1)
    val effFajrAngle: Double get() = if (customAngles) fajrAngle else CALC_METHODS[methodSafe].fajrAngle
    val effMaghribAngle: Double
        get() = if (customAngles && maghribAngle > 0) maghribAngle else CALC_METHODS[methodSafe].maghribAngle

    fun zone(): ZoneId = runCatching { if (zoneId.isBlank()) null else ZoneId.of(zoneId) }.getOrNull()
        ?: ZoneId.systemDefault()

    fun toJson(): JSONObject = JSONObject().apply {
        put("city", city); put("lat", lat); put("lon", lon); put("zone", zoneId); put("loc_set", locationSet)
        put("method", method); put("fajr_angle", fajrAngle); put("maghrib_angle", maghribAngle)
        put("hijri_adjust", hijriAdjust)
        put("azan_on", JSONArray(azanOn)); put("azan_sound", JSONArray(azanSound))
        put("azan_default", azanDefault)
        put("azan_volume", azanVolume); put("azan_lead", azanLeadSec)
        put("offsets", JSONArray(offsetMin)); put("use24h", use24h); put("keep_on", keepScreenOn)
        put("azan_bg", azanPicture); put("clock_bg", clockPicture)
        put("selected_wallpaper", selectedWallpaper)
        put("web_remote", webRemote); put("web_pin", webPin); put("brightness", brightness); put("yt_key", youTubeKey)
        put("back_min", backToClockMin); put("clock_name", clockName); put("kiosk", kioskMode)
        put("admin_pin", adminPin); put("ota_server_url", otaServerUrl); put("ota_channel", normalizeOtaChannel(otaChannel))
        put("max_volume_limit", maxVolumeLimit)
        put("app_language", appLanguage)
        put("media_timeout_sec", mediaTimeoutSec)
        put("media_stay_awake", mediaStayAwake)
        put("voice_assistant_enabled", voiceAssistantEnabled)
        put("voice_recognition_mode", normalizeVoiceRecognitionMode(voiceRecognitionMode))
        put("wake_word_enabled", wakeWordEnabled)
        put("wake_word_sensitivity", wakeWordSensitivity.coerceIn(MIN_WAKE_WORD_SENSITIVITY, MAX_WAKE_WORD_SENSITIVITY).toDouble())
        put("child_lock", childLockEnabled)
    }

    /**
     * Maps [voiceRecognitionMode] to the `sherpa-onnx` `OfflineWhisperModelConfig.language` value:
     * - `"auto"` -> `""` (seamless bilingual auto-detect)
     * - `"ar"` -> `"ar"` (Arabic Priority)
     * - `"en"` -> `"en"` (English Priority)
     */
    fun whisperLanguageCode(): String = when (normalizeVoiceRecognitionMode(voiceRecognitionMode)) {
        VOICE_MODE_AR -> "ar"
        VOICE_MODE_EN -> "en"
        else -> ""
    }

    /**
     * Returns the ordered non-blank Azan sound candidates for [azanIndex]
     * (0 = Fajr, 1 = Dhuhr/Asr, 2 = Maghrib/Isha; also supports 5-prayer indices 0..4),
     * checking the per-prayer selection in [azanSound] first, then the global selected [azanDefault]
     * (and Dhuhr slot `azanSound[1]`).
     */
    fun azanCandidatesFor(azanIndex: Int): List<String> {
        val specificRaw = azanSound.getOrNull(azanIndex)?.trim().orEmpty()
        val mappedIndex = when (azanIndex) {
            0 -> 0 // Fajr
            1 -> 1 // Dhuhr / Asr
            2 -> 2 // Maghrib / Isha
            3 -> 1 // Asr -> Dhuhr slot when 5-prayer index is used
            4 -> 2 // Isha -> Maghrib slot when 5-prayer index is used
            else -> azanIndex.coerceIn(0, 2)
        }
        val specificMapped = azanSound.getOrNull(mappedIndex)?.trim().orEmpty()
        val globalDefault = azanDefault.trim()
        val dhuhrSlot = azanSound.getOrNull(1)?.trim().orEmpty()
        return listOf(specificRaw, specificMapped, globalDefault, dhuhrSlot)
            .filter { it.isNotBlank() }
            .distinct()
    }

    /** Same checks as loadSettings() in the firmware. */
    fun validated(): AppSettings {
        var fa = fajrAngle
        var ma = maghribAngle
        if (fa != 0.0 && (fa < FAJR_ANGLE_MIN || fa > FAJR_ANGLE_MAX + 0.05)) fa = 0.0
        if (fa <= 0) ma = 0.0
        else if (ma < MAGHRIB_ANGLE_MIN - 0.05 || ma > MAGHRIB_ANGLE_MAX + 0.05) ma = CALC_METHODS[methodSafe].maghribAngle
        else if (ma < MAGHRIB_ANGLE_MIN) ma = MAGHRIB_ANGLE_MIN
        val cleanWall = selectedWallpaper.trim().substringAfterLast('/')
            .takeIf { it.isNotEmpty() && com.nouralwelayah.clock.system.FileSystemManager.isSafeRelativePath(it) }
            ?: ""
        val cleanSensitivity = if (wakeWordSensitivity.isNaN()) {
            DEFAULT_WAKE_WORD_SENSITIVITY
        } else {
            wakeWordSensitivity.coerceIn(MIN_WAKE_WORD_SENSITIVITY, MAX_WAKE_WORD_SENSITIVITY)
        }
        val slotCount = maxOf(3, azanSound.size.coerceAtMost(5))
        val cleanAzanSound = List(slotCount) { azanSound.getOrElse(it) { "" }.trim() }
        val cleanAzanDefault = azanDefault.trim().ifBlank { cleanAzanSound.getOrElse(1) { "" } }
        return copy(
            lat = lat.coerceIn(-90.0, 90.0), lon = lon.coerceIn(-180.0, 180.0),
            method = methodSafe, fajrAngle = fa, maghribAngle = ma,
            hijriAdjust = hijriAdjust.coerceIn(-2, 2),
            azanOn = List(3) { azanOn.getOrElse(it) { true } },
            azanSound = cleanAzanSound,
            azanDefault = cleanAzanDefault,
            azanVolume = azanVolume.coerceIn(0, 21), azanLeadSec = azanLeadSec.coerceIn(0, 500),
            offsetMin = List(5) { offsetMin.getOrElse(it) { 0 }.coerceIn(-60, 60) },
            adminPin = adminPin.ifBlank { "9200" },
            otaServerUrl = otaServerUrl.trim().ifBlank { com.nouralwelayah.clock.BuildConfig.OTA_BASE_URL },
            otaChannel = normalizeOtaChannel(otaChannel),
            maxVolumeLimit = maxVolumeLimit.coerceIn(0, 100),
            appLanguage = if (appLanguage.trim().lowercase() == "ar") "ar" else "en",
            mediaTimeoutSec = mediaTimeoutSec.coerceIn(5, 3600),
            selectedWallpaper = cleanWall,
            voiceRecognitionMode = normalizeVoiceRecognitionMode(voiceRecognitionMode),
            wakeWordSensitivity = cleanSensitivity,
        )
    }

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val d = AppSettings()
            fun bools(k: String, def: List<Boolean>) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optBoolean(it) } } ?: def
            fun strs(k: String, def: List<String>) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } } ?: def
            fun ints(k: String, def: List<Int>) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optInt(it) } } ?: def
            return AppSettings(
                city = o.optString("city", d.city), lat = o.optDouble("lat", d.lat), lon = o.optDouble("lon", d.lon),
                zoneId = o.optString("zone", d.zoneId), locationSet = o.optBoolean("loc_set", d.locationSet),
                method = o.optInt("method", d.method), fajrAngle = o.optDouble("fajr_angle", d.fajrAngle),
                maghribAngle = o.optDouble("maghrib_angle", d.maghribAngle),
                hijriAdjust = o.optInt("hijri_adjust", d.hijriAdjust),
                azanOn = bools("azan_on", d.azanOn), azanSound = strs("azan_sound", d.azanSound),
                // older settings had no default: Fajr and Maghrib fell back to the Dhuhr sound
                azanDefault = if (o.has("azan_default")) o.optString("azan_default")
                    else strs("azan_sound", d.azanSound).getOrElse(1) { "" },
                azanVolume = o.optInt("azan_volume", d.azanVolume), azanLeadSec = o.optInt("azan_lead", d.azanLeadSec),
                offsetMin = ints("offsets", d.offsetMin), use24h = o.optBoolean("use24h", d.use24h),
                keepScreenOn = o.optBoolean("keep_on", d.keepScreenOn),
                azanPicture = o.optBoolean("azan_bg", d.azanPicture),
                clockPicture = o.optBoolean("clock_bg", d.clockPicture),
                selectedWallpaper = o.optString("selected_wallpaper", d.selectedWallpaper),
                webRemote = o.optBoolean("web_remote", d.webRemote), webPin = o.optString("web_pin", d.webPin),
                brightness = o.optInt("brightness", d.brightness).let { if (it < 0) -1 else it.coerceIn(5, 100) },
                youTubeKey = o.optString("yt_key", d.youTubeKey),
                backToClockMin = o.optInt("back_min", d.backToClockMin).coerceIn(0, 60),
                clockName = o.optString("clock_name", d.clockName).trim().let { if (ClockName.valid(it)) it else "" },
                kioskMode = o.optBoolean("kiosk", d.kioskMode),
                adminPin = o.optString("admin_pin", d.adminPin),
                otaServerUrl = o.optString("ota_server_url", d.otaServerUrl),
                otaChannel = normalizeOtaChannel(o.optString("ota_channel", d.otaChannel)),
                maxVolumeLimit = o.optInt("max_volume_limit", d.maxVolumeLimit),
                appLanguage = o.optString("app_language", d.appLanguage),
                mediaTimeoutSec = o.optInt("media_timeout_sec", d.mediaTimeoutSec),
                mediaStayAwake = o.optBoolean("media_stay_awake", d.mediaStayAwake),
                voiceAssistantEnabled = o.optBoolean("voice_assistant_enabled", d.voiceAssistantEnabled),
                voiceRecognitionMode = normalizeVoiceRecognitionMode(
                    o.optString("voice_recognition_mode", d.voiceRecognitionMode)
                ),
                wakeWordEnabled = o.optBoolean("wake_word_enabled", d.wakeWordEnabled),
                wakeWordSensitivity = o.optDouble("wake_word_sensitivity", d.wakeWordSensitivity.toDouble()).toFloat(),
                childLockEnabled = o.optBoolean("child_lock", d.childLockEnabled),
            ).validated()
        }

        const val OTA_CHANNEL_STABLE = "stable"
        const val OTA_CHANNEL_BETA = "beta"

        const val VOICE_MODE_AUTO = "auto"
        const val VOICE_MODE_AR = "ar"
        const val VOICE_MODE_EN = "en"

        const val DEFAULT_WAKE_WORD_SENSITIVITY = 0.25f
        const val MIN_WAKE_WORD_SENSITIVITY = 0.10f
        const val MAX_WAKE_WORD_SENSITIVITY = 0.60f

        fun normalizeOtaChannel(raw: String?): String =
            when (raw?.trim()?.lowercase()) {
                OTA_CHANNEL_BETA -> OTA_CHANNEL_BETA
                else -> OTA_CHANNEL_STABLE
            }

        fun normalizeVoiceRecognitionMode(raw: String?): String =
            when (raw?.trim()?.lowercase()) {
                VOICE_MODE_AR -> VOICE_MODE_AR
                VOICE_MODE_EN -> VOICE_MODE_EN
                else -> VOICE_MODE_AUTO
            }
    }
}

/** Settings + alarms, kept in SharedPreferences as JSON. One instance per process. */
object Store {
    private const val PREFS = "prayer_clock"
    private lateinit var appContext: Context
    private val _settings = MutableStateFlow(AppSettings())
    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val settings: StateFlow<AppSettings> get() = _settings
    val alarms: StateFlow<List<Alarm>> get() = _alarms

    @Volatile
    var isHydrated: Boolean = false
        private set

    @Volatile
    private var hydratedWithUnlockedUser: Boolean = false

    private fun isUserUnlocked(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            um?.isUserUnlocked ?: true
        } else {
            true
        }
    }

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        val unlocked = isUserUnlocked(app)
        if (::appContext.isInitialized) {
            if (unlocked && !hydratedWithUnlockedUser) {
                hydrateFromPrefs(unlocked = true)
            }
            isHydrated = true
            return
        }
        appContext = app
        hydrateFromPrefs(unlocked = unlocked)
        isHydrated = true
    }

    private fun hydrateFromPrefs(unlocked: Boolean) {
        val p = prefs(appContext)
        _settings.value = runCatching { AppSettings.fromJson(JSONObject(p.getString("settings", "{}")!!)) }
            .getOrDefault(AppSettings())
        _alarms.value = runCatching {
            val a = JSONArray(p.getString("alarms", "[]"))
            List(a.length()) { Alarm.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (unlocked) {
            hydratedWithUnlockedUser = true
            // Mirror current settings & alarms into Device Protected Storage so Direct Boot reads stay consistent
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                runCatching {
                    val dpPrefs = appContext.createDeviceProtectedStorageContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    dpPrefs.edit()
                        .putString("settings", _settings.value.toJson().toString())
                        .putString("alarms", JSONArray(_alarms.value.map { it.toJson() }).toString())
                        .apply()
                }
            }
        }
    }

    /**
     * Direct-Boot-safe [android.content.SharedPreferences] accessor:
     * Checks `UserManager.isUserUnlocked` on API 24+ and falls back to
     * `context.createDeviceProtectedStorageContext()` when the device is in Direct Boot
     * (`LOCKED_BOOT_COMPLETED`) before user unlock.
     */
    fun prefs(context: Context = appContext): android.content.SharedPreferences {
        val baseCtx = if (::appContext.isInitialized) appContext else context.applicationContext
        val storageCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
            !isUserUnlocked(baseCtx)
        ) {
            baseCtx.createDeviceProtectedStorageContext()
        } else {
            baseCtx
        }
        return storageCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun saveSettings(s: AppSettings) {
        val v = s.validated()
        _settings.value = v
        if (!::appContext.isInitialized) return
        val json = v.toJson().toString()
        runCatching {
            prefs().edit().putString("settings", json).apply()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isUserUnlocked(appContext)) {
                appContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("settings", json)
                    .apply()
            }
            backup()
        }
    }

    fun saveAlarms(list: List<Alarm>) {
        _alarms.value = list
        if (!::appContext.isInitialized) return
        val json = JSONArray(list.map { it.toJson() }).toString()
        runCatching {
            prefs().edit().putString("alarms", json).apply()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isUserUnlocked(appContext)) {
                appContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("alarms", json)
                    .apply()
            }
            backup()
        }
    }

    /** PrayerClock/config/settings.json + alarms.json, written in the background. */
    private fun backup() { val c = appContext; Thread { AppFolders.backup(c) }.start() }

    // snooze (survives the process being killed)
    var snoozeUntil: Long
        get() = prefs().getLong("snooze_until", 0L)
        set(v) { prefs().edit().putLong("snooze_until", v).apply() }
    var snoozeAlarmId: Long
        get() = prefs().getLong("snooze_id", -1L)
        set(v) { prefs().edit().putLong("snooze_id", v).apply() }
}
