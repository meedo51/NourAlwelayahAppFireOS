package com.nouralwelayah.clock.ui

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nouralwelayah.clock.BuildConfig
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.ota.KbOtaManager
import java.util.Locale

private val BrandNavyDeep = Color(0xFF041424)

/**
 * Formats the appliance's stable hardware identifier (`Settings.Secure.ANDROID_ID`) as `NW-XXXXXXXX`
 * via [com.nouralwelayah.clock.ota.DeviceIdProvider] (backed by Device-Protected Storage).
 */
@SuppressLint("HardwareIds")
fun resolveApplianceDeviceNumber(context: Context): String {
    return com.nouralwelayah.clock.ota.DeviceIdProvider.getDeviceId(context)
}

/**
 * Pure helper formatting a raw Android ID string into `NW-XXXXXXXX`.
 */
internal fun formatApplianceDeviceNumber(rawAndroidId: String): String {
    return com.nouralwelayah.clock.ota.DeviceIdProvider.formatDeviceNumber(rawAndroidId)
}

/**
 * Bilingual (Arabic / English) "About Nour Alwelayah" / "حول نور الولاية" modal dialog
 * styled with the appliance's navy-and-gold branding.
 *
 * Displays:
 * 1. Device Number / رقم الجهاز (`NW-XXXXXXXX`)
 * 2. Current Version / الإصدار الحالي (`v<BuildConfig.VERSION_NAME>`)
 * 3. Build Number / رقم البناء (`<BuildConfig.VERSION_CODE>`)
 * 4. Release Date / تاريخ الإصدار (`BuildConfig.RELEASE_DATE`)
 * 5. Knowledge Base Version / إصدار قاعدة المعرفة (`v<kbVersion>`)
 * 6. Release Changelogs / سجل التغييرات (`BuildConfig.RELEASE_CHANGELOG`, scrollable)
 * 7. Developer Information / المطورون (`Yaser Alsaleh & Ahmed Alsaleh`)
 * 8. Website / الموقع الإلكتروني (`https://alwelayah.xus.me` — plain selectable text, never launches a browser)
 * 9. Contact Information / معلومات التواصل (`Alsaleha528@gmail.com` and `+966564607514`)
 */
@Composable
fun AboutNourAlwelayahDialog(
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val ar = isArabic()
    val deviceNumber = remember(ctx) { resolveApplianceDeviceNumber(ctx) }
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE
    val releaseDate = BuildConfig.RELEASE_DATE
    val releaseChangelog = BuildConfig.RELEASE_CHANGELOG
    remember(ctx) { KbOtaManager.getInstalledKbVersion(ctx) }
    val kbVersion by KbOtaManager.installedKbVersionFlow.collectAsState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 760.dp)
                .fillMaxWidth(0.88f)
                .border(1.5.dp, C_GOLD, RoundedCornerShape(20.dp))
                .testTag("about_nour_alwelayah_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = C_BG,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header: Nour Alwelayah Logo + Title + Subtitle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(BrandNavyDeep)
                            .border(1.dp, C_GOLD.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.splash_logo),
                            contentDescription = tr("Nour Alwelayah Logo", "شعار نور الولاية"),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(60.dp)
                                .testTag("about_dialog_logo"),
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = tr("About Nour Alwelayah", "حول نور الولاية"),
                            color = C_GOLD,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("about_dialog_title"),
                        )
                        Text(
                            text = tr(
                                "Nour Alwelayah Smart Islamic Prayer Clock Appliance",
                                "ساعة نور الولاية الذكية لمواقيت الصلاة والأذان"
                            ),
                            color = C_DIM,
                            fontSize = 14.sp,
                        )
                    }
                }

                HorizontalDivider(color = C_GREY)

                // Key-Value Metadata Card
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C_CARD, RoundedCornerShape(14.dp))
                            .border(1.dp, C_GREY, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AboutKeyValueRow(
                            label = tr("Device Number / رقم الجهاز", "رقم الجهاز / Device Number"),
                            value = deviceNumber,
                            monospace = true,
                            valueColor = C_GOLD,
                            testTag = "about_device_number_value",
                        )
                        HorizontalDivider(color = C_GREY.copy(alpha = 0.45f))

                        AboutKeyValueRow(
                            label = tr("Current Version / الإصدار الحالي", "الإصدار الحالي / Current Version"),
                            value = "v$versionName",
                            monospace = true,
                            testTag = "about_current_version_value",
                        )
                        HorizontalDivider(color = C_GREY.copy(alpha = 0.45f))

                        AboutKeyValueRow(
                            label = tr("Build Number / رقم البناء", "رقم البناء / Build Number"),
                            value = versionCode.toString(),
                            monospace = true,
                            testTag = "about_build_number_value",
                        )
                        HorizontalDivider(color = C_GREY.copy(alpha = 0.45f))

                        AboutKeyValueRow(
                            label = tr("Release Date / تاريخ الإصدار", "تاريخ الإصدار / Release Date"),
                            value = releaseDate,
                            monospace = true,
                            testTag = "about_release_date_value",
                        )
                        HorizontalDivider(color = C_GREY.copy(alpha = 0.45f))

                        AboutKeyValueRow(
                            label = tr(
                                "Knowledge Base Version / إصدار قاعدة المعرفة",
                                "إصدار قاعدة المعرفة / Knowledge Base Version"
                            ),
                            value = "v$kbVersion",
                            monospace = true,
                            valueColor = C_GOLD,
                            testTag = "about_kb_version_value",
                        )
                        HorizontalDivider(color = C_GREY.copy(alpha = 0.45f))

                        AboutKeyValueRow(
                            label = tr("Developers / المطورون", "المطورون / Developers"),
                            value = "Yaser Alsaleh & Ahmed Alsaleh",
                            valueColor = C_GOLD,
                            testTag = "about_developers_value",
                        )
                        HorizontalDivider(color = C_GREY.copy(alpha = 0.45f))

                        AboutKeyValueRow(
                            label = tr("Website / الموقع الإلكتروني", "الموقع الإلكتروني / Website"),
                            value = "https://alwelayah.xus.me",
                            monospace = true,
                            testTag = "about_website_value",
                        )
                        HorizontalDivider(color = C_GREY.copy(alpha = 0.45f))

                        AboutKeyValueRow(
                            label = tr("Contact / معلومات التواصل", "معلومات التواصل / Contact"),
                            value = "Alsaleha528@gmail.com  •  +966564607514",
                            monospace = true,
                            testTag = "about_contact_value",
                        )
                    }
                }

                // Scrollable Release Changelogs Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = tr("Release Changelogs / سجل التغييرات", "سجل التغييرات / Release Changelogs"),
                        color = C_GOLD,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val changelogScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp, max = 140.dp)
                            .background(C_CARD, RoundedCornerShape(12.dp))
                            .border(1.dp, C_GREY, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                            .verticalScroll(changelogScroll)
                            .testTag("about_release_changelog_box"),
                    ) {
                        SelectionContainer {
                            Text(
                                text = releaseChangelog,
                                color = C_TEXT,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            )
                        }
                    }
                }

                // Footer Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ar) {
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = C_GOLD,
                            contentColor = BrandNavyDeep,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .widthIn(min = 140.dp)
                            .testTag("about_dialog_close_button"),
                    ) {
                        Text(
                            text = tr("Close / إغلاق", "إغلاق / Close"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutKeyValueRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: Color = C_TEXT,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = C_DIM,
            fontSize = 14.sp,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(0.55f)
                .testTag(testTag),
        )
    }
}
