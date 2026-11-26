package com.nouralwelayah.clock.ui.p4

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import com.nouralwelayah.clock.ui.RoundSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.session.MediaController
import com.nouralwelayah.clock.alert.ActiveAlert
import com.nouralwelayah.clock.data.Alarm
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.media.MediaHub
import com.nouralwelayah.clock.ui.C_BG
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.ChildLockFeedback
import com.nouralwelayah.clock.ui.ClockPicture
import com.nouralwelayah.clock.ui.ClockScreen
import com.nouralwelayah.clock.ui.VoiceOverlay
import com.nouralwelayah.clock.voice.VoiceAssistantService
import com.nouralwelayah.clock.voice.VoiceUiState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime


/**
 * The tablet: the ESP32-P4 clock's screens (ui.cpp) - Clock, Prayer, Alarms, Music, WiFi, Settings tabs at
 * the bottom - plus the media player for video and YouTube.
 */
@Composable
fun P4Tablet(s: AppSettings, alarms: List<Alarm>, now: ZonedDateTime, alert: ActiveAlert?, onTabChange: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(P4Tab.CLOCK) }
    BackHandler(enabled = tab != P4Tab.CLOCK) { tab = P4Tab.CLOCK }
    val (player, playing) = rememberPlayer()
    // an azan / alarm: the Clock tab with its mini player (like the P4)
    // (not for a test: the editor / Prayer tab that started it stays open with its Stop, like the P4's edTest)
    LaunchedEffect(alert) { if (alert != null && !alert.isTest && tab != P4Tab.CLOCK) { tab = P4Tab.CLOCK; onTabChange(true) } }
    // Stop (tablet, phone, web page) or MediaActivity inactivity timeout = back to the clock
    LaunchedEffect(Unit) { MediaHub.closeScreen.collect { tab = P4Tab.CLOCK; onTabChange(true) } }
    val forceClock by MediaHub.forceClockTab.collectAsState()
    LaunchedEffect(forceClock) {
        if (forceClock) {
            tab = P4Tab.CLOCK
            onTabChange(true)
            MediaHub.forceClockTab.value = false
        }
    }
    LaunchedEffect(s.childLockEnabled) {
        if (s.childLockEnabled && tab != P4Tab.CLOCK) {
            tab = P4Tab.CLOCK
            onTabChange(true)
        }
    }
    LaunchedEffect(Unit) {
        MediaHub.requestedTab.collect { tabName ->
            val targetTab = P4Tab.entries.firstOrNull { it.name.equals(tabName, ignoreCase = true) }
            if (targetTab != null) {
                if (Store.settings.value.childLockEnabled && targetTab != P4Tab.CLOCK) {
                    ChildLockFeedback.notifyLockedTap()
                } else {
                    tab = targetTab
                    onTabChange(targetTab == P4Tab.CLOCK)
                }
            }
        }
    }
    // like the P4's auto-lock: another tab, no touch for a while (Settings), nothing playing -> the clock again
    var lastTouch by remember { mutableStateOf(0L) }
    val ytPlaying by MediaHub.youTubePlaying.collectAsState()
    LaunchedEffect(tab, lastTouch, playing.playing, ytPlaying, s.backToClockMin) {
        if (tab == P4Tab.CLOCK || playing.playing || ytPlaying || s.backToClockMin <= 0) return@LaunchedEffect
        kotlinx.coroutines.delay(s.backToClockMin * 60_000L)
        tab = P4Tab.CLOCK; onTabChange(true)
    }
    // a video that plays while these screens are shown (resumed from the mini player, the Music tab, the phone or
    // the web page) belongs on its screen: open the media player full screen, else only its sound is heard
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    // Offline Voice Assistant state & RECORD_AUDIO runtime permission launcher
    val voiceIoEngine = remember(ctx) { VoiceAssistantService.getVoiceIoEngine(ctx) }
    val voiceUiState by voiceIoEngine.uiState.collectAsState()
    val isWakeWordListening by voiceIoEngine.isWakeWordListening.collectAsState()
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceIoEngine.startListening()
        }
    }
    val triggerVoiceAssistant: () -> Unit = remember(ctx, voiceIoEngine, s.voiceAssistantEnabled, alert) {
        {
            if (s.voiceAssistantEnabled && alert == null) {
                val hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    voiceIoEngine.startListening()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
    // Strict UI-layer Azan/Alarm preemption (pairs with synchronous AlertService preemption)
    LaunchedEffect(alert) {
        if (alert != null) {
            voiceIoEngine.abortImmediately()
            com.nouralwelayah.clock.ui.pauseYouTubePlayback()
        }
    }

    LaunchedEffect(playing.playing, playing.id) {
        val id = playing.id ?: return@LaunchedEffect
        val video = com.nouralwelayah.clock.data.MediaLibrary.files.value.firstOrNull { it.id == id }?.video
            ?: com.nouralwelayah.clock.data.AppFolders.isVideo(java.io.File(android.net.Uri.parse(id).path.orEmpty()))
        if (playing.playing && video && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            MediaHub.fullScreen.value = true
            ctx.startActivity(android.content.Intent(ctx, com.nouralwelayah.clock.media.MediaActivity::class.java))
        }
    }
    // like the P4: no Android navigation buttons. A swipe from the bottom edge shows them; some tablets
    // (Fire OS 5, ...) then keep them, so they are hidden again 4 s after they appear, and on every return.
    val view = LocalView.current
    val bars = remember(view) {
        (view.context as? android.app.Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
    }
    var barsShownAt by remember { mutableStateOf(0L) }
    DisposableEffect(bars) {
        fun hide() { bars?.hide(WindowInsetsCompat.Type.navigationBars()); barsShownAt = 0L }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide()
        val resume = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) hide()
        }
        lifecycle.addObserver(resume)
        // Android < 11: the old flags tell when the buttons come back
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 30) view.setOnSystemUiVisibilityChangeListener { f ->
            if (f and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0 && barsShownAt == 0L)
                barsShownAt = android.os.SystemClock.uptimeMillis()
        }
        onDispose {
            lifecycle.removeObserver(resume)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT < 30) view.setOnSystemUiVisibilityChangeListener(null)
            bars?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
    LaunchedEffect(bars) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) continue
            val now = android.os.SystemClock.uptimeMillis()
            if (android.os.Build.VERSION.SDK_INT >= 30 && barsShownAt == 0L &&
                androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.navigationBars()) == true)
                barsShownAt = now
            if (barsShownAt != 0L && now - barsShownAt >= 4000) {
                bars?.hide(WindowInsetsCompat.Type.navigationBars()); barsShownAt = 0L
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(C_BG)) {
        // the P4's 1280 x 800 px, scaled to this screen (16:10 like the tablet)
        val k = minOf(maxWidth.value / 1280f, maxHeight.value / 800f)
        P4Scaled(k) {
            val s4 = LocalP4.current
            with(s4) {
                Column(Modifier.fillMaxSize().pointerInput(Unit) {
                    // every touch (seen before the screens handle it) restarts the 2-minute wait
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            lastTouch = System.currentTimeMillis()
                        }
                    }
                }) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            P4Tab.CLOCK -> {
                                ClockPicture(s, alert)             // azan picture / wallpaper behind the clock
                                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                                    ClockScreen(
                                        s, now, alert,
                                        onOpenSettings = { tab = P4Tab.SETTINGS },
                                        showGear = false,
                                        background = Color.Transparent,
                                        links = false,
                                        aboveCards = when {
                                            alert != null -> { { AlertMini(alert, Modifier.padding(horizontal = 8.p, vertical = 6.p)) } }
                                            playing.id != null -> { { MiniPlayer(player, playing, Modifier.padding(horizontal = 8.p, vertical = 6.p)) } }
                                            else -> null
                                        },
                                        onVoiceClick = if (s.voiceAssistantEnabled) triggerVoiceAssistant else null,
                                        voiceActive = voiceUiState !is VoiceUiState.Idle,
                                        wakeWordListening = isWakeWordListening,
                                    )
                                }
                            }
                            else -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(16.p)) {
                                when (tab) {
                                    P4Tab.PRAYER -> PrayerTab(s, now)
                                    P4Tab.ALARMS -> AlarmsTab(s, alarms, now)
                                    P4Tab.PLAYER -> PlayerTab(player, playing)
                                    P4Tab.WIFI -> WifiTab(s)
                                    P4Tab.SETTINGS -> SettingsTab(s)
                                    else -> Unit
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        P4TabBar(tab) {
                            if (!s.childLockEnabled) {
                                tab = it
                                onTabChange(it == P4Tab.CLOCK)
                            } else {
                                ChildLockFeedback.notifyLockedTap()
                            }
                        }
                        if (s.childLockEnabled && alert == null) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            down.consume()
                                            do {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { it.consume() }
                                            } while (event.changes.any { it.pressed })
                                            ChildLockFeedback.notifyLockedTap()
                                        }
                                    },
                            )
                        }
                    }
                }

                // Translucent Navy Voice Assistant Modal Overlay (1280 x 800 scaled, Azan-preempted)
                VoiceOverlay(
                    state = voiceUiState,
                    isAlertActive = alert != null,
                    onCancel = { voiceIoEngine.abortImmediately() },
                    onDismiss = { voiceIoEngine.abortImmediately() },
                    onRetry = triggerVoiceAssistant,
                    onSelectSuggestion = { suggestion, rawQuery, normalizedQuery ->
                        voiceIoEngine.applyCorrectionSuggestion(
                            suggestion = suggestion,
                            rawQuery = rawQuery,
                            normalizedQuery = normalizedQuery,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** The P4's mini player while an azan / alarm / test rings: Stop, Snooze (alarm), title, time, volume 0..21. */
@Composable
private fun AlertMini(a: ActiveAlert, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var prog by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(a) { while (true) { prog = com.nouralwelayah.clock.alert.AlertState.progress(); kotlinx.coroutines.delay(500) } }
    val vol by com.nouralwelayah.clock.alert.AlertState.volume.collectAsState()
    val paused by com.nouralwelayah.clock.alert.AlertState.paused.collectAsState()
    val s4 = LocalP4.current
    with(s4) {
        P4Card(modifier.fillMaxWidth().height(74.p), border = C_GOLD) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.p)) {
                P4Button(if (paused) "▶" else "❚❚", Modifier.size(90.p, 46.p), size = 24) {
                    com.nouralwelayah.clock.alert.AlertState.togglePause()
                }
                P4Button("■", Modifier.size(90.p, 46.p), size = 24) {
                    ctx.startService(com.nouralwelayah.clock.alert.AlertService.stopIntent(ctx))
                }
                if (a.isAlarm && !a.isTest) P4Button(androidx.compose.ui.res.stringResource(com.nouralwelayah.clock.R.string.alert_snooze), Modifier.size(110.p, 46.p), color = Color(0xFFFB8C00), size = 18) {
                    ctx.startService(com.nouralwelayah.clock.alert.AlertService.snoozeIntent(ctx))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (a.isAlarm) "🔔  " else "🕌  ") + a.title + if (a.subtitle.isNotEmpty()) "   " + a.subtitle else "",
                            color = C_GOLD, fontSize = 20.f, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${fmtMs(prog.first.toLong())} / ${if (prog.second > 0) fmtMs(prog.second.toLong()) else "--:--"}",
                            color = C_DIM, fontSize = 20.f)
                    }
                    LinearProgressIndicator(progress = { if (prog.second > 0) prog.first.toFloat() / prog.second else 0f },
                        modifier = Modifier.fillMaxWidth().height(10.p).padding(top = 4.p))
                }
                Text("🔊", fontSize = 24.f, color = C_DIM)
                RoundSlider(vol.toFloat(), { com.nouralwelayah.clock.alert.AlertState.setVolume(it.toInt()) }, Modifier.width(300.p),
                    valueRange = 0f..21f, steps = 20)
                Text("$vol", color = C_TEXT, fontSize = 28.f, modifier = Modifier.width(48.p))
            }
        }
    }
}

