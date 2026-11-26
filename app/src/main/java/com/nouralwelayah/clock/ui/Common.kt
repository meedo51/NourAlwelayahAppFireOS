package com.nouralwelayah.clock.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nouralwelayah.clock.Sys
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.kiosk.KioskPolicy
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Rounded card like the firmware's mkCard(). */
@Composable
fun Section(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(C_CARD, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title, color = C_ACCENT, fontSize = 17.sp)
        content()
    }
}

@Composable
fun Hint(text: String) = Text(text, color = C_DIM, fontSize = 13.sp)

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) C_TEXT else C_DIM)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Simple drop-down: a button showing the selection and a menu. */
@Composable
fun Dropdown(
    items: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    // edge-to-edge: a tall menu would run under the gesture bar and hide its last items,
    // so keep it to 60% of the screen (it scrolls) and open it at the selected item
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
    val scroll = rememberScrollState()
    val itemPx = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(open) { if (open) scroll.scrollTo(((selected - 2).coerceAtLeast(0)) * itemPx) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(items.getOrElse(selected) { "" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (enabled) C_TEXT else C_DIM)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = maxH),
            scrollState = scroll) {
            items.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

/** Text field for numbers; the caller checks the value on save (like the firmware). */
@Composable
fun NumberField(
    label: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '-' || (decimal && it == '.') }) },
        label = { Text(label, fontSize = 13.sp) }, singleLine = true, enabled = enabled, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

fun displayName(context: Context, uri: String): String {
    if (uri.isBlank()) return "Default"
    if (AppFolders.isFolderRef(uri)) return AppFolders.refLabel(uri).let { if ("(/" in it) it else "$it  (alarms folder)" }
    return runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Chosen file"
}

/**
 * Pick an audio (or, with types, video) file from the phone (kept readable after a reboot). "" = default sound.
 * fromAlarms: also a "Folder" menu with the files in PrayerClock/alarms (stored as "alarms/<name>").
 */
@Composable
fun SoundPicker(
    label: String, uri: String, defaultText: String,
    types: Array<String> = arrayOf("audio/*"), fromAlarms: Boolean = false, onChange: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onChange(picked.toString())
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = C_TEXT)
            Text(if (uri.isBlank()) defaultText else displayName(ctx, uri), color = C_DIM, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fromAlarms) {
            TextButton(onClick = { menu = true }) { Text("Folder") }
            // like the firmware's alarm sound browser: any PrayerClock folder
            if (menu) SoundBrowser(uri, onPick = { menu = false; onChange(it) }, onClose = { menu = false })
        }
        TextButton(onClick = { launcher.launch(types) }) { Text("Choose") }
        if (uri.isNotBlank()) TextButton(onClick = { onChange("") }) { Text("Default") }
    }
}

val FieldWidth = Modifier.width(140.dp)

