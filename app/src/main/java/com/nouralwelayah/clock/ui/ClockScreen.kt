package com.nouralwelayah.clock.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.alert.ActiveAlert
import com.nouralwelayah.clock.alert.AlertService
import com.nouralwelayah.clock.core.Planner
import com.nouralwelayah.clock.core.Prayer
import com.nouralwelayah.clock.core.fmtMinutes
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.SNOOZE_MIN
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.media.MediaActivity
import com.nouralwelayah.clock.ui.p4.LocalP4
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT_EN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
private val DATE_FMT_AR = DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy", Locale("ar"))
// Fixed-width digits + strict LTR text direction so HH:MM:SS never flips in RTL mode
private val DIGITS = TextStyle(fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr)

/** The main clock screen, like the firmware's Clock tab. Landscape / tablet: prayer cards in a row at the bottom. */
@Composable
fun ClockScreen(
    s: AppSettings, now: ZonedDateTime, alert: ActiveAlert?, onOpenSettings: () -> Unit,
    showGear: Boolean = true,        // false where the tabs are shown (phone): Settings is a tab there
    background: Color = C_BG,        // transparent over a ClockPicture (tablet)
    links: Boolean = true,           // Media / Clock remote links in the top row (the P4 tablet layout has tabs)
    aboveCards: (@Composable () -> Unit)? = null,   // wide: shown right above the prayer cards (P4 mini player)
    onVoiceClick: (() -> Unit)? = null,
    voiceActive: Boolean = false,
    wakeWordListening: Boolean = false,
) {
    val ar = isArabic()
    val activeLang = if (ar) "ar" else s.appLanguage
    val today = Planner.day(s, now.toLocalDate(), now.zone)
    // over a picture (tablet): small text gets a shadow so it stays readable on bright parts
    val onPicture = background == Color.Transparent
    val link = if (onPicture) C_GOLD else C_ACCENT
    val shade = if (onPicture) TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), 6f)) else TextStyle.Default
    val next = Planner.nextPrayer(s, now)
    val s4 = LocalP4.current

    // Hidden Technician Trigger state (5 rapid taps in top-left 48.dp x 48.dp corner)
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var showAdminAuth by remember { mutableStateOf(false) }
    var showAdminDashboard by remember { mutableStateOf(false) }

    with(s4) {
        BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
            val wide = maxWidth > maxHeight
            val clockSize: TextUnit = with(androidx.compose.ui.platform.LocalDensity.current) {
                val byWidth = maxWidth * (if (wide) 0.21f else 0.25f)
                val byHeight = maxHeight * (if (wide) 0.32f else 0.16f)
                minOf(byWidth, byHeight).toSp()
            }
            val infoSize = (clockSize.value * 0.17f).coerceIn(16f, 34f).sp

            Column(
                Modifier.fillMaxSize().then(if (wide) Modifier else Modifier.verticalScroll(rememberScrollState())),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ctx = LocalContext.current
                // Top Header Row: City Name & Timezone indicator with balanced 1280x800 P4 scaling and RTL/LTR support
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.p, start = 24.p, end = 24.p),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = s.city,
                        color = C_GOLD,
                        fontSize = 24.f,
                        fontWeight = FontWeight.Bold,
                        style = shade,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag("clock_header_city"),
                    )

                    if (links) {
                        TextButton(
                            onClick = { ctx.startActivity(Intent(ctx, MediaActivity::class.java)) },
                            modifier = Modifier.padding(horizontal = 12.p),
                        ) {
                            Text(
                                text = stringResource(R.string.clock_media_link),
                                color = link,
                                fontSize = 18.f,
                                style = shade,
                                maxLines = 1,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.p),
                    ) {
                        if (s.voiceAssistantEnabled && alert == null && onVoiceClick != null) {
                            VoiceMicButton(
                                onPicture = onPicture,
                                isActive = voiceActive,
                                onClick = onVoiceClick,
                                wakeWordListening = wakeWordListening,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (onPicture) C_CARD.copy(alpha = 0.78f) else C_CARD,
                                    shape = RoundedCornerShape(8.p),
                                )
                                .border(
                                    width = 1.p,
                                    color = C_GOLD.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(8.p),
                                )
                                .padding(horizontal = 14.p, vertical = 6.p)
                                .testTag("clock_header_timezone"),
                        ) {
                            Text(
                                text = s.zone().id,
                                color = if (onPicture) C_TEXT else C_DIM,
                                fontSize = 18.f,
                                fontWeight = FontWeight.Medium,
                                style = shade,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        com.nouralwelayah.clock.ui.p4.P4BatteryMonitor(onPicture = onPicture)
                        if (showGear) {
                            IconButton(onClick = onOpenSettings) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = stringResource(R.string.tab_settings),
                                    tint = if (onPicture) C_TEXT else C_DIM,
                                )
                            }
                        }
                    }
                }

                if (!s.locationSet) {
                    TextButton(onClick = onOpenSettings) {
                        Text(
                            text = stringResource(R.string.clock_set_location_prompt, s.city),
                            color = C_GOLD,
                            fontSize = 18.f,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (wide) Spacer(Modifier.weight(0.45f))
                ClockText(s, now, clockSize)
                Spacer(Modifier.height(4.p))
                Text(
                    text = now.format(if (ar) DATE_FMT_AR else DATE_FMT_EN),
                    color = C_TEXT,
                    fontSize = infoSize,
                    textAlign = TextAlign.Center,
                    style = shade,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Localized Hijri date string with Arabic month names when in Arabic mode
                Text(
                    text = today.hijri.format(ctx, activeLang),
                    color = link,
                    fontSize = infoSize * 0.85f,
                    textAlign = TextAlign.Center,
                    style = shade,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (next != null) {
                    val mins = Duration.between(now.withSecond(0).withNano(0), next.second).toMinutes()
                    val nextLabel = next.first.localizedLabel()
                    val nextTimeStr = fmtMinutes(next.second.hour * 60 + next.second.minute, s.use24h)
                    val hoursRem = mins / 60
                    val minsRem = "%02d".format(Locale.US, mins % 60)
                    Spacer(Modifier.height(4.p))
                    Text(
                        text = stringResource(R.string.clock_next_prayer_format, nextLabel, nextTimeStr, hoursRem, minsRem),
                        color = C_GOLD,
                        fontSize = infoSize * 0.85f,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        style = shade,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // wide: the bar floats top right (below), so the prayer cards keep their place
                if (alert != null && !wide) AlertBar(alert)
                if (wide) Spacer(Modifier.weight(0.55f)) else Spacer(Modifier.height(16.dp))
                if (wide) aboveCards?.invoke()

                val nextP = next?.first      // tomorrow's Fajr after Maghrib: the Fajr card, like the firmware
                if (wide) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.p, vertical = 8.p),
                        horizontalArrangement = Arrangement.spacedBy(8.p),
                    ) {
                        Prayer.entries.forEach { p ->
                            PrayerCard(p.localizedLabel(), fmtMinutes(today[p], s.use24h), p == nextP, Modifier.weight(1f), infoSize)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Prayer.entries.forEach { p ->
                            PrayerRow(p.localizedLabel(), fmtMinutes(today[p], s.use24h), p == nextP)
                        }
                    }
                }
            }
            if (alert != null && wide && links) Box(
                Modifier.align(Alignment.TopEnd)
                    .padding(top = 64.p, end = 16.p),   // below the top header row
            ) { AlertBar(alert) }

            // Client Child Lock full-screen touch-absorbing overlay (placed below the top-left 5-tap technician trigger)
            if (s.childLockEnabled && alert == null) {
                ChildLockOverlay(
                    isArabic = ar,
                    onUnlock = {
                        Store.saveSettings(Store.settings.value.copy(childLockEnabled = false))
                    },
                )
            }

            // Hidden 48.dp x 48.dp transparent technician trigger in the top-left corner (5 rapid taps)
            // Always sits above ChildLockOverlay so a technician can access AdminDashboard even when Child Lock is active
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastTapTimeMs > 2_000L) {
                                    tapCount = 1
                                } else {
                                    tapCount += 1
                                }
                                lastTapTimeMs = nowMs
                                if (tapCount >= 5) {
                                    tapCount = 0
                                    showAdminAuth = true
                                }
                            }
                        )
                    }
                    .testTag("admin_secret_corner_trigger"),
            )
        }
    }

    if (showAdminAuth) {
        AdminPinOverlay(
            expectedPin = s.adminPin,
            onAuthenticated = {
                showAdminAuth = false
                showAdminDashboard = true
            },
            onDismiss = {
                showAdminAuth = false
            },
        )
    }

    if (showAdminDashboard) {
        AdminDashboard(
            onClose = {
                showAdminDashboard = false
            },
        )
    }
}

