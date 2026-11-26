package com.nouralwelayah.clock.ui.p4

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.alert.AlertScheduler
import com.nouralwelayah.clock.alert.AlertService
import com.nouralwelayah.clock.core.Planner
import com.nouralwelayah.clock.data.Alarm
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Folder
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.system.FileSystemManager
import com.nouralwelayah.clock.ui.C_ACCENT
import com.nouralwelayah.clock.ui.C_BG
import com.nouralwelayah.clock.ui.C_CARD
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.Dropdown
import com.nouralwelayah.clock.ui.RoundSlider
import com.nouralwelayah.clock.ui.displayName
import com.nouralwelayah.clock.ui.tr
import java.time.LocalDate
import java.time.ZonedDateTime

private const val SLOTS = 40          // MAX_ALARMS in the firmware
private const val PER_PAGE = 7        // ALARMS_PER_PAGE: rows at y = 74 + r * 88, 80 px high
private val REL_NAMES_EN = listOf("Fajr", "Dhuhr", "Maghrib")
private val REL_NAMES_AR = listOf("الفجر", "الظهر", "المغرب")
private val DAY_SHORT_EN = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val DAY_SHORT_AR = listOf("الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
private val MON_SHORT_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MON_SHORT_AR = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")

/** An unused slot looks like the firmware's resetAlarm(): 07:00, Mon - Fri, alarm sound, volume 15, off. */
private fun emptySlot() = Alarm(enabled = false, hour = 7, minute = 0, days = 0x3E, volume = 15)

/** The firmware's alarmText(): "30 min after Maghrib   |   Fri   |   Adeela.mp3   |   Vol 12". */
private fun alarmText(ctx: android.content.Context, a: Alarm, ar: Boolean): String {
    val sep = "   |   "
    val sb = StringBuilder()
    val relNames = if (ar) REL_NAMES_AR else REL_NAMES_EN
    val dayShort = if (ar) DAY_SHORT_AR else DAY_SHORT_EN
    val monShort = if (ar) MON_SHORT_AR else MON_SHORT_EN
    if (a.relPrayer in 0..2) {
        val o = a.relOffset
        if (ar) {
            sb.append(if (o == 0) "عند ${relNames[a.relPrayer]}" else "${Math.abs(o)} د ${if (o < 0) "قبل" else "بعد"} ${relNames[a.relPrayer]}")
        } else {
            sb.append(if (o == 0) "At ${relNames[a.relPrayer]}" else "${Math.abs(o)} min ${if (o < 0) "before" else "after"} ${relNames[a.relPrayer]}")
        }
        sb.append(sep)
    }
    sb.append(when {
        a.useDate -> if (ar) {
            "مرة واحدة في %02d %s %d".format(a.day, monShort[(a.month - 1).coerceIn(0, 11)], a.year)
        } else {
            "Once on %02d %s %d".format(a.day, monShort[(a.month - 1).coerceIn(0, 11)], a.year)
        }
        a.days == 0 -> if (ar) "مرة واحدة" else "Once (next time)"
        a.days == 0x7F -> if (ar) "يومياً" else "Every day"
        a.days == 0x3E -> if (ar) "الإثنين - الجمعة" else "Mon - Fri"
        else -> (0..6).filter { a.days and (1 shl it) != 0 }.joinToString(", ") { dayShort[it] }
    })
    if (a.ramadan && !a.useDate) sb.append(sep).append(if (ar) "رمضان فقط" else "Ramadan only")
    sb.append(sep).append(soundName(ctx, a.sound)).append(sep).append(if (ar) "الصوت " else "Vol ").append(a.volume)
    return sb.toString()
}

private fun soundName(ctx: android.content.Context, ref: String) = when {
    ref.isBlank() -> "alarm.mp3"
    AppFolders.isFolderRef(ref) -> AppFolders.refLabel(ref)
    else -> displayName(ctx, ref)
}

/** buildAlarmsTab() + uiRefreshAlarms(): 40 slots, 7 per page; tap a row to edit it (the P4's editor). */
@Composable
fun AlarmsTab(s: AppSettings, alarms: List<Alarm>, now: ZonedDateTime) {
    val ctx = LocalContext.current
    val ar = com.nouralwelayah.clock.ui.isArabic()
    val today = Planner.day(s, now.toLocalDate(), now.zone)
    val slots = maxOf(SLOTS, alarms.size)
    val pages = (slots + PER_PAGE - 1) / PER_PAGE
    var page by remember { mutableIntStateOf(0) }
    var editing by remember { mutableIntStateOf(-1) }        // slot being edited
    val requestedEditorSlot by com.nouralwelayah.clock.media.MediaHub.requestedAlarmEditorSlot.collectAsState()

    androidx.compose.runtime.LaunchedEffect(requestedEditorSlot) {
        val targetSlot = requestedEditorSlot
        if (targetSlot != null && targetSlot in 0 until slots) {
            page = (targetSlot / PER_PAGE).coerceIn(0, (pages - 1).coerceAtLeast(0))
            editing = targetSlot
            com.nouralwelayah.clock.media.MediaHub.requestedAlarmEditorSlot.value = null
        }
    }

    fun save(list: List<Alarm>) { Store.saveAlarms(list); AlertScheduler.reschedule(ctx) }
    /** Puts [a] into slot [i]: replaces an alarm, or adds it (an empty slot = after the last alarm). */
    fun put(i: Int, a: Alarm) = save(if (i < alarms.size) alarms.toMutableList().also { it[i] = a } else alarms + a)

    val s4 = LocalP4.current
    with(s4) {
        Box(Modifier.fillMaxSize()) {
            P4Text(
                tr(
                    "Tap an alarm to edit it. Each alarm has its own sound and volume.",
                    "اضغط على أي منبه لتعديله. لكل منبه صوت ومستوى صوت خاص به."
                ),
                20,
                C_DIM,
                Modifier.offset(0.p, 4.p),
            )
            P4Text(
                tr(
                    "${alarms.count { it.enabled }} of $slots alarms on",
                    "${alarms.count { it.enabled }} من أصل $slots منبهاً مفعّلاً"
                ),
                20,
                C_DIM,
                Modifier.offset(0.p, 34.p),
            )
            Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                P4Button(tr("◀ Prev", "◀ السابق"), Modifier.size(150.p, 60.p), enabled = page > 0) { page-- }
                Box(Modifier.width(170.p), contentAlignment = Alignment.Center) {
                    P4Text(tr("Page ${page + 1} / $pages", "صفحة ${page + 1} / $pages"), 28, C_TEXT, maxLines = 1)
                }
                P4Button(tr("Next ▶", "التالي ▶"), Modifier.size(150.p, 60.p), enabled = page < pages - 1) { page++ }
            }
            for (r in 0 until PER_PAGE) {
                val i = page * PER_PAGE + r
                if (i >= slots) break
                val a = alarms.getOrNull(i) ?: emptySlot()
                val m = Planner.alarmMinute(a, today)
                Row(
                    Modifier.offset(0.p, (74 + r * 88).p).fillMaxWidth().height(80.p)
                        .background(C_CARD, RoundedCornerShape(12.p)).clickable { editing = i }.padding(horizontal = 10.p),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    P4Text(if (m < 0) "--:--" else "%02d:%02d".format(m / 60, m % 60), 28, if (a.enabled) C_TEXT else C_DIM,
                        Modifier.width(210.p), 1)
                    Column(Modifier.weight(1f)) {
                        P4Text(a.label.ifBlank { tr("Alarm ${i + 1}", "منبه ${i + 1}") }, 20, C_TEXT, maxLines = 1)
                        P4Text(alarmText(ctx, a, ar), 20, C_DIM, maxLines = 1)
                    }
                    Switch(a.enabled, { on -> put(i, a.copy(enabled = on)) })
                }
            }
        }
    }

    if (editing >= 0) {
        val i = editing
        AlarmEditorP4(s, i, alarms.getOrNull(i) ?: emptySlot().copy(enabled = true),
            onClose = { editing = -1 },
            onSave = { put(i, it); editing = -1 },
            onDelete = { if (i < alarms.size) save(alarms.filterIndexed { k, _ -> k != i }); editing = -1 })
    }
}

