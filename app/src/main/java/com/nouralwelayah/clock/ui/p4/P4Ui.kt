package com.nouralwelayah.clock.ui.p4

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.media.MediaService
import com.nouralwelayah.clock.ui.C_ACCENT
import com.nouralwelayah.clock.ui.C_BG
import com.nouralwelayah.clock.ui.C_CARD
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_GREY
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.tr
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The ESP32-P4 clock's screen is 1280 x 800 px (tab bar 72 px at the bottom). The tablet layout uses the
 * same numbers, scaled: [k] = dp per P4 pixel. ui.cpp is the reference for sizes and positions.
 */
class P4Scale(val k: Float) {
    val Int.p: Dp get() = (this * k).dp
    val Int.f: TextUnit get() = (this * k).sp
}

val LocalP4 = staticCompositionLocalOf { P4Scale(1f) }

@Composable
fun P4Scaled(k: Float, content: @Composable () -> Unit) = CompositionLocalProvider(LocalP4 provides P4Scale(k), content = content)

/** mkCard(): dark card with rounded corners. */
@Composable
fun P4Card(modifier: Modifier = Modifier, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val s = LocalP4.current
    with(s) {
        Column(
            modifier.background(C_CARD, RoundedCornerShape(12.p))
                .then(if (border != null) Modifier.border(2.p, border, RoundedCornerShape(12.p)) else Modifier)
                .padding(16.p),
            content = content,
        )
    }
}

/** mkButton(): teal button with a text (symbols as Unicode). */
@Composable
fun P4Button(text: String, modifier: Modifier = Modifier, color: Color = C_ACCENT, size: Int = 20, enabled: Boolean = true, onClick: () -> Unit) {
    val s = LocalP4.current
    with(s) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = RoundedCornerShape(10.p),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.p, vertical = 4.p)) {
            Text(text, fontSize = size.f, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** mkLabel() */
@Composable
fun P4Text(text: String, size: Int = 20, color: Color = C_TEXT, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val s = LocalP4.current
    with(s) { Text(text, color = color, fontSize = size.f, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        lineHeight = (size * 1.25f).toInt().f) }
}

/**
 * Live system battery state read from Android [Intent.ACTION_BATTERY_CHANGED] and [BatteryManager].
 */
data class P4BatteryState(
    val percentage: Int = 100,
    val isCharging: Boolean = false,
)

private fun parseBatteryIntent(context: Context, intent: Intent?): P4BatteryState {
    if (intent != null) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pct = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL && plugged != 0 ||
            plugged != 0
        return P4BatteryState(percentage = pct, isCharging = charging)
    }
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100
    val charging = bm?.isCharging == true
    return P4BatteryState(percentage = pct, isCharging = charging)
}

/**
 * Observes real-time Android battery level and charging status via sticky [Intent.ACTION_BATTERY_CHANGED].
 */
@Composable
fun rememberBatteryState(): P4BatteryState {
    val ctx = LocalContext.current
    var state by remember {
        val sticky = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        mutableStateOf(parseBatteryIntent(ctx, sticky))
    }

    DisposableEffect(ctx) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                val batteryIntent = if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    intent
                } else {
                    runCatching {
                        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    }.getOrNull()
                }
                state = parseBatteryIntent(context, batteryIntent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val sticky = runCatching { ctx.registerReceiver(receiver, filter) }.getOrNull()
        if (sticky != null) {
            state = parseBatteryIntent(ctx, sticky)
        }
        onDispose {
            runCatching { ctx.unregisterReceiver(receiver) }
        }
    }
    return state
}

/**
 * Renders the system battery monitor (icon + percentage + charging indicator) for P4Tablet.
 * The battery icon and charging indicator turn Brand Gold ([C_GOLD] `#CC9C56`) whenever charging.
 */
