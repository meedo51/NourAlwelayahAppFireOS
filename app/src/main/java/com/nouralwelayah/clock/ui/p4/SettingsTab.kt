package com.nouralwelayah.clock.ui.p4

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.Kiosk
import com.nouralwelayah.clock.alert.AlertScheduler
import com.nouralwelayah.clock.core.CALC_METHODS
import com.nouralwelayah.clock.core.fmtAngle
import com.nouralwelayah.clock.core.isSunsetAngle
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.CityFile
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.kiosk.KioskPolicy
import com.nouralwelayah.clock.media.MediaActivity
import com.nouralwelayah.clock.ui.AboutNourAlwelayahDialog
import com.nouralwelayah.clock.ui.C_ACCENT
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.ClockPicturesSection
import com.nouralwelayah.clock.ui.Dropdown
import com.nouralwelayah.clock.ui.Hint
import com.nouralwelayah.clock.ui.NumberField
import com.nouralwelayah.clock.ui.OnlineCityDialog
import com.nouralwelayah.clock.ui.RoundSlider
import com.nouralwelayah.clock.ui.Section
import com.nouralwelayah.clock.ui.cityName
import com.nouralwelayah.clock.ui.getLocation
import com.nouralwelayah.clock.ui.isArabic
import com.nouralwelayah.clock.ui.resolveApplianceDeviceNumber
import com.nouralwelayah.clock.ui.resolveApplianceLocation
import com.nouralwelayah.clock.ui.tr
import com.nouralwelayah.clock.web.WebRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * SettingsTab: location (left), time zone / method / Hijri / language (right),
 * 24 h / brightness / language switcher / Save (middle bar),
 * plus read-only Bluetooth hardware identity, Clock pictures, and Client Child Lock.
 */