/**
 * Renders the main digital clock (HH:MM, seconds, and optional AM/PM).
 * Strictly locked to [LayoutDirection.Ltr] so seconds always remain on the far right of HH:MM
 * even when the global appliance layout is in Arabic RTL mode.
 */
@Composable
private fun ClockText(s: AppSettings, now: ZonedDateTime, size: TextUnit) {
    val h = if (s.use24h) "%02d".format(Locale.US, now.hour)
            else (if (now.hour % 12 == 0) 12 else now.hour % 12).toString()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$h:${"%02d".format(Locale.US, now.minute)}",
                color = C_CLOCK,
                fontSize = size,
                fontWeight = FontWeight.Medium,
                style = DIGITS,
                lineHeight = size,
            )
            Text(
                text = "%02d".format(Locale.US, now.second),
                color = C_SECONDS,
                fontSize = size * 0.26f,
                style = DIGITS,
                modifier = Modifier.padding(start = 8.dp, bottom = (size.value * 0.12f).dp),
            )
            if (!s.use24h) {
                Text(
                    text = if (now.hour < 12) stringResource(R.string.clock_am) else stringResource(R.string.clock_pm),
                    color = C_DIM,
                    fontSize = size * 0.16f,
                    modifier = Modifier.padding(start = 6.dp, bottom = (size.value * 0.12f).dp),
                )
            }
        }
    }
}

