package com.nouralwelayah.clock.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.nouralwelayah.clock.Sys

/**
 * The clock name (firmware [NAME], settings "clock_name"): the tablet's Bluetooth name (what phones list) and the
 * name the Wi-Fi page / Send find it by. "" = the device's own Bluetooth name, unchanged.
 */
object ClockName {
    const val MAX = 20
    const val RULE = "Clock name: up to 20 letters, numbers, spaces and - _ . (at least one letter or number)"

    /** Same check as the firmware's clockNameValid: "" or 1..20 of A-Z a-z 0-9 space - _ . with a letter / digit. */
    fun valid(n: String): Boolean {
        if (n.isEmpty()) return true
        if (n.length > MAX || n.first() == ' ' || n.last() == ' ') return false
        if (n.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in " -_.") }) return false
        return n.any { it.isLetterOrDigit() }
    }

    /** The device's Bluetooth name now ("" without Bluetooth). */
    @SuppressLint("MissingPermission")
    fun bluetoothName(c: Context): String = runCatching {
        Sys.svc(c.applicationContext, android.bluetooth.BluetoothManager::class.java)?.adapter?.name
    }.getOrNull().orEmpty()

    /** The name shown to phones: the setting, else the Bluetooth name, else "NourAlwelayah". */
    fun shown(c: Context): String =
        Store.settings.value.clockName.ifEmpty { bluetoothName(c) }.ifEmpty { "NourAlwelayah" }

    /**
     * Sets the Bluetooth name to the setting or "NourAlwelayah" (Bluetooth must be on).
     */
    @SuppressLint("MissingPermission")
    fun apply(c: Context): Boolean {
        val n = Store.settings.value.clockName.ifEmpty { "NourAlwelayah" }
        val prefs = c.applicationContext.getSharedPreferences("clock_name", Context.MODE_PRIVATE)
        val own = prefs.getString("own_bt_name", null)
        return runCatching {
            val a = Sys.svc(c.applicationContext, android.bluetooth.BluetoothManager::class.java)?.adapter ?: return false
            if (!a.isEnabled) return false
            val want = if (n.isEmpty()) own ?: return true else n
            if (n.isNotEmpty() && own == null) prefs.edit().putString("own_bt_name", a.name.orEmpty()).apply()
            val ok = a.name == want || a.setName(want).also { Log.i("ClockName", "Bluetooth name -> $want: $it") }
            if (ok && n.isEmpty()) prefs.edit().remove("own_bt_name").apply()     // the device's own name is back
            ok
        }.getOrDefault(false)
    }
}