@Composable
fun SettingsTab(s: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ar = isArabic()
    var city by remember(s) { mutableStateOf(s.city) }
    var lat by remember(s) { mutableStateOf(String.format(Locale.US, "%.4f", s.lat)) }
    var lon by remember(s) { mutableStateOf(String.format(Locale.US, "%.4f", s.lon)) }
    val zones = remember(ar) {
        val defaultLabel = if (ar) {
            "المنطقة الزمنية للجهاز (${ZoneId.systemDefault().id})"
        } else {
            "Appliance time zone (${ZoneId.systemDefault().id})"
        }
        listOf(defaultLabel to "") + WebRemote.TZ_LIST
    }
    val zoneNow = zones.indexOfFirst { it.second == s.zoneId }
    var tz by remember(s) { mutableIntStateOf(if (zoneNow >= 0) zoneNow else zones.size) }
    var tzOff by remember(s) { mutableStateOf(if (zoneNow >= 0) "" else s.zoneId) }
    var method by remember(s) { mutableIntStateOf(s.methodSafe) }
    var hijri by remember(s) { mutableIntStateOf(s.hijriAdjust) }
    var h24 by remember(s) { mutableStateOf(s.use24h) }
    var msg by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(s.childLockEnabled) {
        if (s.childLockEnabled && showAboutDialog) {
            showAboutDialog = false
        }
    }

    val cityList by CityFile.cities.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CityFile.reload(ctx) }
    }

    fun useLocation(l: Location, fallbackCity: String? = null, fallbackTimezone: String? = null) {
        lat = String.format(Locale.US, "%.4f", l.latitude)
        lon = String.format(Locale.US, "%.4f", l.longitude)
        if (!fallbackTimezone.isNullOrBlank()) {
            val zIdx = zones.indexOfFirst { it.second == fallbackTimezone }
            if (zIdx >= 0) {
                tz = zIdx
            } else {
                tz = zones.size
                tzOff = fallbackTimezone
            }
        }
        scope.launch {
            val resolvedCity = withContext(Dispatchers.IO) { cityName(ctx, l) }
                ?: fallbackCity?.takeIf { it.isNotBlank() }
            if (!resolvedCity.isNullOrBlank()) {
                city = resolvedCity.take(31)
            }
            msg = if (ar) "تم تحديد الموقع - اضغط حفظ." else "Location found - press Save."
        }
    }

    fun runLocationLookup(permissionDenied: Boolean = false) {
        msg = if (ar) "جارٍ تحديد الموقع..." else "Finding the location..."
        resolveApplianceLocation(ctx) { res ->
            val loc = res.location
            if (loc != null) {
                useLocation(loc, fallbackCity = res.city, fallbackTimezone = res.timezone)
            } else {
                val reason = if (ar) {
                    res.reasonAr.ifBlank {
                        if (permissionDenied) "تم رفض إذن الموقع" else "تعذّر الوصول للموقع"
                    }
                } else {
                    res.reasonEn.ifBlank {
                        if (permissionDenied) "no permission" else "unavailable"
                    }
                }
                msg = if (ar) {
                    "الموقع غير متاح ($reason)"
                } else {
                    "Location not available ($reason)"
                }
            }
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val granted = r.values.any { it }
        runLocationLookup(permissionDenied = !granted)
    }

    fun triggerGpsButton() {
        KioskPolicy.tryAutoGrantAppPermissions(ctx)
        val hasFine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            runLocationLookup(permissionDenied = false)
        } else {
            perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val requestedSettingsAction by com.nouralwelayah.clock.media.MediaHub.requestedSettingsAction.collectAsState()
    LaunchedEffect(requestedSettingsAction) {
        when (requestedSettingsAction) {
            "ONLINE_CITY" -> {
                online = true
                com.nouralwelayah.clock.media.MediaHub.requestedSettingsAction.value = null
            }
            "GPS_SYNC" -> {
                com.nouralwelayah.clock.media.MediaHub.requestedSettingsAction.value = null
                triggerGpsButton()
            }
        }
    }

    fun save() {
        val la = lat.toDoubleOrNull()
        val lo = lon.toDoubleOrNull()
        if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
            msg = if (ar) "خط العرض أو خط الطول غير صالح" else "Invalid latitude / longitude"
            return
        }
        val zone = if (tz < zones.size) {
            zones[tz].second
        } else {
            runCatching {
                val t = tzOff.trim().removePrefix("UTC").removePrefix("GMT")
                if ('/' in t) {
                    ZoneId.of(t).id
                } else {
                    val neg = t.startsWith("-")
                    val b = t.trimStart('+', '-')
                    val mins = if (':' in b) {
                        b.substringBefore(':').toInt() * 60 + b.substringAfter(':').toInt()
                    } else {
                        (b.toDouble() * 60).toInt()
                    }
                    ZoneOffset.ofTotalSeconds((if (neg) -mins else mins) * 60).id
                }
            }.getOrNull() ?: run {
                msg = if (ar) {
                    "فارق توقيت غير صالح - استخدم مثلاً +3 أو Asia/Riyadh"
                } else {
                    "Invalid UTC offset - use e.g. +3, -5:30 or Asia/Riyadh"
                }
                return
            }
        }
        Store.saveSettings(
            Store.settings.value.copy(
                city = city.trim().ifEmpty { "My location" }.take(31),
                lat = la,
                lon = lo,
                zoneId = zone,
                locationSet = true,
                method = method,
                hijriAdjust = hijri,
                use24h = h24,
            )
        )
        AlertScheduler.reschedule(ctx)
        msg = if (ar) "تم الحفظ." else "Saved."
    }

    val s4 = LocalP4.current
    with(s4) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.p)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.p)) {
                // ---- location 600 px
                P4Card(Modifier.width(600.p).height(470.p)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.p)) {
                        OutlinedTextField(
                            city,
                            { city = it.take(31) },
                            label = { Text(tr("City name", "اسم المدينة")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.p), verticalAlignment = Alignment.CenterVertically) {
                            Dropdown(
                                listOf(tr("☰  List", "☰  القائمة")) + cityList.map { it.name },
                                0,
                                { i ->
                                    if (i > 0) {
                                        cityList[i - 1].let { c ->
                                            city = c.name
                                            lat = c.lat.toString()
                                            lon = c.lon.toString()
                                            zones.indexOfFirst { it.second == c.zone }.let { z ->
                                                if (z >= 0) tz = z else { tz = zones.size; tzOff = c.zone }
                                            }
                                            msg = if (ar) "${c.name} - اضغط حفظ" else "${c.name} - press Save"
                                        }
                                    }
                                },
                                Modifier.width(200.p),
                            )
                            P4Button("⌖  GPS", Modifier.size(150.p, 56.p)) {
                                triggerGpsButton()
                            }
                            P4Button(tr("⬇  Online", "⬇  بحث"), Modifier.size(170.p, 56.p)) { online = true }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.p)) {
                            NumberField(tr("Latitude", "خط العرض"), lat, { lat = it }, Modifier.weight(1f), decimal = true)
                            NumberField(tr("Longitude", "خط الطول"), lon, { lon = it }, Modifier.weight(1f), decimal = true)
                        }
                        if (msg.isNotEmpty()) P4Text(msg, 20, C_GOLD)
                        P4Text(
                            tr(
                                "List: cities built into the appliance. GPS: the appliance's location. Online: search any city (with its time zone). Or type the latitude and longitude.",
                                "القائمة: المدن المدمجة في الجهاز. GPS: موقع الجهاز الحالي. بحث: ابحث عن أي مدينة عبر الإنترنت، أو أدخل خط العرض والطول يدوياً."
                            ),
                            20,
                            C_DIM,
                        )
                    }
                }
                // ---- time zone, method, Hijri, Language 630 px
                P4Card(Modifier.weight(1f).height(470.p)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.p)) {
                        P4Text(tr("Time zone", "المنطقة الزمنية"), 20, C_DIM)
                        Dropdown(
                            zones.map { it.first } + tr("Custom UTC offset / zone", "منطقة زمنية مخصصة / UTC"),
                            tz,
                            { tz = it },
                            Modifier.fillMaxWidth(),
                        )
                        if (tz >= zones.size) {
                            OutlinedTextField(
                                tzOff,
                                { tzOff = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("+3:00   or   Asia/Riyadh") },
                            )
                        }
                        P4Text(tr("Calculation method (Fajr / Maghrib angles)", "طريقة الحساب (زوايا الفجر / المغرب)"), 20, C_DIM)
                        Dropdown(
                            CALC_METHODS.map { m ->
                                "${m.name}  (${String.format(Locale.US, "%.1f", m.fajrAngle)} / " +
                                    (if (isSunsetAngle(m.maghribAngle)) tr("sunset", "الغروب") else fmtAngle(m.maghribAngle)) + ")"
                            },
                            method,
                            { method = it },
                            Modifier.fillMaxWidth(),
                        )
                        P4Text(tr("Hijri date adjust (local moon sighting)", "تعديل التاريخ الهجري (رؤية الهلال)"), 20, C_DIM)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.p)) {
                            Dropdown(
                                listOf(
                                    tr("-2 days", "-٢ يوم"),
                                    tr("-1 day", "-١ يوم"),
                                    tr("0 (Umm al-Qura)", "٠ (أم القرى)"),
                                    tr("+1 day", "+١ يوم"),
                                    tr("+2 days", "+٢ يوم"),
                                ),
                                hijri + 2,
                                { hijri = it - 2 },
                                Modifier.width(300.p),
                            )
                            Switch(
                                checked = s.clockPicture && s.selectedWallpaper.isNotBlank(),
                                onCheckedChange = { wantOn ->
                                    if (wantOn) {
                                        val chosen = s.selectedWallpaper.ifBlank {
                                            com.nouralwelayah.clock.ui.listWallpaperFiles(ctx).firstOrNull()?.name ?: ""
                                        }
                                        Store.saveSettings(
                                            Store.settings.value.copy(
                                                clockPicture = chosen.isNotBlank(),
                                                selectedWallpaper = chosen,
                                            )
                                        )
                                    } else {
                                        Store.saveSettings(
                                            Store.settings.value.copy(
                                                clockPicture = false,
                                                selectedWallpaper = "",
                                            )
                                        )
                                    }
                                    com.nouralwelayah.clock.ui.picturesChanged()
                                },
                            )
                            P4Text(tr("Clock picture", "خلفية الساعة"), 20)
                        }
                    }
                }
            }
            // ---- 24 h, brightness, keep on, Save
            P4Card(Modifier.fillMaxWidth().height(100.p)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.p)) {
                    P4Text(tr("24-hour clock", "نظام ٢٤ ساعة"), 20)
                    Switch(h24, { h24 = it })
                    P4Text(tr("Brightness", "السطوع"), 20)
                    var b by remember(s.brightness) { mutableFloatStateOf(if (s.brightness < 0) 100f else s.brightness.toFloat()) }
                    RoundSlider(
                        b,
                        { b = it },
                        Modifier.width(220.p),
                        valueRange = 5f..100f,
                        onValueChangeFinished = { Store.saveSettings(Store.settings.value.copy(brightness = b.toInt())) },
                    )
                    P4Button(if (s.brightness < 0) tr("Auto ✓", "تلقائي ✓") else tr("Auto", "تلقائي"), Modifier.size(100.p, 50.p), color = GREY) {
                        Store.saveSettings(Store.settings.value.copy(brightness = -1))
                    }
                    P4Text(tr("Keep screen on", "إبقاء الشاشة قيد التشغيل"), 20)
                    Switch(s.keepScreenOn, { Store.saveSettings(Store.settings.value.copy(keepScreenOn = it)) })
                    Column(Modifier.weight(1f)) {}
                    P4Button(tr("💾  Save", "💾  حفظ"), Modifier.size(180.p, 64.p)) { save() }
                }
            }
            Section(tr("This device", "هذا الجهاز"), Modifier.fillMaxWidth()) {
                LanguageSwitcherSectionRow(s)
                BluetoothNameReadOnlyRow()
            }
            com.nouralwelayah.clock.ui.VoiceAssistantSection(
                s = s,
                modifier = Modifier.fillMaxWidth(),
                onStatusMessage = { msg = it },
            )
            ClockPicturesSection(s, Modifier.fillMaxWidth())
            ChildLockSection(s)
            AboutNourAlwelayahSection(
                s = s,
                onOpenAbout = {
                    if (!s.childLockEnabled) {
                        showAboutDialog = true
                    }
                },
            )
        }
    }
    if (showAboutDialog && !s.childLockEnabled) {
        AboutNourAlwelayahDialog(
            onDismiss = { showAboutDialog = false },
        )
    }
    if (online) {
        OnlineCityDialog(city, onDismiss = { online = false }) { p ->
            city = p.name.take(31)
            lat = String.format(Locale.US, "%.4f", p.lat)
            lon = String.format(Locale.US, "%.4f", p.lon)
            if (p.zone.isNotEmpty()) {
                zones.indexOfFirst { it.second == p.zone }.let { z ->
                    if (z >= 0) tz = z else { tz = zones.size; tzOff = p.zone }
                }
            }
            msg = if (ar) "${p.label} - اضغط حفظ" else "${p.label} - press Save"
            if (p.zone.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val r = CityFile.save(ctx, city, p.lat, p.lon, p.zone)
                    msg = "${p.label} - ${CityFile.savedText(r)} - " + if (ar) "اضغط حفظ" else "press Save"
                }
            }
            online = false
        }
    }
}

