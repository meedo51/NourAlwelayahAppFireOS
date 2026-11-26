package com.nouralwelayah.clock.ui.p4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.alert.AlertScheduler
import com.nouralwelayah.clock.alert.AlertService
import com.nouralwelayah.clock.core.AzanPrayer
import com.nouralwelayah.clock.core.CALC_METHODS
import com.nouralwelayah.clock.core.FAJR_ANGLE_MAX
import com.nouralwelayah.clock.core.FAJR_ANGLE_MIN
import com.nouralwelayah.clock.core.MAGHRIB_ANGLE_MAX
import com.nouralwelayah.clock.core.MAGHRIB_ANGLE_MIN
import com.nouralwelayah.clock.core.Planner
import com.nouralwelayah.clock.core.Prayer
import com.nouralwelayah.clock.core.fmtAngle
import com.nouralwelayah.clock.core.fmtMinutes
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.AzanLibrary
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.ui.C_CARD
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.Dropdown
import com.nouralwelayah.clock.ui.NumberField
import com.nouralwelayah.clock.ui.isArabic
import com.nouralwelayah.clock.ui.localizedLabel
import com.nouralwelayah.clock.ui.tr
import java.time.ZonedDateTime
import java.util.Locale

/** buildPrayerTab(): today's times + azan switches + tests (left), azan sounds and calculation (right). */
@Composable
fun PrayerTab(s: AppSettings, now: ZonedDateTime) {
    val ctx = LocalContext.current
    val ar = isArabic()
    val today = Planner.day(s, now.toLocalDate(), now.zone)
    val m = CALC_METHODS[s.methodSafe]
    val tick by AppFolders.refreshed.collectAsState()
    val files = remember(tick) { AzanLibrary.list(ctx) }
    // right card: a draft, saved with the button (like the firmware)
    var fajrSnd by remember(s) { mutableStateOf(s.azanSound.getOrElse(0) { "" }) }
    var dhuhrSnd by remember(s) { mutableStateOf(s.azanDefault.ifBlank { s.azanSound.getOrElse(1) { "" } }) }
    var maghSnd by remember(s) { mutableStateOf(s.azanSound.getOrElse(2) { "" }) }
    var vol by remember(s) { mutableStateOf(s.azanVolume.toString()) }
    var lead by remember(s) { mutableStateOf(s.azanLeadSec.toString()) }
    var useMethod by remember(s) { mutableStateOf(!s.customAngles) }
    var fajr by remember(s) { mutableStateOf(String.format(Locale.US, "%.1f", s.effFajrAngle)) }
    var magh by remember(s) { mutableStateOf(fmtAngle(s.effMaghribAngle)) }
    var msg by remember { mutableStateOf("") }

    fun indexInFiles(selectedRef: String): Int {
        if (selectedRef.isBlank()) return 0
        val exact = files.indexOf(selectedRef)
        if (exact >= 0) return exact + 1
        val cleanName = selectedRef.trim().substringAfterLast('/')
        val byName = files.indexOfFirst { it.equals(cleanName, ignoreCase = true) }
        return if (byName >= 0) byName + 1 else 0
    }

    fun persistAzanSelection(fSnd: String = fajrSnd, dSnd: String = dhuhrSnd, mSnd: String = maghSnd) {
        val cur = Store.settings.value
        val parsedVol = vol.toIntOrNull()?.takeIf { it in 0..21 } ?: cur.azanVolume
        Store.saveSettings(
            cur.copy(
                azanSound = listOf(fSnd, dSnd, mSnd),
                azanDefault = dSnd,
                azanVolume = parsedVol,
            )
        )
    }

    fun save() {
        val v = vol.toIntOrNull(); val ld = lead.toIntOrNull()
        if (v == null || v !in 0..21) { msg = if (ar) "مستوى صوت الأذان يجب أن يكون بين 0 و 21" else "Azan volume must be 0 - 21"; return }
        if (ld == null || ld !in 0..500) { msg = if (ar) "بدء الأذان مبكراً يجب أن يكون بين 0 و 500 ثانية" else "Start azan early must be 0 - 500 s"; return }
        var fa = 0.0; var ma = 0.0
        if (!useMethod) {
            val f = fajr.toDoubleOrNull()
            if (f == null || f < FAJR_ANGLE_MIN || f > FAJR_ANGLE_MAX) { msg = if (ar) "زاوية الفجر يجب أن تكون 14.0 - 19.9" else "Fajr angle must be 14.0 - 19.9"; return }
            val g = magh.toDoubleOrNull()
            if (g == null || g < 0.8 || g > MAGHRIB_ANGLE_MAX) { msg = if (ar) "زاوية المغرب يجب أن تكون 0.833 - 9.9" else "Maghrib angle must be 0.833 - 9.9"; return }
            fa = f; ma = maxOf(g, MAGHRIB_ANGLE_MIN)
        }
        // Dhuhr = the general azan (azanDefault & azanSound[1]), Fajr / Maghrib their own or "same as Dhuhr"
        Store.saveSettings(
            Store.settings.value.copy(
                azanSound = listOf(fajrSnd, dhuhrSnd, maghSnd),
                azanDefault = dhuhrSnd,
                azanVolume = v,
                azanLeadSec = ld,
                fajrAngle = fa,
                maghribAngle = ma,
            )
        )
        AlertScheduler.reschedule(ctx)
        msg = if (ar) "تم الحفظ وتحديث أوقات الصلاة." else "Saved. Prayer times are updated."
    }

    val s4 = LocalP4.current
    with(s4) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.p)) {
            // ---- left 600 px
            Column(
                Modifier.width(600.p).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 6.p),
                    verticalArrangement = Arrangement.spacedBy(8.p),
                ) {
                    Prayer.entries.forEach { p ->
                        val k = AzanPrayer.entries.indexOfFirst { it.prayer == p }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(C_CARD, RoundedCornerShape(12.p))
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = p.localizedLabel(),
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = C_TEXT,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(175.p),
                            )
                            Text(
                                text = fmtMinutes(today[p], s.use24h),
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = C_GOLD,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Row(
                                modifier = Modifier.width(150.p),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (k < 0) {
                                    P4Text(tr("no azan", "بدون أذان"), 18, C_DIM, maxLines = 1)
                                } else {
                                    P4Text(tr("Azan", "الأذان"), 18, C_DIM, maxLines = 1)
                                    Spacer(Modifier.width(12.p))
                                    Switch(
                                        checked = s.azanOn[k],
                                        onCheckedChange = { on -> // works at once, like prAzanSwCb()
                                            Store.saveSettings(
                                                Store.settings.value.copy(
                                                    azanOn = s.azanOn.toMutableList().also { it[k] = on },
                                                ),
                                            )
                                            AlertScheduler.reschedule(ctx)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                P4Text(
                    "${s.city}  |  ${m.name}\n" +
                        tr("Fajr", "الفجر") + " ${String.format(Locale.US, "%.1f", s.effFajrAngle)}  /  " +
                        tr("Maghrib", "المغرب") + " " +
                        (if (s.effMaghribAngle <= 0.834) tr("sunset", "الغروب") else fmtAngle(s.effMaghribAngle)) +
                        if (s.customAngles) tr("  (custom)", "  (مخصص)") else "",
                    20,
                    C_DIM,
                    Modifier.padding(top = 4.p, bottom = 8.p),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.p)) {
                    AzanPrayer.entries.forEach { az ->
                        P4Button("▶ ${az.prayer.localizedLabel()}", Modifier.size(142.p, 64.p)) {
                            persistAzanSelection()
                            ContextCompat.startForegroundService(ctx, AlertService.azanIntent(ctx, az.ordinal, -1, test = true))
                        }
                    }
                    P4Button(tr("■ Stop", "■ إيقاف"), Modifier.size(144.p, 64.p), color = GREY) { ctx.startService(AlertService.stopIntent(ctx)) }
                }
            }
            // ---- right 632 px
            P4Card(Modifier.weight(1f).fillMaxHeight()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.p)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        P4Text(tr("Azan sounds - files in /azan", "أصوات الأذان - الملفات في /azan"), 20, C_DIM, Modifier.weight(1f))
                    }
                    val same = listOf(tr("Same as Dhuhr", "نفس أذان الظهر")) + files
                    SoundRow(tr("Fajr", "الفجر"), same, indexInFiles(fajrSnd)) {
                        fajrSnd = if (it == 0) "" else files[it - 1]
                        persistAzanSelection(fSnd = fajrSnd)
                    }
                    SoundRow(tr("Dhuhr", "الظهر"), listOf(tr("Default alarm sound", "الصوت الافتراضي")) + files, indexInFiles(dhuhrSnd)) {
                        dhuhrSnd = if (it == 0) "" else files[it - 1]
                        persistAzanSelection(dSnd = dhuhrSnd)
                    }
                    SoundRow(tr("Maghrib", "المغرب"), same, indexInFiles(maghSnd)) {
                        maghSnd = if (it == 0) "" else files[it - 1]
                        persistAzanSelection(mSnd = maghSnd)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.p)) {
                        NumberField(tr("Azan volume (0-21)", "صوت الأذان (0-21)"), vol, { vol = it }, Modifier.width(260.p))
                        NumberField(tr("Start azan early (0-500 s)", "تقديم الأذان (0-500 ث)"), lead, { lead = it }, Modifier.width(280.p))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.p)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(260.p)) {
                            Checkbox(useMethod, { useMethod = it })
                            P4Text(tr("Method angles", "زوايا الطريقة"), 20)
                        }
                        NumberField(tr("Fajr angle (14.0 - 19.9)", "زاوية الفجر (14.0 - 19.9)"), fajr, { fajr = it }, Modifier.width(280.p), enabled = !useMethod, decimal = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.p)) {
                        NumberField(tr("Maghrib angle (0.833 = sunset)", "زاوية المغرب (0.833 = الغروب)"), magh, { magh = it }, Modifier.width(260.p), enabled = !useMethod, decimal = true)
                        Switch(s.azanPicture, { Store.saveSettings(Store.settings.value.copy(azanPicture = it)) })
                        P4Text(tr("Azan picture", "صورة الأذان"), 20)
                    }
                    if (msg.isNotEmpty()) P4Text(msg, 20, C_GOLD)
                    Box(Modifier.weight(1f, fill = false))
                    P4Button(tr("💾  Save prayer settings", "💾  حفظ إعدادات الصلاة"), Modifier.size(340.p, 64.p)) { save() }
                }
            }
        }
    }
}

@Composable
private fun SoundRow(label: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val s4 = LocalP4.current
    with(s4) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            P4Text(label, 20, C_TEXT, Modifier.width(100.p))
            Dropdown(items, selected.coerceAtLeast(0), onSelect, Modifier.weight(1f))
        }
    }
}
