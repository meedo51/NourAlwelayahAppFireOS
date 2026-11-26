package com.nouralwelayah.clock

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.content.Context
import android.os.Build

/*
 * Small helpers so the same code runs on Fire OS 5 (Android 5.1, the "fire" app) and on Android 8+.
 */

/** Notification channels exist from Android 8; before that notifications need none (the call does nothing). */
inline fun ifChannels(block: () -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) block()
}

/** Bluetooth LE connection (the LE transport can only be asked for from Android 6). */
@android.annotation.SuppressLint("MissingPermission")   // the callers check the Bluetooth permission
fun BluetoothDevice.connectLe(c: Context, cb: BluetoothGattCallback): BluetoothGatt? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE)
    else connectGatt(c, false, cb)

/** Fire OS 5 / Android 5: no local-only hotspot, no app-only Wi-Fi join (no-router mode needs Android 8 / 10). */
val HAS_HOTSPOT get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

/** The device's IPv4 address in its network ("" without one). Android 5 has no ConnectivityManager.activeNetwork. */
fun localIpv4(c: Context): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val cm = Sys.svc(c, android.net.ConnectivityManager::class.java) ?: return ""
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return ""
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress.orEmpty()
    }
    return runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .firstOrNull()?.hostAddress
    }.getOrNull().orEmpty()
}

/**
 * Turns the screen on for [ms] (tablet: the clock after a restart, an azan / alarm). The activity flags
 * (setTurnScreenOn / FLAG_TURN_SCREEN_ON) alone do not wake Fire OS 5; this wake lock does, then the Clock
 * screen's keep-screen-on holds it.
 */
fun wakeScreen(c: Context, ms: Long = 10_000) {
    runCatching {
        @Suppress("DEPRECATION")
        Sys.svc(c.applicationContext, android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE, "PrayerClock:screen")
            .acquire(ms)
    }
}
