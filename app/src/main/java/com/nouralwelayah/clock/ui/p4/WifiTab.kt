package com.nouralwelayah.clock.ui.p4

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nouralwelayah.clock.HAS_HOTSPOT
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.media.BleServerState
import com.nouralwelayah.clock.media.MediaRemoteServer
import com.nouralwelayah.clock.system.WifiController
import com.nouralwelayah.clock.system.WifiScannedNetwork
import com.nouralwelayah.clock.system.WifiStatusSnapshot
import com.nouralwelayah.clock.ui.C_ACCENT
import com.nouralwelayah.clock.ui.C_BG
import com.nouralwelayah.clock.ui.C_CARD
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_GREY
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.WebRemoteSection
import com.nouralwelayah.clock.ui.isArabic
import com.nouralwelayah.clock.ui.tr
import com.nouralwelayah.clock.web.Hotspot
import kotlinx.coroutines.delay

private val StatusEmerald = Color(0xFF22C55E)
private val StatusAmber = Color(0xFFF59E0B)

/**
 * Phase 16 Redesigned Wi-Fi & Bluetooth Remote Tab for the Nour Alwelayah Appliance (1280x800 landscape).
 *
 * Includes:
 * 1. Artistic Wi-Fi Status Card with glowing connection status pill, dynamic signal-strength icon,
 *    active SSID display (raw IP and legacy settings hint removed), and in-app Wi-Fi Manager launcher.
 * 2. Built-in Custom Wi-Fi Scanner & Connector Modal ([WifiManagerDialog]) powered by [WifiController].
 * 3. Functional Bluetooth Remote Status & Connected Devices Card wired to [MediaRemoteServer.bleState].
 * 4. Two-Column Wi-Fi Web Remote Card with Dynamic Async QR Code ([WebRemoteSection]).
 */
@Composable
fun WifiTab(s: AppSettings) {
    val ctx = LocalContext.current
    val wifiSnap by WifiController.status.collectAsState()
    val bleState by MediaRemoteServer.bleState.collectAsState()
    val openWifiManagerReq by com.nouralwelayah.clock.media.MediaHub.openWifiManager.collectAsState()
    var showWifiModal by remember { mutableStateOf(false) }

    LaunchedEffect(openWifiManagerReq) {
        if (openWifiManagerReq) {
            showWifiModal = true
            com.nouralwelayah.clock.media.MediaHub.openWifiManager.value = false
        }
    }

    // Keep Wi-Fi & BLE states continuously updated while the tab is visible
    DisposableEffect(ctx) {
        val unregister = WifiController.registerScanReceiver(ctx)
        MediaRemoteServer.syncBleSnapshot(ctx)
        onDispose { unregister() }
    }

    LifecycleResumeEffect(Unit) {
        WifiController.refreshStatus(ctx)
        MediaRemoteServer.syncBleSnapshot(ctx)
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        while (true) {
            WifiController.refreshStatus(ctx)
            delay(3_000L)
        }
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.all { it }) {
            MediaRemoteServer.refreshBle(ctx)
        } else {
            MediaRemoteServer.syncBleSnapshot(ctx)
        }
    }

    val s4 = LocalP4.current
    with(s4) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(16.p),
        ) {
            // Left Column (600.p): Artistic Wi-Fi Status Card + Live Bluetooth Remote Card
            Column(
                modifier = Modifier
                    .width(600.p)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.p),
            ) {
                ArtisticWifiStatusCard(
                    wifiSnap = wifiSnap,
                    onOpenWifiManager = { showWifiModal = true },
                )

                BluetoothRemoteStatusCard(
                    bleState = bleState,
                    onRefreshBle = {
                        if (MediaRemoteServer.canRun(ctx)) {
                            MediaRemoteServer.refreshBle(ctx)
                        } else {
                            val perms = MediaRemoteServer.permissions()
                            if (perms.isNotEmpty()) {
                                blePermissionLauncher.launch(perms)
                            } else {
                                MediaRemoteServer.refreshBle(ctx)
                            }
                        }
                    },
                )
            }

            // Right Column: Redesigned Wi-Fi Web Remote with Dynamic QR Code + No-Router Hotspot Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.p),
            ) {
                WebRemoteSection(s, Modifier.fillMaxWidth())
                if (HAS_HOTSPOT) NoRouterCard()
            }
        }
    }

    if (showWifiModal) {
        WifiManagerDialog(
            onDismiss = {
                showWifiModal = false
                WifiController.refreshStatus(ctx)
            },
        )
    }
}

