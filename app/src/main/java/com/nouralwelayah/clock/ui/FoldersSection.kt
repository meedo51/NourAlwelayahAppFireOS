package com.nouralwelayah.clock.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.nouralwelayah.clock.alert.AlertScheduler
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.Folder
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.Store
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/** Settings > Folders: Internal storage/PrayerClock/... like the clock's SD card, and the config backup. */
@Composable
fun FoldersSection(modifier: Modifier) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var askImport by remember { mutableStateOf(false) }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    LifecycleResumeEffect(Unit) {           // back from "All files access": set the folders up
        refresh++
        onPauseOrDispose { }
    }
    val access = remember(refresh) {
        AppFolders.setup(ctx).also { if (it) { MediaLibrary.rescan(ctx); picturesChanged() } }
    }

    Section("Folders", modifier) {
        if (!access) {
            Hint("Like the clock's SD card: Internal storage/${AppFolders.ROOT}/ with " +
                Folder.entries.joinToString(", ") { it.dir } + ". Copy files there (Files app or USB) and the app " +
                "uses them. Needs \"All files access\".")
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { ctx.startActivity(AppFolders.accessIntent(ctx)) }
                        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                } else legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }) { Text("Allow all files access") }
        } else {
            Text("Internal storage/${AppFolders.ROOT}", color = C_ACCENT)
            Folder.entries.forEach { f ->
                val tick by AppFolders.refreshed.collectAsState()
                val n = remember(refresh, tick) { AppFolders.files(ctx, f).size }
                Hint("${f.dir}  ($n)  -  " + when (f) {
                    Folder.AZAN -> "azan sounds (Prayer tab)"
                    Folder.AUDIOS -> "audios for the media player"
                    Folder.VIDEOS -> "videos for the media player"
                    Folder.ALARMS -> "alarm sounds / videos (alarm > Folder)"
                    Folder.IMAGES -> "azan pictures (fajr / dhuhr / maghrib in the name = that azan)"
                    Folder.WALLPAPER -> "clock pictures"
                    Folder.CONFIG -> "settings.json + alarms.json, saved on every change"
                })
            }
            SdRefreshButton { msg = it; refresh++ }
            OutlinedButton(onClick = { askImport = true }) { Text("Import settings + alarms from config") }
            Hint("To copy this device's setup to another: copy the config folder to it, then Import there.")
        }
        if (msg.isNotEmpty()) Text(msg, color = C_GOLD)
    }

    if (askImport) AlertDialog(
        onDismissRequest = { askImport = false },
        title = { Text("Import from config?") },
        text = { Text("Settings and alarms are replaced by settings.json and alarms.json from the config folder.") },
        confirmButton = {
            TextButton(onClick = {
                askImport = false
                val (s, a) = AppFolders.readBackup(ctx)
                if (s == null && a == null) msg = "No settings.json or alarms.json in the config folder."
                else {
                    s?.let { Store.saveSettings(it) }
                    a?.let { Store.saveAlarms(it) }
                    AlertScheduler.reschedule(ctx)
                    msg = "Imported" + (if (s != null) " settings" else "") + (if (s != null && a != null) " and" else "") +
                        (if (a != null) " ${a.size} alarm(s)" else "") + "."
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { askImport = false }) { Text("Cancel") } },
    )
}

/** ⟳ SD refresh: all PrayerClock folders read again (music, videos, azan, alarms, pictures). */
@Composable
fun SdRefreshButton(modifier: Modifier = Modifier, done: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(enabled = !busy, modifier = modifier, onClick = {
        busy = true
        scope.launch {
            val n = withContext(Dispatchers.IO) { AppFolders.refreshAll(ctx) }
            busy = false
            done("Read again: $n files in the PrayerClock folders")
        }
    }) { Text(if (busy) "Reading..." else "⟳  SD refresh") }
}

