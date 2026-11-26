package com.nouralwelayah.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.media.MediaRemoteServer
import com.nouralwelayah.clock.system.WifiController
import com.nouralwelayah.clock.ui.p4.LocalP4
import com.nouralwelayah.clock.ui.p4.P4Card
import com.nouralwelayah.clock.ui.p4.QrCode
import com.nouralwelayah.clock.web.WebRemote

private val EmeraldConnected = Color(0xFF22C55E)

/**
 * Redesigned Wi-Fi Web Remote Card with Dynamic QR Code (Phase 16).
 *
 * Two-column horizontal layout:
 * - Start column: Title ("Wi-Fi Web Remote" / "التحكم عبر المتصفح"), enable switch,
 *   concise instruction ("Scan with any phone or open on PC"), formatted local URL (`http://<ip>:8080/`),
 *   PIN protection status badge, and compact PIN editor.
 * - End column: Crisp, high-contrast QR Code rendered on a rounded white/cream container with 8.dp
 *   padding (generated asynchronously on Dispatchers.Default), or a clean offline placeholder when disconnected.
 */
@Composable
fun WebRemoteSection(s: AppSettings, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ar = isArabic()
    val wifiSnap by WifiController.status.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        WifiController.refreshStatus(ctx)
        onPauseOrDispose { }
    }

    val liveIp = wifiSnap.ipAddress
    val address = remember(refresh, s.webRemote, liveIp) {
        if (liveIp.isNotEmpty()) "http://$liveIp:${WebRemote.PORT}/" else WebRemote.address(ctx)
    }
    var pin by remember(s.webPin) { mutableStateOf(s.webPin) }
    var msg by remember { mutableStateOf("") }
    val hasPin = s.webPin.isNotEmpty()
    val s4 = LocalP4.current

    with(s4) {
        P4Card(
            modifier = modifier.testTag("wifi_web_remote_card"),
            border = C_GOLD.copy(alpha = 0.28f),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.p)) {
                // Top Row: Two-column layout (Start: info + URL + PIN badge, End: Dynamic QR Code / Offline Placeholder)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.p),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left / Start Column
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.p),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = tr("Wi-Fi Web Remote", "التحكم عبر المتصفح"),
                                color = C_TEXT,
                                fontSize = 24.f,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Switch(
                                checked = s.webRemote,
                                onCheckedChange = { enabled ->
                                    Store.saveSettings(Store.settings.value.copy(webRemote = enabled))
                                    if (enabled) MediaRemoteServer.start(ctx)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = C_BG,
                                    checkedTrackColor = C_GOLD,
                                    uncheckedThumbColor = C_DIM,
                                    uncheckedTrackColor = C_BG,
                                ),
                                modifier = Modifier.testTag("web_remote_toggle"),
                            )
                        }

                        Text(
                            text = tr(
                                "Scan with any phone or open on PC",
                                "امسح الرمز بأي هاتف أو افتح الرابط على الكمبيوتر",
                            ),
                            color = C_DIM,
                            fontSize = 17.f,
                        )

                        // Formatted local URL Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(C_BG, RoundedCornerShape(10.p))
                                .border(
                                    width = 1.p,
                                    color = if (s.webRemote && address.isNotEmpty()) C_GOLD.copy(alpha = 0.55f) else C_GREY,
                                    shape = RoundedCornerShape(10.p),
                                )
                                .padding(horizontal = 12.p, vertical = 10.p),
                        ) {
                            Text(
                                text = when {
                                    !s.webRemote -> tr("Web Remote Disabled", "التحكم عبر المتصفح معطّل")
                                    address.isEmpty() -> tr("Offline — Connect to Wi-Fi first", "غير متصل — اتصل بشبكة الواي فاي أولاً")
                                    else -> address.removeSuffix("/")
                                },
                                color = if (s.webRemote && address.isNotEmpty()) C_GOLD else C_DIM,
                                fontSize = 19.f,
                                fontWeight = FontWeight.Bold,
                                fontFamily = if (s.webRemote && address.isNotEmpty()) FontFamily.Monospace else FontFamily.Default,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("web_remote_url_text"),
                            )
                        }

                        // Subtle PIN Protection Status Badge
                        Row(
                            modifier = Modifier
                                .background(
                                    color = if (hasPin) C_GOLD.copy(alpha = 0.14f) else C_BG.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(50),
                                )
                                .border(
                                    width = 1.p,
                                    color = if (hasPin) C_GOLD.copy(alpha = 0.5f) else C_GREY,
                                    shape = RoundedCornerShape(50),
                                )
                                .padding(horizontal = 12.p, vertical = 5.p),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.p),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.p)
                                    .background(
                                        color = if (hasPin) EmeraldConnected else C_GOLD,
                                        shape = CircleShape,
                                    ),
                            )
                            Text(
                                text = if (hasPin) {
                                    tr("🔒 PIN Protection Active", "🔒 الحماية بالرمز السري مفعّلة")
                                } else {
                                    tr("🔓 No PIN (Open on Local Wi-Fi)", "🔓 بدون رمز سري (مفتوح على الشبكة)")
                                },
                                color = if (hasPin) C_GOLD else C_DIM,
                                fontSize = 15.f,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    // Right / End Column: High-Contrast QR Code or Clean Offline Placeholder
                    if (s.webRemote && address.isNotEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.p),
                        ) {
                            QrCode(
                                text = address,
                                modifier = Modifier
                                    .size(164.p)
                                    .border(2.p, C_GOLD, RoundedCornerShape(12.dp))
                                    .testTag("web_remote_qr_code"),
                                containerColor = Color(0xFFFAF8F5),
                                containerPadding = 8.dp,
                                cornerRadius = 12.dp,
                            )
                            Text(
                                text = tr("SCAN ME", "امسح الرمز"),
                                color = C_GOLD,
                                fontSize = 13.f,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(164.p)
                                .background(C_BG, RoundedCornerShape(12.p))
                                .border(1.p, C_GREY, RoundedCornerShape(12.p))
                                .padding(12.p)
                                .testTag("web_remote_qr_offline_placeholder"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.p),
                            ) {
                                Text(
                                    text = if (!s.webRemote) "⏸" else "📵",
                                    fontSize = 30.f,
                                    color = C_DIM,
                                )
                                Text(
                                    text = if (!s.webRemote) {
                                        tr("Turn on Web Remote to show QR", "فعّل التحكم لإظهار رمز QR")
                                    } else {
                                        tr("Wi-Fi Offline\nQR Unavailable", "لا يوجد اتصال\nرمز QR غير متاح")
                                    },
                                    color = C_DIM,
                                    fontSize = 15.f,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 19.f,
                                )
                            }
                        }
                    }
                }

                // Bottom PIN Configuration Row (when Web Remote is enabled)
                if (s.webRemote) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.p),
                    ) {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { v -> pin = v.filter(Char::isDigit).take(8) },
                            singleLine = true,
                            label = {
                                Text(
                                    text = tr(
                                        "PIN (4 - 8 digits, empty = none)",
                                        "الرمز السري PIN (4 - 8 أرقام، فارغ = بدون)",
                                    ),
                                    fontSize = 15.f,
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
                                .testTag("web_remote_pin_input"),
                        )
                        Spacer(Modifier.width(2.p))
                        OutlinedButton(
                            onClick = {
                                msg = if (pin.isNotEmpty() && pin.length < 4) {
                                    if (ar) "الرمز السري يجب أن يتكون من 4 إلى 8 أرقام" else "The PIN needs 4 - 8 digits"
                                } else {
                                    Store.saveSettings(Store.settings.value.copy(webPin = pin))
                                    if (pin.isEmpty()) {
                                        if (ar) "تمت إزالة الرمز السري" else "PIN removed"
                                    } else {
                                        if (ar) "تم حفظ الرمز السري" else "PIN saved"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.p),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = C_BG,
                                contentColor = C_GOLD,
                            ),
                            contentPadding = PaddingValues(horizontal = 16.p, vertical = 12.p),
                            modifier = Modifier
                                .height(54.p)
                                .testTag("web_remote_save_pin_button"),
                        ) {
                            Text(
                                text = tr("Save PIN", "حفظ الرمز"),
                                fontSize = 17.f,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (msg.isNotEmpty()) {
                        Text(
                            text = msg,
                            color = C_GOLD,
                            fontSize = 16.f,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
