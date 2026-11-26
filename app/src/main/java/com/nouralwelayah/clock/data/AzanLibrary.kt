package com.nouralwelayah.clock.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nouralwelayah.clock.system.FileSystemManager
import java.io.File
import java.net.URLDecoder

/**
 * The user's azan files, in NourAlwelayah/azan (AppFolders; private filesDir/azan without storage access).
 * Picked files are copied in, so they keep working when the original is moved or deleted. Settings refer to a file by its name here.
 * A reference containing "://" is a content/file URI (older settings, alarm sounds) and is resolved safely.
 */
object AzanLibrary {
    private fun dir(c: Context) = AppFolders.dir(c, Folder.AZAN)       // NourAlwelayah/azan (or private storage)

    fun list(c: Context): List<String> = AppFolders.files(c, Folder.AZAN)
        .filter { AppFolders.isAudio(it) || AppFolders.isVideo(it) }
        .map { it.name }

    /** Copies the picked file into the library. Returns its name, or null if it could not be read. */
    fun add(c: Context, uri: Uri): String? = runCatching {
        val shown = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "azan.mp3"
        val clean = shown.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim().ifEmpty { "azan.mp3" }
        val base = clean.substringBeforeLast('.', clean)
        val ext = if ('.' in clean) "." + clean.substringAfterLast('.') else ""
        var f = File(dir(c), clean)
        var n = 2
        while (f.exists()) f = File(dir(c), "$base ($n)$ext").also { n++ }
        val tmp = File(dir(c), ".${f.name}.part")
        c.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(f))
        AppFolders.scan(c, f)
        f.name
    }.getOrNull()

    fun delete(c: Context, name: String) {
        if (name.isNotBlank() && "://" !in name) {
            File(dir(c), name).delete()
            runCatching {
                File(FileSystemManager.getSubdirectory(c, Folder.AZAN.dir), name).delete()
            }
        }
    }

    /**
     * Resolves any saved Azan or Alarm sound reference into a verified local [File]
     * (`file.exists() && file.isFile && file.canRead() && file.length() > 0L`), supporting:
     * - Bare filenames in `/azan` or `/alarms` (including Arabic characters, spaces, and parentheses, e.g., `أذان - أباذر الحلواجي.mp3`)
     * - Relative paths (`azan/0azan.mp3`, `/azan/0azan.mp3`, `NourAlwelayah/azan/...`, `alarms/...`)
     * - Full filesystem paths (`/sdcard/NourAlwelayah/azan/...`, `/storage/emulated/0/NourAlwelayah/azan/...`)
     * - `file://` URIs (including percent-encoded Arabic/space characters)
     */
    fun fileFor(c: Context, ref: String): File? {
        val roots = buildList {
            runCatching { add(FileSystemManager.getRootDirectory(c)) }
            runCatching { add(AppFolders.root()) }
            runCatching { add(c.filesDir) }
        }
        val azanDirs = buildList {
            runCatching { add(dir(c)) }
            runCatching { add(FileSystemManager.getSubdirectory(c, Folder.AZAN.dir)) }
            runCatching { add(File(AppFolders.root(), Folder.AZAN.dir)) }
            runCatching { add(File(c.filesDir, Folder.AZAN.privateDir ?: Folder.AZAN.dir)) }
        }
        val alarmDirs = buildList {
            runCatching { add(AppFolders.dir(c, Folder.ALARMS)) }
            runCatching { add(FileSystemManager.getSubdirectory(c, Folder.ALARMS.dir)) }
            runCatching { add(File(AppFolders.root(), Folder.ALARMS.dir)) }
        }
        return fileForDirectories(
            ref = ref,
            roots = roots,
            azanDirs = azanDirs,
            alarmDirs = alarmDirs,
            folderRefResolver = { rel -> AppFolders.refFile(c, rel) },
            fallbackFileList = {
                runCatching { AppFolders.files(c, Folder.AZAN) + AppFolders.files(c, Folder.ALARMS) }
                    .getOrDefault(emptyList())
            }
        )
    }

    internal fun fileForDirectories(
        ref: String,
        roots: List<File>,
        azanDirs: List<File>,
        alarmDirs: List<File> = emptyList(),
        folderRefResolver: (String) -> File? = { null },
        fallbackFileList: () -> List<File> = { emptyList() },
    ): File? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        // Non-file content:// or android.resource:// URIs are not direct java.io.File instances
        if ("://" in trimmed && !trimmed.startsWith("file://", ignoreCase = true)) {
            return null
        }

        val candidates = LinkedHashSet<String>()
        if (trimmed.startsWith("file://", ignoreCase = true)) {
            val stripped = trimmed.substring("file://".length)
            val rawUriPath = runCatching { java.net.URI(trimmed).path }.getOrNull()
                ?: runCatching { Uri.parse(trimmed).path }.getOrNull()
                ?: stripped
            if (!rawUriPath.isNullOrBlank()) {
                candidates.add(rawUriPath)
            }
            if (stripped.isNotBlank()) {
                candidates.add(stripped)
                runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrNull()?.let { candidates.add(it) }
            }
        } else {
            candidates.add(trimmed)
            if ('%' in trimmed) {
                runCatching { URLDecoder.decode(trimmed, "UTF-8") }.getOrNull()?.let { candidates.add(it) }
            }
        }

        for (candidate in candidates) {
            resolveCandidateFile(
                rawPath = candidate,
                roots = roots,
                azanDirs = azanDirs,
                alarmDirs = alarmDirs,
                folderRefResolver = folderRefResolver,
                fallbackFileList = fallbackFileList,
            )?.let { return it }
        }
        return null
    }

    private fun isValidPlayableFile(file: File?): Boolean {
        return file != null && file.exists() && file.isFile && file.canRead() && file.length() > 0L
    }

    private fun resolveCandidateFile(
        rawPath: String,
        roots: List<File>,
        azanDirs: List<File>,
        alarmDirs: List<File>,
        folderRefResolver: (String) -> File?,
        fallbackFileList: () -> List<File>,
    ): File? {
        val normalized = rawPath.replace('\\', '/').trim()
        if (normalized.isEmpty()) return null

        // 1. Direct absolute path check (e.g., /sdcard/NourAlwelayah/azan/... or /storage/emulated/0/...)
        if (normalized.startsWith("/")) {
            val directFile = File(normalized)
            if (isValidPlayableFile(directFile)) return directFile
        }

        // Strip leading slashes and optional "sdcard/", "storage/emulated/0/", or "NourAlwelayah/" prefixes
        var rel = normalized.trimStart('/')
        val lowerRel = rel.lowercase()
        when {
            lowerRel.startsWith("storage/emulated/0/") -> rel = rel.substring("storage/emulated/0/".length).trimStart('/')
            lowerRel.startsWith("sdcard/") -> rel = rel.substring("sdcard/".length).trimStart('/')
        }
        if (rel.lowercase().startsWith("${AppFolders.ROOT.lowercase()}/")) {
            rel = rel.substring(AppFolders.ROOT.length + 1).trimStart('/')
        }

        if (!FileSystemManager.isSafeRelativePath(rel)) return null

        // 2. If it's a relative folder reference (e.g., "azan/foo.mp3", "alarms/bar.mp3", "audios/baz.mp3")
        if ('/' in rel) {
            folderRefResolver(rel)?.takeIf { isValidPlayableFile(it) }?.let { return it }

            for (root in roots) {
                FileSystemManager.resolveSafePath(root, rel)?.takeIf { isValidPlayableFile(it) }?.let { return it }
            }
        }

        // 3. Bare filename (or fallback to filename component after last '/') across all Azan & Alarm directories
        val fileName = rel.substringAfterLast('/').trim()
        if (fileName.isNotEmpty() && FileSystemManager.isSafeRelativePath(fileName)) {
            val searchDirs = azanDirs + alarmDirs
            for (directory in searchDirs) {
                val direct = File(directory, fileName)
                if (isValidPlayableFile(direct)) return direct
            }

            // Case-insensitive filename match across searchDirs and fallbackFileList
            for (directory in searchDirs) {
                val matched = directory.listFiles()?.firstOrNull { f ->
                    isValidPlayableFile(f) && f.name.equals(fileName, ignoreCase = true)
                }
                if (matched != null) return matched
            }

            fallbackFileList().firstOrNull { f ->
                isValidPlayableFile(f) && f.name.equals(fileName, ignoreCase = true)
            }?.let { return it }
        }

        return null
    }

    /** Playable URI for a reference, or null if blank or the file is gone. */
    fun uriFor(c: Context, ref: String): Uri? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        fileFor(c, trimmed)?.let { return Uri.fromFile(it) }
        return if ("://" in trimmed && !trimmed.startsWith("file://", ignoreCase = true)) {
            runCatching { Uri.parse(trimmed) }.getOrNull()
        } else {
            null
        }
    }
}
