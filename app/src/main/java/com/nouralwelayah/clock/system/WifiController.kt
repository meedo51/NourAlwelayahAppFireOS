package com.nouralwelayah.clock.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.Sys
import com.nouralwelayah.clock.localIpv4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents a deduplicated nearby Wi-Fi network discovered by [WifiController].
 */
data class WifiScannedNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val signalLevel: Int, // 0..4
    val isSecured: Boolean,
    val securityLabel: String,
    val isCurrentNetwork: Boolean,
)

/**
 * Snapshot of the appliance's current Wi-Fi state.
 */
data class WifiStatusSnapshot(
    val isWifiEnabled: Boolean = true,
    val isConnected: Boolean = false,
    val ssid: String = "",
    val rssi: Int = -100,
    val signalLevel: Int = 0, // 0..4
    val ipAddress: String = "",
)

/**
 * Result of a programmatic Wi-Fi connection attempt.
 */
data class WifiConnectResult(
    val accepted: Boolean,
    val isDeviceOwner: Boolean,
    val messageEn: String,
    val messageAr: String,
    val needsSystemPanelFallback: Boolean = false,
)

/**
 * In-App Wi-Fi Scanner, Connection Engine & Status Monitor for the Nour Alwelayah Appliance.
 *
 * - Performs active Wi-Fi scans via [WifiManager.startScan] and listens for
 *   [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION] to emit deduplicated SSIDs sorted by RSSI.
 * - Connects programmatically via [WifiConfiguration] (when running as Device Owner or on API <= 28)
 *   and [WifiNetworkSuggestion] (on API 29+).
 * - Provides [openConnectivityPanel] to launch Android's floating internet panel
 *   ([Settings.Panel.ACTION_INTERNET_CONNECTIVITY]) if the OS restricts silent network switching on
 *   non-Device-Owner test builds.
 */
object WifiController {
    private const val TAG = "WifiController"

    private val _networks = MutableStateFlow<List<WifiScannedNetwork>>(emptyList())
    val networks: StateFlow<List<WifiScannedNetwork>> = _networks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _status = MutableStateFlow(WifiStatusSnapshot())
    val status: StateFlow<WifiStatusSnapshot> = _status.asStateFlow()