@Composable
private fun LanguageSwitcherSectionRow(s: AppSettings) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(
            text = tr("Appliance language / لغة الجهاز:", "لغة الجهاز / Appliance language:"),
            color = C_DIM,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        if (s.appLanguage != "ar") {
            Button(onClick = { Store.saveSettings(Store.settings.value.copy(appLanguage = "en")) }) {
                Text("English ✓")
            }
            OutlinedButton(onClick = { Store.saveSettings(Store.settings.value.copy(appLanguage = "ar")) }) {
                Text("العربية", color = C_GOLD)
            }
        } else {
            OutlinedButton(onClick = { Store.saveSettings(Store.settings.value.copy(appLanguage = "en")) }) {
                Text("English", color = C_GOLD)
            }
            Button(onClick = { Store.saveSettings(Store.settings.value.copy(appLanguage = "ar")) }) {
                Text("العربية ✓")
            }
        }
    }
}

/**
 * Read-only Bluetooth hardware name display via [BluetoothAdapter.getDefaultAdapter]?.name.
 */
@SuppressLint("MissingPermission")
@Composable
private fun BluetoothNameReadOnlyRow() {
    val ctx = LocalContext.current
    val hwBluetoothName = remember {
        val canReadBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (canReadBt) {
            @Suppress("DEPRECATION")
            runCatching { BluetoothAdapter.getDefaultAdapter()?.name }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: KioskPolicy.APPLIANCE_BT_NAME
        } else {
            KioskPolicy.APPLIANCE_BT_NAME
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(tr("Bluetooth name:", "اسم البلوتوث:"), color = C_DIM, fontSize = 16.sp)
        Text(hwBluetoothName, color = C_GOLD, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
    }
    Hint(
        tr(
            "Hardware Bluetooth identity broadcasted to the Nour Alwelayah companion remote.",
            "معرّف البلوتوث الخاص بالجهاز للاتصال بتطبيق التحكم عن بعد (نور الولاية)."
        )
    )
}

@Composable
private fun ChildLockSection(s: AppSettings) {
    Section(tr("Child Lock / قفل الأطفال", "قفل الأطفال / Child Lock"), Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Switch(
                checked = s.childLockEnabled,
                onCheckedChange = { wantLocked ->
                    Store.saveSettings(Store.settings.value.copy(childLockEnabled = wantLocked))
                    if (wantLocked) {
                        com.nouralwelayah.clock.media.MediaHub.forceClockTab.value = true
                    }
                },
                modifier = androidx.compose.ui.Modifier.then(
                    androidx.compose.ui.Modifier
                ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr(
                        "Lock touchscreen against accidental taps",
                        "قفل شاشة اللمس لمنع اللمس غير المقصود من الأطفال"
                    ),
                    color = if (s.childLockEnabled) C_GOLD else C_TEXT,
                    fontSize = 16.sp,
                )
                Text(
                    text = tr(
                        "Keeps the clock, prayer times, Azan, and hands-free Voice Assistant (\"Welayah\") active. Hold the lock icon on the clock screen for 3 seconds to unlock.",
                        "يبقي الساعة ومواقيت الصلاة والأذان والمساعد الصوتي («ولاية») قيد التشغيل. لفتح القفل، اضغط مطولاً على رمز القفل في شاشة الساعة لمدة ٣ ثوانٍ."
                    ),
                    color = C_DIM,
                    fontSize = 13.sp,
                )
            }
            Button(
                onClick = {
                    Store.saveSettings(Store.settings.value.copy(childLockEnabled = true))
                    com.nouralwelayah.clock.media.MediaHub.forceClockTab.value = true
                },
            ) {
                Text(tr("Lock Screen Now", "قفل الشاشة الآن"))
            }
        }
    }
}

@Composable
private fun AboutNourAlwelayahSection(
    s: AppSettings,
    onOpenAbout: () -> Unit,
) {
    val ctx = LocalContext.current
    val deviceNumber = remember(ctx) { resolveApplianceDeviceNumber(ctx) }
    Section(tr("About Nour Alwelayah / حول نور الولاية", "حول نور الولاية / About Nour Alwelayah"), Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tr(
                        "Nour Alwelayah Smart Clock  •  v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        "ساعة نور الولاية الذكية  •  v${BuildConfig.VERSION_NAME} (رقم البناء ${BuildConfig.VERSION_CODE})"
                    ),
                    color = C_GOLD,
                    fontSize = 16.sp,
                )
                Text(
                    text = tr(
                        "Device: $deviceNumber  •  Released: ${BuildConfig.RELEASE_DATE}  •  Developers: Yaser Alsaleh & Ahmed Alsaleh",
                        "رقم الجهاز: $deviceNumber  •  تاريخ الإصدار: ${BuildConfig.RELEASE_DATE}  •  المطورون: Yaser Alsaleh & Ahmed Alsaleh"
                    ),
                    color = C_DIM,
                    fontSize = 13.sp,
                )
            }
            Button(
                onClick = onOpenAbout,
                enabled = !s.childLockEnabled,
            ) {
                Text(tr("About Nour Alwelayah", "حول نور الولاية"))
            }
        }
    }
}


