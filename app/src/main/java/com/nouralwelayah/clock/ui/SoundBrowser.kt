package com.nouralwelayah.clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.Folder

/**
 * The alarm sound browser, like the firmware's [SNDPICK] (1.4.5): folders, ".. (Parent folder)", sound (and video)
 * files of the NourAlwelayah folders, starting in the folder of the current sound or /alarms. Refresh reads the
 * folders again, Default = the alarm sound (""), Cancel. [onPick] gets the reference (AppFolders.soundRef).
 */
@Composable
fun SoundBrowser(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val access = AppFolders.hasAccess(ctx)
    val fsmRoot = remember { com.nouralwelayah.clock.system.FileSystemManager.getRootDirectory(ctx) }
    val root = remember {
        if (access && AppFolders.root().isDirectory) AppFolders.root() else fsmRoot
    }
    val defaultAlarmsDir = remember {
        com.nouralwelayah.clock.system.FileSystemManager.getSubdirectory(ctx, Folder.ALARMS.dir)
            .takeIf { it.isDirectory && !it.listFiles().isNullOrEmpty() }
            ?: AppFolders.dir(ctx, Folder.ALARMS)
    }
    val startDir = remember(current) {
        AppFolders.refFile(ctx, current)?.parentFile ?: defaultAlarmsDir
    }
    var cwd by remember { mutableStateOf(startDir) }
    var refresh by remember { mutableIntStateOf(0) }
    val chosen = remember(current) { AppFolders.refFile(ctx, current)?.canonicalPath }

    // folder gone: /alarms, then the top
    if (!cwd.isDirectory) cwd = defaultAlarmsDir.takeIf { it.isDirectory } ?: root
    val items = remember(cwd, refresh) {
        val all = cwd.listFiles()?.filter {
            !it.name.startsWith(".") &&
                it.name != "System Volume Information" &&
                !com.nouralwelayah.clock.system.FileSystemManager.isConfigPath(it.name)
        }.orEmpty()
        all.filter { it.isDirectory }.sortedBy { it.name.lowercase() } +
            all.filter { it.isFile && (AppFolders.isAudio(it) || AppFolders.isVideo(it)) }.sortedBy { it.name.lowercase() }
    }
    val rootCanon = remember(root) { runCatching { root.canonicalPath }.getOrElse { root.absolutePath } }
    val cwdCanon = remember(cwd) { runCatching { cwd.canonicalPath }.getOrElse { cwd.absolutePath } }
    val fsmCanon = remember(fsmRoot) { runCatching { fsmRoot.canonicalPath }.getOrElse { fsmRoot.absolutePath } }
    val activeRootCanon = if (cwdCanon.startsWith(fsmCanon)) fsmCanon else rootCanon
    val atTop = cwdCanon == activeRootCanon
    val shown = "/" + cwdCanon.removePrefix(activeRootCanon).trimStart('/').ifEmpty { Folder.ALARMS.dir }
    val big = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val fs = if (big) 20.sp else 16.sp

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(if (big) 0.62f else 0.94f).height((LocalConfiguration.current.screenHeightDp * 0.86f).dp)
            .background(C_CARD, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text(tr("♪  Alarm sound", "♪  نغمة المنبه"), color = C_GOLD, fontSize = if (big) 28.sp else 22.sp)
            Text("📁 $shown", color = C_DIM, fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (!atTop) item { Row_("⬆", tr(".. (Parent folder)", ".. (المجلد السابق)"), fs) { cwd.parentFile?.let { cwd = it } } }
                items(items, key = { it.path }) { f ->
                    val fCanon = runCatching { f.canonicalPath }.getOrElse { f.absolutePath }
                    when {
                        f.isDirectory -> Row_("📁", f.name, fs) { cwd = f }
                        else -> Row_(if (fCanon == chosen) "✓" else if (AppFolders.isVideo(f)) "🎬" else "♪", f.name, fs) {
                            val ref = AppFolders.soundRef(ctx, f).ifBlank { AppFolders.alarmRef(f) }
                            onPick(ref)
                        }
                    }
                }
                if (items.isEmpty()) item {
                    Text(
                        tr("(no folders or sound files here)", "(لا توجد مجلدات أو ملفات صوتية هنا)"),
                        color = C_DIM, fontSize = fs, modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val h = if (big) 60.dp else 48.dp
                Button(onClick = { refresh++ }, Modifier.weight(1f).height(h)) { Text(tr("⟳  Refresh", "⟳  تحديث"), fontSize = fs) }
                Button(onClick = { onPick("") }, Modifier.weight(1f).height(h),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) { Text(tr("Default", "الافتراضي"), fontSize = fs) }
                Button(onClick = onClose, Modifier.weight(1f).height(h),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) { Text(tr("Cancel", "إلغاء"), fontSize = fs) }
            }
        }
    }
}

@Composable
private fun Row_(icon: String, name: String, fs: androidx.compose.ui.unit.TextUnit, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(icon, color = C_ACCENT, fontSize = fs) }
            Text(name, color = C_TEXT, fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = Color(0x22FFFFFF))
    }
}
