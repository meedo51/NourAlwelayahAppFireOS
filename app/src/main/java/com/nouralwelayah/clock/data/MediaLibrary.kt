package com.nouralwelayah.clock.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** One entry of the media player's lists: a file on the device (audio or video) or a YouTube video. */
data class MediaEntry(val id: String, val title: String, val video: Boolean) {
    val isYouTube: Boolean get() = id.startsWith(YT) || id.startsWith(YTL)
    /** A YouTube playlist (plays all its videos in order). */
    val isPlaylist: Boolean get() = id.startsWith(YTL)
    /** The video id, or the playlist id for a playlist. */
    val youTubeId: String get() = if (isPlaylist) id.removePrefix(YTL) else id.removePrefix(YT)
    val uri: Uri get() = Uri.parse(id)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("video", video)

    companion object {
        const val YT = "yt:"
        const val YTL = "ytl:"
        fun fromJson(o: JSONObject) = MediaEntry(o.getString("id"), o.optString("title"), o.optBoolean("video"))
    }
}

/**
 * The media player's files and YouTube videos. Everything in PrayerClock/music and PrayerClock/videos is
 * listed first by itself. Picked files stay where they are (videos are too big to copy);
 * the app keeps read permission for them, so they still play after a reboot.
 */
object MediaLibrary {
    private const val PREFS = "media"
    private lateinit var appContext: Context
    private val _added = MutableStateFlow<List<MediaEntry>>(emptyList())      // picked with + Files / + Folder
    private val _inFolders = MutableStateFlow<List<MediaEntry>>(emptyList())  // PrayerClock/music + videos
    private val _files = MutableStateFlow<List<MediaEntry>>(emptyList())      // both: folders first
    private val _youTube = MutableStateFlow<List<MediaEntry>>(emptyList())
    val files: StateFlow<List<MediaEntry>> get() = _files
    val youTube: StateFlow<List<MediaEntry>> get() = _youTube

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _added.value = load("files")
        _youTube.value = load("youtube")
        rescan(context)
    }

    /** Reads NourAlwelayah/audios and NourAlwelayah/videos again (files copied in or deleted meanwhile). */
    fun rescan(c: Context) {
        val found = (AppFolders.files(c, Folder.AUDIOS) + AppFolders.files(c, Folder.VIDEOS))
            .filter { AppFolders.isAudio(it) || AppFolders.isVideo(it) }
            .map { MediaEntry(Uri.fromFile(it).toString(), it.nameWithoutExtension, AppFolders.isVideo(it)) }
        _inFolders.value = found
        combine()
    }

    /** A file from the music / videos folder: remove it there, not in the app. */
    fun inFolder(e: MediaEntry): Boolean = _inFolders.value.any { it.id == e.id }

    private fun combine() { _files.value = _inFolders.value + _added.value }

    private fun prefs(): android.content.SharedPreferences {
        val storageCtx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val um = appContext.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            if (um != null && !um.isUserUnlocked) {
                appContext.createDeviceProtectedStorageContext()
            } else {
                appContext
            }
        } else {
            appContext
        }
        return storageCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun load(key: String): List<MediaEntry> = runCatching {
        val a = JSONArray(prefs().getString(key, "[]"))
        List(a.length()) { MediaEntry.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<MediaEntry>) =
        prefs().edit().putString(key, JSONArray(list.map { it.toJson() }).toString()).apply()

    private fun setFiles(list: List<MediaEntry>) { _added.value = list; save("files", list); combine() }
    private fun setYouTube(list: List<MediaEntry>) { _youTube.value = list; save("youtube", list) }

    /** Files picked with OpenMultipleDocuments. Returns how many were added. */
    fun addFiles(c: Context, uris: List<Uri>): Int {
        val new = uris.mapNotNull { u ->
            runCatching { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val type = c.contentResolver.getType(u).orEmpty()
            if (!type.startsWith("audio/") && !type.startsWith("video/")) return@mapNotNull null
            MediaEntry(u.toString(), displayName(c, u) ?: "Media file", type.startsWith("video/"))
        }.filter { n -> _added.value.none { it.id == n.id } }
        setFiles(_added.value + new)
        return new.size
    }

    /** Every audio / video file in a folder picked with OpenDocumentTree (not its sub-folders). */
    fun addFolder(c: Context, tree: Uri): Int {
        runCatching { c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = mutableListOf<MediaEntry>()
        c.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val type = cur.getString(2).orEmpty()
                if (!type.startsWith("audio/") && !type.startsWith("video/")) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
                found += MediaEntry(uri.toString(), cur.getString(1) ?: "Media file", type.startsWith("video/"))
            }
        }
        val new = found.sortedBy { it.title.lowercase() }.filter { n -> _added.value.none { it.id == n.id } }
        setFiles(_added.value + new)
        return new.size
    }

    fun removeFile(e: MediaEntry) = setFiles(_added.value.filter { it.id != e.id })

    /**
     * Adds a YouTube link (watch, youtu.be, shorts, embed, live) or playlist (a link with list=). Null if it is
     * neither. Network: call off the main thread.
     */
    fun addYouTube(link: String): MediaEntry? {
        playlistId(link)?.let { pl ->
            _youTube.value.firstOrNull { it.isPlaylist && it.youTubeId == pl }?.let { return it }
            val e = MediaEntry(MediaEntry.YTL + pl, oEmbedTitle("https://www.youtube.com/playlist?list=$pl")
                ?.let { "$it (playlist)" } ?: "YouTube playlist", video = true)
            setYouTube(_youTube.value + e)
            return e
        }
        val id = youTubeId(link) ?: return null
        _youTube.value.firstOrNull { it.youTubeId == id }?.let { return it }
        val e = MediaEntry(MediaEntry.YT + id, youTubeTitle(id) ?: "YouTube video $id", video = true)
        setYouTube(_youTube.value + e)
        return e
    }

    /** A video or playlist chosen in the YouTube search: saved (if new) with the title the search gave. */
    fun addYouTubeFound(id: String, title: String, playlist: Boolean): MediaEntry {
        val key = (if (playlist) MediaEntry.YTL else MediaEntry.YT) + id
        _youTube.value.firstOrNull { it.id == key }?.let { return it }
        val e = MediaEntry(key, if (playlist) "$title (playlist)" else title, video = true)
        setYouTube(_youTube.value + e)
        return e
    }

    fun removeYouTube(e: MediaEntry) = setYouTube(_youTube.value.filter { it.id != e.id })

    /** The playlist id of a link with list= (not YouTube's own "mix" lists, which only work in its app). */
    fun playlistId(text: String): String? {
        if (!text.contains("youtu")) return null
        val m = Regex("[?&]list=([A-Za-z0-9_-]{12,})").find(text) ?: return null
        return m.groupValues[1].takeIf { !it.startsWith("RD") }
    }

    fun youTubeId(text: String): String? {
        val m = Regex("""(?:youtu\.be/|youtube(?:-nocookie)?\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})""")
            .find(text) ?: return null
        return m.groupValues[1]
    }

    /** The video's title from YouTube's oEmbed service, or null without network. */
    private fun youTubeTitle(id: String): String? = oEmbedTitle("https://www.youtube.com/watch?v=$id")

    private fun oEmbedTitle(page: String): String? = runCatching {
        val json = URL("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(page, "UTF-8"))
            .openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
            .getInputStream().bufferedReader().use { it.readText() }
        JSONObject(json).optString("title").ifBlank { null }
    }.getOrNull()

    private fun displayName(c: Context, u: Uri): String? = runCatching {
        c.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
}
