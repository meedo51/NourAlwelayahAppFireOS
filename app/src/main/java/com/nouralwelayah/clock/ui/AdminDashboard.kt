package com.nouralwelayah.clock.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.Kiosk
import com.nouralwelayah.clock.MainActivity
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.Sys
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.kiosk.KioskPolicy
import com.nouralwelayah.clock.ota.KbOtaManager
import com.nouralwelayah.clock.ota.KbOtaState
import com.nouralwelayah.clock.ota.OtaIntegrityHealthCheck
import com.nouralwelayah.clock.ota.OtaIntegrityStatus
import com.nouralwelayah.clock.ota.OtaState
import com.nouralwelayah.clock.ota.OtaUpdateManager
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Secure Number Pad overlay triggered by 5 rapid taps on the top-left corner of the idle clock UI.
 * Validates the entered PIN against [AppSettings.adminPin] before unlocking [AdminDashboard].
 */
@Composable
fun AdminPinOverlay(
    expectedPin: String,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xDD080D14))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = 420.dp)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(C_CARD)
                    .border(1.5.dp, C_GOLD, RoundedCornerShape(20.dp))
                    .clickable(enabled = false) { }
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Admin Lock",
                        tint = C_GOLD,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Technician Authentication",
                        color = C_GOLD,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = "Enter Technician PIN to unlock Nour Alwelayah Portal",
                    color = C_DIM,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )

                // Masked PIN display box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(C_BG, RoundedCornerShape(12.dp))
                        .border(1.dp, if (errorMsg.isNotEmpty()) Color(0xFFEF5350) else C_GREY, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val masked = "•".repeat(enteredPin.length).ifEmpty { "Enter PIN" }
                    Text(
                        text = masked,
                        color = if (enteredPin.isEmpty()) C_DIM else C_TEXT,
                        fontSize = if (enteredPin.isEmpty()) 16.sp else 28.sp,
                        letterSpacing = if (enteredPin.isEmpty()) 1.sp else 8.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (errorMsg.isNotEmpty()) {
                    Text(
                        text = errorMsg,
                        color = Color(0xFFEF5350),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 3x4 Number Pad
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("CLR", "0", "⌫"),
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rows.forEach { rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowKeys.forEach { key ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(C_BG)
                                        .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                                        .clickable {
                                            errorMsg = ""
                                            when (key) {
                                                "CLR" -> enteredPin = ""
                                                "⌫" -> if (enteredPin.isNotEmpty()) {
                                                    enteredPin = enteredPin.dropLast(1)
                                                }
                                                else -> if (enteredPin.length < 8) {
                                                    val updated = enteredPin + key
                                                    enteredPin = updated
                                                    val targetPin = expectedPin.ifBlank { "9200" }
                                                    if (updated == targetPin) {
                                                        onAuthenticated()
                                                    }
                                                }
                                            }
                                        }
                                        .testTag("admin_pin_key_$key"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = key,
                                        color = if (key == "CLR" || key == "⌫") C_GOLD else C_TEXT,
                                        fontSize = if (key == "CLR") 15.sp else 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("admin_pin_cancel_button"),
                    ) {
                        Text("Cancel", color = C_TEXT)
                    }
                    Button(
                        onClick = {
                            val targetPin = expectedPin.ifBlank { "9200" }
                            if (enteredPin == targetPin) {
                                onAuthenticated()
                            } else {
                                errorMsg = "Incorrect Technician PIN"
                                enteredPin = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("admin_pin_unlock_button"),
                    ) {
                        Text("Unlock", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Full-screen Hidden Technician Portal (`AdminDashboard`) for on-site Nour Alwelayah provisioning.
 *
 * Includes:
 * 1. Header: "Nour Alwelayah Technician Portal" with a "Close/Exit" button to return to the clock.
 * 2. Wi-Fi Configurator: Launches `Settings.ACTION_WIFI_SETTINGS` (whitelisted in Lock Task mode).
 * 3. OTA Server Input: Edits and saves `AppSettings.otaServerUrl` to `Store`.
 * 4. Volume Limiter: Slider and numeric input to restrict `AppSettings.maxVolumeLimit` (0..100).
 * 5. Kiosk Master Toggle: Toggles `AppSettings.kioskMode`; when disabled, updates `kioskMode = false`
 *    and invokes `Kiosk.clear(context)` so the tablet can be serviced normally.
 */
@Composable
fun AdminDashboard(
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val currentSettings by Store.settings.collectAsState()

    var otaUrlInput by remember(currentSettings.otaServerUrl) { mutableStateOf(currentSettings.otaServerUrl) }
    var volumeLimitFloat by remember(currentSettings.maxVolumeLimit) {
        mutableFloatStateOf(currentSettings.maxVolumeLimit.toFloat())
    }
    var volumeLimitText by remember(currentSettings.maxVolumeLimit) {
        mutableStateOf(currentSettings.maxVolumeLimit.toString())
    }
    var adminPinInput by remember(currentSettings.adminPin) { mutableStateOf(currentSettings.adminPin) }
    var statusBanner by remember { mutableStateOf("") }

    // Pause Watchdog auto-reorder while the technician is inside the Admin Portal (e.g., configuring Wi-Fi)
    DisposableEffect(Unit) {
        MainActivity.isAdminSessionActive = true
        onDispose {
            MainActivity.isAdminSessionActive = false
        }
    }

    BackHandler(onBack = onClose)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        NourAlwelayahLocaleProvider(languageCode = currentSettings.appLanguage) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(C_BG)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Header: localized "Nour Alwelayah Technician Portal" with Close/Exit button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(C_CARD, RoundedCornerShape(16.dp))
                    .border(1.dp, C_GOLD, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(C_ACCENT.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.admin_portal_title),
                            tint = C_GOLD,
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.admin_portal_title),
                            color = C_GOLD,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.admin_portal_subtitle),
                            color = C_DIM,
                            fontSize = 13.sp,
                        )
                    }
                }

                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("admin_dashboard_close_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.admin_close_exit),
                        tint = Color(0xFF0c1823),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.admin_close_exit),
                        color = Color(0xFF0c1823),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (statusBanner.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF142433), RoundedCornerShape(10.dp))
                        .border(1.dp, C_ACCENT, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(text = statusBanner, color = C_TEXT, fontSize = 14.sp)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 2. Bilingual UI Language & RTL Toggle ("en" <-> "ar")
                val switchedEnMsg = stringResource(R.string.admin_lang_switched_en)
                val switchedArMsg = stringResource(R.string.admin_lang_switched_ar)
                AdminSectionCard(
                    title = stringResource(R.string.admin_language_title),
                    subtitle = stringResource(R.string.admin_language_subtitle),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val isEnglish = currentSettings.appLanguage != "ar"
                        val isArabic = currentSettings.appLanguage == "ar"

                        Button(
                            onClick = {
                                Store.saveSettings(currentSettings.copy(appLanguage = "en"))
                                statusBanner = switchedEnMsg
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isEnglish) C_GOLD else C_BG,
                                contentColor = if (isEnglish) Color(0xFF0c1823) else C_TEXT,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .border(1.dp, if (isEnglish) C_GOLD else C_GREY, RoundedCornerShape(12.dp))
                                .testTag("admin_lang_toggle_en"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.admin_lang_en),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Button(
                            onClick = {
                                Store.saveSettings(currentSettings.copy(appLanguage = "ar"))
                                statusBanner = switchedArMsg
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isArabic) C_GOLD else C_BG,
                                contentColor = if (isArabic) Color(0xFF0c1823) else C_TEXT,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .border(1.dp, if (isArabic) C_GOLD else C_GREY, RoundedCornerShape(12.dp))
                                .testTag("admin_lang_toggle_ar"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.admin_lang_ar),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // 2B. Offline Voice Assistant Controls, KB Metadata, Manual Sync & Test Query Console
                VoiceAssistantSection(
                    s = currentSettings,
                    modifier = Modifier.fillMaxWidth(),
                    onStatusMessage = { statusBanner = it },
                )

                // 3. Kiosk Mode / وضع الكشك (Lock Task)
                val isArabicKiosk = currentSettings.appLanguage == "ar"
                val isDeviceOwnerProvisioned = remember(currentSettings.kioskMode, statusBanner) {
                    KioskPolicy.isOwner(ctx)
                }
                AdminSectionCard(
                    title = if (isArabicKiosk) {
                        "وضع الكشك / Kiosk Mode (Lock Task)"
                    } else {
                        "Kiosk Mode / وضع الكشك (Lock Task)"
                    },
                    subtitle = if (isArabicKiosk) {
                        "تثبيت التطبيق على الشاشة ومنع الخروج للنظام (يتطلب صلاحية Device Owner للقفل الكامل)."
                    } else {
                        "Pin Nour Alwelayah in Android Lock Task mode and enforce Device Owner appliance restrictions."
                    },
                ) {
                    // Device Owner Status Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isDeviceOwnerProvisioned) Color(0xFF102A20) else Color(0xFF2B2011),
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                1.dp,
                                if (isDeviceOwnerProvisioned) C_ACCENT else C_GOLD,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .testTag("admin_kiosk_device_owner_status"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (isDeviceOwnerProvisioned) {
                                    if (isArabicKiosk) {
                                        "حالة الجهاز: مالك الجهاز مفعّل (Device Owner Active)"
                                    } else {
                                        "Provisioning Status: Device Owner Active (isDeviceOwnerApp = true)"
                                    }
                                } else {
                                    if (isArabicKiosk) {
                                        "حالة الجهاز: الوضع القياسي — غير مفعّل كمالك للجهاز (Device Owner Inactive)"
                                    } else {
                                        "Provisioning Status: Standard Test Device (Device Owner NOT Active)"
                                    }
                                },
                                color = if (isDeviceOwnerProvisioned) C_ACCENT else C_GOLD,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (!isDeviceOwnerProvisioned) {
                                Text(
                                    text = if (isArabicKiosk) {
                                        "ملاحظة: الجهاز غير مهيأ كـ Device Owner حالياً؛ سيتم طلب تثبيت الشاشة القياسي فقط."
                                    } else {
                                        "Note: Device Owner is not active on this test tablet. Full unattended Lock Task policy requires dpm set-device-owner."
                                    },
                                    color = C_DIM,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (currentSettings.kioskMode) {
                                    if (isArabicKiosk) {
                                        "وضع الكشك (Lock Task): مفعّل"
                                    } else {
                                        "Kiosk Mode (Lock Task): ACTIVE"
                                    }
                                } else {
                                    if (isArabicKiosk) {
                                        "وضع الكشك (Lock Task): معطّل (وضع الصيانة)"
                                    } else {
                                        "Kiosk Mode (Lock Task): DISABLED (Service / Maintenance Mode)"
                                    }
                                },
                                color = if (currentSettings.kioskMode) C_ACCENT else C_GOLD,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (isDeviceOwnerProvisioned) {
                                    if (isArabicKiosk) {
                                        "يتم تطبيق startLockTask() تلقائياً وتقييد شريط النظام والإعدادات."
                                    } else {
                                        "Immediately applies startLockTask() / stopLockTask() on MainActivity."
                                    }
                                } else {
                                    if (isArabicKiosk) {
                                        "وضع الاختبار: يحفظ إعداد الكشك ولكن الجهاز ليس Device Owner."
                                    } else {
                                        "Test device mode: Device Owner is not active."
                                    }
                                },
                                color = C_DIM,
                                fontSize = 13.sp,
                            )
                        }

                        Switch(
                            checked = currentSettings.kioskMode,
                            onCheckedChange = { enabled ->
                                val hostActivity = Kiosk.findActivity(ctx)
                                if (!enabled) {
                                    Store.saveSettings(currentSettings.copy(kioskMode = false))
                                    Kiosk.clear(ctx)
                                    hostActivity?.let { Kiosk.exit(it) }
                                    statusBanner = if (isArabicKiosk) {
                                        "تم إيقاف وضع الكشك وإلغاء تثبيت الشاشة (stopLockTask)."
                                    } else {
                                        "Kiosk Mode disabled and stopLockTask() executed — tablet unlocked for servicing."
                                    }
                                } else {
                                    Store.saveSettings(currentSettings.copy(kioskMode = true))
                                    if (KioskPolicy.isOwner(ctx)) {
                                        val applied = KioskPolicy.applyFullKioskPolicy(ctx)
                                        hostActivity?.let { Kiosk.enter(it) }
                                        statusBanner = if (applied) {
                                            if (isArabicKiosk) {
                                                "تم تفعيل وضع الكشك وتطبيق سياسة Device Owner و startLockTask()."
                                            } else {
                                                "Kiosk Mode enabled — Device Owner policy and startLockTask() applied."
                                            }
                                        } else {
                                            if (isArabicKiosk) {
                                                "تم تفعيل وضع الكشك وطلب startLockTask()."
                                            } else {
                                                "Kiosk Mode enabled — Lock Task pinning requested."
                                            }
                                        }
                                    } else {
                                        statusBanner = if (isArabicKiosk) {
                                            "تم تفعيل إعداد الكشك (الجهاز الحالي ليس Device Owner)."
                                        } else {
                                            "Kiosk Mode setting enabled (Device Owner is not active on this test device)."
                                        }
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = C_ACCENT,
                            ),
                            modifier = Modifier.testTag("admin_kiosk_master_toggle"),
                        )
                    }
                }

                // 3. Wi-Fi Configurator
                AdminSectionCard(
                    title = "Wi-Fi Network Configurator",
                    subtitle = "Launches Android Wi-Fi Settings (whitelisted in KioskPolicy Lock Task packages).",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "System Wi-Fi Panel",
                                color = C_TEXT,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Package com.android.settings is whitelisted in Lock Task mode",
                                color = C_DIM,
                                fontSize = 13.sp,
                            )
                        }

                        Button(
                            onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                    statusBanner = "Opened Android Wi-Fi Settings."
                                }.onFailure { e ->
                                    statusBanner = "Unable to open Wi-Fi Settings: ${e.message}"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("admin_wifi_configurator_button"),
                        ) {
                            Text("Configure Wi-Fi", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // 4. System Update / تحديث النظام (Dual-Mode OTA Engine + Channel Selector + Server URL)
                val otaState by OtaUpdateManager.state.collectAsState()
                val otaIntegrityStatus by OtaIntegrityHealthCheck.status.collectAsState()
                val otaScope = rememberCoroutineScope()
                val isArabicUi = currentSettings.appLanguage == "ar"
                val currentVersionName = remember { OtaUpdateManager.getCurrentVersionName(ctx) }
                val currentVersionCode = remember { OtaUpdateManager.getCurrentVersionCode(ctx) }
                val isDeviceOwner = remember(currentSettings.kioskMode, statusBanner) {
                    OtaUpdateManager.isDeviceOwner(ctx)
                }
                val selectedChannel = AppSettings.normalizeOtaChannel(currentSettings.otaChannel)

                AdminSectionCard(
                    title = if (isArabicUi) "تحديث النظام (System Update)" else "System Update (تحديث النظام)",
                    subtitle = if (isArabicUi) {
                        "إدارة تحديثات النظام الهوائية (OTA) والتحقق من النسخ الجديدة وقنوات الإصدار."
                    } else {
                        "Self-hosted Over-The-Air (OTA) firmware update manager with SHA-256 verification."
                    },
                ) {
                    // Row 1: Current Version & Provisioning Mode Badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_BG, RoundedCornerShape(12.dp))
                            .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isArabicUi) "الإصدار الحالي" else "Current Version",
                                color = C_DIM,
                                fontSize = 12.sp,
                            )
                            Text(
                                text = "v$currentVersionName (Build $currentVersionCode)",
                                color = C_TEXT,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("admin_ota_current_version"),
                            )
                        }

                        val badgeBg = if (isDeviceOwner) Color(0xFF143828) else Color(0xFF2B2412)
                        val badgeBorder = if (isDeviceOwner) C_ACCENT else C_GOLD
                        val badgeText = when {
                            isDeviceOwner && isArabicUi -> "وضع مالك الجهاز — تحديث صامت (Device Owner — Silent OTA)"
                            isDeviceOwner -> "Device Owner — Silent OTA"
                            isArabicUi -> "الوضع القياسي — تحديث بتأكيد (Standard Mode — Prompted OTA)"
                            else -> "Standard Mode — Prompted OTA"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(badgeBg)
                                .border(1.dp, badgeBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .testTag("admin_ota_provisioning_badge"),
                        ) {
                            Text(
                                text = badgeText,
                                color = if (isDeviceOwner) C_ACCENT else C_GOLD,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    // Row 2: Update Channel Toggle (Stable / Beta) + Check for Updates Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val isStable = selectedChannel == AppSettings.OTA_CHANNEL_STABLE
                        val isBeta = selectedChannel == AppSettings.OTA_CHANNEL_BETA
                        val isBusy = otaState is OtaState.Checking ||
                            otaState is OtaState.Downloading ||
                            otaState is OtaState.Verifying ||
                            otaState is OtaState.Installing

                        Button(
                            onClick = {
                                OtaUpdateManager.setSelectedChannel(ctx, AppSettings.OTA_CHANNEL_STABLE)
                                statusBanner = if (isArabicUi) {
                                    "تم اختيار قناة التحديث: مستقر (Stable)"
                                } else {
                                    "Switched OTA Update Channel to Stable"
                                }
                            },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isStable) C_GOLD else C_BG,
                                contentColor = if (isStable) Color(0xFF0c1823) else C_TEXT,
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .border(1.dp, if (isStable) C_GOLD else C_GREY, RoundedCornerShape(10.dp))
                                .testTag("admin_ota_channel_stable"),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = if (isArabicUi) "مستقر (Stable)" else "Stable",
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Button(
                            onClick = {
                                OtaUpdateManager.setSelectedChannel(ctx, AppSettings.OTA_CHANNEL_BETA)
                                statusBanner = if (isArabicUi) {
                                    "تم اختيار قناة التحديث: تجريبي (Beta)"
                                } else {
                                    "Switched OTA Update Channel to Beta"
                                }
                            },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBeta) C_GOLD else C_BG,
                                contentColor = if (isBeta) Color(0xFF0c1823) else C_TEXT,
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .border(1.dp, if (isBeta) C_GOLD else C_GREY, RoundedCornerShape(10.dp))
                                .testTag("admin_ota_channel_beta"),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = if (isArabicUi) "تجريبي (Beta)" else "Beta",
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick = {
                                otaScope.launch {
                                    OtaUpdateManager.checkForUpdates(ctx, selectedChannel)
                                }
                            },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("admin_ota_check_button"),
                        ) {
                            Text(
                                text = if (isArabicUi) "التحقق من التحديثات" else "Check for Updates",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // Row 3: Live OTA State Feedback (Idle / Checking / UpToDate / UpdateAvailable / Downloading / Verifying / Installing / Error)
                    when (val st = otaState) {
                        is OtaState.Idle -> {
                            Text(
                                text = if (isArabicUi) {
                                    "القناة النشطة: ${selectedChannel.uppercase(Locale.ROOT)} • الرابط: ${OtaUpdateManager.resolveManifestUrl(ctx)}"
                                } else {
                                    "Active Channel: ${selectedChannel.uppercase(Locale.ROOT)} • Endpoint: ${OtaUpdateManager.resolveManifestUrl(ctx)}"
                                },
                                color = C_DIM,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        is OtaState.Checking -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(C_BG, RoundedCornerShape(10.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (isArabicUi) {
                                        "جارٍ التحقق من وجود تحديثات جديدة..."
                                    } else {
                                        "Checking for updates from server..."
                                    },
                                    color = C_GOLD,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = C_ACCENT,
                                    trackColor = C_GREY,
                                )
                            }
                        }

                        is OtaState.UpToDate -> {
                            val manifestCode = st.manifestVersionCode
                            val manifestName = st.manifestVersionName
                            val chan = (st.channel ?: selectedChannel).lowercase(Locale.ROOT)
                            val isAhead = manifestCode != null && st.currentVersionCode > manifestCode
                            val boxBg = if (isAhead) Color(0xFF142433) else Color(0xFF102A20)
                            val boxBorder = if (isAhead) C_GOLD else C_ACCENT

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(boxBg, RoundedCornerShape(10.dp))
                                    .border(1.dp, boxBorder, RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                                    .testTag("admin_ota_up_to_date_box"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (isArabicUi) {
                                        "المثبّت (Installed): v${st.currentVersionName} (Build ${st.currentVersionCode})"
                                    } else {
                                        "Installed: v${st.currentVersionName} (Build ${st.currentVersionCode})"
                                    },
                                    color = C_TEXT,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.testTag("admin_ota_installed_version_row"),
                                )
                                if (manifestName != null && manifestCode != null) {
                                    Text(
                                        text = if (isArabicUi) {
                                            "آخر إصدار في القناة (Channel Latest): v$manifestName (Build $manifestCode)"
                                        } else {
                                            "Channel Latest: v$manifestName (Build $manifestCode)"
                                        },
                                        color = C_DIM,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.testTag("admin_ota_channel_latest_row"),
                                    )
                                }
                                Text(
                                    text = if (isAhead && manifestName != null) {
                                        if (isArabicUi) {
                                            "متقدم على قناة $chan (الأحدث: v$manifestName) — Ahead of $chan channel (Latest: v$manifestName)"
                                        } else {
                                            "Ahead of $chan channel (Latest: v$manifestName)"
                                        }
                                    } else {
                                        if (isArabicUi) {
                                            "النظام محدّث على آخر إصدار: v${st.currentVersionName} (Build ${st.currentVersionCode})"
                                        } else {
                                            "System is up to date: v${st.currentVersionName} (Build ${st.currentVersionCode})"
                                        }
                                    },
                                    color = if (isAhead) C_GOLD else C_ACCENT,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("admin_ota_status_text"),
                                )
                            }
                        }

                        is OtaState.UpdateAvailable -> {
                            val m = st.manifest
                            val notes = if (isArabicUi) {
                                m.releaseNotesAr.ifBlank { m.releaseNotesEn }
                            } else {
                                m.releaseNotesEn.ifBlank { m.releaseNotesAr }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF142433), RoundedCornerShape(12.dp))
                                    .border(1.dp, C_GOLD, RoundedCornerShape(12.dp))
                                    .padding(16.dp)
                                    .testTag("admin_ota_update_available_card"),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            text = if (isArabicUi) {
                                                "يتوفر تحديث جديد: v${m.versionName} (Build ${m.versionCode})"
                                            } else {
                                                "Update Available: v${m.versionName} (Build ${m.versionCode})"
                                            },
                                            color = C_GOLD,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = if (m.mandatory) {
                                                if (isArabicUi) "تحديث إلزامي • القناة: ${m.channel}" else "Mandatory Update • Channel: ${m.channel}"
                                            } else {
                                                if (isArabicUi) "القناة: ${m.channel}" else "Channel: ${m.channel}"
                                            },
                                            color = C_DIM,
                                            fontSize = 12.sp,
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            otaScope.launch {
                                                OtaUpdateManager.downloadAndInstall(ctx, m)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = C_GOLD),
                                        modifier = Modifier
                                            .height(48.dp)
                                            .testTag("admin_ota_install_button"),
                                    ) {
                                        Text(
                                            text = if (isArabicUi) "تنزيل وتثبيت" else "Download & Install",
                                            color = Color(0xFF0c1823),
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }

                                if (notes.isNotBlank()) {
                                    Text(
                                        text = notes,
                                        color = C_TEXT,
                                        fontSize = 14.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(C_BG, RoundedCornerShape(8.dp))
                                            .padding(12.dp),
                                    )
                                }
                            }
                        }

                        is OtaState.Downloading -> {
                            val downloadedMb = st.downloadedBytes.toDouble() / (1024.0 * 1024.0)
                            val totalMb = if (st.totalBytes > 0L) st.totalBytes.toDouble() / (1024.0 * 1024.0) else -1.0
                            val sizeLabel = if (totalMb > 0.0) {
                                String.format(Locale.US, "%.2f MB / %.2f MB", downloadedMb, totalMb)
                            } else {
                                String.format(Locale.US, "%.2f MB", downloadedMb)
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(C_BG, RoundedCornerShape(10.dp))
                                    .border(1.dp, C_ACCENT, RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                                    .testTag("admin_ota_downloading_box"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = if (isArabicUi) {
                                            "جارٍ تنزيل التحديث v${st.manifest.versionName} (${st.progressPercent}%)"
                                        } else {
                                            "Downloading v${st.manifest.versionName} (${st.progressPercent}%)"
                                        },
                                        color = C_TEXT,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = sizeLabel,
                                        color = C_GOLD,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }

                                if (st.totalBytes > 0L) {
                                    LinearProgressIndicator(
                                        progress = { (st.progressPercent / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = C_ACCENT,
                                        trackColor = C_GREY,
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = C_ACCENT,
                                        trackColor = C_GREY,
                                    )
                                }
                            }
                        }

                        is OtaState.Verifying -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(C_BG, RoundedCornerShape(10.dp))
                                    .border(1.dp, C_GOLD, RoundedCornerShape(10.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (isArabicUi) {
                                        "جارٍ التحقق من البصمة الرقمية SHA-256 للحزمة v${st.manifest.versionName}..."
                                    } else {
                                        "Verifying SHA-256 checksum for v${st.manifest.versionName}..."
                                    },
                                    color = C_GOLD,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = C_GOLD,
                                    trackColor = C_GREY,
                                )
                            }
                        }

                        is OtaState.Installing -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(C_BG, RoundedCornerShape(10.dp))
                                    .border(1.dp, C_ACCENT, RoundedCornerShape(10.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = when {
                                        st.silentMode && isArabicUi ->
                                            "جارٍ التثبيت الصامت للإصدار v${st.manifest.versionName} (سيتم إعادة تشغيل الساعة تلقائياً)..."
                                        st.silentMode ->
                                            "Installing v${st.manifest.versionName} silently via Device Owner session (app will auto-relaunch)..."
                                        isArabicUi ->
                                            "تم فتح مثبّت الحزم لتثبيت الإصدار v${st.manifest.versionName}..."
                                        else ->
                                            "Launched Android package installer for v${st.manifest.versionName}..."
                                    },
                                    color = C_ACCENT,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = C_ACCENT,
                                    trackColor = C_GREY,
                                )
                            }
                        }

                        is OtaState.Error -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2D1215), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFEF5350), RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                                    .testTag("admin_ota_error_box"),
                            ) {
                                Text(
                                    text = if (isArabicUi) {
                                        "خطأ في التحديث: ${st.message}"
                                    } else {
                                        "Update Error: ${st.message}"
                                    },
                                    color = Color(0xFFEF5350),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = C_GREY, modifier = Modifier.padding(vertical = 4.dp))

                    // Startup OTA Storage Integrity Health Status row
                    val integrityLabel = when (val st = otaIntegrityStatus) {
                        is OtaIntegrityStatus.Clean -> if (isArabicUi) {
                            "فحص سلامة تخزين التحديثات (Startup Integrity): نظيف" +
                                if (st.orphansRemoved > 0) " (تم تنظيف ${st.orphansRemoved} ملف قديم)" else ""
                        } else {
                            "Startup OTA Integrity Check: Clean" +
                                if (st.orphansRemoved > 0) " (${st.orphansRemoved} obsolete file(s) removed)" else ""
                        }
                        is OtaIntegrityStatus.Verified -> if (isArabicUi) {
                            "فحص سلامة التحديثات: تم التحقق من ${st.verifiedChunks} أجزاء (${st.verifiedBytes / 1024} KB) للإصدار ${st.versionCode}"
                        } else {
                            "Startup OTA Integrity Check: Verified ${st.verifiedChunks} chunk(s) (${st.verifiedBytes / 1024} KB) for build ${st.versionCode}"
                        }
                        is OtaIntegrityStatus.Repaired -> if (isArabicUi) {
                            "فحص سلامة التحديثات: تم إصلاح/إزالة ${st.corruptedChunksRemoved} جزء تالف (Truncated to ${st.truncatedToBytes} bytes)"
                        } else {
                            "Startup OTA Integrity Check: Repaired (${st.corruptedChunksRemoved} corrupted chunk(s) removed, truncated to ${st.truncatedToBytes} bytes)"
                        }
                    }
                    val integrityColor = when (otaIntegrityStatus) {
                        is OtaIntegrityStatus.Repaired -> C_GOLD
                        is OtaIntegrityStatus.Verified -> C_ACCENT
                        is OtaIntegrityStatus.Clean -> C_DIM
                    }
                    Text(
                        text = integrityLabel,
                        color = integrityColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("admin_ota_integrity_status"),
                    )

                    HorizontalDivider(color = C_GREY, modifier = Modifier.padding(vertical = 4.dp))

                    // Row 3B: Compact Knowledge Base OTA Row (kbVersion, last sync time, Check KB Update button & live progress/status)
                    val kbOtaState by KbOtaManager.state.collectAsState()
                    val installedKbVersion by KbOtaManager.installedKbVersionFlow.collectAsState()
                    val lastKbSyncMs by KbOtaManager.lastSyncEpochMsFlow.collectAsState()
                    remember(ctx) {
                        KbOtaManager.getInstalledKbVersion(ctx)
                        KbOtaManager.getLastSyncEpochMs(ctx)
                    }
                    val isKbBusy = kbOtaState is KbOtaState.Checking ||
                        kbOtaState is KbOtaState.Downloading ||
                        kbOtaState is KbOtaState.Verifying

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_BG, RoundedCornerShape(12.dp))
                            .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .testTag("admin_kb_ota_row"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = if (isArabicUi) {
                                        "تحديث قاعدة المعرفة الصوتية (Knowledge Base OTA)"
                                    } else {
                                        "Knowledge Base OTA (تحديث قاعدة المعرفة)"
                                    },
                                    color = C_GOLD,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (isArabicUi) {
                                        "إصدار قاعدة المعرفة: v$installedKbVersion • آخر مزامنة: ${KbOtaManager.formatSyncTime(lastKbSyncMs, true)}"
                                    } else {
                                        "KB Version: v$installedKbVersion • Last Sync: ${KbOtaManager.formatSyncTime(lastKbSyncMs, false)}"
                                    },
                                    color = C_TEXT,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.testTag("admin_kb_ota_version_info"),
                                )
                            }

                            Spacer(Modifier.width(12.dp))

                            Button(
                                onClick = {
                                    KbOtaManager.triggerManualCheck(ctx, selectedChannel)
                                },
                                enabled = !isKbBusy,
                                colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("admin_kb_ota_check_button"),
                            ) {
                                Text(
                                    text = if (isArabicUi) "تحديث قاعدة المعرفة" else "Check KB Update",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        val (kbStatusText, kbStatusColor) = when (val kst = kbOtaState) {
                            is KbOtaState.Idle -> {
                                val url = KbOtaManager.resolveKbManifestUrl(ctx, selectedChannel)
                                (if (isArabicUi) "الرابط: $url" else "Endpoint: $url") to C_DIM
                            }
                            is KbOtaState.Checking -> {
                                (if (isArabicUi) "Checking... جارٍ التحقق من تحديث قاعدة المعرفة..." else "Checking...") to C_GOLD
                            }
                            is KbOtaState.Downloading -> {
                                (if (isArabicUi) {
                                    "Downloading... جارٍ التنزيل (${kst.progressPercent}%)"
                                } else {
                                    "Downloading... (${kst.progressPercent}%)"
                                }) to C_GOLD
                            }
                            is KbOtaState.Verifying -> {
                                (if (isArabicUi) {
                                    "Verifying... جارٍ التحقق من SHA-256 وفحص سلامة SQLite..."
                                } else {
                                    "Verifying..."
                                }) to C_GOLD
                            }
                            is KbOtaState.Updated -> {
                                (if (isArabicUi) {
                                    "Updated to v${kst.kbVersion} — تم التحديث إلى الإصدار v${kst.kbVersion}"
                                } else {
                                    "Updated to v${kst.kbVersion}"
                                }) to C_ACCENT
                            }
                            is KbOtaState.UpToDate -> {
                                val suffix = if (kst.note.isNotBlank()) " (${kst.note})" else ""
                                (if (isArabicUi) {
                                    "Up to date (v${kst.kbVersion})$suffix — قاعدة المعرفة محدّثة"
                                } else {
                                    "Up to date (v${kst.kbVersion})$suffix"
                                }) to C_ACCENT
                            }
                            is KbOtaState.Error -> {
                                (if (isArabicUi) "خطأ: ${kst.message}" else "Error: ${kst.message}") to Color(0xFFEF5350)
                            }
                        }

                        Text(
                            text = kbStatusText,
                            color = kbStatusColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("admin_kb_ota_status_text"),
                        )

                        if (isKbBusy) {
                            val dl = kbOtaState as? KbOtaState.Downloading
                            if (dl != null && dl.totalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = { (dl.progressPercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = C_GOLD,
                                    trackColor = C_GREY,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = C_GOLD,
                                    trackColor = C_GREY,
                                )
                            }
                        }
                    }

                    // Row 4: Base OTA Server URL Override Input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = otaUrlInput,
                            onValueChange = { otaUrlInput = it },
                            label = {
                                Text(if (isArabicUi) "رابط خادم التحديثات (OTA Base URL)" else "OTA Base URL")
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_ota_server_input"),
                        )
                        Button(
                            onClick = {
                                val cleaned = otaUrlInput.trim().ifBlank { BuildConfig.OTA_BASE_URL }
                                otaUrlInput = cleaned
                                Store.saveSettings(currentSettings.copy(otaServerUrl = cleaned))
                                statusBanner = if (isArabicUi) {
                                    "تم حفظ رابط خادم التحديثات: $cleaned"
                                } else {
                                    "Saved OTA Base URL: $cleaned"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("admin_ota_save_button"),
                        ) {
                            Text(
                                text = if (isArabicUi) "حفظ الرابط" else "Save URL",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // 5. Volume Limiter
                AdminSectionCard(
                    title = "Hardware Volume Limiter",
                    subtitle = "Restrict the maximum output volume percentage (0% – 100%) on this appliance.",
                ) {
                    val limitInt = volumeLimitFloat.roundToInt().coerceIn(0, 100)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Slider(
                            value = volumeLimitFloat,
                            onValueChange = {
                                volumeLimitFloat = it
                                volumeLimitText = it.roundToInt().coerceIn(0, 100).toString()
                            },
                            onValueChangeFinished = {
                                val capped = volumeLimitFloat.roundToInt().coerceIn(0, 100)
                                applyVolumeCeiling(ctx, currentSettings, capped)
                                statusBanner = "Applied Maximum Volume Limit: $capped%"
                            },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = C_GOLD,
                                activeTrackColor = C_ACCENT,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_volume_limiter_slider"),
                        )

                        OutlinedTextField(
                            value = volumeLimitText,
                            onValueChange = { txt ->
                                val filtered = txt.filter { it.isDigit() }.take(3)
                                volumeLimitText = filtered
                                filtered.toIntOrNull()?.let { v ->
                                    volumeLimitFloat = v.coerceIn(0, 100).toFloat()
                                }
                            },
                            label = { Text("Max %") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .width(100.dp)
                                .testTag("admin_volume_limiter_input"),
                        )

                        Button(
                            onClick = {
                                val capped = (volumeLimitText.toIntOrNull() ?: limitInt).coerceIn(0, 100)
                                volumeLimitFloat = capped.toFloat()
                                volumeLimitText = capped.toString()
                                applyVolumeCeiling(ctx, currentSettings, capped)
                                statusBanner = "Saved Maximum Volume Limit: $capped%"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("admin_volume_limiter_save_button"),
                        ) {
                            Text("Apply ($limitInt%)", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // 6. Technician PIN & Live Kiosk Diagnostics
                AdminSectionCard(
                    title = "Technician Access PIN & Appliance Diagnostics",
                    subtitle = "Update the 5-tap technician PIN (default: 9200) and inspect DevicePolicyManager state.",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = adminPinInput,
                            onValueChange = { adminPinInput = it.filter { ch -> ch.isDigit() }.take(8) },
                            label = { Text("Technician PIN") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_pin_change_input"),
                        )
                        OutlinedButton(
                            onClick = {
                                val pin = adminPinInput.trim().ifBlank { "9200" }
                                adminPinInput = pin
                                Store.saveSettings(currentSettings.copy(adminPin = pin))
                                statusBanner = "Updated Technician PIN."
                            },
                            modifier = Modifier.height(54.dp),
                        ) {
                            Text("Update PIN", color = C_GOLD)
                        }
                    }

                    HorizontalDivider(color = C_GREY, modifier = Modifier.padding(vertical = 8.dp))

                    val report = remember(currentSettings.kioskMode, statusBanner) {
                        KioskPolicy.describeState(ctx)
                    }
                    Text(
                        text = report,
                        color = C_DIM,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_BG, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun AdminSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(C_CARD, RoundedCornerShape(14.dp))
            .border(1.dp, C_GREY, RoundedCornerShape(14.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = C_GOLD,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = C_DIM,
                fontSize = 13.sp,
            )
        }
        content()
    }
}

private fun applyVolumeCeiling(context: Context, settings: AppSettings, maxPercent: Int) {
    val cappedAzanVol = ((21 * maxPercent) / 100.0).roundToInt().coerceIn(0, 21)
    Store.saveSettings(
        settings.copy(
            maxVolumeLimit = maxPercent,
            azanVolume = settings.azanVolume.coerceAtMost(cappedAzanVol),
        )
    )
    runCatching {
        val am = Sys.svc(context, AudioManager::class.java)
        for (stream in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM)) {
            val streamMax = am.getStreamMaxVolume(stream)
            val allowedMax = ((streamMax * maxPercent) / 100.0).roundToInt().coerceIn(0, streamMax)
            val current = am.getStreamVolume(stream)
            if (current > allowedMax) {
                am.setStreamVolume(stream, allowedMax, 0)
            }
        }
    }
}
