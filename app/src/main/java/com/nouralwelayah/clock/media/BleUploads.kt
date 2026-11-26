package com.nouralwelayah.clock.media

import android.content.Context
import android.util.Base64
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.Folder
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.ui.picturesChanged
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/**
 * Files sent over the Bluetooth remote link (stage 3: small files without Wi-Fi). Per phone:
 *   {"c":"upbegin","dir":"/azan","name":"x.mp3","size":n}  -> {"t":"upack","off":0}
 *   {"c":"updata","off":o,"b64":"..."}                     -> {"t":"upack","off":o+bytes}
 *   {"c":"upend"}                                         -> {"t":"updone","name":"x.mp3"}
 *   {"c":"upabort"}                                       -> (nothing)
 * Errors: {"t":"uperr","err":"..."} and the upload is dropped. "dir" = a PrayerClock folder.
 * Written to ".name.part", renamed at the end. Runs on the main thread (pieces of a few KB).
 */
class BleUploads(private val ctx: Context) {
    private class Up(val target: File, val tmp: File, val out: OutputStream, var off: Long, val size: Long)

    private val ups = mutableMapOf<String, Up>()

    /** Handles an upload command of [client]; returns the answer, or null for no answer. */
    fun handle(client: String, o: JSONObject): JSONObject? = try {
        when (o.optString("c")) {
            "upbegin" -> begin(client, o)
            "updata" -> data(client, o)
            "upend" -> end(client)
            "upabort" -> { drop(client); null }
            else -> null
        }
    } catch (e: Exception) {
        drop(client)
        err(e.message ?: "Upload failed")
    }

    /** The phone went away: forget its unfinished file. */
    fun drop(client: String) {
        ups.remove(client)?.let { runCatching { it.out.close() }; it.tmp.delete() }
    }

    private fun err(text: String) = JSONObject().put("t", "uperr").put("err", text)
    private fun ack(off: Long) = JSONObject().put("t", "upack").put("off", off)

    private fun begin(client: String, o: JSONObject): JSONObject {
        drop(client)
        val dirName = o.optString("dir").trim('/').substringBefore('/')
        val folder = Folder.entries.firstOrNull { it.dir == dirName } ?: return err("Unknown folder: /$dirName")
        val name = o.optString("name").replace('\\', '/').substringAfterLast('/').trim()
        if (name.isEmpty() || name == "." || name == ".." || name.startsWith(".")) return err("Bad file name")
        val dir = AppFolders.dir(ctx, folder)
        val size = o.optLong("size", -1)
        if (size > 0 && dir.usableSpace < size + 1_000_000) return err("The tablet's storage is full")
        val target = File(dir, name)
        val tmp = File(dir, ".$name.part")
        ups[client] = Up(target, tmp, tmp.outputStream().buffered(65536), 0, size)
        return ack(0)
    }

    private fun data(client: String, o: JSONObject): JSONObject {
        val up = ups[client] ?: return err("No upload in progress")
        val off = o.optLong("off", -1)
        if (off != up.off) { drop(client); return err("Upload out of order") }
        val bytes = Base64.decode(o.optString("b64"), Base64.DEFAULT)
        up.out.write(bytes)
        up.off += bytes.size
        return ack(up.off)
    }

    private fun end(client: String): JSONObject {
        val up = ups.remove(client) ?: return err("No upload in progress")
        up.out.close()
        if (up.size >= 0 && up.off != up.size) { up.tmp.delete(); return err("File incomplete (${up.off} of ${up.size} bytes)") }
        if (up.target.exists() && !up.target.delete()) { up.tmp.delete(); return err("That file is in use") }
        if (!up.tmp.renameTo(up.target)) { up.tmp.delete(); return err("Could not save the file") }
        AppFolders.scan(ctx, up.target)
        MediaLibrary.rescan(ctx)
        picturesChanged()
        return JSONObject().put("t", "updone").put("name", up.target.name)
    }
}