/** Slider with a round thumb (Material 3 1.3 draws a vertical bar) and no gap in the track around it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoundSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = SliderDefaults.colors()
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        thumb = {
            Box(Modifier.size(36.dp).shadow(3.dp, CircleShape)
                .background(if (enabled) colors.thumbColor else colors.disabledThumbColor, CircleShape))
        },
        track = { state ->
            SliderDefaults.Track(sliderState = state, enabled = enabled, colors = colors,
                thumbTrackGapSize = 0.dp, drawStopIndicator = null)
        },
    )
}

@Composable
fun PermissionRows(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    @Suppress("UNUSED_EXPRESSION") tick

    val pm = Sys.svc(ctx, PowerManager::class.java)
    val batteryOk = if (Build.VERSION.SDK_INT >= 23) pm.isIgnoringBatteryOptimizations(ctx.packageName) else true
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (batteryOk) "Unrestricted battery: enabled" else "Allow running in the background (Doze)",
            Modifier.weight(1f), color = if (batteryOk) C_DIM else C_GOLD, fontSize = 14.sp,
        )
        if (!batteryOk && Build.VERSION.SDK_INT >= 23) OutlinedButton(onClick = {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
        }) { Text("Allow") }
    }

    if (Build.VERSION.SDK_INT >= 31) {
        val am = Sys.svc(ctx, AlarmManager::class.java)
        val exactOk = am.canScheduleExactAlarms()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (exactOk) "Exact alarms: enabled" else "Allow exact alarms",
                Modifier.weight(1f), color = if (exactOk) C_DIM else C_GOLD, fontSize = 14.sp,
            )
            if (!exactOk) OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                }
            }) { Text("Allow") }
        }
    }

    if (Build.VERSION.SDK_INT >= 23) {
        val drawOk = Settings.canDrawOverlays(ctx)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (drawOk) "Display over other apps (open after restart): enabled"
                else "Display over other apps (lets the clock open by itself after a restart)",
                Modifier.weight(1f), color = if (drawOk) C_DIM else C_GOLD, fontSize = 14.sp,
            )
            if (!drawOk) OutlinedButton(onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }.onFailure {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                }
            }) { Text("Allow") }
        }
    }
}

/**
 * Result of [resolveApplianceLocation]: contains either a resolved [location] (from device GPS/Network
 * or indoor Wi-Fi IP geolocation fallback) with optional [city] and [timezone], or `null` with a concise
 * bilingual failure [reasonEn] / [reasonAr].
 */
data class LocationLookupResult(
    val location: Location?,
    val city: String? = null,
    val timezone: String? = null,
    val source: String = "",
    val reasonEn: String = "",
    val reasonAr: String = "",
)

private const val ACTIVE_LOCATION_TIMEOUT_MS = 6_000L
private const val RECENT_LOCATION_MAX_AGE_MS = 30 * 60 * 1_000L // 30 minutes
private const val IP_FALLBACK_CONNECT_TIMEOUT_MS = 4_500
private const val IP_FALLBACK_READ_TIMEOUT_MS = 4_500
private val locationBgExecutor = Executors.newCachedThreadPool()

/**
 * Resolves the appliance's location with multi-stage resilience for indoor tablets:
 * 1. Auto-grants `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` when running as Device Owner.
 * 2. Checks recent `getLastKnownLocation` across `GPS_PROVIDER`, `NETWORK_PROVIDER`, and `PASSIVE_PROVIDER`.
 * 3. Requests an active single fix using [LocationManager.getCurrentLocation] (API 30+) or
 *    [LocationManager.requestSingleUpdate] (API < 30) across `NETWORK_PROVIDER` and `GPS_PROVIDER`
 *    with a 6-second timeout.
 * 4. If both device providers time out or return `null` (common indoors without Google Play Services),
 *    falls back to lightweight HTTPS IP-geolocation (`https://ipwho.is/` and `https://ipapi.co/json/`)
 *    to obtain approximate `latitude`, `longitude`, and `city`.
 * 5. Invokes [done] on the Main thread with a [LocationLookupResult].
 */