/** buildEditor(): the P4's alarm editor (1180 x 740 card over the screen). */
@Composable
private fun AlarmEditorP4(s: AppSettings, slot: Int, start: Alarm, onClose: () -> Unit, onSave: (Alarm) -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val ar = com.nouralwelayah.clock.ui.isArabic()
    val relNames = if (ar) REL_NAMES_AR else REL_NAMES_EN
    val dayShort = if (ar) DAY_SHORT_AR else DAY_SHORT_EN
    val monShort = if (ar) MON_SHORT_AR else MON_SHORT_EN
    var label by remember { mutableStateOf(start.label) }
    var mode by remember { mutableIntStateOf(start.relPrayer + 1) }            // 0 fixed, 1..3 Fajr / Dhuhr / Maghrib
    var hour by remember { mutableIntStateOf(start.hour) }
    var minute by remember { mutableIntStateOf(start.minute) }
    var after by remember { mutableStateOf(start.relOffset >= 0) }
    var relMin by remember { mutableIntStateOf(Math.abs(start.relOffset)) }
    var days by remember { mutableIntStateOf(start.days) }
    var ram by remember { mutableStateOf(start.ramadan) }
    var useDate by remember { mutableStateOf(start.useDate) }
    val todayDate = LocalDate.now(s.zone())
    var day by remember { mutableIntStateOf(if (start.useDate) start.day else todayDate.dayOfMonth) }
    var month by remember { mutableIntStateOf(if (start.useDate) start.month else todayDate.monthValue) }
    var year by remember { mutableIntStateOf(if (start.useDate) start.year else todayDate.year) }
    var vol by remember { mutableFloatStateOf(start.volume.toFloat()) }
    var sound by remember { mutableStateOf(start.sound) }
    var soundMenuExpanded by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val refreshed by AppFolders.refreshed.collectAsState()
    val alarmAudioFiles = remember(refreshed, browse, soundMenuExpanded) {
        val fsmAlarmsDir = FileSystemManager.getSubdirectory(ctx, Folder.ALARMS.dir)
        val fsmList = fsmAlarmsDir.listFiles()?.filter {
            it.isFile && !it.name.startsWith(".") && (AppFolders.isAudio(it) || AppFolders.isVideo(it))
        }.orEmpty()
        val folderList = AppFolders.files(ctx, Folder.ALARMS).filter {
            AppFolders.isAudio(it) || AppFolders.isVideo(it)
        }
        val merged = LinkedHashMap<String, java.io.File>()
        for (f in fsmList + folderList) {
            merged.putIfAbsent(f.name.lowercase(), f)
        }
        merged.values.sortedBy { it.name.lowercase() }
    }

    fun current() = start.copy(
        label = label.trim().take(30), relPrayer = mode - 1, hour = hour, minute = minute,
        relOffset = if (mode == 0) 0 else if (after) relMin else -relMin,
        days = days, ramadan = ram && !useDate, useDate = useDate, day = day, month = month, year = year,
        sound = sound, volume = vol.toInt(), enabled = true,
    )

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val s4 = LocalP4.current
        with(s4) {
            Box(Modifier.size(1180.p, 740.p).background(C_CARD, RoundedCornerShape(16.p)).padding(16.p)) {
                // ---- title row
                P4Text(tr("Alarm ${slot + 1}", "منبه ${slot + 1}"), 28, C_TEXT, Modifier.offset(0.p, 8.p))
                P4Text(tr("Title", "العنوان"), 20, C_DIM, Modifier.offset(180.p, 14.p))
                OutlinedTextField(
                    label,
                    { label = it.take(30) },
                    singleLine = true,
                    placeholder = { Text(tr("e.g. Suhoor  (max 30 letters)", "مثال: السحور (حد أقصى 30 حرفاً)")) },
                    modifier = Modifier.offset(250.p, (-6).p).width(600.p),
                )
                // ---- when (left)
                P4Text(tr("When", "الوقت"), 20, C_DIM, Modifier.offset(0.p, 80.p))
                Box(Modifier.offset(0.p, 110.p).width(360.p)) {
                    Dropdown(
                        listOf(
                            tr("Fixed time", "وقت ثابت"),
                            tr("Before / after Fajr", "قبل / بعد الفجر"),
                            tr("Before / after Dhuhr", "قبل / بعد الظهر"),
                            tr("Before / after Maghrib", "قبل / بعد المغرب"),
                        ),
                        mode,
                        { mode = it },
                        Modifier.fillMaxWidth(),
                    )
                }
                if (mode == 0) Row(Modifier.offset(0.p, 190.p), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.p)) {
                    Dropdown((0..23).map { "%02d".format(it) }, hour, { hour = it }, Modifier.width(130.p))
                    P4Text(":", 34, C_TEXT)
                    Dropdown((0..59).map { "%02d".format(it) }, minute, { minute = it }, Modifier.width(130.p))
                } else Row(Modifier.offset(0.p, 190.p), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.p)) {
                    Dropdown(listOf(tr("Before", "قبل"), tr("After", "بعد")), if (after) 1 else 0, { after = it == 1 }, Modifier.width(150.p))
                    Dropdown((0..180).map { "$it" }, relMin, { relMin = it }, Modifier.width(110.p))
                    P4Text(tr("min", "دقيقة"), 28, C_TEXT)
                }
                // preview, like edUpdatePreview()
                val preview = if (mode == 0) "" else run {
                    val d = Planner.day(s, todayDate)
                    val m = Planner.alarmMinute(current(), d)
                    val p = d.minutes[listOf(0, 2, 4)[mode - 1]]
                    if (m < 0 || p < 0) "" else if (ar) {
                        "يرن اليوم الساعة %02d:%02d\n(%s الساعة %02d:%02d)".format(m / 60, m % 60, relNames[mode - 1], p / 60, p % 60)
                    } else {
                        "Today it rings at %02d:%02d\n(%s is at %02d:%02d)".format(m / 60, m % 60, relNames[mode - 1], p / 60, p % 60)
                    }
                }
                P4Text(preview, 20, C_GOLD, Modifier.offset(0.p, 280.p))
                // ---- repeat (right)
                P4Text(tr("Repeat on (none selected = ring once)", "التكرار في الأيام (بدون تحديد = مرة واحدة)"), 20, C_DIM, Modifier.offset(400.p, 80.p))
                for (d in 0..6) {
                    val on = days and (1 shl d) != 0
                    Box(Modifier.offset((400 + d * 106).p, 110.p).size(100.p, 60.p)
                        .background(if (on) C_ACCENT else Color(0xFF37474F), RoundedCornerShape(10.p))
                        .clickable(enabled = !useDate) { days = days xor (1 shl d) }, contentAlignment = Alignment.Center) {
                        P4Text(dayShort[d], 18, if (useDate) C_DIM else Color.White)
                    }
                }
                Row(Modifier.offset(400.p, 186.p), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.p)) {
                    Switch(ram, { ram = it; if (it) useDate = false })
                    P4Text(tr("Ramadan only (works with the days above)", "في شهر رمضان فقط (يعمل مع الأيام المحددة أعلاه)"), 20, C_TEXT)
                }
                Row(Modifier.offset(400.p, 244.p), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.p)) {
                    Switch(useDate, { useDate = it; if (it) ram = false })
                    P4Text(tr("Specific date (ignores the days and Ramadan)", "تاريخ محدد (يتجاهل الأيام ورمضان)"), 20, C_TEXT)
                }
                if (useDate) Row(Modifier.offset(400.p, 304.p), horizontalArrangement = Arrangement.spacedBy(12.p)) {
                    Dropdown((1..31).map { "$it" }, day - 1, { day = it + 1 }, Modifier.width(110.p))
                    Dropdown(monShort, month - 1, { month = it + 1 }, Modifier.width(130.p))
                    val years = (todayDate.year..todayDate.year + 5).toList()
                    Dropdown(years.map { "$it" }, years.indexOf(year).coerceAtLeast(0), { year = years[it] }, Modifier.width(140.p))
                }
                // ---- sound dropdown + folder browser
                P4Text(tr("Sound (select from /NourAlwelayah/alarms/ or browse folders)", "الصوت (اختر من مجلد التنبيهات أو تصفح المجلدات)"), 20, C_DIM, Modifier.offset(0.p, 456.p))
                Row(
                    modifier = Modifier.offset(0.p, 484.p),
                    horizontalArrangement = Arrangement.spacedBy(10.p),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(460.p, 60.p)
                            .background(Color(0xFF37474F), RoundedCornerShape(10.p))
                            .border(1.dp, C_GOLD.copy(alpha = 0.65f), RoundedCornerShape(10.p))
                            .clickable { soundMenuExpanded = true }
                            .padding(horizontal = 14.p),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "♪  " + (if (sound.isEmpty()) tr("Default (alarm.mp3)", "الافتراضي (alarm.mp3)") else soundName(ctx, sound)),
                                color = Color.White,
                                fontSize = 20.f,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("▾", color = C_GOLD, fontSize = 22.f)
                        }
                        DropdownMenu(
                            expanded = soundMenuExpanded,
                            onDismissRequest = { soundMenuExpanded = false },
                            modifier = Modifier
                                .width(460.dp)
                                .background(C_BG),
                        ) {
                            val isDefaultSelected = sound.isEmpty()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = (if (isDefaultSelected) "✓  " else "♪  ") + tr("Default Alarm Sound (alarm.mp3)", "الصوت الافتراضي (alarm.mp3)"),
                                        color = if (isDefaultSelected) C_GOLD else C_TEXT,
                                        fontSize = 18.sp,
                                        fontWeight = if (isDefaultSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    sound = ""
                                    soundMenuExpanded = false
                                },
                            )
                            HorizontalDivider(color = Color(0x22FFFFFF))
                            alarmAudioFiles.forEach { file ->
                                val ref = AppFolders.alarmRef(file)
                                val isSelected = sound.equals(ref, ignoreCase = true) ||
                                    sound.substringAfterLast('/').equals(file.name, ignoreCase = true)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = (if (isSelected) "✓  " else "♪  ") + file.name,
                                            color = if (isSelected) C_GOLD else C_TEXT,
                                            fontSize = 18.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    onClick = {
                                        sound = ref
                                        soundMenuExpanded = false
                                    },
                                )
                            }
                            HorizontalDivider(color = Color(0x22FFFFFF))
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "📁  " + tr("Browse all Nour Alwelayah folders...", "تصفح جميع مجلدات نور الولاية..."),
                                        color = C_GOLD,
                                        fontSize = 17.sp,
                                    )
                                },
                                onClick = {
                                    soundMenuExpanded = false
                                    browse = true
                                },
                            )
                        }
                    }
                    P4Button("📁", Modifier.size(90.p, 60.p), color = GREY) {
                        browse = true
                    }
                }
                if (browse) {
                    com.nouralwelayah.clock.ui.SoundBrowser(
                        current = sound,
                        onPick = { pickedRef ->
                            sound = pickedRef
                            browse = false
                        },
                        onClose = { browse = false },
                    )
                }
                P4Button(tr("▶ Test", "▶ تجربة"), Modifier.offset(580.p, 484.p).size(150.p, 60.p)) {
                    val defaultAlarmTitle = if (ar) "منبه ${slot + 1}" else "Alarm ${slot + 1}"
                    ContextCompat.startForegroundService(ctx,
                        AlertService.alarmTestIntent(ctx, sound, label.ifBlank { defaultAlarmTitle }, vol.toInt()))
                }
                P4Button(tr("■ Stop", "■ إيقاف"), Modifier.offset(745.p, 484.p).size(150.p, 60.p), color = GREY) {
                    ctx.startService(AlertService.stopIntent(ctx))
                }
                // ---- volume
                P4Text(tr("Volume for this alarm (0-21)", "مستوى الصوت لهذا المنبه (0-21)"), 20, C_DIM, Modifier.offset(690.p, 590.p))
                Row(Modifier.offset(690.p, 624.p).width(460.p), verticalAlignment = Alignment.CenterVertically) {
                    P4Text("🔊", 28, C_DIM)
                    RoundSlider(vol, { vol = it }, Modifier.weight(1f).padding(horizontal = 10.p), valueRange = 0f..21f, steps = 20)
                    P4Text("${vol.toInt()}", 28, C_TEXT, Modifier.width(44.p))
                }
                if (msg.isNotEmpty()) P4Text(msg, 20, C_GOLD, Modifier.offset(0.p, 570.p))
                // ---- buttons
                P4Button(tr("✓ Save", "✓ حفظ"), Modifier.offset(0.p, 620.p).size(200.p, 64.p)) {
                    if (useDate && runCatching { LocalDate.of(year, month, day) }.isFailure) { msg = if (ar) "تاريخ غير صالح" else "No such date"; return@P4Button }
                    ctx.startService(AlertService.stopIntent(ctx))
                    onSave(current())
                }
                P4Button(tr("✕ Cancel", "✕ إلغاء"), Modifier.offset(220.p, 620.p).size(200.p, 64.p), color = GREY) {
                    ctx.startService(AlertService.stopIntent(ctx)); onClose()
                }
                P4Button(tr("🗑 Delete", "🗑 حذف"), Modifier.offset(440.p, 620.p).size(200.p, 64.p), color = Color(0xFFE53935)) {
                    ctx.startService(AlertService.stopIntent(ctx)); onDelete()
                }
            }
        }
    }
}

@Suppress("unused") private val keepBg = C_BG
@Suppress("unused") private fun keep(m: Modifier) = m.fillMaxHeight()
