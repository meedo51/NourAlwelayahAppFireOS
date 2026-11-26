package com.nouralwelayah.clock.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The appliance's folders: Internal storage/NourAlwelayah/azan, audios, images, alarms,
 * videos, config, wallpaper.
 */
enum class Folder(val dir: String, val privateDir: String?) {
    AZAN("azan", "azan"),                 // azan sounds (settings refer to them by file name)
    AUDIOS("audios", null),               // audios for the media player (/NourAlwelayah/audios)
    VIDEOS("videos", null),               // videos for the media player (/NourAlwelayah/videos)
    ALARMS("alarms", null),               // alarm sounds / videos
    IMAGES("images", "azanpics"),         // azan pictures (firmware [AZBG] /images)
    WALLPAPER("wallpaper", "wallpaper"),  // clock pictures (firmware [CLKBG] /wallpaper)
    CONFIG("config", null);               // settings.json + alarms.json backup

    companion object {
        val MUSIC: Folder get() = AUDIOS
    }
}

object AppFolders {
    const val ROOT = "NourAlwelayah"
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi", "wma")
    private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi", "ts")
    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    fun root(): File = File(Environment.getExternalStorageDirectory(), ROOT)

    fun hasAccess(c: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Android 11+: the system screen to allow "All files access" (older: ask for WRITE_EXTERNAL_STORAGE). */
    fun accessIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))

    /** Where a folder's files are now: the shared folder with access, else the hardened getExternalFilesDir(null)/NourAlwelayah storage. */
    fun dir(c: Context, f: Folder): File {
        val extSubDir = com.nouralwelayah.clock.system.FileSystemManager.getSubdirectory(c, f.dir)
        if (!hasAccess(c)) return extSubDir
        if (!ready) setup(c)            // access just allowed: create the folders, move the private files first
        return File(root(), f.dir).apply { mkdirs() }
    }

    @Volatile private var ready = false

    /** Sets the folders up if access was allowed since the last call. True if it did (lists need reading again). */
    fun ensure(c: Context): Boolean = hasAccess(c) && !ready && setup(c)

    /** Files of a folder (no hidden ones), sorted by name. Includes both hardened app-external and shared storage. */
    fun files(c: Context, f: Folder): List<File> {
        val primary = dir(c, f).listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
        val fsmDir = com.nouralwelayah.clock.system.FileSystemManager.getSubdirectory(c, f.dir)
        val secondary = if (fsmDir.canonicalPath != dir(c, f).canonicalPath) {
            fsmDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
        } else {
            emptyList()
        }
        val byName = LinkedHashMap<String, File>()
        for (file in primary + secondary) {
            byName.putIfAbsent(file.name.lowercase(), file)
        }
        return byName.values.sortedBy { it.name.lowercase() }
    }

    /** +1 on every "SD refresh": the lists of the folders (azan, alarms, music, pictures) read them again. */
    val refreshed = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * SD refresh: every PrayerClock folder read again (like the clock re-reading its SD card) - files copied in
     * over USB / the Files app show up in the music list, azan / alarm sounds and pictures, and Android indexes
     * them for the file pickers. Returns the number of files. Call off the main thread.
     */
    fun refreshAll(c: Context): Int {
        setup(c)
        val all = if (hasAccess(c)) root().walkTopDown().maxDepth(4).filter { it.isFile && !it.name.startsWith(".") }.toList()
                  else Folder.entries.flatMap { files(c, it) }
        if (hasAccess(c)) all.chunked(200).forEach { scan(c, *it.toTypedArray()) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            MediaLibrary.rescan(c)
            com.nouralwelayah.clock.ui.picturesChanged()
            refreshed.value++
        }
        return all.size
    }

    /** Lets Android index a file in the shared folders (file pickers, gallery, other apps see it). */
    fun scan(c: Context, vararg f: File) {
        if (f.isNotEmpty() && f.all { it.path.startsWith(root().path) })
            MediaScannerConnection.scanFile(c.applicationContext, f.map { it.path }.toTypedArray(), null, null)
    }

    fun isAudio(f: File) = f.extension.lowercase() in AUDIO
    fun isVideo(f: File) = f.extension.lowercase() in VIDEO
    fun isImage(f: File) = f.extension.lowercase() in IMAGE

    /**
     * With access: creates the folders and moves the files the app kept privately (before access) into them.
     * Safe to call often. Returns true if the shared folders are in use.
     */
    @Synchronized
    fun setup(c: Context): Boolean {
        if (!hasAccess(c)) { ready = false; return false }
        runCatching {
            Folder.entries.forEach { f ->
                val shared = File(root(), f.dir).apply { mkdirs() }
                val old = f.privateDir?.let { File(c.filesDir, it) }
                old?.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.forEach { src ->
                    val dst = File(shared, src.name)
                    if (dst.exists()) src.delete()                      // same name already there: keep that one
                    else { if (!src.renameTo(dst)) { src.copyTo(dst); src.delete() }; scan(c, dst) }
                }
            }
        }
        ready = true
        // first backup only if there is none: a fresh install must not overwrite the config before Import
        if (!File(root(), "${Folder.CONFIG.dir}/settings.json").exists() && Store.settings.value.locationSet) backup(c)
        return true
    }

    // ---- config: settings + alarms backup ----

    /** Writes config/settings.json and config/alarms.json (called on every save). */
    fun backup(c: Context) {
        if (!hasAccess(c)) return
        runCatching {
            val d = File(root(), Folder.CONFIG.dir).apply { mkdirs() }
            File(d, "settings.json").writeText(Store.settings.value.toJson().toString(2))
            File(d, "alarms.json").writeText(JSONArray(Store.alarms.value.map { it.toJson() }).toString(2))
        }
    }

    /** Reads config/settings.json and config/alarms.json, if there. Returns what was found (null = none). */
    fun readBackup(c: Context): Pair<AppSettings?, List<Alarm>?> {
        if (!hasAccess(c)) return null to null
        val d = File(root(), Folder.CONFIG.dir)
        val s = runCatching { AppSettings.fromJson(JSONObject(File(d, "settings.json").readText())) }.getOrNull()
        val a = runCatching {
            val arr = JSONArray(File(d, "alarms.json").readText())
            List(arr.length()) { Alarm.fromJson(arr.getJSONObject(it)) }
        }.getOrNull()
        return s to a
    }

    // ---- alarm sounds from the PrayerClock folders (like the firmware's SD card, any folder) ----
    // stored as the path under PrayerClock without the leading "/": "alarms/bell.mp3", "music/Quran/a.mp3"
    // (a picked phone file is a content:// URI, an azan library file a bare name)

    const val ALARM_REF = "alarms/"
    const val AZAN_REF = "azan/"
    fun alarmRef(f: File) = ALARM_REF + f.name

    /** A sound stored as a PrayerClock path ("alarms/x.mp3", "music/a/b.mp3"). */
    fun isFolderRef(ref: String) = ref.isNotBlank() && "://" !in ref && '/' in ref

    /** The reference of a file in the NourAlwelayah folders ("" if it is outside them). */
    fun soundRef(c: Context, f: File): String {
        val cf = runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }
        val fsmRoot = runCatching {
            com.nouralwelayah.clock.system.FileSystemManager.getRootDirectory(c).canonicalFile.path + File.separator
        }.getOrNull()
        if (fsmRoot != null && cf.path.startsWith(fsmRoot)) {
            return cf.path.removePrefix(fsmRoot).replace(File.separatorChar, '/')
        }
        val sharedRoot = runCatching { root().canonicalFile.path + File.separator }.getOrNull()
        if (sharedRoot != null && cf.path.startsWith(sharedRoot)) {
            return cf.path.removePrefix(sharedRoot).replace(File.separatorChar, '/')
        }
        val priv = runCatching {
            File(c.filesDir, Folder.ALARMS.privateDir ?: Folder.ALARMS.dir).canonicalFile
        }.getOrNull()
        if (priv != null && cf.parentFile == priv) return alarmRef(cf)
        if (cf.parentFile?.name.equals(Folder.ALARMS.dir, ignoreCase = true)) return alarmRef(cf)
        return ""
    }

    /** The file of a folder reference, or null (missing, or it leaves NourAlwelayah). */
    fun refFile(c: Context, ref: String): File? {
        if (!isFolderRef(ref)) return null
        if (!com.nouralwelayah.clock.system.FileSystemManager.isSafeRelativePath(ref)) return null

        val fsmRoot = com.nouralwelayah.clock.system.FileSystemManager.getRootDirectory(c)
        com.nouralwelayah.clock.system.FileSystemManager.resolveSafePath(fsmRoot, ref)
            ?.takeIf { it.exists() && it.isFile }
            ?.let { return it }

        if ((ref.startsWith(ALARM_REF) || ref.startsWith(AZAN_REF)) && ref.count { it == '/' } == 1) {
            val folder = if (ref.startsWith(AZAN_REF)) Folder.AZAN else Folder.ALARMS
            val prefix = if (ref.startsWith(AZAN_REF)) AZAN_REF else ALARM_REF
            val fileName = ref.removePrefix(prefix)
            com.nouralwelayah.clock.system.FileSystemManager.resolveSafePath(dir(c, folder), fileName)
                ?.takeIf { it.exists() && it.isFile }
                ?.let { return it }
            val fsmSub = com.nouralwelayah.clock.system.FileSystemManager.getSubdirectory(c, folder.dir)
            com.nouralwelayah.clock.system.FileSystemManager.resolveSafePath(fsmSub, fileName)
                ?.takeIf { it.exists() && it.isFile }
                ?.let { return it }
        }

        val sharedCandidate = com.nouralwelayah.clock.system.FileSystemManager.resolveSafePath(root(), ref)
        if (sharedCandidate != null && sharedCandidate.exists() && sharedCandidate.isFile) {
            return sharedCandidate
        }

        val privCandidate = runCatching { File(c.filesDir, ref).canonicalFile }.getOrNull()
        val privRoot = runCatching { c.filesDir.canonicalFile.path + File.separator }.getOrNull()
        if (privCandidate != null && privRoot != null && privCandidate.path.startsWith(privRoot) && privCandidate.isFile) {
            return privCandidate
        }
        return null
    }

    /** Web page path of a reference: "/alarms/x.mp3" ("" when it is not a folder file). */
    fun refPagePath(ref: String) = if (isFolderRef(ref)) "/$ref" else ""

    /** "x.mp3" for /alarms, "x.mp3  (/music/Quran)" for another folder. */
    fun refLabel(ref: String): String {
        val name = ref.substringAfterLast('/')
        val dir = ref.substringBeforeLast('/', "")
        return if (dir == Folder.ALARMS.dir) name else "$name  (/$dir)"
    }
}