@Composable
fun P4BatteryMonitor(
    modifier: Modifier = Modifier,
    onPicture: Boolean = false,
    showContainer: Boolean = true,
) {
    val battery = rememberBatteryState()
    val s4 = LocalP4.current
    val iconColor = when {
        battery.isCharging -> C_GOLD
        battery.percentage <= 15 -> Color(0xFFE53935)
        onPicture -> C_TEXT
        else -> C_DIM
    }
    val textColor = when {
        battery.isCharging -> C_GOLD
        onPicture -> C_TEXT
        else -> C_DIM
    }
    val a11yDesc = if (battery.isCharging) {
        tr("Battery ${battery.percentage} percent, charging", "البطارية ${battery.percentage} بالمائة، قيد الشحن")
    } else {
        tr("Battery ${battery.percentage} percent", "البطارية ${battery.percentage} بالمائة")
    }

    with(s4) {
        val containerModifier = if (showContainer) {
            modifier
                .background(
                    color = if (onPicture) C_CARD.copy(alpha = 0.78f) else C_CARD,
                    shape = RoundedCornerShape(8.p),
                )
                .border(
                    width = 1.p,
                    color = if (battery.isCharging) C_GOLD else C_GOLD.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.p),
                )
                .padding(horizontal = 12.p, vertical = 6.p)
        } else {
            modifier.padding(horizontal = 10.p, vertical = 4.p)
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = containerModifier
                    .semantics { contentDescription = a11yDesc }
                    .testTag("p4_battery_monitor"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.p),
            ) {
                // Simple battery icon that turns gold when charging
                BatteryIconCanvas(
                    percentage = battery.percentage,
                    isCharging = battery.isCharging,
                    tint = iconColor,
                    modifier = Modifier.size(28.p, 16.p),
                )
                Text(
                    text = String.format(Locale.US, "%d%%", battery.percentage),
                    color = textColor,
                    fontSize = 18.f,
                    fontWeight = if (battery.isCharging) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BatteryIconCanvas(
    percentage: Int,
    isCharging: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = (size.height * 0.13f).coerceAtLeast(1.5f)
        val tipWidth = size.width * 0.10f
        val bodyWidth = size.width - tipWidth - strokeWidth
        val bodyHeight = size.height - strokeWidth
        val bodyLeft = strokeWidth / 2f
        val bodyTop = strokeWidth / 2f
        val corner = CornerRadius(size.height * 0.22f, size.height * 0.22f)

        // Outer battery casing
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = corner,
            style = Stroke(width = strokeWidth),
        )

        // Positive battery terminal tip on the right
        val tipHeight = bodyHeight * 0.46f
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft + bodyWidth + strokeWidth * 0.3f, bodyTop + (bodyHeight - tipHeight) / 2f),
            size = Size(tipWidth, tipHeight),
            cornerRadius = CornerRadius(tipWidth * 0.4f, tipWidth * 0.4f),
        )

        // Inner battery fill bar proportional to remaining percentage
        val pad = strokeWidth * 1.35f
        val maxFillW = (bodyWidth - pad * 2f).coerceAtLeast(0f)
        val fillH = (bodyHeight - pad * 2f).coerceAtLeast(0f)
        val fillW = maxFillW * (percentage.coerceIn(0, 100) / 100f)
        if (fillW > 0.5f && fillH > 0.5f) {
            drawRoundRect(
                color = tint.copy(alpha = if (isCharging) 0.45f else 0.85f),
                topLeft = Offset(bodyLeft + pad, bodyTop + pad),
                size = Size(fillW, fillH),
                cornerRadius = CornerRadius(size.height * 0.12f, size.height * 0.12f),
            )
        }

        // Charging bolt overlay inside the battery icon when charging
        if (isCharging) {
            val cx = bodyLeft + bodyWidth * 0.50f
            val cy = bodyTop + bodyHeight * 0.50f
            val bw = bodyWidth * 0.26f
            val bh = bodyHeight * 0.72f
            val bolt = Path().apply {
                moveTo(cx + bw * 0.15f, cy - bh * 0.50f)
                lineTo(cx - bw * 0.50f, cy + bh * 0.06f)
                lineTo(cx - bw * 0.04f, cy + bh * 0.06f)
                lineTo(cx - bw * 0.18f, cy + bh * 0.50f)
                lineTo(cx + bw * 0.50f, cy - bh * 0.06f)
                lineTo(cx + bw * 0.04f, cy - bh * 0.06f)
                close()
            }
            drawPath(path = bolt, color = C_GOLD)
        }
    }
}

enum class P4Tab(
    val iconPrefix: String,
    @StringRes val labelRes: Int,
) {
    CLOCK("⌂", R.string.tab_clock),
    PRAYER("☰", R.string.tab_prayer),
    ALARMS("🔔", R.string.tab_alarms),
    PLAYER("♪", R.string.tab_player),
    WIFI("📶", R.string.tab_wifi),
    SETTINGS("⚙", R.string.tab_settings),
}

/** The tab bar at the bottom (72 px, like lv_tabview with LV_DIR_BOTTOM). */
@Composable
fun P4TabBar(tab: P4Tab, onTab: (P4Tab) -> Unit) {
    val s = LocalP4.current
    with(s) {
        Row(
            Modifier.fillMaxWidth().height(72.p).background(C_BG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            P4Tab.entries.forEach { t ->
                val on = t == tab
                val labelText = "${t.iconPrefix}  ${stringResource(t.labelRes)}"
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTab(t) }
                        .background(if (on) C_CARD else C_BG),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(labelText, color = if (on) C_ACCENT else C_DIM, fontSize = 20.f, maxLines = 1)
                    if (on) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.p).background(C_ACCENT))
                }
            }
        }
    }
}

/** What the media player plays now (Music tab, mini player). */
data class PlayerNow(
    val title: String = "", val playing: Boolean = false, val pos: Long = 0, val dur: Long = 0, val id: String? = null,
    val volume: Float = 1f,
)

/** The app's media player (MediaService) as a controller, and its state twice a second. */
@Composable
fun rememberPlayer(): Pair<MediaController?, PlayerNow> {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf<MediaController?>(null) }
    var now by remember { mutableStateOf(PlayerNow()) }
    DisposableEffect(Unit) {
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, MediaService::class.java))).buildAsync()
        f.addListener({ c = runCatching { f.get() }.getOrNull() }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }
    LaunchedEffect(c) {
        while (true) {
            val p = c
            now = if (p == null) PlayerNow() else PlayerNow(
                title = p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(), playing = p.isPlaying,
                pos = p.currentPosition, dur = if (p.duration == C.TIME_UNSET) 0 else p.duration,
                id = p.currentMediaItem?.mediaId, volume = p.volume,
            )
            delay(500)
        }
    }
    return c to now
}

fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

val GREY = C_GREY
