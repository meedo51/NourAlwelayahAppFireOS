package com.nouralwelayah.clock

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import com.nouralwelayah.clock.alert.AlertState
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.kiosk.KioskPolicy
import com.nouralwelayah.clock.kiosk.WatchdogService
import com.nouralwelayah.clock.media.MediaRemoteServer
import com.nouralwelayah.clock.ota.OtaIntegrityHealthCheck
import com.nouralwelayah.clock.system.AssetExtractor
import com.nouralwelayah.clock.system.FileSystemManager
import com.nouralwelayah.clock.ui.C_BG
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.NourAlwelayahLocaleProvider
import com.nouralwelayah.clock.ui.PrayerClockTheme
import com.nouralwelayah.clock.ui.p4.P4Tablet
import com.nouralwelayah.clock.ui.picturesChanged
import com.nouralwelayah.clock.ui.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isForeground: Boolean = false

        @Volatile
        var isAdminSessionActive: Boolean = false
    }

    private fun suppressSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Intercept bootloader draw (must be called BEFORE super.onCreate)
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        // 2. Hold the splash screen until the Reactive Store is hydrated
        // This ensures the user never sees a blank 00:00 UI before SharedPreferences loads
        splashScreen.setKeepOnScreenCondition {
            !Store.isHydrated
        }

        // Deep UI Suppression: aggressively hide status and navigation bars immediately after Splash Screen installation
        suppressSystemBars()

        lockTabletLandscape()
        // Appliance = wall clock: the azan / alarm brings it to the front, over the lock screen, screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {   // Fire OS 5 / Android < 8.1
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // opened after a restart (BootReceiver): its notification is done
        Sys.svc(applicationContext, android.app.NotificationManager::class.java)
            .cancel(com.nouralwelayah.clock.alert.BootReceiver.BOOT_NOTIFICATION)

        // Hardened File System & Asset Initialization before UI mounts
        FileSystemManager.initialize(this)
        Store.init(this)
        OtaIntegrityHealthCheck.runAsync(this)
        lifecycleScope.launch {
            AssetExtractor.extractIfNeeded(this@MainActivity)
            MediaLibrary.rescan(this@MainActivity)
            picturesChanged()
        }

        // Programmatically set hardware Bluetooth name to "NourAlwelayah" on appliance initialization
        KioskPolicy.enforceBluetoothName(this)
        // Start the Immortal Watchdog Foreground Service
        ContextCompat.startForegroundService(this, Intent(this, WatchdogService::class.java))
        // Apply full Device Owner kiosk policy and enter Lock Task on activity creation when kioskMode is enabled
        if (Store.settings.value.kioskMode && KioskPolicy.isOwner(this)) {
            if (KioskPolicy.applyFullKioskPolicy(this)) {
                Kiosk.enter(this)
            }
        }
        // Ensure window decor and edge-to-edge system bars match #0c1823 (brand_navy) with no mismatched letterboxing
        window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#0c1823"))
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0c1823")),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#0c1823")),
        )
        suppressSystemBars()
        // 3. Permanently lock UI entry point to P4Tablet appliance hierarchy wrapped in reactive LocaleProvider
        setContent {
            val settingsState by Store.settings.collectAsState()
            val extracting by AssetExtractor.isExtracting.collectAsState()
            var bootCheckCompleted by remember {
                mutableStateOf(AssetExtractor.isExtracted(this@MainActivity))
            }

            LaunchedEffect(Unit) {
                if (!bootCheckCompleted) {
                    AssetExtractor.extractIfNeeded(this@MainActivity)
                    MediaLibrary.rescan(this@MainActivity)
                    picturesChanged()
                    bootCheckCompleted = true
                }
            }

            NourAlwelayahLocaleProvider(languageCode = settingsState.appLanguage) {
                PrayerClockTheme {
                    val kioskActive = remember(settingsState.kioskMode) {
                        KioskPolicy.isKioskActive(this@MainActivity)
                    }
                    BackHandler(enabled = kioskActive) {
                        // Consumes back navigation at the root when already on the Clock tab in Kiosk mode
                    }
                    if (extracting || !bootCheckCompleted) {
                        HardwareEngineInitSplash()
                    } else {
                        NourAlwelayahAppliance()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        suppressSystemBars()
        if (Store.settings.value.kioskMode && KioskPolicy.isOwner(this)) {
            KioskPolicy.applyFullKioskPolicy(this)
        }
        if (KioskPolicy.isKioskActive(this) && Kiosk.status(this) == ActivityManager.LOCK_TASK_MODE_NONE) {
            Kiosk.enter(this)
        }
    }

    override fun onPause() {
        isForeground = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            suppressSystemBars()
            if (KioskPolicy.isKioskActive(this) && Kiosk.status(this) == ActivityManager.LOCK_TASK_MODE_NONE) {
                Kiosk.enter(this)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (KioskPolicy.isKioskActive(this)) {
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

@Composable
private fun NourAlwelayahAppliance() {
    val settings by Store.settings.collectAsState()
    val alarms by Store.alarms.collectAsState()
    val alert by AlertState.current.collectAsState()

    // the clock ticks every second, on the second
    var now by remember { mutableStateOf(ZonedDateTime.now(settings.zone())) }
    LaunchedEffect(settings.zoneId) {
        while (true) {
            now = ZonedDateTime.now(settings.zone())
            delay(1000L - (System.currentTimeMillis() % 1000L) + 5L)
        }
    }

    val ctx = LocalView.current.context
    val folderAccess = remember { mutableStateOf(AppFolders.hasAccess(ctx)) }
    LifecycleResumeEffect(Unit) {
        AppFolders.ensure(ctx)
        val hasNow = AppFolders.hasAccess(ctx)
        if (hasNow != folderAccess.value) {
            folderAccess.value = hasNow
            MediaLibrary.rescan(ctx)
            picturesChanged()
        }
        onPauseOrDispose { }
    }
    // Consolidated startup runtime permission request (Notifications + Bluetooth + Media)
    // Prevents dual simultaneous permission dialogs from colliding or causing frame drops
    val startupPermsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MainActivity.isAdminSessionActive = false
        KioskPolicy.enforceBluetoothName(ctx)
        if (MediaRemoteServer.canRun(ctx)) {
            MediaRemoteServer.stop(ctx)
            MediaRemoteServer.start(ctx)
        }
    }
    LaunchedEffect(Unit) {
        KioskPolicy.enforceBluetoothName(ctx)
        MediaRemoteServer.start(ctx) // Wi-Fi remote starts immediately without Bluetooth permissions

        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!MediaRemoteServer.canRun(ctx)) {
                addAll(MediaRemoteServer.permissions())
            }
            if (!MediaRemoteServer.canReadMedia(ctx)) {
                addAll(MediaRemoteServer.mediaPermissions())
            }
        }.distinct().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            MainActivity.isAdminSessionActive = true
            startupPermsLauncher.launch(needed.toTypedArray())
        }
    }

    val window = (ctx as Activity).window
    var p4Clock by remember { mutableStateOf(true) }
    val keepOn = (p4Clock || settings.backToClockMin > 0) && settings.keepScreenOn
    DisposableEffect(keepOn) {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(settings.brightness) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (settings.brightness < 0) -1f else settings.brightness / 100f
        }
    }

    P4Tablet(settings, alarms, now, alert) { p4Clock = it }
}

@Composable
private fun HardwareEngineInitSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(C_BG)
            .testTag("hardware_engine_init_splash"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                color = C_GOLD,
                strokeWidth = 3.dp,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = tr("Initializing Hardware Engine...", "جاري تهيئة محرك النظام..."),
                color = C_GOLD,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = tr("Extracting & verifying appliance file system", "استخراج والتحقق من ملفات النظام"),
                color = C_DIM,
                fontSize = 15.sp,
            )
        }
    }
}