@Composable
private fun PrayerCard(name: String, time: String, highlight: Boolean, modifier: Modifier, size: TextUnit) {
    Column(
        modifier.background(if (highlight) C_ACCENT else C_CARD, RoundedCornerShape(14.dp)).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, color = if (highlight) Color.White else C_DIM, fontSize = size * 0.7f)
        Text(time, color = if (highlight) Color.White else C_TEXT, fontSize = size, style = DIGITS)
    }
}

@Composable
private fun PrayerRow(name: String, time: String, highlight: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(if (highlight) C_ACCENT else C_CARD, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = if (highlight) Color.White else C_DIM, fontSize = 20.sp, modifier = Modifier.weight(1f))
        Text(time, color = if (highlight) Color.White else C_TEXT, fontSize = 24.sp, style = DIGITS)
    }
}

/** Shown while an azan / alarm / test plays (the firmware's mini player). */
@Composable
private fun AlertBar(a: ActiveAlert) {
    val ctx = LocalContext.current
    Row(
        Modifier.padding(12.dp).background(C_CARD, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(a.title, color = C_GOLD, fontSize = 18.sp)
            Text(a.subtitle, color = C_DIM, fontSize = 13.sp)
        }
        Button(onClick = { ctx.startService(AlertService.stopIntent(ctx)) },
            colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT)) { Text(stringResource(R.string.player_stop)) }
        if (a.isAlarm) Button(onClick = { ctx.startService(AlertService.snoozeIntent(ctx)) },
            colors = ButtonDefaults.buttonColors(containerColor = C_GREY)) { Text(stringResource(R.string.alert_snooze_min, SNOOZE_MIN)) }
    }
}

/**
 * Shared tap notification bridge so taps on the locked bottom tab bar also trigger the Child Lock toast pill.
 */
object ChildLockFeedback {
    private val _tapTick = MutableStateFlow(0L)
    val tapTick: StateFlow<Long> = _tapTick.asStateFlow()

    fun notifyLockedTap() {
        _tapTick.value = System.currentTimeMillis()
    }
}

private const val CHILD_LOCK_HOLD_DURATION_MS = 3_000L
private const val CHILD_LOCK_TOAST_DURATION_MS = 2_600L