    /**
     * Required runtime permissions for Wi-Fi scanning on the current Android API level.
     */
    fun scanPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> emptyArray()
    }

    fun hasScanPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
            if (nearbyGranted) return true
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
    }

    /**
     * Queries the current Wi-Fi connection snapshot (SSID, RSSI, 0..4 signal bars, IP).
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun refreshStatus(context: Context): WifiStatusSnapshot {
        val appCtx = context.applicationContext
        val wm = runCatching { Sys.svc(appCtx, WifiManager::class.java) }.getOrNull()
        val enabled = wm?.isWifiEnabled == true
        val info = runCatching { wm?.connectionInfo }.getOrNull()
        val rawSsid = info?.ssid?.trim('"').orEmpty()
        val cleanSsid = if (rawSsid.isEmpty() || rawSsid == "<unknown ssid>") "" else rawSsid
        val ip = localIpv4(appCtx)
        val rssi = info?.rssi ?: -100
        val hasWifiTransport = runCatching {
            val cm = Sys.svc(appCtx, ConnectivityManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(net)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                cm?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI &&
                    cm.activeNetworkInfo?.isConnected == true
            }
        }.getOrDefault(false)

        val connected = enabled && (ip.isNotEmpty() || hasWifiTransport || (cleanSsid.isNotEmpty() && rssi > -120))
        val level = if (!connected) 0 else calculateSignalLevel(rssi)
        val snap = WifiStatusSnapshot(
            isWifiEnabled = enabled,
            isConnected = connected,
            ssid = cleanSsid,
            rssi = rssi,
            signalLevel = level,
            ipAddress = ip,
        )
        _status.value = snap
        return snap
    }

    /**
     * Triggers an active Wi-Fi scan and immediately parses any cached scan results.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun startScan(context: Context): Boolean {
        val appCtx = context.applicationContext
        val wm = runCatching { Sys.svc(appCtx, WifiManager::class.java) }.getOrNull() ?: return false
        if (!wm.isWifiEnabled) {
            runCatching { wm.isWifiEnabled = true }
        }
        refreshStatus(appCtx)
        _isScanning.value = true
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        // Always update with latest available scanResults immediately as well
        parseScanResults(appCtx, wm)
        if (!started) {
            _isScanning.value = false
        }
        return started
    }

    /**
     * Registers a lifecycle-scoped BroadcastReceiver for [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION]
     * and [WifiManager.NETWORK_STATE_CHANGED_ACTION]. Returns a callback to unregister.
     */
    fun registerScanReceiver(context: Context, onUpdated: () -> Unit = {}): () -> Unit {
        val appCtx = context.applicationContext
        val wm = runCatching { Sys.svc(appCtx, WifiManager::class.java) }.getOrNull()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        _isScanning.value = false
                        if (wm != null) parseScanResults(appCtx, wm)
                        refreshStatus(appCtx)
                        onUpdated()
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION,
                    WifiManager.WIFI_STATE_CHANGED_ACTION,
                    WifiManager.RSSI_CHANGED_ACTION -> {
                        refreshStatus(appCtx)
                        if (wm != null) parseScanResults(appCtx, wm)
                        onUpdated()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
        runCatching { appCtx.registerReceiver(receiver, filter) }
        if (wm != null) parseScanResults(appCtx, wm)
        refreshStatus(appCtx)
        return {
            runCatching { appCtx.unregisterReceiver(receiver) }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun parseScanResults(context: Context, wm: WifiManager) {
        val currentSnap = refreshStatus(context)
        val activeSsid = currentSnap.ssid
        val rawResults: List<ScanResult> = runCatching { wm.scanResults.orEmpty() }.getOrDefault(emptyList())

        val bySsid = linkedMapOf<String, ScanResult>()
        for (result in rawResults.sortedByDescending { it.level }) {
            val ssid = runCatching { result.SSID }.getOrNull()?.trim()?.trim('"').orEmpty()
            if (ssid.isEmpty() || ssid == "<unknown ssid>") continue
            val existing = bySsid[ssid]
            if (existing == null || result.level > existing.level) {
                bySsid[ssid] = result
            }
        }

        val items = bySsid.values.map { sr ->
            val ssid = sr.SSID.trim().trim('"')
            val caps = sr.capabilities.orEmpty().uppercase()
            val isSecured = caps.contains("WPA") ||
                caps.contains("WEP") ||
                caps.contains("PSK") ||
                caps.contains("EAP") ||
                caps.contains("SAE")
            val secLabel = when {
                caps.contains("WPA3") || caps.contains("SAE") -> "WPA3"
                caps.contains("WPA2") -> "WPA2"
                caps.contains("WPA") -> "WPA"
                caps.contains("WEP") -> "WEP"
                caps.contains("EAP") -> "802.1X"
                else -> "Open"
            }
            WifiScannedNetwork(
                ssid = ssid,
                bssid = sr.BSSID.orEmpty(),
                rssi = sr.level,
                signalLevel = calculateSignalLevel(sr.level),
                isSecured = isSecured,
                securityLabel = secLabel,
                isCurrentNetwork = activeSsid.isNotEmpty() && activeSsid.equals(ssid, ignoreCase = true),
            )
        }.sortedWith(
            compareByDescending<WifiScannedNetwork> { it.isCurrentNetwork }
                .thenByDescending { it.rssi },
        )

        // If currently connected to an SSID not yet in scan results, prepend it so the user always sees it
        val finalList = if (activeSsid.isNotEmpty() && items.none { it.ssid.equals(activeSsid, ignoreCase = true) }) {
            listOf(
                WifiScannedNetwork(
                    ssid = activeSsid,
                    bssid = "",
                    rssi = currentSnap.rssi,
                    signalLevel = currentSnap.signalLevel.coerceAtLeast(1),
                    isSecured = true,
                    securityLabel = "WPA2",
                    isCurrentNetwork = true,
                ),
            ) + items
        } else {
            items
        }

        _networks.value = finalList
    }

    /**
     * Converts RSSI dBm (-100..-30) to a 0..4 signal strength level.
     */
    fun calculateSignalLevel(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -90 -> 1
        else -> 0
    }

    /**
     * Connects the appliance to [ssid] with [password] (empty for open networks).
     *
     * Strategy:
     * 1. On API <= 28 or when running as Device Owner, attempts direct [WifiConfiguration]
     *    (`addNetwork` + `enableNetwork` + `reconnect`).
     * 2. On API 29+, also registers a [WifiNetworkSuggestion] so Android 10+ auto-connects.
     * 3. Returns a [WifiConnectResult] with localized feedback and indicates whether the
     *    floating system connectivity sheet fallback may be helpful on non-Device-Owner test devices.
     */
    @SuppressLint("MissingPermission")
    fun connect(context: Context, ssid: String, password: String): WifiConnectResult {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) {
            return WifiConnectResult(
                accepted = false,
                isDeviceOwner = false,
                messageEn = "Network SSID cannot be empty.",
                messageAr = "اسم الشبكة لا يمكن أن يكون فارغاً.",
            )
        }

        val appCtx = context.applicationContext
        val wm = runCatching { Sys.svc(appCtx, WifiManager::class.java) }.getOrNull()
            ?: return WifiConnectResult(
                accepted = false,
                isDeviceOwner = false,
                messageEn = "Wi-Fi service is unavailable on this device.",
                messageAr = "خدمة الواي فاي غير متوفرة على هذا الجهاز.",
            )

        val dpm = runCatching { Sys.svc(appCtx, DevicePolicyManager::class.java) }.getOrNull()
        val isDeviceOwner = runCatching { dpm?.isDeviceOwnerApp(appCtx.packageName) == true }.getOrDefault(false)

        if (!wm.isWifiEnabled) {
            runCatching { wm.isWifiEnabled = true }
        }

        // 1. Direct WifiConfiguration connection (always tried on API <= 28, and on API 29+ when Device Owner)
        var legacyConnected = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isDeviceOwner) {
            legacyConnected = connectViaWifiConfiguration(wm, cleanSsid, password)
        }

        // 2. WifiNetworkSuggestion on API 29+
        var suggestionAccepted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suggestionAccepted = connectViaSuggestionApi29(wm, cleanSsid, password)
            // Even when not Device Owner, try legacy enableNetwork as a best-effort if suggestion succeeded
            if (!legacyConnected) {
                legacyConnected = connectViaWifiConfiguration(wm, cleanSsid, password)
            }
        }

        val ok = legacyConnected || suggestionAccepted
        val needsFallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isDeviceOwner && !legacyConnected

        refreshStatus(appCtx)

        return when {
            legacyConnected -> WifiConnectResult(
                accepted = true,
                isDeviceOwner = isDeviceOwner,
                messageEn = "Connecting to \"$cleanSsid\"...",
                messageAr = "جارٍ الاتصال بالشبكة \"$cleanSsid\"...",
                needsSystemPanelFallback = false,
            )
            suggestionAccepted -> WifiConnectResult(
                accepted = true,
                isDeviceOwner = isDeviceOwner,
                messageEn = if (isDeviceOwner) {
                    "Network \"$cleanSsid\" configured. Connecting..."
                } else {
                    "Network \"$cleanSsid\" added. Approve the system prompt or use the Quick Panel if needed."
                },
                messageAr = if (isDeviceOwner) {
                    "تم ضبط الشبكة \"$cleanSsid\". جارٍ الاتصال..."
                } else {
                    "تمت إضافة الشبكة \"$cleanSsid\". وافق على إشعار النظام أو استخدم اللوحة السريعة عند الحاجة."
                },
                needsSystemPanelFallback = needsFallback,
            )
            else -> WifiConnectResult(
                accepted = ok,
                isDeviceOwner = isDeviceOwner,
                messageEn = "OS blocked silent Wi-Fi switch. Tap Quick Wi-Fi Panel below to finish connecting.",
                messageAr = "رفض النظام التبديل الصامت للشبكة. اضغط على لوحة الواي فاي السريعة أدناه لإتمام الاتصال.",
                needsSystemPanelFallback = true,
            )
        }
    }

    @SuppressLint("NewApi", "MissingPermission")
    private fun connectViaSuggestionApi29(wm: WifiManager, ssid: String, pass: String): Boolean {
        return try {
            val builder = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsAppInteractionRequired(false)

            if (pass.isNotEmpty()) {
                builder.setWpa2Passphrase(pass)
            }

            val suggestion = builder.build()
            val list = listOf(suggestion)
            runCatching { wm.removeNetworkSuggestions(list) }
            val status = wm.addNetworkSuggestions(list)
            val ok = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            Log.i(TAG, "API 29+ addNetworkSuggestions(ssid=\"$ssid\"): status=$status (ok=$ok)")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "API 29+ WifiNetworkSuggestion failed for \"$ssid\": ${e.message}", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectViaWifiConfiguration(wm: WifiManager, ssid: String, pass: String): Boolean {
        return try {
            val quotedSsid = "\"${ssid.replace("\"", "\\\"")}\""
            val config = WifiConfiguration().apply {
                SSID = quotedSsid
                if (pass.isEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"${pass.replace("\"", "\\\"")}\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }

            runCatching {
                wm.configuredNetworks
                    ?.filter { it.SSID == quotedSsid || it.SSID == ssid }
                    ?.forEach { existing -> wm.removeNetwork(existing.networkId) }
            }

            val netId = wm.addNetwork(config)
            if (netId == -1) {
                Log.w(TAG, "WifiConfiguration.addNetwork returned -1 for \"$ssid\"")
                return false
            }

            wm.disconnect()
            val enabled = wm.enableNetwork(netId, true)
            val reconnected = wm.reconnect()
            runCatching { wm.saveConfiguration() }
            Log.i(TAG, "WifiConfiguration connect(\"$ssid\"): netId=$netId, enabled=$enabled, reconnected=$reconnected")
            enabled
        } catch (e: Exception) {
            Log.e(TAG, "WifiConfiguration connect failed for \"$ssid\": ${e.message}", e)
            false
        }
    }

    /**
     * Opens Android's floating in-app Internet Connectivity Panel (API 29+) so the user can
     * confirm or switch networks without leaving the Nour Alwelayah Appliance UI.
     */
    fun openConnectivityPanel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching {
                context.startActivity(panelIntent)
                true
            }.getOrDefault(false)
            if (launched) return

            val wifiPanelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val wifiPanelLaunched = runCatching {
                context.startActivity(wifiPanelIntent)
                true
            }.getOrDefault(false)
            if (wifiPanelLaunched) return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