/** buildMiniPlayer(): on the Clock tab while something plays. */
@Composable
private fun MiniPlayer(c: MediaController?, now: PlayerNow, modifier: Modifier) {
    val s4 = LocalP4.current
    with(s4) {
        P4Card(modifier.fillMaxWidth().height(74.p), border = C_GOLD) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.p)) {
                P4Button(if (now.playing) "❚❚" else "▶", Modifier.size(90.p, 46.p), size = 24) {
                    c?.let { if (it.isPlaying) it.pause() else it.play() }
                }
                P4Button("■", Modifier.size(90.p, 46.p), size = 24) { c?.let { it.stop(); it.clearMediaItems() } }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(now.title, color = C_TEXT, fontSize = 20.f, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${fmtMs(now.pos)} / ${if (now.dur > 0) fmtMs(now.dur) else "--:--"}", color = C_DIM, fontSize = 20.f)
                    }
                    LinearProgressIndicator(progress = { if (now.dur > 0) now.pos.toFloat() / now.dur else 0f },
                        modifier = Modifier.fillMaxWidth().height(10.p).padding(top = 4.p))
                }
                Text("🔊", fontSize = 24.f, color = C_DIM)
                var v by remember(now.volume) { mutableFloatStateOf(now.volume) }
                RoundSlider(v, { v = it; c?.volume = it; MediaHub.volume.value = it }, Modifier.width(300.p))
                Text("${(v * 21).toInt()}", color = C_TEXT, fontSize = 28.f, modifier = Modifier.width(48.p))
            }
        }
    }
}