/**
 * Full-screen touch-absorbing overlay rendered when [AppSettings.childLockEnabled] is `true`.
 *
 * - Absorbs all taps and drags across the main UI so children cannot switch tabs or tap controls.
 * - Displays a subtle, semi-transparent lock badge in the bottom-right corner (away from the top-left 5-tap admin trigger).
 * - Tapping anywhere on the locked screen displays a bilingual toast pill for 2.6 seconds.
 * - Holding the lock badge continuously for 3 seconds fills a smooth circular progress ring and unlocks the screen.
 */
@Composable
fun ChildLockOverlay(
    isArabic: Boolean,
    onUnlock: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var isHoldingLock by remember { mutableStateOf(false) }
    var toastTriggerMs by remember { mutableLongStateOf(0L) }
    var showLockedToast by remember { mutableStateOf(false) }

    val externalTapTick by ChildLockFeedback.tapTick.collectAsState()
    LaunchedEffect(externalTapTick) {
        if (externalTapTick > 0L) {
            toastTriggerMs = externalTapTick
        }
    }

    LaunchedEffect(toastTriggerMs) {
        if (toastTriggerMs > 0L) {
            showLockedToast = true
            delay(CHILD_LOCK_TOAST_DURATION_MS)
            showLockedToast = false
        }
    }

    // Force LTR layout direction for the overlay container so the lock badge always stays on the right edge,
    // completely away from the physical top-left 5-tap technician corner trigger in both English and Arabic modes.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        toastTriggerMs = System.currentTimeMillis()
                    }
                }
                .testTag("child_lock_touch_blocker"),
        ) {
            // Subtle bilingual toast/pill when the locked screen is tapped or while holding the lock icon
            AnimatedVisibility(
                visible = showLockedToast || isHoldingLock,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
            ) {
                val toastMessage = if (isArabic) {
                    "الشاشة مقفلة — اضغط مطولاً على رمز القفل لمدة ٣ ثوانٍ للفتح"
                } else {
                    "Screen Locked — Hold lock icon for 3s to unlock"
                }
                Box(
                    modifier = Modifier
                        .background(C_CARD.copy(alpha = 0.92f), RoundedCornerShape(999.dp))
                        .border(1.dp, C_GOLD.copy(alpha = 0.8f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .testTag("child_lock_toast_pill"),
                ) {
                    Text(
                        text = toastMessage,
                        color = C_GOLD,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Subtle, semi-transparent lock badge with 3-second continuous hold circular progress ring
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 76.dp, end = 24.dp)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(C_CARD.copy(alpha = if (isHoldingLock) 0.90f else 0.55f))
                    .border(
                        width = 1.5.dp,
                        color = if (isHoldingLock) C_GOLD else C_GOLD.copy(alpha = 0.45f),
                        shape = CircleShape,
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            isHoldingLock = true
                            holdProgress = 0f
                            var unlocked = false

                            val progressJob: Job = scope.launch {
                                val startMs = System.currentTimeMillis()
                                while (isActive) {
                                    val elapsed = System.currentTimeMillis() - startMs
                                    val p = (elapsed.toFloat() / CHILD_LOCK_HOLD_DURATION_MS).coerceIn(0f, 1f)
                                    holdProgress = p
                                    if (p >= 1f) {
                                        unlocked = true
                                        isHoldingLock = false
                                        holdProgress = 0f
                                        onUnlock()
                                        break
                                    }
                                    delay(16L)
                                }
                            }

                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (!unlocked && event.changes.any { it.pressed })
                            } finally {
                                progressJob.cancel()
                                if (!unlocked) {
                                    isHoldingLock = false
                                    holdProgress = 0f
                                    toastTriggerMs = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                    .testTag("child_lock_unlock_button"),
                contentAlignment = Alignment.Center,
            ) {
                if (holdProgress > 0f) {
                    CircularProgressIndicator(
                        progress = { holdProgress },
                        modifier = Modifier.size(58.dp),
                        color = C_GOLD,
                        trackColor = C_GREY.copy(alpha = 0.4f),
                        strokeWidth = 4.dp,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = if (isArabic) {
                        "الشاشة مقفلة — اضغط مطولاً على رمز القفل لمدة ٣ ثوانٍ للفتح"
                    } else {
                        "Screen Locked — Hold lock icon for 3s to unlock"
                    },
                    tint = if (isHoldingLock) C_GOLD else C_TEXT.copy(alpha = 0.78f),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