@SuppressLint("MissingPermission")
fun resolveApplianceLocation(ctx: Context, done: (LocationLookupResult) -> Unit) {
    val appCtx = ctx.applicationContext
    val mainHandler = Handler(Looper.getMainLooper())
    val completed = AtomicBoolean(false)

    fun deliver(result: LocationLookupResult) {
        if (completed.compareAndSet(false, true)) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                done(result)
            } else {
                mainHandler.post { done(result) }
            }
        }
    }

    // Attempt Device Owner auto-grant first so kiosk tablets never fail due to ungranted permissions
    KioskPolicy.tryAutoGrantAppPermissions(appCtx)

    val hasFine = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasPermission = hasFine || hasCoarse

    val lm = runCatching { Sys.svc(appCtx, LocationManager::class.java) }.getOrNull()
    val allProviders = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    val activeProviders = if (hasPermission && lm != null) {
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }
    } else {
        emptyList()
    }

    // 1. Check for a reasonably fresh last-known location first
    if (hasPermission && lm != null) {
        val lastKnown = allProviders
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }

        if (lastKnown != null && (System.currentTimeMillis() - lastKnown.time) <= RECENT_LOCATION_MAX_AGE_MS) {
            deliver(
                LocationLookupResult(
                    location = lastKnown,
                    city = lastKnown.extras?.getString("ip_city"),
                    source = lastKnown.provider ?: "last_known",
                )
            )
            return
        }
    }

    // Helper that runs the indoor Wi-Fi IP geolocation fallback on a background thread
    fun fallbackToIpGeolocation(staleLastKnown: Location?) {
        locationBgExecutor.execute {
            val ipResult = fetchIpGeolocationFallback()
            if (ipResult != null) {
                deliver(ipResult)
                return@execute
            }
            // If IP fallback also failed, use any older cached location if available
            if (staleLastKnown != null) {
                deliver(
                    LocationLookupResult(
                        location = staleLastKnown,
                        source = staleLastKnown.provider ?: "cached",
                    )
                )
                return@execute
            }
            val online = isNetworkConnected(appCtx)
            val reasonEn = when {
                !hasPermission && !online -> "no permission & offline"
                !hasPermission -> "no permission & IP lookup failed"
                activeProviders.isEmpty() && !online -> "location disabled & offline"
                activeProviders.isEmpty() -> "location disabled"
                !online -> "GPS timeout & offline"
                else -> "no GPS signal indoors & IP lookup unreachable"
            }
            val reasonAr = when {
                !hasPermission && !online -> "لا يوجد إذن موقع ولا اتصال بالإنترنت"
                !hasPermission -> "لا يوجد إذن موقع وتعذّر تحديد الموقع عبر الشبكة"
                activeProviders.isEmpty() && !online -> "خدمة الموقع متوقفة ولا يوجد اتصال بالإنترنت"
                activeProviders.isEmpty() -> "خدمة الموقع متوقفة"
                !online -> "انتهت مهلة GPS ولا يوجد اتصال بالإنترنت"
                else -> "لا توجد إشارة GPS داخلياً وتعذّر تحديد الموقع عبر Wi-Fi"
            }
            deliver(
                LocationLookupResult(
                    location = null,
                    reasonEn = reasonEn,
                    reasonAr = reasonAr,
                )
            )
        }
    }

    val staleCandidate = if (hasPermission && lm != null) {
        allProviders.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
    } else {
        null
    }

    // If we don't have location permission or no active providers are enabled, go straight to Wi-Fi IP fallback
    if (!hasPermission || lm == null || activeProviders.isEmpty()) {
        fallbackToIpGeolocation(staleCandidate)
        return
    }

    // 2. Request an active single fix from NETWORK_PROVIDER and GPS_PROVIDER with a 6-second timeout
    val cancellationSignals = mutableListOf<CancellationSignal>()
    val legacyListeners = mutableListOf<LocationListener>()
    val deviceFixResolved = AtomicBoolean(false)

    fun cleanupActiveRequests() {
        cancellationSignals.forEach { sig -> runCatching { sig.cancel() } }
        legacyListeners.forEach { listener -> runCatching { lm.removeUpdates(listener) } }
    }

    val timeoutRunnable = Runnable {
        if (deviceFixResolved.compareAndSet(false, true)) {
            cleanupActiveRequests()
            fallbackToIpGeolocation(staleCandidate)
        }
    }
    mainHandler.postDelayed(timeoutRunnable, ACTIVE_LOCATION_TIMEOUT_MS)

    var startedAnyRequest = false
    for (provider in activeProviders) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cancellationSignals.add(signal)
            val ok = runCatching {
                lm.getCurrentLocation(
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(appCtx),
                ) { loc ->
                    if (loc != null && deviceFixResolved.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        cleanupActiveRequests()
                        deliver(
                            LocationLookupResult(
                                location = loc,
                                source = provider,
                            )
                        )
                    }
                }
                true
            }.getOrDefault(false)
            if (ok) startedAnyRequest = true
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (deviceFixResolved.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        cleanupActiveRequests()
                        deliver(
                            LocationLookupResult(
                                location = location,
                                source = provider,
                            )
                        )
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            legacyListeners.add(listener)
            val ok = runCatching {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                true
            }.getOrDefault(false)
            if (ok) startedAnyRequest = true
        }
    }

    if (!startedAnyRequest && deviceFixResolved.compareAndSet(false, true)) {
        mainHandler.removeCallbacks(timeoutRunnable)
        cleanupActiveRequests()
        fallbackToIpGeolocation(staleCandidate)
    }
}