/**
 * 1. Artistic Wi-Fi Status Card (Phase 16):
 * - Stylized Connection Status Pill with animated glowing indicator (Emerald/Gold when connected,
 *   muted gray/amber when offline).
 * - Dynamic Wi-Fi signal strength icon and active SSID name.
 * - Raw IP display and explanatory system settings paragraph removed.
 * - Launches the built-in Jetpack Compose [WifiManagerDialog].
 */
@Composable
private fun ArtisticWifiStatusCard(
    wifiSnap: WifiStatusSnapshot,
    onOpenWifiManager: () -> Unit,
) {
    val s4 = LocalP4.current
    val pulseTransition = rememberInfiniteTransition(label = "wifi_status_pulse")
    val glowAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wifi_glow_alpha",
    )

    val connected = wifiSnap.isConnected
    val dotColor = if (connected) StatusEmerald else StatusAmber
    val pillBorderColor = if (connected) C_GOLD else C_GREY

    with(s4) {
        P4Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("artistic_wifi_status_card"),
            border = if (connected) C_GOLD.copy(alpha = 0.35f) else C_GREY,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.p)) {
                // Header Row: Section Title + Stylized Connection Status Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.p),
                    ) {
                        WifiSignalArcIcon(
                            level = if (connected) wifiSnap.signalLevel.coerceAtLeast(1) else 0,
                            activeColor = C_GOLD,
                            inactiveColor = C_DIM.copy(alpha = 0.35f),
                            modifier = Modifier.size(30.p),
                        )
                        Text(
                            text = tr("Wi-Fi Network", "الشبكة اللاسلكية (Wi-Fi)"),
                            color = C_TEXT,
                            fontSize = 25.f,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Stylized Connection Status Pill with glowing dot
                    Row(
                        modifier = Modifier
                            .background(
                                color = if (connected) C_BG else C_BG.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(50),
                            )
                            .border(2.p, pillBorderColor, RoundedCornerShape(50))
                            .padding(horizontal = 14.p, vertical = 6.p)
                            .testTag("wifi_connection_status_pill"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.p),
                    ) {
                        Box(
                            modifier = Modifier.size(16.p),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.p)
                                    .background(
                                        color = dotColor.copy(alpha = if (connected) glowAlpha * 0.45f else 0.2f),
                                        shape = CircleShape,
                                    ),
                            )
                            Box(
                                modifier = Modifier
                                    .size(9.p)
                                    .background(color = dotColor, shape = CircleShape),
                            )
                        }
                        Text(
                            text = if (connected) {
                                tr("Connected", "متصل")
                            } else {
                                tr("Offline", "غير متصل")
                            },
                            color = if (connected) C_GOLD else C_DIM,
                            fontSize = 17.f,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Active SSID Hero Showcase Banner (No raw IP address shown)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(C_BG, RoundedCornerShape(12.p))
                        .border(
                            width = 1.p,
                            color = if (connected) C_GOLD.copy(alpha = 0.45f) else C_GREY,
                            shape = RoundedCornerShape(12.p),
                        )
                        .padding(horizontal = 16.p, vertical = 14.p),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.p),
                    ) {
                        Text(
                            text = tr("ACTIVE NETWORK (SSID)", "الشبكة المتصلة حالياً"),
                            color = C_DIM,
                            fontSize = 14.f,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                connected && wifiSnap.ssid.isNotEmpty() -> wifiSnap.ssid
                                connected -> tr("Connected Wi-Fi Network", "شبكة واي فاي متصلة")
                                !wifiSnap.isWifiEnabled -> tr("Wi-Fi Radio Disabled", "الواي فاي متوقف")
                                else -> tr("Not connected to any network", "غير متصل بأي شبكة")
                            },
                            color = if (connected) C_TEXT else C_DIM,
                            fontSize = 24.f,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("wifi_active_ssid_text"),
                        )
                    }

                    // Dynamic 4-Bar Signal Meter Badge
                    Row(
                        modifier = Modifier
                            .background(C_CARD, RoundedCornerShape(10.p))
                            .border(1.p, C_GREY, RoundedCornerShape(10.p))
                            .padding(horizontal = 12.p, vertical = 8.p),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.p),
                    ) {
                        WifiSignalBarsCanvas(
                            level = if (connected) wifiSnap.signalLevel.coerceAtLeast(1) else 0,
                            activeColor = if (connected) C_GOLD else C_DIM,
                            inactiveColor = C_GREY,
                            modifier = Modifier.size(26.p, 20.p),
                        )
                        Text(
                            text = if (connected) {
                                when (wifiSnap.signalLevel) {
                                    4 -> tr("Excellent", "ممتازة")
                                    3 -> tr("Good", "جيدة")
                                    2 -> tr("Fair", "متوسطة")
                                    else -> tr("Weak", "ضعيفة")
                                }
                            } else {
                                tr("No Signal", "لا توجد إشارة")
                            },
                            color = if (connected) C_GOLD else C_DIM,
                            fontSize = 15.f,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // In-App Wi-Fi Scanner & Connector Modal Trigger Button
                P4Button(
                    text = tr("📶  Wi-Fi Network Manager", "📶  إدارة شبكات الواي فاي"),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.p)
                        .testTag("open_wifi_manager_button"),
                    color = C_ACCENT,
                    size = 21,
                    onClick = onOpenWifiManager,
                )
            }
        }
    }
}

