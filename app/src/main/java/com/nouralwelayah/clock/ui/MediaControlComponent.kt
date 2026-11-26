package com.nouralwelayah.clock.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Brand-aligned Material 3 Media Control Component with Play, Pause, and Stop [IconButton]s
 * styled in Nour Alwelayah Navy (#0c1823 / #142433) and Gold (#cc9c56).
 *
 * Transmits JSON commands (`{"cmd":"play"}`, `{"cmd":"pause"}`, `{"cmd":"stop"}`) over the
 * Bluetooth service via [onSendBleCommand].
 */
@Composable
fun MediaControlComponent(
    onSendBleCommand: suspend (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()

    val pauseVector = remember {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                // Left bar
                moveTo(6f, 19f)
                horizontalLineTo(10f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                close()
                // Right bar
                moveTo(14f, 5f)
                verticalLineTo(19f)
                horizontalLineTo(18f)
                verticalLineTo(5f)
                close()
            }
        }.build()
    }

    val stopVector = remember {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f)
                horizontalLineTo(18f)
                verticalLineTo(18f)
                horizontalLineTo(6f)
                close()
            }
        }.build()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = C_CARD),
        border = BorderStroke(1.dp, C_GOLD.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = tr("Appliance Media Controls", "التحكم بوسائط الساعة"),
                color = C_GOLD,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Play IconButton
                MediaActionIconButton(
                    icon = Icons.Filled.PlayArrow,
                    label = tr("Play", "تشغيل"),
                    contentDescription = tr("Play media on appliance", "تشغيل الوسائط على الساعة"),
                    testTag = "media_control_play_button",
                    highlighted = !isPlaying,
                    enabled = enabled,
                    onClick = {
                        scope.launch {
                            onSendBleCommand(JSONObject().put("cmd", "play"))
                        }
                    },
                )

                // Pause IconButton
                MediaActionIconButton(
                    icon = pauseVector,
                    label = tr("Pause", "إيقاف مؤقت"),
                    contentDescription = tr("Pause media on appliance", "إيقاف الوسائط مؤقتاً"),
                    testTag = "media_control_pause_button",
                    highlighted = isPlaying,
                    enabled = enabled,
                    onClick = {
                        scope.launch {
                            onSendBleCommand(JSONObject().put("cmd", "pause"))
                        }
                    },
                )

                // Stop IconButton
                MediaActionIconButton(
                    icon = stopVector,
                    label = tr("Stop", "إيقاف"),
                    contentDescription = tr("Stop media on appliance", "إيقاف التشغيل"),
                    testTag = "media_control_stop_button",
                    highlighted = false,
                    enabled = enabled,
                    onClick = {
                        scope.launch {
                            onSendBleCommand(JSONObject().put("cmd", "stop"))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MediaActionIconButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    testTag: String,
    highlighted: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (highlighted) C_GOLD else C_BG
    val contentColor = if (highlighted) C_BG else C_GOLD

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = C_BG.copy(alpha = 0.5f),
                disabledContentColor = C_DIM.copy(alpha = 0.4f),
            ),
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(containerColor, CircleShape)
                .border(1.5.dp, C_GOLD, CircleShape)
                .testTag(testTag),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) contentColor else C_DIM.copy(alpha = 0.4f),
                modifier = Modifier.size(30.dp),
            )
        }

        Text(
            text = label,
            color = if (highlighted) C_GOLD else C_TEXT,
            fontSize = 13.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
