package com.nouralwelayah.clock

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo

/** The Nour Alwelayah appliance: P4 layout, mini player for the azan, quiet notifications. */
fun isTablet(c: Context) = true

/**
 * Appliance: Android still needs a notification for a sound playing in the background, so it goes to this channel -
 * lowest importance: no pop-up, no status bar icon, no sound.
 */
const val QUIET_CHANNEL = "quiet"

fun quietChannel(c: Context): String {
    ifChannels {
        Sys.svc(c, NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(QUIET_CHANNEL, "Background (quiet)", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null); setShowBadge(false); enableVibration(false)
            })
    }
    return QUIET_CHANNEL
}

/**
 * Tablet hardware (smallest width >= 600 dp): lock screens in landscape (either way up), like a wall clock.
 * On smaller emulator surfaces, avoid forcing sensor landscape rotation to prevent FrameEvents surface drops.
 */
fun Activity.lockTabletLandscape() {
    if (resources.configuration.smallestScreenWidthDp >= 600) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