/**
 * 3. Functional Bluetooth Remote Status & Connected Devices Card (Phase 16):
 * - Wired directly to [MediaRemoteServer.bleState] (`StateFlow<BleServerState>`).
 * - Displays live advertising state ("Ready — Advertising as NourAlwelayah" / "جاهز — يبث باسم NourAlwelayah")
 *   with a glowing status badge.
 * - Shows a live list of connected companion devices (device name + masked MAC address + connected dot)
 *   or a clean "No remotes connected" state.
 * - Functional Refresh button restarts BLE advertising & re-queries active GATT connections with a spinning icon.
 */
@Composable
private fun BluetoothRemoteStatusCard(
    bleState: BleServerState,
    onRefreshBle: () -> Unit,
) {
    val s4 = LocalP4.current
    val pulseTransition = rememberInfiniteTransition(label = "ble_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ble_glow_alpha",
    )

    val spinAngle by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ble_refresh_spin",
    )

    val isReady = bleState.hasPermission && bleState.isBluetoothEnabled && bleState.isAdvertising
    val badgeColor = when {
        isReady -> StatusEmerald
        bleState.hasPermission && bleState.isBluetoothEnabled -> C_GOLD
        else -> StatusAmber
    }

    with(s4) {
        P4Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bluetooth_remote_status_card"),
            border = C_GOLD.copy(alpha = 0.28f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.p)) {
                // Header Row: Title + Functional Refresh Button with Spinning Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = tr("Bluetooth Remote", "التحكم عبر البلوتوث"),
                        color = C_TEXT,
                        fontSize = 24.f,
                        fontWeight = FontWeight.Bold,
                    )

                    Button(
                        onClick = onRefreshBle,
                        shape = RoundedCornerShape(10.p),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C_GREY,
                            contentColor = C_GOLD,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.p, vertical = 6.p),
                        modifier = Modifier
                            .height(48.p)
                            .border(1.p, C_GOLD.copy(alpha = 0.55f), RoundedCornerShape(10.p))
                            .testTag("ble_refresh_button"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.p),
                        ) {
                            Text(
                                text = "⟳",
                                fontSize = 20.f,
                                fontWeight = FontWeight.Bold,
                                color = C_GOLD,
                                modifier = if (bleState.isRefreshing) Modifier.rotate(spinAngle) else Modifier,
                            )
                            Text(
                                text = if (bleState.isRefreshing) {
                                    tr("Refreshing...", "جارٍ التحديث...")
                                } else {
                                    tr("Refresh", "تحديث")
                                },
                                fontSize = 17.f,
                                fontWeight = FontWeight.SemiBold,
                                color = C_TEXT,
                            )
                        }
                    }
                }

                // Glowing Advertising Status Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(C_BG, RoundedCornerShape(10.p))
                        .border(
                            width = 1.p,
                            color = if (isReady) C_GOLD.copy(alpha = 0.55f) else C_GREY,
                            shape = RoundedCornerShape(10.p),
                        )
                        .padding(horizontal = 14.p, vertical = 10.p)
                        .testTag("ble_advertising_status_badge"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.p),
                ) {
                    Box(
                        modifier = Modifier.size(16.p),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.p)
                                .background(
                                    color = badgeColor.copy(alpha = if (isReady) pulseAlpha * 0.45f else 0.2f),
                                    shape = CircleShape,
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .size(9.p)
                                .background(color = badgeColor, shape = CircleShape),
                        )
                    }

                    Text(
                        text = when {
                            !bleState.hasPermission -> tr(
                                "Needs Nearby Devices permission — tap Refresh to grant",
                                "يتطلب إذن الأجهزة القريبة — اضغط تحديث لمنح الإذن",
                            )
                            !bleState.isBluetoothEnabled -> tr(
                                "Bluetooth is turned off on this appliance",
                                "البلوتوث متوقف حالياً على هذا الجهاز",
                            )
                            bleState.isAdvertising -> tr(
                                "Ready — Advertising as ${bleState.advertisedName}",
                                "جاهز — يبث باسم ${bleState.advertisedName}",
                            )
                            else -> tr(
                                "Standby — Tap Refresh to broadcast as ${bleState.advertisedName}",
                                "في وضع الاستعداد — اضغط تحديث للبث باسم ${bleState.advertisedName}",
                            )
                        },
                        color = if (isReady) C_GOLD else C_DIM,
                        fontSize = 18.f,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Connected Companion Devices Header + Count Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = tr("CONNECTED COMPANION REMOTES", "أجهزة التحكم المتصلة حالياً"),
                        color = C_DIM,
                        fontSize = 14.f,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${bleState.connectedClients.size}",
                        color = if (bleState.connectedClients.isNotEmpty()) C_GOLD else C_DIM,
                        fontSize = 15.f,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (bleState.connectedClients.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_BG.copy(alpha = 0.7f), RoundedCornerShape(10.p))
                            .border(1.p, C_GREY, RoundedCornerShape(10.p))
                            .padding(horizontal = 14.p, vertical = 12.p)
                            .testTag("ble_no_remotes_connected"),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = tr(
                                "No remotes connected",
                                "لا توجد أجهزة تحكم متصلة حالياً",
                            ),
                            color = C_DIM,
                            fontSize = 17.f,
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.p),
                        modifier = Modifier.testTag("ble_connected_clients_list"),
                    ) {
                        bleState.connectedClients.forEach { client ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(C_BG, RoundedCornerShape(10.p))
                                    .border(1.p, C_GOLD.copy(alpha = 0.5f), RoundedCornerShape(10.p))
                                    .padding(horizontal = 14.p, vertical = 10.p),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.p),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.p)
                                            .background(StatusEmerald, CircleShape),
                                    )
                                    Column {
                                        Text(
                                            text = client.displayName,
                                            color = C_TEXT,
                                            fontSize = 18.f,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = client.maskedAddress,
                                            color = C_DIM,
                                            fontSize = 14.f,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }

                                Text(
                                    text = tr("Connected", "متصل"),
                                    color = StatusEmerald,
                                    fontSize = 15.f,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 2. Built-in Custom Wi-Fi Scanner & Connector Modal (Phase 16):
 * - Styled in BrandNavy (#0C1823), BrandCard (#142433), and BrandGold (#CC9C56).
 * - Active Scan / Refresh button using [WifiController.startScan] & BroadcastReceiver.
 * - Lists nearby deduplicated SSIDs sorted by RSSI with signal-bar icons and lock icons.
 * - Tapping a network opens an inline password field (with show/hide toggle) and Connect button.
 * - Includes a graceful fallback button to open Android's in-app floating connectivity sheet
 *   ([WifiController.openConnectivityPanel]) if the OS restricts silent switching on non-Device-Owner builds.
 */
@Composable
private fun WifiManagerDialog(
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val ar = isArabic()
    val networks by WifiController.networks.collectAsState()
    val isScanning by WifiController.isScanning.collectAsState()
    val wifiSnap by WifiController.status.collectAsState()

    var selectedSsid by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf("") }
    var showPanelHighlight by remember { mutableStateOf(false) }

    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            WifiController.startScan(ctx)
        } else {
            statusBanner = if (ar) {
                "يلزم منح إذن الموقع/الشبكة القريبة للبحث عن شبكات الواي فاي."
            } else {
                "Location / Nearby Wi-Fi permission is required to scan nearby networks."
            }
        }
    }

    fun triggerScan() {
        statusBanner = ""
        if (WifiController.hasScanPermission(ctx)) {
            WifiController.startScan(ctx)
        } else {
            scanPermissionLauncher.launch(WifiController.scanPermissions())
        }
    }

    LaunchedEffect(Unit) {
        triggerScan()
    }

    val spinTransition = rememberInfiniteTransition(label = "wifi_scan_spin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wifi_scan_angle",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .width(860.dp)
                .heightIn(min = 480.dp, max = 680.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, C_GOLD, RoundedCornerShape(18.dp))
                .testTag("wifi_manager_dialog"),
            color = C_BG,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Modal Header: Title, Active Status Pill, Scan/Refresh Button, Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        WifiSignalArcIcon(
                            level = if (wifiSnap.isConnected) wifiSnap.signalLevel.coerceAtLeast(1) else 2,
                            activeColor = C_GOLD,
                            inactiveColor = C_DIM.copy(alpha = 0.35f),
                            modifier = Modifier.size(32.dp),
                        )
                        Column {
                            Text(
                                text = tr("Wi-Fi Network Manager", "إدارة الشبكة اللاسلكية (Wi-Fi)"),
                                color = C_TEXT,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (wifiSnap.isConnected && wifiSnap.ssid.isNotEmpty()) {
                                    tr("Connected to ${wifiSnap.ssid}", "متصل حالياً بـ ${wifiSnap.ssid}")
                                } else {
                                    tr("Select a nearby network below to connect", "اختر شبكة من القائمة أدناه للاتصال")
                                },
                                color = if (wifiSnap.isConnected) C_GOLD else C_DIM,
                                fontSize = 15.sp,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { triggerScan() },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = C_CARD,
                                contentColor = C_GOLD,
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .border(1.dp, C_GOLD, RoundedCornerShape(10.dp))
                                .testTag("wifi_modal_scan_button"),
                        ) {
                            Text(
                                text = "⟳",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = if (isScanning) Modifier.rotate(spinAngle) else Modifier,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isScanning) {
                                    tr("Scanning...", "جارٍ البحث...")
                                } else {
                                    tr("Scan / Refresh", "بحث / تحديث")
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = C_CARD,
                                contentColor = C_TEXT,
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("wifi_modal_close_button"),
                        ) {
                            Text(
                                text = tr("✕  Close", "✕  إغلاق"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Status feedback banner if present
                if (statusBanner.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_CARD, RoundedCornerShape(10.dp))
                            .border(1.dp, C_GOLD, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = statusBanner,
                            color = C_GOLD,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Discovered Networks List
                if (networks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(C_CARD, RoundedCornerShape(12.dp))
                            .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = if (isScanning) {
                                    tr("Scanning for nearby Wi-Fi networks...", "جارٍ البحث عن شبكات الواي فاي القريبة...")
                                } else {
                                    tr("No Wi-Fi networks found yet. Tap Scan / Refresh or use the Quick Wi-Fi Panel below.", "لم يتم العثور على شبكات بعد. اضغط بحث / تحديث أو استخدم لوحة الواي فاي السريعة أدناه.")
                                },
                                color = C_DIM,
                                fontSize = 17.sp,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("wifi_modal_network_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(networks, key = { it.ssid }) { net ->
                            val isExpanded = selectedSsid == net.ssid
                            WifiNetworkRowItem(
                                network = net,
                                isExpanded = isExpanded,
                                password = password,
                                showPassword = showPassword,
                                onSelectNetwork = {
                                    if (selectedSsid == net.ssid) {
                                        selectedSsid = null
                                    } else {
                                        selectedSsid = net.ssid
                                        password = ""
                                        showPassword = false
                                    }
                                },
                                onPasswordChange = { password = it },
                                onToggleShowPassword = { showPassword = !showPassword },
                                onConnect = {
                                    val res = WifiController.connect(
                                        context = ctx,
                                        ssid = net.ssid,
                                        password = if (net.isSecured) password else "",
                                    )
                                    statusBanner = if (ar) res.messageAr else res.messageEn
                                    showPanelHighlight = res.needsSystemPanelFallback
                                    if (res.accepted) {
                                        selectedSsid = null
                                        password = ""
                                    }
                                },
                            )
                        }
                    }
                }

                // Bottom Footer: Graceful In-App Floating Connectivity Panel Fallback
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(C_CARD, RoundedCornerShape(12.dp))
                        .border(
                            width = if (showPanelHighlight) 1.5.dp else 1.dp,
                            color = if (showPanelHighlight) C_GOLD else C_GREY,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(
                                "System Quick Wi-Fi Sheet (In-App Floating Panel)",
                                "لوحة اتصال النظام السريعة (نافذة منبثقة داخل التطبيق)",
                            ),
                            color = C_TEXT,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = tr(
                                "Use if Android requires confirmation on non-Device-Owner builds without leaving the clock.",
                                "استخدمها إذا طلب النظام تأكيد التبديل بدون مغادرة شاشة الساعة.",
                            ),
                            color = C_DIM,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { WifiController.openConnectivityPanel(ctx) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (showPanelHighlight) C_GOLD else C_BG,
                            contentColor = if (showPanelHighlight) C_BG else C_GOLD,
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .border(1.dp, C_GOLD, RoundedCornerShape(10.dp))
                            .testTag("wifi_modal_floating_panel_button"),
                    ) {
                        Text(
                            text = tr("⚙  Quick Wi-Fi Panel", "⚙  لوحة الواي فاي السريعة"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiNetworkRowItem(
    network: WifiScannedNetwork,
    isExpanded: Boolean,
    password: String,
    showPassword: Boolean,
    onSelectNetwork: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleShowPassword: () -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(C_CARD, RoundedCornerShape(12.dp))
            .border(
                width = if (network.isCurrentNetwork || isExpanded) 1.5.dp else 1.dp,
                color = when {
                    network.isCurrentNetwork -> StatusEmerald
                    isExpanded -> C_GOLD
                    else -> C_GREY
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onSelectNetwork)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                WifiSignalBarsCanvas(
                    level = network.signalLevel.coerceAtLeast(1),
                    activeColor = if (network.isCurrentNetwork) StatusEmerald else C_GOLD,
                    inactiveColor = C_GREY,
                    modifier = Modifier.size(28.dp, 22.dp),
                )
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = network.ssid,
                            color = C_TEXT,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (network.isSecured) {
                            Text(
                                text = "🔒",
                                fontSize = 15.sp,
                                color = C_GOLD,
                            )
                        }
                    }
                    Text(
                        text = "${network.securityLabel}  •  ${network.rssi} dBm",
                        color = C_DIM,
                        fontSize = 13.sp,
                    )
                }
            }

            if (network.isCurrentNetwork) {
                Row(
                    modifier = Modifier
                        .background(StatusEmerald.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .border(1.dp, StatusEmerald, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(StatusEmerald, CircleShape),
                    )
                    Text(
                        text = tr("Connected", "متصل"),
                        color = StatusEmerald,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Text(
                    text = if (isExpanded) tr("▲ Hide", "▲ إخفاء") else tr("Connect ▶", "اتصال ▶"),
                    color = C_GOLD,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Inline Password & Connect Panel when tapped
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(C_BG, RoundedCornerShape(10.dp))
                    .border(1.dp, C_GOLD.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (network.isSecured) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            singleLine = true,
                            label = {
                                Text(
                                    text = tr("Wi-Fi Password", "كلمة مرور الشبكة"),
                                    fontSize = 14.sp,
                                )
                            },
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                TextButton(onClick = onToggleShowPassword) {
                                    Text(
                                        text = if (showPassword) tr("Hide", "إخفاء") else tr("Show", "إظهار"),
                                        color = C_GOLD,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = C_GOLD,
                                unfocusedBorderColor = C_GREY,
                                focusedLabelColor = C_GOLD,
                                unfocusedLabelColor = C_DIM,
                                focusedTextColor = C_TEXT,
                                unfocusedTextColor = C_TEXT,
                                cursorColor = C_GOLD,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("wifi_password_input"),
                        )

                        Button(
                            onClick = onConnect,
                            enabled = password.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = C_GOLD,
                                contentColor = C_BG,
                            ),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("wifi_connect_submit_button"),
                        ) {
                            Text(
                                text = tr("Connect", "اتصال"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tr("Open network (no password required)", "شبكة مفتوحة (لا تتطلب كلمة مرور)"),
                            color = C_DIM,
                            fontSize = 15.sp,
                        )
                        Button(
                            onClick = onConnect,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = C_GOLD,
                                contentColor = C_BG,
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("wifi_connect_open_button"),
                        ) {
                            Text(
                                text = tr("Connect Now", "اتصل الآن"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom Canvas drawing 4 ascending Wi-Fi signal strength bars (0..4).
 */
@Composable
private fun WifiSignalBarsCanvas(
    level: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val barCount = 4
        val gap = size.width * 0.10f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val fraction = (i + 1) / barCount.toFloat()
            val barHeight = size.height * (0.28f + 0.72f * fraction)
            val left = i * (barWidth + gap)
            val top = size.height - barHeight
            drawRoundRect(
                color = if (i < level) activeColor else inactiveColor,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.35f, barWidth * 0.35f),
            )
        }
    }
}

/**
 * Custom Canvas drawing concentric Wi-Fi wave arcs + base dot.
 */
@Composable
private fun WifiSignalArcIcon(
    level: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = (size.minDimension * 0.11f).coerceAtLeast(2f)
        val cx = size.width * 0.5f
        val cy = size.height * 0.82f

        // Base transmitter dot
        drawCircle(
            color = if (level >= 1) activeColor else inactiveColor,
            radius = stroke * 0.85f,
            center = Offset(cx, cy),
        )

        val radii = listOf(size.minDimension * 0.28f, size.minDimension * 0.48f, size.minDimension * 0.68f)
        radii.forEachIndexed { idx, r ->
            val active = level >= (idx + 2)
            drawArc(
                color = if (active) activeColor else inactiveColor,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Stage 4: the tablet's own Wi-Fi for when there is no router (phones join by the QR code, or Send does it). */
@Composable
private fun NoRouterCard() {
    val ctx = LocalContext.current
    val ar = isArabic()
    val info by Hotspot.info.collectAsState()
    val status by Hotspot.status.collectAsState()
    var err by remember { mutableStateOf("") }
    fun start() { err = ""; Hotspot.start(ctx) { _, e -> err = e } }
    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) start() else err = if (ar) "يلزم منح الإذن لتشغيل نقطة اتصال الجهاز" else "Permission needed for the tablet's own Wi-Fi"
    }
    val s4 = LocalP4.current
    with(s4) {
        P4Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.p)) {
                P4Text(tr("No-router mode", "وضع الاتصال المباشر (بدون راوتر)"), 24, C_TEXT)
                P4Text(
                    tr(
                        "The tablet makes its own Wi-Fi (no internet). Join it with the phone's camera (QR code), or just use the phone's Send - it joins by itself. Then open the page below in a browser.",
                        "ينشئ الجهاز شبكة Wi-Fi محلية خاصة به. اتصل بها عبر مسح رمز QR بكاميرا الهاتف، ثم افتح العنوان أدناه في المتصفح.",
                    ),
                    17,
                    C_DIM,
                )
                val i = info
                if (i == null) {
                    P4Button(
                        if (status == "Starting...") tr("Starting...", "جارٍ التشغيل...") else tr("📶  Start the tablet's Wi-Fi", "📶  تشغيل شبكة الجهاز"),
                        Modifier.size(380.p, 56.p),
                        enabled = status != "Starting...",
                    ) {
                        if (perms.all {
                                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
                            }
                        ) start() else ask.launch(perms)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.p)) {
                        QrCode(i.qr, Modifier.size(190.p))
                        Column(verticalArrangement = Arrangement.spacedBy(6.p)) {
                            P4Text(tr("Wi-Fi name", "اسم الشبكة"), 16, C_DIM); P4Text(i.ssid, 22, C_TEXT)
                            P4Text(tr("Password", "كلمة المرور"), 16, C_DIM); P4Text(i.pass, 22, C_TEXT)
                            P4Text(tr("Page", "الصفحة"), 16, C_DIM); P4Text(i.page.ifEmpty { tr("(address not found)", "(العنوان غير متاح)") }, 22, C_ACCENT)
                        }
                    }
                    P4Button(tr("■  Stop the tablet's Wi-Fi", "■  إيقاف شبكة الجهاز"), Modifier.size(340.p, 54.p), color = GREY) { Hotspot.stop() }
                }
                if (err.isNotEmpty()) P4Text(err, 18, C_GOLD)
                P4Text(
                    tr(
                        "While it is on, the tablet may leave the home Wi-Fi. It stops when Prayer Clock closes.",
                        "أثناء التشغيل قد ينفصل الجهاز مؤقتاً عن شبكة المنزل.",
                    ),
                    15,
                    C_DIM,
                )
            }
        }
    }
}
