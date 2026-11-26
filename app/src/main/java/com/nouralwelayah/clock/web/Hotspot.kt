package com.nouralwelayah.clock.web

import com.nouralwelayah.clock.Sys
import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/** The tablet's own Wi-Fi: name, password and the tablet's address in it. */
data class HotspotInfo(val ssid: String, val pass: String, val ip: String) {
    /** Wi-Fi QR code text (phone cameras join with it). */
    val qr: String get() = "WIFI:T:WPA;S:${esc(ssid)};P:${esc(pass)};;"
    val page: String get() = if (ip.isEmpty()) "" else "http://$ip:${WebRemote.PORT}/"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
}

/**
 * No-router mode (stage 4): the tablet makes its own Wi-Fi with Android's local-only hotspot (no internet,
 * name and password chosen by Android). Phones join it - by the QR code, or the app by itself (Send) after
 * asking over Bluetooth - and reach the Wi-Fi remote / file upload at the tablet's address.
 * Needs location on (Android 12) or "Nearby devices" (13+). Lives while this process holds the reservation.
 */
object Hotspot {
    val info = MutableStateFlow<HotspotInfo?>(null)
    val status = MutableStateFlow("Off")

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val main = Handler(Looper.getMainLooper())
    private val waiting = mutableListOf<(HotspotInfo?, String) -> Unit>()

    val on: Boolean get() = reservation != null

    /** Starts it (or gives the running one). [done] gets the details, or null and the reason. Main thread. */
    @SuppressLint("MissingPermission", "NewApi")
    fun start(ctx: Context, done: (HotspotInfo?, String) -> Unit = { _, _ -> }) {
        info.value?.let { done(it, ""); return }
        if (!com.nouralwelayah.clock.HAS_HOTSPOT) { done(null, "The tablet's own Wi-Fi needs Android 8 or newer"); return }
        waiting += done
        if (waiting.size > 1) return                     // already starting
        val wm = Sys.svc(ctx.applicationContext, WifiManager::class.java)
        homeIp = allIps().toSet()                        // the addresses before: the hotspot's is the new one
        status.value = "Starting..."
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val (ssid, pass) = credentials(r)
                    // the hotspot's own interface comes up a moment later
                    fun finish(tries: Int) {
                        val ip = hotspotIp()
                        if (ip.isEmpty() && tries > 0) { main.postDelayed({ finish(tries - 1) }, 500); return }
                        val i = HotspotInfo(ssid, pass, ip)
                        info.value = i
                        status.value = "On"
                        Log.i(TAG, "on: $ssid $ip")
                        flush(i, "")
                    }
                    finish(10)
                }
                override fun onStopped() { reservation = null; info.value = null; status.value = "Off" }
                override fun onFailed(reason: Int) {
                    status.value = "Off"
                    flush(null, when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "The tablet does not allow a hotspot"
                        ERROR_INCOMPATIBLE_MODE -> "The tablet's own hotspot (tethering) is on - turn it off"
                        ERROR_NO_CHANNEL -> "No free Wi-Fi channel"
                        else -> "Could not start the tablet's Wi-Fi (error $reason)"
                    })
                }
            }, main)
        } catch (e: SecurityException) {
            status.value = "Off"
            flush(null, if (Build.VERSION.SDK_INT >= 33) "Allow Prayer Clock the Nearby devices permission"
                        else "Turn on Location and allow Prayer Clock to use it")
        } catch (e: IllegalStateException) {             // one is already running for this app
            status.value = "Off"
            flush(null, "Already starting - try again")
        }
    }

    @SuppressLint("NewApi")   // reservation is only set on Android 8+
    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        info.value = null
        status.value = "Off"
    }

    private fun flush(i: HotspotInfo?, err: String) {
        val l = waiting.toList(); waiting.clear()
        l.forEach { it(i, err) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> =
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            (if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else c.ssid).orEmpty() to c.passphrase.orEmpty()
        } else {
            val c = r.wifiConfiguration
            c?.SSID.orEmpty().trim('"') to c?.preSharedKey.orEmpty().trim('"')
        }

    private var homeIp = emptySet<String>()

    /** IPv4 addresses of the interfaces that are up, hotspot-like names first. */
    private fun allIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { n -> if (listOf("ap", "swlan", "softap").any { n.name.startsWith(it) }) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    /**
     * The tablet's address in its own Wi-Fi: the one that was not there before. A tablet with one Wi-Fi radio
     * (like the P1250) leaves the home Wi-Fi and uses wlan0 for it; others add ap0 / swlan0.
     */
    private fun hotspotIp(): String = allIps().firstOrNull { it !in homeIp }.orEmpty()

    private const val TAG = "Hotspot"
}
