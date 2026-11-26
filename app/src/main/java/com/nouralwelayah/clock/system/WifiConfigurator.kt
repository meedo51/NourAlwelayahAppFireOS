package com.nouralwelayah.clock.system

import android.annotation.SuppressLint
import android.content.Context

/**
 * Programmatic Appliance Wi-Fi Executor for Nour Alwelayah.
 * Delegates to [WifiController] for unified Device Owner / legacy / API 29+ network configuration.
 */
object WifiConfigurator {
    @SuppressLint("MissingPermission")
    fun connect(context: Context, ssid: String, pass: String): Boolean =
        WifiController.connect(context, ssid, pass).accepted
}