/**
 * Lightweight HTTPS IP-geolocation fallback for indoor Wi-Fi tablets without GPS signal or Google Play Services.
 * Tries `https://ipwho.is/` first, then `https://ipapi.co/json/`.
 */
internal fun fetchIpGeolocationFallback(): LocationLookupResult? {
    val endpoints = listOf(
        "https://ipwho.is/",
        "https://ipapi.co/json/",
    )
    for (endpoint in endpoints) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = IP_FALLBACK_CONNECT_TIMEOUT_MS
                readTimeout = IP_FALLBACK_READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "NourAlwelayah-Clock/1.0")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parsed = parseIpGeolocationJson(body)
                if (parsed != null) {
                    return parsed
                }
            }
        } catch (_: Exception) {
            // Try next endpoint
        } finally {
            conn?.disconnect()
        }
    }
    return null
}

/**
 * Parses a JSON response from `ipwho.is` or `ipapi.co/json/` into a [LocationLookupResult].
 */
internal fun parseIpGeolocationJson(jsonText: String): LocationLookupResult? = runCatching {
    val obj = JSONObject(jsonText)
    if (obj.has("success") && !obj.optBoolean("success", true)) {
        return null
    }
    if (obj.optBoolean("error", false)) {
        return null
    }
    val lat = obj.optDouble("latitude", Double.NaN)
    val lon = obj.optDouble("longitude", Double.NaN)
    if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
        return null
    }
    if (lat == 0.0 && lon == 0.0) {
        return null
    }
    val city = obj.optString("city", "").trim()
        .ifEmpty { obj.optString("region", "").trim() }
        .ifEmpty { obj.optString("country_name", obj.optString("country", "")).trim() }
        .takeIf { it.isNotBlank() }

    val tz = when {
        obj.optJSONObject("timezone") != null -> obj.optJSONObject("timezone")?.optString("id", "")?.trim()
        else -> obj.optString("timezone", "").trim()
    }?.takeIf { it.isNotBlank() }

    val loc = Location("ip_geolocation").apply {
        latitude = lat
        longitude = lon
        time = System.currentTimeMillis()
        extras = android.os.Bundle().apply {
            if (!city.isNullOrBlank()) {
                putString("ip_city", city)
            }
            if (!tz.isNullOrBlank()) {
                putString("ip_timezone", tz)
            }
        }
    }
    LocationLookupResult(
        location = loc,
        city = city,
        timezone = tz,
        source = "ip_geolocation",
    )
}.getOrNull()

private fun isNetworkConnected(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.isConnected == true
    }
}.getOrDefault(false)

@SuppressLint("MissingPermission")
fun getLocation(ctx: Context, done: (Location?) -> Unit) {
    resolveApplianceLocation(ctx) { res ->
        done(res.location)
    }
}

@Suppress("DEPRECATION")
fun cityName(ctx: Context, l: Location): String? {
    val ipCity = runCatching { l.extras?.getString("ip_city")?.takeIf { it.isNotBlank() } }.getOrNull()
    val geocoded = runCatching {
        Geocoder(ctx, Locale.ENGLISH).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()
    return geocoded?.takeIf { it.isNotBlank() } ?: ipCity
}
