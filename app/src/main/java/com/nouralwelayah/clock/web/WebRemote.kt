package com.nouralwelayah.clock.web

import com.nouralwelayah.clock.Sys
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.core.content.ContextCompat
import com.nouralwelayah.clock.alert.AlertScheduler
import com.nouralwelayah.clock.alert.AlertService
import com.nouralwelayah.clock.alert.AlertState
import com.nouralwelayah.clock.core.CALC_METHODS
import com.nouralwelayah.clock.core.Planner
import com.nouralwelayah.clock.core.Prayer
import com.nouralwelayah.clock.data.Alarm
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.AzanLibrary
import com.nouralwelayah.clock.data.CityFile
import com.nouralwelayah.clock.data.ClockName
import com.nouralwelayah.clock.data.Folder
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.system.FileSystemManager
import com.nouralwelayah.clock.ui.picturesChanged
import com.nouralwelayah.clock.voice.VoiceAssistantService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the Wi-Fi remote needs from the media player (MediaRemoteServer). Called on the main thread. */
interface WebPlayer {
    /** src / playing / paused / title / pos / dur (s) / track / vol (0..21) of the media player. */
    fun webStatus(o: JSONObject)
    /** A player command of the clock page (toggle, next, track, vol ...). Returns "" or an error. */
    fun webCommand(c: String, v: String): String
    /** Plays a file of PrayerClock/music or videos (the rest of the list after it). */
    fun webPlayFile(f: File): String
    /**
     * The Bluetooth media remote's commands for the page's System tab > Media remote (MediaProtocol: list, state,
     * toggle, next, prev, stop, file, yt, folders, browse, pick, full, vol, ytsearch, ytplay, link).
     * Called on the main thread; [reply] once with the answer (it may come later, e.g. a YouTube search).
     */
    fun webMedia(o: JSONObject, reply: (JSONObject) -> Unit)
}

/**
 * Wi-Fi remote: serves the branded Navy & Gold Nour Alwelayah Web Remote application at `/`
 * (`http://<tablet IP>:8080/`) and handles `/api/...` endpoints for Media Transport, Prayer Times,
 * Settings/Azan toggles, Alarms, and the hardened Wi-Fi File Manager (supporting bulk uploads to
 * `/audios`, `/videos`, `/alarms`, `/azan`, `/images`, `/wallpaper` while hiding `/config`).
 */
class WebRemote(private val ctx: Context, private val player: WebPlayer) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(6)
    private val main: Handler? = runCatching { Looper.getMainLooper()?.let { Handler(it) } }.getOrNull()

    private var nsd: android.net.nsd.NsdManager.RegistrationListener? = null

    /** Announces the page on the Wi-Fi (DNS-SD "_prayerclock._tcp"): the phone's Send finds the tablet by it. */
    private fun announce() {
        val m = Sys.svc(ctx, android.net.nsd.NsdManager::class.java) ?: return
        val info = android.net.nsd.NsdServiceInfo().apply {
            serviceName = Store.settings.value.clockName.ifEmpty { "Prayer Clock " + android.os.Build.MODEL }
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val l = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) { Log.i(TAG, "announced as ${i.serviceName}") }
            override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) { Log.w(TAG, "announce failed $e") }
            override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
        }
        runCatching { m.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l); nsd = l }
    }

    /** The clock name changed: announced again under the new name. */
    fun reannounce() {
        nsd?.let { l -> runCatching { Sys.svc(ctx, android.net.nsd.NsdManager::class.java)?.unregisterService(l) } }
        nsd = null
        announce()
    }

    fun start() {
        if (socket != null) return
        FileSystemManager.initialize(ctx)
        announce()
        thread(name = "web-remote", isDaemon = true) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                socket = s
                Log.i(TAG, "listening on port $PORT")
                while (!s.isClosed) {
                    val c = s.accept()
                    pool.execute { serve(c) }
                }
            } catch (e: Exception) {
                if (socket?.isClosed != true) Log.w(TAG, "server stopped", e)
            }
        }
    }

    fun stop() {
        nsd?.let { l -> runCatching { Sys.svc(ctx, android.net.nsd.NsdManager::class.java)?.unregisterService(l) } }
        nsd = null
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    // ================================================== HTTP

    internal class Req(
        val method: String, val path: String, val args: Map<String, String>,
        val headers: Map<String, String>, val body: InputStream,
    ) {
        fun get(k: String) = args[k].orEmpty()
        fun has(k: String) = args.containsKey(k)
        fun hasv(k: String) = args[k].orEmpty().isNotEmpty()
        fun int(k: String, def: Int = 0) = args[k]?.trim()?.toDoubleOrNull()?.toInt() ?: def
        val length: Long get() = headers["content-length"]?.toLongOrNull() ?: 0L
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream(), 65536)
            val out = s.getOutputStream()
            try {
                val first = readLine(input) ?: return
                val parts = first.split(" ")
                if (parts.size < 2) return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val l = readLine(input) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val target = parts[1]
                val path = target.substringBefore('?')
                val args = mutableMapOf<String, String>()
                if ('?' in target) parseForm(target.substringAfter('?'), args)
                val method = parts[0].uppercase()
                val type = headers["content-type"].orEmpty().lowercase()
                val len = headers["content-length"]?.toLongOrNull() ?: 0L
                if (method == "POST" && len in 1..1_000_000) {
                    if (type.startsWith("application/x-www-form-urlencoded")) {
                        val b = ByteArray(len.toInt())
                        readFully(input, b)
                        parseForm(String(b, Charsets.UTF_8), args)
                    } else if (type.startsWith("application/json")) {
                        val b = ByteArray(len.toInt())
                        readFully(input, b)
                        parseJsonBody(String(b, Charsets.UTF_8), args)
                    }
                }
                route(Req(method, path, args, headers, input), out)
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16384) return null
        }
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var o = 0
        while (o < b.size) { val n = i.read(b, o, b.size - o); if (n < 0) break; o += n }
    }

    private fun parseForm(s: String, into: MutableMap<String, String>) {
        s.split('&').filter { it.isNotEmpty() }.forEach { kv ->
            val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
            val v = if ('=' in kv) URLDecoder.decode(kv.substringAfter('='), "UTF-8") else ""
            into[k] = v
        }
    }

    private fun parseJsonBody(s: String, into: MutableMap<String, String>) {
        runCatching {
            val obj = JSONObject(s)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.opt(k)
                if (v != null && v != JSONObject.NULL) {
                    into[k] = v.toString()
                }
            }
        }
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap()) {
        val h = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        h.append("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        h.append("Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n")
        extra.forEach { (k, v) -> h.append("$k: $v\r\n") }
        h.append("\r\n")
        out.write(h.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun sendJson(out: OutputStream, status: Int, d: Any) =
        send(out, status, "application/json; charset=utf-8", d.toString().toByteArray())

    private fun reason(s: Int) = when (s) { 200 -> "OK"; 204 -> "No Content"; 401 -> "Unauthorized"; 404 -> "Not Found"
        429 -> "Too Many Requests"; else -> "Error" }

    private fun result(err: String, info: String = ""): JSONObject = JSONObject().put("ok", err.isEmpty()).apply {
        if (err.isNotEmpty()) put("err", err)
        if (info.isNotEmpty()) put("msg", info)
    }

    // ================================================== PIN (like checkPin() in remote.cpp)

    private var pinFails = 0
    private var pinLockUntil = 0L

    /** 200 ok, 401 wrong PIN, 429 locked for a minute. */
    @Synchronized
    private fun checkPin(given: String): Int {
        val pin = Store.settings.value.webPin
        if (pin.isEmpty()) return 200
        if (System.currentTimeMillis() < pinLockUntil) return 429
        if (given.trim() == pin) { pinFails = 0; return 200 }
        if (++pinFails >= 5) { pinFails = 0; pinLockUntil = System.currentTimeMillis() + 60_000 }
        return 401
    }

    private fun denied(out: OutputStream, st: Int) =
        sendJson(out, st, JSONObject().put("ok", false).put("err", if (st == 429) "locked" else "pin"))

    // ================================================== routes

    private fun route(r: Req, out: OutputStream) {
        if (r.method == "OPTIONS") {
            send(out, 204, "text/plain", ByteArray(0), mapOf("Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*", "Access-Control-Allow-Private-Network" to "true"))
            return
        }
        when (r.path) {
            "/", "/index.html" -> { sendPage(out, false); return }
            "/PTclock.html", "/p4clock.html" -> { sendPage(out, true); return }
            "/api/fs/get" -> { fsGet(r, out); return }
            "/api/fs/put" -> { fsPut(r, out); return }
        }
        if (!r.path.startsWith("/api/")) { send(out, 404, "text/plain", "Not found".toByteArray()); return }
        val (st, d) = api(r) ?: run { send(out, 404, "text/plain", "Not found".toByteArray()); return }
        sendJson(out, st, d)
    }

    /**
     * One /api call of the clock page, for the web page (HTTP) and the clock remote over Bluetooth (the firmware's
     * {"id","p","m","a"} requests, see MediaRemoteServer). Status 200 / 401 (PIN) / 429 (locked); null = unknown.
     */
    fun api(path: String, post: Boolean, args: Map<String, String>): Pair<Int, Any>? =
        api(Req(if (post) "POST" else "GET", path, args, emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))))

    private fun api(r: Req): Pair<Int, Any>? {
        val st = checkPin(r.get("pin"))
        if (st != 200) return st to JSONObject().put("ok", false).put("err", if (st == 429) "locked" else "pin")
        val post = r.method == "POST"
        val d: Any = try {
            when (r.path) {
                "/api/auth" -> JSONObject().put("ok", true).put("need", Store.settings.value.webPin.isNotEmpty())
                "/api/status" -> status()
                // the playlist: files (music, videos), then the saved YouTube videos
                "/api/list" -> {                                  // the page's ↻: /music and /videos read again
                    onMain { MediaLibrary.rescan(ctx) }
                    JSONArray(MediaLibrary.files.value.map { it.title } + MediaLibrary.youTube.value.map { "YouTube: " + it.title })
                }
                "/api/cmd" -> result(onMain { player.webCommand(r.get("c").trim().lowercase(), r.get("v").trim()) } ?: "Busy")
                "/api/media" -> media(r)
                "/api/child-lock" -> childLock(r, post)
                "/api/settings" -> if (post) settingsPost(r) else settingsGet(r.get("lists") != "0")
                "/api/cities" -> cities(r.get("q"))
                "/api/city" -> city(r)
                "/api/geo" -> geoGet()
                "/api/geo/start" -> geoStart(r.get("q"))
                "/api/geo/use" -> geoUse(r)
                "/api/geo/cancel" -> { geoState = "idle"; result("") }
                "/api/alarms" -> alarmsGet()
                "/api/alarm" -> alarmPost(r)
                "/api/alarm/test" -> alarmTest(r)
                "/api/azan/test" -> azanTest(r)
                "/api/time" -> JSONObject().put("ok", true).put("rtc", true)       // the tablet keeps its own time
                "/api/wifi" -> if (post) result("Change the Wi-Fi in the tablet's Wi-Fi Manager") else wifiGet()
                "/api/restart", "/api/maint", "/api/video",
                "/api/ota/status", "/api/ota/check", "/api/ota/update", "/api/ota/base" -> result("Not available on the tablet")
                "/api/fs/list" -> fsList(r)
                "/api/fs/info" -> fsInfo()
                "/api/fs/mkdir" -> fsOp { val p = path(r.get("path")) ?: return@fsOp "Bad path"; if (p.mkdirs()) changed(p).let { "" } else "Could not create it" }
                "/api/fs/delete" -> fsOp { fsDelete(r.get("path")) }
                "/api/fs/move" -> fsOp { fsMoveCopy(r.get("from"), r.get("to"), move = true) }
                "/api/fs/copy" -> fsOp { fsMoveCopy(r.get("from"), r.get("to"), move = false) }
                "/api/fs/play" -> result(fsPlay(r.get("path")))
                "/api/fs/upbegin" -> upBegin(r)
                "/api/fs/up" -> bleUp(r)                           // Bluetooth upload, base64 pieces
                "/api/fs/read" -> bleRead(r)                       // Bluetooth download, base64 pieces
                else -> return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "api ${r.path}", e)
            result(e.message ?: "Error")
        }
        return 200 to d
    }

    // ---- files over Bluetooth (like apiFsUp / apiFsRead in remote.cpp): a = begin (dir, name) | data (off, b64) | end | abort
    private var bUpTarget: File? = null
    private var bUpTmp: File? = null
    private var bUpOut: OutputStream? = null
    private var bUpOff = 0L

    @Synchronized
    private fun bleUpAbort() {
        runCatching { bUpOut?.close() }; bUpOut = null
        bUpTmp?.delete(); bUpTmp = null; bUpTarget = null
    }

    @Synchronized
    private fun bleUp(r: Req): JSONObject {
        when (r.get("a")) {
            "begin" -> {
                bleUpAbort()
                val dir = path(r.get("dir")) ?: return result("Bad folder")
                if (!dir.exists()) dir.mkdirs()
                if (!dir.isDirectory) return result("Folder not found: " + rel(dir))
                val name = r.get("name").replace('\\', '/').substringAfterLast('/').trim()
                if (name.isEmpty() || name == "." || name == "..") return result("Bad file name")
                val t = File(dir, name)
                bUpTarget = t; bUpTmp = File(dir, ".${t.name}.part"); bUpOut = bUpTmp!!.outputStream().buffered(65536); bUpOff = 0
                return result("")
            }
            "data" -> {
                val o = bUpOut ?: return result("No upload in progress")
                if (r.get("off").toLongOrNull() != bUpOff) { bleUpAbort(); return result("Upload out of order") }
                val b = android.util.Base64.decode(r.get("b64"), android.util.Base64.DEFAULT)
                o.write(b); bUpOff += b.size
                return result("")
            }
            "end" -> {
                val o = bUpOut ?: return result("No upload in progress")
                o.close(); bUpOut = null
                val t = bUpTarget!!; val tmp = bUpTmp!!
                bUpTarget = null; bUpTmp = null
                if (t.exists() && !t.delete()) { tmp.delete(); return result("That file is in use") }
                if (!tmp.renameTo(t)) { tmp.delete(); return result("Rename failed") }
                mirrorIfShared(t)
                changed(t)
                return result("")
            }
            "abort" -> { bleUpAbort(); return result("") }
        }
        return result("Unknown action")
    }

    private fun bleRead(r: Req): JSONObject {
        val f = resolveExistingFile(r.get("path")) ?: return result("Not found: " + r.get("path"))
        val off = r.get("off").toLongOrNull() ?: 0L
        val buf = ByteArray(BLE_FILE_CHUNK)
        var n = 0
        java.io.RandomAccessFile(f, "r").use { raf ->
            if (off < raf.length()) { raf.seek(off); n = maxOf(0, raf.read(buf)) }
        }
        return JSONObject().put("ok", true).put("size", f.length())
            .put("b64", android.util.Base64.encodeToString(buf, 0, n, android.util.Base64.NO_WRAP))
            .put("eof", off + n >= f.length())
    }

    /** The status of the clock page (the Bluetooth link pushes it once a second). */
    fun statusJson(): JSONObject = status()

    /** Media remote of the page (System tab): one command of the Bluetooth media remote, its answer (20 s at most). */
    private fun media(r: Req): JSONObject {
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        val myLooper = runCatching { Looper.myLooper() }.getOrNull()
        if (mainLooper != null && myLooper == mainLooper) return result("Busy")
        val o = JSONObject()
        r.args.forEach { (k, v) -> if (k != "pin") o.put(k, v) }
        val res = java.util.concurrent.atomic.AtomicReference<JSONObject?>(null)
        val done = CountDownLatch(1)
        val runBlock = {
            try {
                player.webMedia(o) { if (res.compareAndSet(null, it)) done.countDown() }
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "media", e) }
                if (res.compareAndSet(null, result(e.message ?: "Error"))) done.countDown()
            }
        }
        val h = main
        if (h != null) h.post(runBlock) else runBlock()
        done.await(20, TimeUnit.SECONDS)
        val d = res.get() ?: return result("The tablet did not answer - try again")
        if (!d.has("ok")) d.put("ok", true)
        return d
    }

    /**
     * Serves the Branded Navy & Gold Wi-Fi Web Remote page (`WEB_REMOTE_HTML`) at `/` and `/index.html`.
     */
    private fun sendPage(out: OutputStream, download: Boolean) {
        val pageBytes = WEB_REMOTE_HTML.toByteArray(Charsets.UTF_8)
        send(
            out = out,
            status = 200,
            type = "text/html; charset=utf-8",
            body = pageBytes,
            extra = if (download) mapOf("Content-Disposition" to "attachment; filename=\"NourAlwelayahRemote.html\"") else emptyMap(),
        )
    }

    /** Runs on the main thread (media controller, services) and waits up to 3 s for the result. */
    private fun <T> onMain(block: () -> T): T? {
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        val myLooper = runCatching { Looper.myLooper() }.getOrNull()
        val h = main
        if (h == null || (mainLooper != null && myLooper == mainLooper)) return block()
        var v: T? = null
        val done = CountDownLatch(1)
        h.post { try { v = block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
        return v
    }

    // ================================================== status

    private fun status(): JSONObject {
        val s = Store.settings.value
        val d = JSONObject()
        d.put("fw", "Nour Alwelayah Appliance $VERSION")
        onMain { player.webStatus(d) }
        val a = AlertState.current.value
        if (a != null) {                         // azan / alarm (or their test) ringing
            d.put("src", if (a.isAlarm) "alarm" else "azan").put("playing", true).put("paused", false)
                .put("title", a.title).put("pos", 0).put("dur", 0).put("track", -1)
                .put("vol", s.azanVolume).put("test", a.isTest)
        } else d.put("test", false)
        if (!d.has("src")) d.put("src", "none").put("playing", false).put("paused", false).put("title", "")
            .put("pos", 0).put("dur", 0).put("track", -1)
        d.put("cfg", cfgFingerprint())
        val bm = Sys.svc(ctx, BatteryManager::class.java)
        d.put("bat", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
        d.put("wifi", wifiName())
        d.put("city", s.city)
        d.put("h24", s.use24h)
        val now = ZonedDateTime.now(s.zone())
        d.put("clock", now.format(DateTimeFormatter.ofPattern(if (s.use24h) "HH:mm:ss" else "hh:mm:ss a", Locale.ENGLISH)))
        d.put("date", now.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", Locale.ENGLISH)))
        val today = Planner.day(s, now.toLocalDate(), now.zone)
        d.put("hijri", today.hijri.toString())
        d.put("pt", JSONArray(today.minutes.toList()))
        Planner.nextPrayer(s, now)?.let { (p, t) ->
            val mins = Duration.between(now.withSecond(0).withNano(0), t).toMinutes()
            d.put("next", String.format(Locale.US, "%s %02d:%02d (in %dh %02dm)", p.label, t.hour, t.minute, mins / 60, mins % 60))
            d.put("nexti", Prayer.entries.indexOf(p))
        }
        d.put("childLockEnabled", s.childLockEnabled)
        return d
    }

    private fun cfgFingerprint(): Long {
        val txt = Store.settings.value.toJson().toString() + JSONArray(Store.alarms.value.map { it.toJson() }) +
            MediaLibrary.files.value.size + "/" + MediaLibrary.youTube.value.size
        return txt.hashCode().toLong() and 0xFFFFFFFFL
    }

    @Suppress("DEPRECATION")
    private fun wifiName(): String {
        val w = Sys.svc(ctx.applicationContext, WifiManager::class.java) ?: return ""
        if (!w.isWifiEnabled) return ""
        val ssid = runCatching { w.connectionInfo?.ssid }.getOrNull()?.trim('"').orEmpty()
        return if (ssid.isEmpty() || ssid == "<unknown ssid>") "Wi-Fi" else ssid
    }

    // ================================================== settings

    private fun azPath(name: String): String {
        val clean = name.trim().substringAfterLast('/')
        return if (clean.isBlank() || "://" in name) "" else "/azan/$clean"
    }
    private fun azName(path: String) = path.trim().substringAfterLast('/')

    /** Time zones: the tablet's own, the firmware's list, and the one in use if it is not in the list. */
    private fun tzZones(s: AppSettings): List<Pair<String, String>> {
        val list = mutableListOf("Tablet time zone (${ZoneId.systemDefault().id})" to "")
        list += TZ_LIST
        if (s.zoneId.isNotBlank() && list.none { it.second == s.zoneId } && !isOffset(s.zoneId)) list += s.zoneId to s.zoneId
        return list
    }

    private fun isOffset(z: String) = z.startsWith("+") || z.startsWith("-") || z.startsWith("GMT") || z.startsWith("UTC")

    internal fun settingsGet(lists: Boolean = true): JSONObject {
        val s = Store.settings.value
        val d = JSONObject()
        d.put("city", s.city).put("lat", s.lat).put("lon", s.lon).put("method", s.method).put("hijri", s.hijriAdjust)
        d.put("h24", s.use24h).put("bright", if (s.brightness < 0) 100 else s.brightness)
        d.put("vol", (MediaHubVolume.get() * 21).toInt()).put("azvol", s.azanVolume).put("lead", s.azanLeadSec)
        d.put("fangle", s.fajrAngle).put("mangle", if (s.customAngles) s.maghribAngle else 0.0)
        val defaultAzan = s.azanDefault.ifBlank { s.azanSound.getOrElse(1) { "" } }
        d.put("azfile", azPath(defaultAzan)).put("azfajr", azPath(s.azanSound.getOrElse(0) { "" })).put("azmag", azPath(s.azanSound.getOrElse(2) { "" }))
        d.put("azbg", s.azanPicture).put("clockbg", s.clockPicture)
        d.put("childLockEnabled", s.childLockEnabled)
        d.put("voice_assistant_enabled", s.voiceAssistantEnabled)
        d.put("voice_recognition_mode", AppSettings.normalizeVoiceRecognitionMode(s.voiceRecognitionMode))
        d.put("wake_word_enabled", s.wakeWordEnabled)
        val bleShown = runCatching { ClockName.shown(ctx) }.getOrDefault(s.clockName.ifEmpty { "NourAlwelayah" })
        val hostIp = runCatching { ipAddress() }.getOrDefault("")
        val azFileList = runCatching { AzanLibrary.list(ctx).map { "/azan/$it" } }.getOrDefault(emptyList())
        d.put("bname", s.clockName).put("ble", bleShown).put("host", hostIp).put("board", "Android tablet")
        d.put("defble", "NourAlwelayah").put("defhost", hostIp)
        d.put("keep", true).put("haspin", s.webPin.isNotEmpty()).put("sd", true)
        d.put("tz", if (s.zoneId.isBlank()) "Tablet (${ZoneId.systemDefault().id})" else s.zoneId)
        d.put("az", JSONArray(s.azanOn))
        d.put("azfiles", JSONArray(azFileList))
        val zones = tzZones(s)
        if (lists) {
            d.put("methods", JSONArray(CALC_METHODS.map { it.name }))
            d.put("mang", JSONArray(CALC_METHODS.map { it.fajrAngle }))
            d.put("mmang", JSONArray(CALC_METHODS.map { it.maghribAngle }))
            d.put("tzs", JSONArray(zones.map { it.first }))
        }
        val i = zones.indexOfFirst { it.second == s.zoneId }
        if (i >= 0) d.put("tzsel", i).put("tzval", "")
        else d.put("tzsel", zones.size).put("tzval", offsetText(s.zone()))
        return d
    }

    private fun offsetText(z: ZoneId): String {
        val sec = z.rules.getOffset(java.time.Instant.now()).totalSeconds
        val m = kotlin.math.abs(sec) / 60
        return (if (sec < 0) "-" else "+") + (m / 60) + (if (m % 60 != 0) ":%02d".format(Locale.US, m % 60) else "")
    }

    private fun parseOffset(t: String): ZoneOffset? = runCatching {
        val s = t.trim().removePrefix("UTC").removePrefix("GMT")
        val neg = s.startsWith("-")
        val b = s.trimStart('+', '-')
        val mins = if (':' in b) b.substringBefore(':').toInt() * 60 + b.substringAfter(':').toInt()
                   else (b.toDouble() * 60).toInt()
        ZoneOffset.ofTotalSeconds((if (neg) -mins else mins) * 60)
    }.getOrNull()

    internal fun settingsPost(json: JSONObject): JSONObject {
        val args = mutableMapOf<String, String>()
        parseJsonBody(json.toString(), args)
        return settingsPost(Req("POST", "/api/settings", args, emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))))
    }

    internal fun settingsPost(args: Map<String, Any?>): JSONObject {
        val strArgs = mutableMapOf<String, String>()
        for ((k, v) in args) {
            if (v != null && v != JSONObject.NULL) {
                strArgs[k] = v.toString()
            }
        }
        return settingsPost(Req("POST", "/api/settings", strArgs, emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))))
    }

    internal fun settingsPost(r: Req): JSONObject {
        var s = Store.settings.value
        val err = ""
        if (r.has("newpin")) {
            val p = r.get("newpin").trim()
            if (p.isNotEmpty() && (p.length !in 4..8 || !p.all(Char::isDigit))) return result("PIN must be 4 - 8 digits (or empty = no PIN)")
            Store.saveSettings(s.copy(webPin = p))
            return result("").put("pin", p)
        }
        if (r.has("bname")) {
            val n = r.get("bname").trim()
            if (!ClockName.valid(n)) return result(ClockName.RULE)
            if (n != s.clockName) { s = s.copy(clockName = n); Store.saveSettings(s) }
        }
        if (r.hasv("bright")) {
            Store.saveSettings(Store.settings.value.copy(brightness = r.int("bright").coerceIn(5, 100)))
            if (r.get("live") == "1") return result("")
            s = Store.settings.value
        }
        if (r.has("lat") || r.has("lon")) {
            val la = r.get("lat").toDoubleOrNull(); val lo = r.get("lon").toDoubleOrNull()
            if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) return result("Invalid latitude / longitude")
            s = s.copy(lat = la, lon = lo, locationSet = true)
        }
        if (r.hasv("city")) s = s.copy(city = r.get("city").trim())
        if (r.has("tzsel")) {
            val zones = tzZones(Store.settings.value)
            val i = r.int("tzsel", -1)
            s = when {
                i in zones.indices -> s.copy(zoneId = zones[i].second)
                i == zones.size -> parseOffset(r.get("tzval"))?.let { s.copy(zoneId = it.id) }
                    ?: return result("Invalid UTC offset - use e.g. +3, -5:30 or 5.5")
                else -> return result("On the tablet use a time zone from the list or a UTC offset")
            }
        }
        if (r.hasv("vol")) onMain { player.webCommand("vol", r.get("vol")) }
        if (r.hasv("azvol")) s = s.copy(azanVolume = r.int("azvol").coerceIn(0, 21))
        if (r.hasv("h24")) s = s.copy(use24h = r.int("h24") != 0)
        val on = s.azanOn.toMutableList()
        for (k in 0..2) if (r.hasv("az$k")) on[k] = r.int("az$k") != 0
        s = s.copy(azanOn = on)
        if (r.hasv("azbg")) s = s.copy(azanPicture = r.int("azbg") != 0)
        if (r.hasv("clockbg")) s = s.copy(clockPicture = r.int("clockbg") != 0)
        if (r.hasv("childLockEnabled") || r.hasv("child_lock")) {
            val raw = (if (r.hasv("childLockEnabled")) r.get("childLockEnabled") else r.get("child_lock")).trim().lowercase()
            val locked = raw == "true" || raw == "1" || raw == "on" || raw == "yes"
            s = s.copy(childLockEnabled = locked)
            if (locked) {
                com.nouralwelayah.clock.media.MediaHub.forceClockTab.value = true
            }
        }
        var voiceSettingsChanged = false
        if (r.has("voice_assistant_enabled")) {
            parseFlexibleBoolean(r.get("voice_assistant_enabled"))?.let { enabled ->
                if (s.voiceAssistantEnabled != enabled) voiceSettingsChanged = true
                s = s.copy(voiceAssistantEnabled = enabled)
            }
        }
        if (r.has("wake_word_enabled")) {
            parseFlexibleBoolean(r.get("wake_word_enabled"))?.let { enabled ->
                if (s.wakeWordEnabled != enabled) voiceSettingsChanged = true
                s = s.copy(wakeWordEnabled = enabled)
            }
        }
        if (r.has("voice_recognition_mode")) {
            val normalizedMode = r.get("voice_recognition_mode").trim().lowercase(Locale.ROOT)
            if (normalizedMode in VALID_VOICE_RECOGNITION_MODES) {
                if (s.voiceRecognitionMode != normalizedMode) voiceSettingsChanged = true
                s = s.copy(voiceRecognitionMode = normalizedMode)
            }
        }
        if (r.hasv("lead")) s = s.copy(azanLeadSec = r.int("lead").coerceIn(0, 500))
        if (r.hasv("hijri")) s = s.copy(hijriAdjust = r.int("hijri").coerceIn(-2, 2))
        if (r.hasv("method")) s = s.copy(method = r.int("method").coerceIn(0, CALC_METHODS.size - 1))
        if (r.has("fangle")) {
            val f = r.get("fangle").toDoubleOrNull()
            if (f == null || (f != 0.0 && (f < 14.0 || f > 19.95))) return result("Fajr angle must be 14.0 - 19.9")
            val m = if (r.has("mangle")) r.get("mangle").toDoubleOrNull() ?: -1.0 else -1.0
            if (r.has("mangle") && (m < 0 || (m != 0.0 && (m < 0.8 || m > 9.95)))) return result("Maghrib angle must be 0.833 - 9.9 (0.833 = sunset)")
            s = if (f == 0.0) s.copy(fajrAngle = 0.0, maghribAngle = 0.0)
                else s.copy(fajrAngle = f, maghribAngle = if (m > 0) m else CALC_METHODS[s.methodSafe].maghribAngle)
        }
        val sounds = s.azanSound.toMutableList()
        while (sounds.size < 3) sounds.add("")
        if (r.has("azfile")) {
            val def = azName(r.get("azfile"))
            s = s.copy(azanDefault = def)
            sounds[1] = def
        }
        if (r.has("azfajr")) sounds[0] = azName(r.get("azfajr"))
        if (r.has("azmag")) sounds[2] = azName(r.get("azmag"))
        s = s.copy(azanSound = sounds)
        Store.saveSettings(s)
        if (voiceSettingsChanged) {
            runCatching { VoiceAssistantService.onVoiceSettingsChanged(ctx, Store.settings.value) }
        }
        runCatching { AlertScheduler.reschedule(ctx) }
        return result(err, "Settings saved")
            .put("childLockEnabled", s.childLockEnabled)
            .put("voice_assistant_enabled", s.voiceAssistantEnabled)
            .put("voice_recognition_mode", AppSettings.normalizeVoiceRecognitionMode(s.voiceRecognitionMode))
            .put("wake_word_enabled", s.wakeWordEnabled)
    }

    /**
     * Reads or updates [AppSettings.childLockEnabled] via `GET/POST /api/child-lock`.
     * Accepts JSON `{"enabled": true|false}` or form-encoded `enabled=true|false|1|0`.
     */
    private fun childLock(r: Req, post: Boolean): JSONObject {
        val current = Store.settings.value
        val hasParam = r.hasv("enabled") || r.hasv("childLockEnabled")
        if (!post && !hasParam) {
            return JSONObject()
                .put("ok", true)
                .put("childLockEnabled", current.childLockEnabled)
        }
        val raw = when {
            r.hasv("enabled") -> r.get("enabled").trim().lowercase()
            r.hasv("childLockEnabled") -> r.get("childLockEnabled").trim().lowercase()
            else -> ""
        }
        val nextEnabled = when (raw) {
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> !current.childLockEnabled
        }
        val updated = current.copy(childLockEnabled = nextEnabled)
        Store.saveSettings(updated)
        if (nextEnabled) {
            com.nouralwelayah.clock.media.MediaHub.forceClockTab.value = true
        }
        return JSONObject()
            .put("ok", true)
            .put("childLockEnabled", updated.childLockEnabled)
    }

    // ================================================== cities / search

    private fun cities(q: String): JSONObject {
        val k = q.trim().lowercase()
        val all = CityFile.reload(ctx)
        val m = all.filter { k.isEmpty() || k in it.name.lowercase() }
        return JSONObject().put("list", JSONArray(m.take(100).map {
            JSONObject().put("n", it.name).put("lat", it.lat).put("lon", it.lon).put("tz", it.zone)
        })).put("total", all.size).put("match", m.size).put("bad", CityFile.badLines).put("file", CityFile.label(ctx))
    }

    private fun city(r: Req): JSONObject {
        if (r.get("a") == "del") return result(if (CityFile.delete(ctx, r.get("name"))) "" else "Could not delete it")
        if (r.get("a") != "use") return result("Unknown action")
        val c = CityFile.reload(ctx).firstOrNull { it.name.equals(r.get("name").trim(), ignoreCase = true) }
            ?: return result("City not found in the list")
        Store.saveSettings(Store.settings.value.copy(city = c.name, lat = c.lat, lon = c.lon, zoneId = c.zone, locationSet = true))
        AlertScheduler.reschedule(ctx)
        return result("", "Location: ${c.name}")
    }

    @Volatile private var geoState = "idle"
    @Volatile private var geoMsg = ""
    @Volatile private var geoQuery = ""
    @Volatile private var geoList: List<com.nouralwelayah.clock.data.GeoPlace> = emptyList()

    private fun geoStart(q: String): JSONObject {
        if (q.isBlank()) return result("Enter a city name")
        if (geoState == "busy") return result("Already searching - try again in a moment")
        geoState = "busy"; geoMsg = "Searching..."; geoQuery = q.trim()
        thread {
            try {
                val l = com.nouralwelayah.clock.data.OnlineGeo.search(q, 8)
                geoList = l
                geoMsg = if (l.isEmpty()) "Nothing found" else "${l.size} found"
                geoState = if (l.isEmpty()) "error" else "done"
            } catch (e: Exception) {
                geoMsg = e.message ?: "Search failed"; geoState = "error"
            }
        }
        return result("")
    }

    private fun geoGet(): JSONObject {
        val d = JSONObject().put("state", geoState).put("msg", geoMsg)
        if (geoState == "done") d.put("list", JSONArray(geoList.map { a ->
            JSONObject().put("label", a.label).put("lat", a.lat).put("lon", a.lon).put("tz", a.zone.ifEmpty { "?" })
        }))
        if (geoState == "error") geoState = "idle"
        return d
    }

    private fun geoUse(r: Req): JSONObject {
        val a = geoList.getOrNull(r.int("i", -1)) ?: return result("Search again")
        val name = r.get("name").trim().ifEmpty { a.name.ifEmpty { geoQuery } }
        val s = Store.settings.value
        val zone = if (a.zone.isNotEmpty()) a.zone else s.zoneId
        Store.saveSettings(s.copy(city = name.take(31), lat = a.lat, lon = a.lon, locationSet = true, zoneId = zone))
        AlertScheduler.reschedule(ctx)
        geoState = "idle"
        val listed = CityFile.savedText(CityFile.save(ctx, name.take(31), a.lat, a.lon, zone))
        return result("", (if (a.zone.isNotEmpty()) "Time zone: ${a.zone}" else "time zone not changed - choose it below if needed") + " - $listed")
    }

    // ================================================== alarms (40 slots like the firmware)

    private fun soundPath(ref: String) = AppFolders.refPagePath(ref)

    /** A page path ("/audios/Quran/a.mp3") as an alarm sound reference, "" if it is not a file in NourAlwelayah. */
    private fun pageRef(p: String): String = resolveExistingFile(p)?.let { AppFolders.soundRef(ctx, it) }.orEmpty()

    private fun alarmsGet(): JSONArray {
        val s = Store.settings.value
        val today = Planner.day(s, java.time.LocalDate.now(s.zone()))
        val list = Store.alarms.value
        return JSONArray((0 until maxOf(MAX_ALARMS, list.size)).map { i ->
            val a = list.getOrNull(i) ?: Alarm(id = -1, enabled = false)
            val m = Planner.alarmMinute(a, today)
            JSONObject().put("on", a.enabled && i < list.size).put("hour", a.hour).put("min", a.minute).put("days", a.days)
                .put("rel", a.relPrayer).put("offset", a.relOffset).put("ram", a.ramadan).put("useDate", a.useDate)
                .put("year", a.year).put("month", a.month).put("day", a.day).put("sound", soundPath(a.sound))
                .put("vol", a.volume).put("label", a.label)
                .put("l", a.label.ifBlank { "Alarm ${i + 1}" })
                .put("t", if (m < 0) "--:--" else "%02d:%02d".format(Locale.US, m / 60, m % 60))
                .put("d", if (i < list.size) alarmDesc(a) else "(empty)")
        })
    }

    private fun alarmDesc(a: Alarm): String {
        val rn = listOf("Fajr", "Dhuhr", "Maghrib")
        var s = ""
        if (a.relPrayer in 0..2) {
            s += if (a.relOffset == 0) "At ${rn[a.relPrayer]}"
                 else "${kotlin.math.abs(a.relOffset)} min ${if (a.relOffset < 0) "before" else "after"} ${rn[a.relPrayer]}"
            s += " | "
        }
        s += when {
            a.useDate -> "Once on %04d-%02d-%02d".format(Locale.US, a.year, a.month, a.day)
            a.days == 0 -> "Once (next time)"
            a.days == 0x7F -> "Every day"
            a.days == 0x3E -> "Mon - Fri"
            else -> (0..6).filter { a.days and (1 shl it) != 0 }.joinToString(", ") { Alarm.DAY_NAMES[it] }
        }
        if (a.ramadan && !a.useDate) s += " | Ramadan only"
        s += " | " + when {
            a.sound.isBlank() -> "alarm sound"
            AppFolders.isFolderRef(a.sound) -> AppFolders.refLabel(a.sound)
            else -> "chosen file"
        }
        return s + " | Vol ${a.volume}"
    }

    private fun alarmPost(r: Req): JSONObject {
        val i = r.int("i", -1)
        if (!r.has("i") || i < 0 || i >= maxOf(MAX_ALARMS, Store.alarms.value.size)) return result("No such alarm")
        val list = Store.alarms.value.toMutableList()
        if (r.get("a") == "del") {
            if (i < list.size) list.removeAt(i)
        } else {
            var a = list.getOrNull(i) ?: Alarm(enabled = false)
            if (r.has("on")) a = a.copy(enabled = r.int("on") != 0)
            if (r.has("hour")) a = a.copy(hour = r.int("hour").coerceIn(0, 23))
            if (r.has("min")) a = a.copy(minute = r.int("min").coerceIn(0, 59))
            if (r.has("days")) a = a.copy(days = r.int("days") and 0x7F)
            if (r.has("rel")) a = a.copy(relPrayer = r.int("rel").coerceIn(-1, 2))
            if (r.has("offset")) a = a.copy(relOffset = r.int("offset").coerceIn(-180, 180))
            if (r.has("ram")) a = a.copy(ramadan = r.int("ram") != 0)
            if (r.has("useDate")) a = a.copy(useDate = r.int("useDate") != 0)
            if (r.has("year")) a = a.copy(year = r.int("year").coerceIn(2000, 2099))
            if (r.has("month")) a = a.copy(month = r.int("month").coerceIn(1, 12))
            if (r.has("day")) a = a.copy(day = r.int("day").coerceIn(1, 31))
            if (r.has("sound")) {
                val p = r.get("sound").trim()
                val keep = soundPath(a.sound).isEmpty() && a.sound.isNotBlank() && p.isEmpty()
                if (!keep) a = a.copy(sound = pageRef(p))
            }
            if (r.has("vol")) a = a.copy(volume = r.int("vol").coerceIn(0, 21))
            if (r.has("label")) a = a.copy(label = r.get("label").trim().take(30))
            if (a.useDate) a = a.copy(ramadan = false)
            if (i < list.size) list[i] = a else list += a
        }
        Store.saveAlarms(list)
        AlertScheduler.reschedule(ctx)
        return result("")
    }

    private fun alarmTest(r: Req): JSONObject {
        when (r.get("a")) {
            "stop" -> ctx.startService(AlertService.stopIntent(ctx))
            "vol" -> Unit
            else -> {
                val a = AlertState.current.value
                if (a != null && !a.isTest) return result("An alarm / azan is playing - stop it first")
                val p = r.get("path").trim()
                val ref = pageRef(p).ifEmpty { azName(p) }
                val name = Store.alarms.value.firstOrNull { it.sound == ref }?.label?.ifBlank { null }
                    ?: p.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Alarm" }
                ContextCompat.startForegroundService(ctx, AlertService.alarmTestIntent(ctx, ref, name, r.int("vol", 15)))
            }
        }
        return result("")
    }

    private fun azanTest(r: Req): JSONObject {
        val a = AlertState.current.value
        if (a != null && !a.isTest) return result("An alarm / azan is playing - stop it first")
        val k = if (r.hasv("k")) r.int("k").coerceIn(0, 2) else if (r.int("fajr") != 0) 0 else 1
        val f = r.get("file").trim()
        ContextCompat.startForegroundService(ctx,
            if (f.isNotEmpty()) AlertService.previewIntent(ctx, azName(f)) else AlertService.azanIntent(ctx, k, -1, test = true))
        return result("")
    }

    // ================================================== Wi-Fi

    private fun ipAddress(): String {
        return com.nouralwelayah.clock.localIpv4(ctx)
    }

    private fun wifiGet(): JSONObject {
        val ip = ipAddress()
        val name = wifiName()
        return JSONObject().put("ok", true).put("status", if (ip.isEmpty()) "Not connected" else "Connected to $name, $ip")
            .put("con", ip.isNotEmpty()).put("busy", false).put("saved", name).put("keep", true)
            .put("ip", if (ip.isEmpty()) "" else "$ip:$PORT")
            .put("rtc", "The tablet's own clock (automatic time)").put("host", ip).put("ble", ClockName.shown(ctx)).put("scan", "idle")
    }

    // ================================================== Hardened File System (/audios, /videos, /alarms, /azan, /images, /wallpaper; /config hidden)

    private fun primaryRoot(): File = FileSystemManager.initialize(ctx)

    /**
     * Resolves a relative path against [FileSystemManager.getRootDirectory] while strictly blocking
     * path traversal (`..`) and hiding `/config` ([FileSystemManager.isConfigPath]).
     */
    private fun path(p: String): File? {
        if (!FileSystemManager.isSafeRelativePath(p)) return null
        if (FileSystemManager.isConfigPath(p)) return null
        val root = primaryRoot()
        return FileSystemManager.resolveSafePath(root, p)
    }

    /**
     * Finds an existing file in either the hardened [FileSystemManager] root or shared [AppFolders.root]
     * (when all-files access is available), while strictly rejecting `/config` or path traversal.
     */
    private fun resolveExistingFile(p: String): File? {
        if (!FileSystemManager.isSafeRelativePath(p)) return null
        if (FileSystemManager.isConfigPath(p)) return null
        val primary = FileSystemManager.resolveSafePath(primaryRoot(), p)
        if (primary != null && primary.exists()) return primary
        if (AppFolders.hasAccess(ctx)) {
            val shared = FileSystemManager.resolveSafePath(AppFolders.root(), p)
            if (shared != null && shared.exists()) return shared
        }
        return primary
    }

    /**
     * When shared storage access is granted, mirrors uploaded files from [primaryRoot] into
     * [AppFolders.root] so both storage locations stay synchronized.
     */
    private fun mirrorIfShared(f: File) {
        if (!AppFolders.hasAccess(ctx)) return
        runCatching {
            AppFolders.setup(ctx)
            val relPath = f.canonicalPath.removePrefix(primaryRoot().canonicalPath).trimStart(File.separatorChar, '/')
            if (relPath.isNotEmpty() && !FileSystemManager.isConfigPath(relPath)) {
                val dst = FileSystemManager.resolveSafePath(AppFolders.root(), relPath)
                if (dst != null && dst.canonicalPath != f.canonicalPath) {
                    dst.parentFile?.mkdirs()
                    f.copyTo(dst, overwrite = true)
                    AppFolders.scan(ctx, dst)
                }
            }
        }
    }

    private fun rel(f: File): String {
        val pRoot = runCatching { primaryRoot().canonicalPath }.getOrElse { primaryRoot().path }
        val sRoot = runCatching { AppFolders.root().canonicalPath }.getOrElse { AppFolders.root().path }
        val fPath = runCatching { f.canonicalPath }.getOrElse { f.path }
        val r = when {
            fPath.startsWith(pRoot) -> fPath.removePrefix(pRoot)
            fPath.startsWith(sRoot) -> fPath.removePrefix(sRoot)
            else -> f.path.removePrefix(primaryRoot().path)
        }.replace(File.separatorChar, '/')
        return if (r.isEmpty()) "/" else if (r.startsWith("/")) r else "/$r"
    }

    private fun fsOp(block: () -> String): JSONObject = result(block())

    private fun fsList(r: Req): JSONObject {
        val reqPath = r.get("path").ifBlank { "/" }
        val dir = path(reqPath) ?: return result("Bad path")
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) return result("Not a folder: " + rel(dir))

        val primaryEntries = dir.listFiles()?.filter {
            !it.name.startsWith(".") && !it.name.equals(Folder.CONFIG.dir, ignoreCase = true)
        }.orEmpty()

        val sharedEntries = if (AppFolders.hasAccess(ctx)) {
            AppFolders.setup(ctx)
            FileSystemManager.resolveSafePath(AppFolders.root(), reqPath)
                ?.takeIf { it.isDirectory && it.canonicalPath != dir.canonicalPath }
                ?.listFiles()
                ?.filter { !it.name.startsWith(".") && !it.name.equals(Folder.CONFIG.dir, ignoreCase = true) }
                .orEmpty()
        } else {
            emptyList()
        }

        val dirsByName = linkedMapOf<String, String>()
        val filesByName = linkedMapOf<String, File>()
        for (entry in primaryEntries + sharedEntries) {
            if (entry.isDirectory) {
                dirsByName.putIfAbsent(entry.name.lowercase(), entry.name)
            } else if (entry.isFile) {
                filesByName.putIfAbsent(entry.name.lowercase(), entry)
            }
        }

        val sortedDirs = dirsByName.values.sortedBy { it.lowercase() }
        val sortedFiles = filesByName.values.sortedBy { it.name.lowercase() }

        val d = JSONObject().put("ok", true).put("path", rel(dir))
        d.put("dirs", JSONArray(sortedDirs))
        d.put("files", JSONArray(sortedFiles.take(FS_LIST_MAX).map { JSONObject().put("n", it.name).put("s", it.length()) }))
        if (sortedFiles.size > FS_LIST_MAX) d.put("more", true)
        return d
    }

    private fun fsInfo(): JSONObject {
        val rootDir = primaryRoot()
        val st = StatFs(rootDir.path)
        val total = st.totalBytes.toDouble()
        return JSONObject().put("ok", true).put("total", total).put("used", total - st.availableBytes)
    }

    private fun fsDelete(p: String): String {
        if (!FileSystemManager.isSafeRelativePath(p) || FileSystemManager.isConfigPath(p)) return "Bad path"
        val pFile = FileSystemManager.resolveSafePath(primaryRoot(), p)
        val sFile = if (AppFolders.hasAccess(ctx)) FileSystemManager.resolveSafePath(AppFolders.root(), p) else null
        if (pFile == primaryRoot() || sFile == AppFolders.root()) return "The root folder itself cannot be deleted"

        var existed = false
        var deleted = false
        if (pFile != null && pFile.exists()) {
            existed = true
            if (pFile.deleteRecursively()) deleted = true
        }
        if (sFile != null && sFile.exists()) {
            existed = true
            if (sFile.deleteRecursively()) deleted = true
        }
        if (!existed) return "Not found: $p"
        if (!deleted) return "Could not delete it"
        pFile?.let { changed(it) }
        return ""
    }

    private fun fsMoveCopy(from: String, to: String, move: Boolean): String {
        val a = resolveExistingFile(from) ?: return "Bad path"
        var b = path(to) ?: return "Bad path"
        if (!a.exists()) return "Not found: " + rel(a)
        if (b.isDirectory) b = File(b, a.name)
        if (!move && b.exists()) {
            val base = b.nameWithoutExtension; val ext = if (b.extension.isEmpty()) "" else "." + b.extension
            var n = 1
            do { b = File(b.parentFile, "$base (copy${if (n > 1) " $n" else ""})$ext"); n++ } while (b.exists())
        }
        if (b.exists()) return "Already exists: " + rel(b)
        if (b.path.startsWith(a.path + "/")) return "Cannot put a folder inside itself"
        b.parentFile?.mkdirs()
        val ok = if (move) a.renameTo(b) || (a.copyRecursively(b) && a.deleteRecursively()) else a.copyRecursively(b)
        if (!ok) return if (move) "Move failed" else "Copy failed"
        mirrorIfShared(b)
        changed(a); changed(b)
        return ""
    }

    /** A file of the folders changed: lists and pictures read again, Android indexes it. */
    private fun changed(f: File) {
        if (f.exists() && f.isFile && AppFolders.hasAccess(ctx)) AppFolders.scan(ctx, f)
        val refreshBlock: () -> Unit = {
            MediaLibrary.rescan(ctx)
            picturesChanged()
            AppFolders.refreshed.value++
        }
        val h = main
        if (h != null) h.post(refreshBlock) else runCatching { refreshBlock() }
    }

    private fun fsPlay(p: String): String {
        val f = resolveExistingFile(p) ?: return "Bad path"
        if (!f.isFile) return "Not found: " + rel(f)
        val top = rel(f).trimStart('/').substringBefore('/')
        return when {
            top == Folder.AUDIOS.dir || top == Folder.VIDEOS.dir -> onMain { player.webPlayFile(f) } ?: "Busy"
            AppFolders.isAudio(f) || AppFolders.isVideo(f) -> {
                val ref = AppFolders.soundRef(ctx, f).ifEmpty { Uri.fromFile(f).toString() }
                ContextCompat.startForegroundService(ctx, AlertService.previewIntent(ctx, ref)); ""
            }
            else -> "Not a sound or video file"
        }
    }

    private fun fsGet(r: Req, out: OutputStream) {
        val st = checkPin(r.get("pin"))
        if (st != 200) { denied(out, st); return }
        val f = resolveExistingFile(r.get("path"))
        if (f == null || !f.isFile) { send(out, 404, "text/plain", "Not found".toByteArray()); return }
        val h = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${f.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"${f.name.replace("\"", "")}\"\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(h.toByteArray())
        f.inputStream().use { it.copyTo(out, 65536) }
        out.flush()
    }

    // upload: /api/fs/upbegin (dir, name, PIN) then /api/fs/put (the raw file) within 30 s,
    // OR direct POST /api/fs/put?dir=/audios&name=track.mp3&pin=...
    @Volatile private var putTarget: File? = null
    @Volatile private var putSince = 0L

    private fun upBegin(r: Req): JSONObject {
        val dir = path(r.get("dir")) ?: return result("Bad folder")
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) return result("Folder not found: " + rel(dir))
        val name = r.get("name").replace('\\', '/').substringAfterLast('/').trim()
        if (name.isEmpty() || name == "." || name == "..") return result("Bad file name")
        putTarget = File(dir, name)
        putSince = System.currentTimeMillis()
        return result("")
    }

    private fun fsPut(r: Req, out: OutputStream) {
        val directTarget: File? = if (r.hasv("dir") && r.hasv("name")) {
            val st = checkPin(r.get("pin"))
            if (st != 200) {
                denied(out, st)
                return
            }
            val dir = path(r.get("dir"))?.apply { if (!exists()) mkdirs() }
            val name = r.get("name").replace('\\', '/').substringAfterLast('/').trim()
            if (dir != null && dir.isDirectory && name.isNotEmpty() && name != "." && name != "..") {
                File(dir, name)
            } else null
        } else null

        val target = directTarget ?: putTarget
        val fresh = directTarget != null || (target != null && System.currentTimeMillis() - putSince < 30_000)
        putTarget = null
        if (!fresh || target == null || r.method != "POST") {
            sendJson(out, 200, result("Upload not started - try again"))
            return
        }
        val tmp = File(target.parentFile, ".${target.name}.part")
        var left = r.length
        val err = try {
            tmp.outputStream().buffered(262144).use { o ->
                val buf = ByteArray(65536)
                while (left > 0) {
                    val n = r.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    o.write(buf, 0, n); left -= n
                }
            }
            when {
                left > 0 -> "Upload aborted"
                target.exists() && !target.delete() -> "That file is in use"
                !tmp.renameTo(target) -> "Rename failed"
                else -> ""
            }
        } catch (e: Exception) {
            "Write failed (storage full?)"
        }
        if (err.isNotEmpty()) {
            tmp.delete()
        } else {
            mirrorIfShared(target)
            changed(target)
            Log.i(TAG, "saved ${target.path}")
        }
        sendJson(out, 200, result(err))
    }

    companion object {
        private const val TAG = "WebRemote"
        const val PORT = 8080
        const val SERVICE_TYPE = "_prayerclock._tcp"
        private const val MAX_ALARMS = 40
        private const val FS_LIST_MAX = 500
        private const val BLE_FILE_CHUNK = 3000
        private const val VERSION = "2.0"

        internal val VALID_VOICE_RECOGNITION_MODES = setOf(
            AppSettings.VOICE_MODE_AUTO,
            AppSettings.VOICE_MODE_AR,
            AppSettings.VOICE_MODE_EN,
        )

        /**
         * Safely parses boolean flags sent as JSON booleans (`true`/`false`), numeric flags (`1`/`0`),
         * or string flags (`"true"`/`"false"`, `"1"`/`"0"`). Returns `null` for unrecognized values.
         */
        internal fun parseFlexibleBoolean(raw: Any?): Boolean? {
            return when (raw) {
                is Boolean -> raw
                is Number -> when (raw.toDouble()) {
                    1.0 -> true
                    0.0 -> false
                    else -> null
                }
                is String -> when (raw.trim().lowercase(Locale.ROOT)) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
                else -> null
            }
        }

        /** The firmware's time zone list (storage.cpp TZ_LIST): name -> first IANA zone. */
        val TZ_LIST = listOf(
            "UTC" to "UTC", "London (GMT/BST)" to "Europe/London", "Lagos (+1)" to "Africa/Lagos",
            "Casablanca (+1)" to "Africa/Casablanca", "Paris / Berlin (CET)" to "Europe/Paris",
            "Cairo (EET, DST)" to "Africa/Cairo", "Istanbul (+3)" to "Europe/Istanbul",
            "Riyadh / Makkah (+3)" to "Asia/Riyadh", "Baghdad / Kuwait / Doha (+3)" to "Asia/Baghdad",
            "Amman / Damascus (+3)" to "Asia/Amman", "Tehran (+3:30)" to "Asia/Tehran",
            "Dubai / Muscat (+4)" to "Asia/Dubai", "Kabul (+4:30)" to "Asia/Kabul", "Karachi (+5)" to "Asia/Karachi",
            "Tashkent (+5)" to "Asia/Tashkent", "India (+5:30)" to "Asia/Kolkata", "Dhaka (+6)" to "Asia/Dhaka",
            "Jakarta (+7)" to "Asia/Jakarta", "Kuala Lumpur / Singapore (+8)" to "Asia/Kuala_Lumpur",
            "Tokyo (+9)" to "Asia/Tokyo", "Sydney (AEST/AEDT)" to "Australia/Sydney",
            "New York / Toronto (ET)" to "America/New_York", "Chicago (CT)" to "America/Chicago",
            "Denver (MT)" to "America/Denver", "Los Angeles (PT)" to "America/Los_Angeles",
            "Beirut (EET, DST)" to "Asia/Beirut", "Moscow (+3)" to "Europe/Moscow",
            "Nairobi / Addis Ababa (+3)" to "Africa/Nairobi", "Khartoum / Tripoli / Johannesburg (+2)" to "Africa/Khartoum",
            "Kathmandu (+5:45)" to "Asia/Kathmandu", "Dakar / Accra (+0)" to "Africa/Dakar",
            "Sao Paulo / Buenos Aires (-3)" to "America/Sao_Paulo", "Halifax (AT)" to "America/Halifax",
            "Auckland (NZ)" to "Pacific/Auckland",
        )

        /** The tablet's address for the page, "" without a network. */
        fun address(c: Context): String {
            val ip = com.nouralwelayah.clock.localIpv4(c)
            return if (ip.isEmpty()) "" else "http://$ip:$PORT/"
        }

        /**
         * Branded Navy (#0C1823), Card (#142433), and Gold (#CC9C56) Bilingual Mobile-First Web Remote SPA.
         */
        private val WEB_REMOTE_HTML = """
<!DOCTYPE html>
<html lang="en" dir="ltr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Nour Alwelayah • Web Remote</title>
<style>
  :root {
    --navy: #0C1823;
    --card: #142433;
    --card-elev: #1B3044;
    --gold: #CC9C56;
    --gold-dim: rgba(204, 156, 86, 0.22);
    --text: #F2F5F8;
    --dim: #94A7B8;
    --border: #223648;
    --emerald: #22C55E;
    --danger: #EF4444;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
  body {
    background: var(--navy);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans Arabic", Tahoma, sans-serif;
    min-height: 100vh;
    padding-bottom: 84px;
  }
  .container { max-width: 760px; margin: 0 auto; padding: 14px; }
  .header {
    background: linear-gradient(145deg, var(--card), #101E2C);
    border: 1px solid var(--gold-dim);
    border-radius: 16px;
    padding: 16px;
    margin-bottom: 14px;
    box-shadow: 0 8px 24px rgba(0,0,0,0.35);
  }
  .header-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 12px;
  }
  .brand-title { font-size: 1.2rem; font-weight: 800; color: var(--gold); letter-spacing: 0.3px; }
  .brand-sub { font-size: 0.8rem; color: var(--dim); margin-top: 2px; }
  .lang-btn {
    background: var(--navy);
    color: var(--gold);
    border: 1px solid var(--gold);
    border-radius: 999px;
    padding: 7px 14px;
    font-size: 0.85rem;
    font-weight: 700;
    cursor: pointer;
  }
  .hero-clock-row {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
    padding-top: 10px;
    border-top: 1px solid var(--border);
  }
  .live-clock {
    font-size: 2.1rem;
    font-weight: 800;
    color: var(--text);
    font-variant-numeric: tabular-nums;
  }
  .live-meta { text-align: end; }
  .live-hijri { font-size: 0.95rem; font-weight: 700; color: var(--gold); }
  .live-date { font-size: 0.8rem; color: var(--dim); }
  .pills-row {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 10px;
  }
  .pill {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: var(--navy);
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 4px 11px;
    font-size: 0.78rem;
    color: var(--dim);
  }
  .pill.gold { border-color: var(--gold); color: var(--gold); font-weight: 600; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--emerald); display: inline-block; }

  .card {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 16px;
    margin-bottom: 14px;
  }
  .card-title {
    font-size: 1.05rem;
    font-weight: 700;
    color: var(--gold);
    margin-bottom: 12px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .tab-panel { display: none; }
  .tab-panel.active { display: block; }

  /* Transport & Media */
  .now-playing-box {
    background: var(--navy);
    border: 1px solid var(--gold-dim);
    border-radius: 12px;
    padding: 14px;
    margin-bottom: 14px;
  }
  .np-label { font-size: 0.75rem; color: var(--dim); text-transform: uppercase; letter-spacing: 0.6px; }
  .np-title { font-size: 1.1rem; font-weight: 700; color: var(--text); margin-top: 4px; word-break: break-word; }
  .transport-row {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 10px;
    margin-bottom: 14px;
  }
  .btn {
    background: var(--navy);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 12px 10px;
    font-size: 0.95rem;
    font-weight: 700;
    cursor: pointer;
    text-align: center;
    transition: transform 0.08s, border-color 0.15s;
  }
  .btn:active { transform: scale(0.97); }
  .btn.gold {
    background: var(--gold);
    color: var(--navy);
    border-color: var(--gold);
  }
  .btn.outline-gold {
    background: var(--navy);
    color: var(--gold);
    border-color: var(--gold);
  }
  .btn.danger {
    background: rgba(239, 68, 68, 0.14);
    color: #FCA5A5;
    border-color: rgba(239, 68, 68, 0.4);
  }
  .vol-row {
    display: flex;
    align-items: center;
    gap: 10px;
    background: var(--navy);
    padding: 10px 14px;
    border-radius: 12px;
    border: 1px solid var(--border);
  }
  input[type="range"] {
    flex: 1;
    accent-color: var(--gold);
    height: 6px;
  }
  .track-list {
    max-height: 320px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .track-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    background: var(--navy);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 11px 13px;
    cursor: pointer;
  }
  .track-item.active {
    border-color: var(--gold);
    background: rgba(204, 156, 86, 0.15);
  }
  .track-name { font-size: 0.92rem; font-weight: 600; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

  /* Prayer Times Grid */
  .prayer-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
    gap: 10px;
    margin-bottom: 14px;
  }
  .prayer-card {
    background: var(--navy);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 14px 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .prayer-card.next {
    border: 1.5px solid var(--gold);
    background: rgba(204, 156, 86, 0.12);
  }
  .prayer-name { font-size: 1.1rem; font-weight: 700; color: var(--text); }
  .prayer-time { font-size: 1.25rem; font-weight: 800; color: var(--gold); font-variant-numeric: tabular-nums; }

  /* Settings Form */
  .form-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 0;
    border-bottom: 1px solid var(--border);
  }
  .form-row:last-child { border-bottom: none; }
  .form-label { font-size: 0.92rem; color: var(--text); font-weight: 600; }
  .input, select {
    background: var(--navy);
    color: var(--text);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 9px 12px;
    font-size: 0.92rem;
    min-width: 140px;
  }
  .input:focus, select:focus { outline: none; border-color: var(--gold); }
  .switch {
    position: relative;
    display: inline-block;
    width: 48px;
    height: 26px;
  }
  .switch input { opacity: 0; width: 0; height: 0; }
  .slider {
    position: absolute; cursor: pointer; inset: 0;
    background-color: var(--navy);
    border: 1px solid var(--border);
    transition: .2s; border-radius: 26px;
  }
  .slider:before {
    position: absolute; content: "";
    height: 18px; width: 18px; left: 4px; bottom: 3px;
    background-color: var(--dim);
    transition: .2s; border-radius: 50%;
  }
  input:checked + .slider { background-color: var(--gold); border-color: var(--gold); }
  input:checked + .slider:before { transform: translateX(20px); background-color: var(--navy); }

  /* File Manager */
  .folder-pills {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 14px;
  }
  .folder-pill {
    background: var(--navy);
    color: var(--dim);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 8px 13px;
    font-size: 0.86rem;
    font-weight: 700;
    cursor: pointer;
  }
  .folder-pill.active {
    background: var(--gold);
    color: var(--navy);
    border-color: var(--gold);
  }
  .upload-zone {
    background: var(--navy);
    border: 1.5px dashed var(--gold);
    border-radius: 12px;
    padding: 14px;
    margin-bottom: 14px;
    text-align: center;
  }
  .progress-wrap {
    margin-top: 10px;
    display: none;
  }
  .progress-bar-bg {
    width: 100%;
    height: 8px;
    background: var(--card);
    border-radius: 999px;
    overflow: hidden;
    margin-top: 6px;
  }
  .progress-bar-fill {
    height: 100%;
    width: 0%;
    background: var(--gold);
    transition: width 0.15s;
  }
  .file-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    background: var(--navy);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 10px 12px;
    margin-bottom: 8px;
  }
  .file-info { flex: 1; min-width: 0; }
  .file-name { font-size: 0.9rem; font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .file-size { font-size: 0.75rem; color: var(--dim); margin-top: 2px; }
  .file-actions { display: flex; gap: 6px; }
  .icon-btn {
    background: var(--card);
    color: var(--gold);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 6px 10px;
    font-size: 0.82rem;
    font-weight: 700;
    cursor: pointer;
    text-decoration: none;
  }
  .icon-btn.del { color: #FCA5A5; border-color: rgba(239,68,68,0.35); }

  /* Bottom Navigation */
  .bottom-nav {
    position: fixed;
    bottom: 0; left: 0; right: 0;
    background: #09131C;
    border-top: 1px solid var(--border);
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    height: 68px;
    z-index: 100;
  }
  .nav-item {
    background: none;
    border: none;
    color: var(--dim);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    font-size: 0.78rem;
    font-weight: 700;
    cursor: pointer;
    position: relative;
  }
  .nav-item.active { color: var(--gold); background: var(--card); }
  .nav-item.active::after {
    content: "";
    position: absolute;
    bottom: 0; left: 15%; right: 15%;
    height: 3px;
    background: var(--gold);
    border-radius: 3px 3px 0 0;
  }
  .nav-icon { font-size: 1.2rem; }

  /* Toast & PIN Modal */
  .toast {
    position: fixed;
    bottom: 80px;
    left: 50%;
    transform: translateX(-50%);
    background: var(--gold);
    color: var(--navy);
    font-weight: 800;
    padding: 10px 18px;
    border-radius: 999px;
    box-shadow: 0 6px 20px rgba(0,0,0,0.45);
    display: none;
    z-index: 300;
    font-size: 0.88rem;
  }
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(12, 24, 35, 0.92);
    display: none;
    align-items: center;
    justify-content: center;
    padding: 16px;
    z-index: 400;
  }
  .modal-box {
    background: var(--card);
    border: 1.5px solid var(--gold);
    border-radius: 16px;
    padding: 22px;
    width: 100%;
    max-width: 360px;
    text-align: center;
  }

  /* Child Lock Quick-Action Card */
  .child-lock-card {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 14px 16px;
    margin-bottom: 16px;
    transition: border-color 0.2s, background 0.2s;
  }
  .child-lock-card.locked {
    border-color: var(--gold);
    background: linear-gradient(135deg, #1b2a1e 0%, #162635 100%);
  }
  .child-lock-info {
    flex: 1;
    min-width: 0;
  }
  .child-lock-title {
    font-size: 0.96rem;
    font-weight: 800;
    color: var(--text);
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .child-lock-card.locked .child-lock-title {
    color: var(--gold);
  }
  .child-lock-sub {
    font-size: 0.78rem;
    color: var(--dim);
    margin-top: 3px;
  }
  .child-lock-btn {
    min-width: 148px;
    white-space: nowrap;
  }
</style>
</head>
<body>
<div class="container">
  <!-- Appliance Header -->
  <div class="header">
    <div class="header-top">
      <div>
        <div class="brand-title" data-i18n="appTitle">Nour Alwelayah Appliance</div>
        <div class="brand-sub" data-i18n="appSub">Smart Islamic Clock & Media Console</div>
      </div>
      <button class="lang-btn" id="langBtn" onclick="toggleLang()">العربية</button>
    </div>
    <div class="hero-clock-row">
      <div class="live-clock" id="stClock">--:--:--</div>
      <div class="live-meta">
        <div class="live-hijri" id="stHijri">--</div>
        <div class="live-date" id="stDate">--</div>
      </div>
    </div>
    <div class="pills-row">
      <span class="pill gold"><span class="dot"></span><span id="stNext">--</span></span>
      <span class="pill">📍 <span id="stCity">--</span></span>
      <span class="pill">📶 <span id="stWifi">--</span></span>
      <span class="pill">🔋 <span id="stBat">--</span></span>
    </div>
  </div>

  <!-- Child Lock / قفل الأطفال Quick-Action Card -->
  <div class="child-lock-card" id="childLockCard">
    <div class="child-lock-info">
      <div class="child-lock-title">
        <span id="childLockIcon">🔓</span>
        <span id="childLockStateText">Child Lock: OFF</span>
      </div>
      <div class="child-lock-sub" id="childLockSubText" data-i18n="childLockDesc">
        Locks tablet touchscreen against accidental taps while keeping Clock, Azan & Voice active.
      </div>
    </div>
    <button class="btn outline-gold child-lock-btn" id="childLockBtn" onclick="toggleChildLock()">
      <span id="childLockBtnIcon">🔒</span>
      <span id="childLockBtnLabel">Lock Screen</span>
    </button>
  </div>

  <!-- TAB 1: MEDIA TRANSPORT & PLAYLIST -->
  <div id="tab-media" class="tab-panel active">
    <div class="card">
      <div class="card-title">
        <span data-i18n="mediaControls">Media Transport & Volume</span>
        <span id="stPlayState" class="pill">--</span>
      </div>
      <div class="now-playing-box">
        <div class="np-label" data-i18n="nowPlaying">NOW PLAYING</div>
        <div class="np-title" id="stTrackTitle">--</div>
      </div>
      <div class="transport-row">
        <button class="btn outline-gold" onclick="sendCmd('prev')">⏮ <span data-i18n="prev">Prev</span></button>
        <button class="btn gold" onclick="sendCmd('toggle')">⏯ <span data-i18n="playPause">Play/Pause</span></button>
        <button class="btn outline-gold" onclick="sendCmd('next')">⏭ <span data-i18n="next">Next</span></button>
        <button class="btn danger" onclick="sendCmd('stop')">■ <span data-i18n="stop">Stop</span></button>
      </div>
      <div class="vol-row">
        <span>🔈</span>
        <button class="icon-btn" onclick="stepVol(-1)">−</button>
        <input type="range" id="volSlider" min="0" max="21" value="15" oninput="onVolInput(this.value)">
        <button class="icon-btn" onclick="stepVol(1)">+</button>
        <span id="volVal" style="font-weight:700;color:var(--gold);min-width:28px;text-align:center;">15</span>
      </div>
    </div>

    <div class="card">
      <div class="card-title">
        <span data-i18n="playlist">Appliance Media Library</span>
        <button class="icon-btn" onclick="loadPlaylist()">↻ <span data-i18n="refresh">Refresh</span></button>
      </div>
      <div id="playlistBox" class="track-list"></div>
    </div>
  </div>

  <!-- TAB 2: PRAYER TIMES & AZAN TEST -->
  <div id="tab-prayer" class="tab-panel">
    <div class="card">
      <div class="card-title">
        <span data-i18n="todayPrayers">Today's Prayer Times</span>
      </div>
      <div id="prayerGrid" class="prayer-grid"></div>
    </div>

    <div class="card">
      <div class="card-title">
        <span data-i18n="azanTestTitle">Test Appliance Azan Audio</span>
      </div>
      <div class="transport-row">
        <button class="btn outline-gold" onclick="testAzan(0)">▶ <span data-i18n="fajr">Fajr</span></button>
        <button class="btn outline-gold" onclick="testAzan(1)">▶ <span data-i18n="dhuhr">Dhuhr</span></button>
        <button class="btn outline-gold" onclick="testAzan(2)">▶ <span data-i18n="maghrib">Maghrib</span></button>
        <button class="btn danger" onclick="sendCmd('stop')">■ <span data-i18n="stop">Stop</span></button>
      </div>
    </div>
  </div>

  <!-- TAB 3: SETTINGS & AZAN TOGGLES -->
  <div id="tab-settings" class="tab-panel">
    <div class="card">
      <div class="card-title"><span data-i18n="azanToggles">Automatic Azan Switches</span></div>
      <div class="form-row">
        <span class="form-label" data-i18n="fajrAzan">Fajr Azan</span>
        <label class="switch"><input type="checkbox" id="cfgAz0"><span class="slider"></span></label>
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="dhuhrAzan">Dhuhr / Asr Azan</span>
        <label class="switch"><input type="checkbox" id="cfgAz1"><span class="slider"></span></label>
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="maghribAzan">Maghrib / Isha Azan</span>
        <label class="switch"><input type="checkbox" id="cfgAz2"><span class="slider"></span></label>
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="azanVol">Azan Volume (0 - 21)</span>
        <input type="number" class="input" id="cfgAzVol" min="0" max="21" style="width:90px;min-width:90px;">
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="azanLead">Start Azan Early (sec)</span>
        <input type="number" class="input" id="cfgLead" min="0" max="500" style="width:100px;min-width:100px;">
      </div>
    </div>

    <div class="card">
      <div class="card-title"><span data-i18n="clockLocation">Clock & Location Settings</span></div>
      <div class="form-row">
        <span class="form-label" data-i18n="cityName">City Name</span>
        <input type="text" class="input" id="cfgCity">
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="calcMethod">Calculation Method</span>
        <select id="cfgMethod" class="input"></select>
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="hijriAdj">Hijri Adjustment (-2..+2)</span>
        <input type="number" class="input" id="cfgHijri" min="-2" max="2" style="width:90px;min-width:90px;">
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="use24h">24-Hour Clock</span>
        <label class="switch"><input type="checkbox" id="cfgH24"><span class="slider"></span></label>
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="azanPic">Show Azan Picture</span>
        <label class="switch"><input type="checkbox" id="cfgAzBg"><span class="slider"></span></label>
      </div>
      <div class="form-row">
        <span class="form-label" data-i18n="clockPic">Show Clock Wallpaper</span>
        <label class="switch"><input type="checkbox" id="cfgClockBg"><span class="slider"></span></label>
      </div>
      <div style="margin-top:14px;">
        <button class="btn gold" style="width:100%;" onclick="saveSettings()">💾 <span data-i18n="saveSettings">Save Settings</span></button>
      </div>
    </div>
  </div>

  <!-- TAB 4: FAST WI-FI FILE MANAGER -->
  <div id="tab-files" class="tab-panel">
    <div class="card">
      <div class="card-title">
        <span data-i18n="fileManager">Appliance File Manager</span>
        <span id="fsStorageInfo" class="pill">--</span>
      </div>
      <!-- Public folders only (/config is strictly excluded) -->
      <div class="folder-pills" id="folderPills">
        <button class="folder-pill active" onclick="selectFolder('audios')">♪ /audios</button>
        <button class="folder-pill" onclick="selectFolder('videos')">🎬 /videos</button>
        <button class="folder-pill" onclick="selectFolder('alarms')">🔔 /alarms</button>
        <button class="folder-pill" onclick="selectFolder('azan')">🕌 /azan</button>
        <button class="folder-pill" onclick="selectFolder('images')">🖼 /images</button>
        <button class="folder-pill" onclick="selectFolder('wallpaper')">🌅 /wallpaper</button>
      </div>

      <div class="upload-zone">
        <div style="font-weight:700;color:var(--gold);margin-bottom:8px;" data-i18n="bulkUploadTitle">
          Bulk Upload Files to Selected Folder
        </div>
        <input type="file" id="bulkFileInput" multiple style="display:none;" onchange="uploadSelectedFiles(this.files)">
        <button class="btn gold" onclick="document.getElementById('bulkFileInput').click()">
          ⬆ <span data-i18n="chooseFiles">Choose Files to Upload</span>
        </button>
        <div class="progress-wrap" id="uploadProgressWrap">
          <div id="uploadStatusText" style="font-size:0.82rem;color:var(--gold);">Uploading...</div>
          <div class="progress-bar-bg"><div class="progress-bar-fill" id="uploadProgressFill"></div></div>
        </div>
      </div>

      <div id="fileListBox"></div>
    </div>
  </div>
</div>

<!-- Bottom Navigation Bar -->
<div class="bottom-nav">
  <button class="nav-item active" onclick="switchTab('media', this)">
    <span class="nav-icon">♪</span>
    <span data-i18n="navMedia">Media</span>
  </button>
  <button class="nav-item" onclick="switchTab('prayer', this)">
    <span class="nav-icon">☰</span>
    <span data-i18n="navPrayer">Prayer</span>
  </button>
  <button class="nav-item" onclick="switchTab('settings', this)">
    <span class="nav-icon">⚙</span>
    <span data-i18n="navSettings">Settings</span>
  </button>
  <button class="nav-item" onclick="switchTab('files', this)">
    <span class="nav-icon">📁</span>
    <span data-i18n="navFiles">Files</span>
  </button>
</div>

<!-- PIN Modal -->
<div class="modal-backdrop" id="pinModal">
  <div class="modal-box">
    <div style="font-size:1.2rem;font-weight:800;color:var(--gold);margin-bottom:8px;" data-i18n="pinTitle">Enter Remote PIN</div>
    <div style="font-size:0.85rem;color:var(--dim);margin-bottom:14px;" data-i18n="pinSub">This appliance requires a PIN for remote control.</div>
    <input type="password" id="pinInput" class="input" style="width:100%;text-align:center;font-size:1.2rem;margin-bottom:12px;" placeholder="••••">
    <button class="btn gold" style="width:100%;" onclick="submitPin()" data-i18n="unlock">Unlock</button>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
const I18N = {
  en: {
    appTitle: "Nour Alwelayah Appliance",
    appSub: "Smart Islamic Clock & Media Console",
    mediaControls: "Media Transport & Volume",
    nowPlaying: "NOW PLAYING",
    prev: "Prev",
    playPause: "Play/Pause",
    next: "Next",
    stop: "Stop",
    playlist: "Appliance Media Library",
    refresh: "Refresh",
    todayPrayers: "Today's Prayer Times",
    azanTestTitle: "Test Appliance Azan Audio",
    fajr: "Fajr",
    sunrise: "Sunrise",
    dhuhr: "Dhuhr",
    asr: "Asr",
    sunset: "Sunset",
    maghrib: "Maghrib",
    azanToggles: "Automatic Azan Switches",
    fajrAzan: "Fajr Azan",
    dhuhrAzan: "Dhuhr / Asr Azan",
    maghribAzan: "Maghrib / Isha Azan",
    azanVol: "Azan Volume (0 - 21)",
    azanLead: "Start Azan Early (sec)",
    clockLocation: "Clock & Location Settings",
    cityName: "City Name",
    calcMethod: "Calculation Method",
    hijriAdj: "Hijri Adjustment (-2..+2)",
    use24h: "24-Hour Clock",
    azanPic: "Show Azan Picture",
    clockPic: "Show Clock Wallpaper",
    saveSettings: "Save Settings",
    fileManager: "Appliance File Manager",
    bulkUploadTitle: "Bulk Upload Files to Selected Folder",
    chooseFiles: "Choose Files to Upload",
    navMedia: "Media",
    navPrayer: "Prayer",
    navSettings: "Settings",
    navFiles: "Files",
    pinTitle: "Enter Remote PIN",
    pinSub: "This appliance requires a PIN for remote control.",
    unlock: "Unlock",
    noTrack: "No media playing",
    playing: "Playing",
    paused: "Paused",
    stopped: "Stopped",
    emptyFolder: "No files in this folder yet.",
    childLockOn: "Child Lock: ON",
    childLockOff: "Child Lock: OFF",
    childLockDesc: "Locks tablet touchscreen against accidental taps while keeping Clock, Azan & Voice active.",
    childLockActionLock: "Lock Screen",
    childLockActionUnlock: "Unlock Screen",
    childLockToastOn: "Child Lock enabled on tablet",
    childLockToastOff: "Child Lock disabled on tablet",
    childLockErr: "Unable to reach tablet to toggle Child Lock"
  },
  ar: {
    appTitle: "ساعة نور الولاية الذكية",
    appSub: "لوحة التحكم بالصوتيات وأوقات الصلاة",
    mediaControls: "التحكم بالمشغل ومستوى الصوت",
    nowPlaying: "قيد التشغيل الآن",
    prev: "السابق",
    playPause: "تشغيل/إيقاف",
    next: "التالي",
    stop: "إيقاف",
    playlist: "مكتبة الوسائط على الجهاز",
    refresh: "تحديث",
    todayPrayers: "أوقات الصلاة اليوم",
    azanTestTitle: "تجربة صوت الأذان على الجهاز",
    fajr: "الفجر",
    sunrise: "الشروق",
    dhuhr: "الظهر",
    asr: "العصر",
    sunset: "الغروب",
    maghrib: "المغرب",
    azanToggles: "مفاتيح الأذان التلقائي",
    fajrAzan: "أذان الفجر",
    dhuhrAzan: "أذان الظهر / العصر",
    maghribAzan: "أذان المغرب / العشاء",
    azanVol: "مستوى صوت الأذان (0 - 21)",
    azanLead: "تقديم الأذان (بالثواني)",
    clockLocation: "إعدادات الساعة والموقع",
    cityName: "اسم المدينة",
    calcMethod: "طريقة الحساب",
    hijriAdj: "تعديل التاريخ الهجري (-2..+2)",
    use24h: "نظام 24 ساعة",
    azanPic: "عرض صورة الأذان",
    clockPic: "عرض خلفية الساعة",
    saveSettings: "حفظ الإعدادات",
    fileManager: "مدير ملفات الجهاز",
    bulkUploadTitle: "رفع ملفات متعددة إلى المجلد المحدد",
    chooseFiles: "اختر الملفات للرفع",
    navMedia: "الوسائط",
    navPrayer: "الصلاة",
    navSettings: "الإعدادات",
    navFiles: "الملفات",
    pinTitle: "أدخل الرمز السري PIN",
    pinSub: "يتطلب هذا الجهاز إدخال الرمز السري للتحكم عن بعد.",
    unlock: "فتح القفل",
    noTrack: "لا يوجد ملف قيد التشغيل",
    playing: "يعمل الآن",
    paused: "متوقف مؤقتاً",
    stopped: "متوقف",
    emptyFolder: "لا توجد ملفات في هذا المجلد بعد.",
    childLockOn: "قفل الأطفال: مفعّل",
    childLockOff: "قفل الأطفال: متوقف",
    childLockDesc: "يقفل شاشة لمس الجهاز لمنع لمس الأطفال مع إبقاء الساعة والأذان والمساعد الصوتي قيد التشغيل.",
    childLockActionLock: "تفعيل القفل",
    childLockActionUnlock: "إيقاف القفل",
    childLockToastOn: "تم تفعيل قفل الأطفال على الجهاز",
    childLockToastOff: "تم إيقاف قفل الأطفال على الجهاز",
    childLockErr: "تعذّر الاتصال بالجهاز لتغيير حالة قفل الأطفال"
  }
};

let lang = localStorage.getItem('nw_lang') || 'en';
let pin = localStorage.getItem('nw_pin') || '';
let currentFolder = 'audios';
let lastStatus = null;
let volDebounce = null;
let childLockState = false;
let childLockPending = false;

function t(key) {
  return (I18N[lang] && I18N[lang][key]) || I18N.en[key] || key;
}

function renderChildLockState(enabled) {
  childLockState = !!enabled;
  const card = document.getElementById('childLockCard');
  const icon = document.getElementById('childLockIcon');
  const stateTxt = document.getElementById('childLockStateText');
  const btn = document.getElementById('childLockBtn');
  const btnIcon = document.getElementById('childLockBtnIcon');
  const btnLabel = document.getElementById('childLockBtnLabel');
  if (!card || !stateTxt || !btn) return;

  card.classList.toggle('locked', childLockState);
  icon.textContent = childLockState ? '🔒' : '🔓';
  stateTxt.textContent = childLockState ? t('childLockOn') : t('childLockOff');
  btn.className = 'btn child-lock-btn ' + (childLockState ? 'gold' : 'outline-gold');
  btnIcon.textContent = childLockState ? '🔓' : '🔒';
  btnLabel.textContent = childLockState ? t('childLockActionUnlock') : t('childLockActionLock');
}

async function toggleChildLock() {
  if (childLockPending) return;
  const btn = document.getElementById('childLockBtn');
  const targetState = !childLockState;
  childLockPending = true;
  if (btn) btn.disabled = true;
  try {
    const q = new URLSearchParams();
    if (pin) q.set('pin', pin);
    const url = '/api/child-lock' + (q.toString() ? '?' + q.toString() : '');
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json;charset=UTF-8' },
      body: JSON.stringify({ enabled: targetState })
    });
    if (res.status === 401 || res.status === 429) {
      document.getElementById('pinModal').style.display = 'flex';
      throw new Error('PIN required');
    }
    const data = await res.json();
    if (data && data.ok) {
      renderChildLockState(!!data.childLockEnabled);
      showToast(data.childLockEnabled ? t('childLockToastOn') : t('childLockToastOff'));
    } else {
      showToast((data && data.err) || t('childLockErr'));
    }
  } catch (e) {
    if (e.message !== 'PIN required') {
      showToast(t('childLockErr'));
    }
  } finally {
    childLockPending = false;
    if (btn) btn.disabled = false;
  }
}

function applyLang() {
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
  document.getElementById('langBtn').textContent = lang === 'ar' ? 'English' : 'العربية';
  document.querySelectorAll('[data-i18n]').forEach(el => {
    const k = el.getAttribute('data-i18n');
    el.textContent = t(k);
  });
  renderChildLockState(childLockState);
  if (lastStatus) renderPrayerGrid(lastStatus);
}

function toggleLang() {
  lang = lang === 'ar' ? 'en' : 'ar';
  localStorage.setItem('nw_lang', lang);
  applyLang();
}

function showToast(msg) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.style.display = 'block';
  clearTimeout(window.__toastTimer);
  window.__toastTimer = setTimeout(() => { el.style.display = 'none'; }, 2600);
}

async function apiCall(path, params = {}, isPost = false) {
  const q = new URLSearchParams(params);
  if (pin) q.set('pin', pin);
  const url = isPost ? path : (path + '?' + q.toString());
  const opts = isPost ? {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: q.toString()
  } : { method: 'GET' };
  const res = await fetch(url, opts);
  if (res.status === 401 || res.status === 429) {
    document.getElementById('pinModal').style.display = 'flex';
    throw new Error('PIN required');
  }
  return await res.json();
}

function submitPin() {
  pin = document.getElementById('pinInput').value.trim();
  localStorage.setItem('nw_pin', pin);
  document.getElementById('pinModal').style.display = 'none';
  initAll();
}

function switchTab(name, btn) {
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  document.querySelectorAll('.nav-item').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  if (name === 'settings') loadSettings();
  if (name === 'files') { loadFsInfo(); loadFolderFiles(currentFolder); }
  if (name === 'media') loadPlaylist();
}

function fmtMinutes(m, h24) {
  if (m == null || m < 0) return '--:--';
  const h = Math.floor(m / 60);
  const min = m % 60;
  const mm = min < 10 ? '0' + min : '' + min;
  if (h24) return (h < 10 ? '0' + h : '' + h) + ':' + mm;
  const ampm = h >= 12 ? (lang === 'ar' ? 'م' : 'PM') : (lang === 'ar' ? 'ص' : 'AM');
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return h12 + ':' + mm + ' ' + ampm;
}

function renderPrayerGrid(st) {
  const keys = ['fajr', 'sunrise', 'dhuhr', 'asr', 'sunset', 'maghrib'];
  const pt = st.pt || [];
  const nextIdx = st.nexti;
  const h24 = !!st.h24;
  const box = document.getElementById('prayerGrid');
  box.innerHTML = keys.map((k, idx) => {
    const isNext = idx === nextIdx;
    return '<div class="prayer-card ' + (isNext ? 'next' : '') + '">' +
      '<span class="prayer-name">' + t(k) + '</span>' +
      '<span class="prayer-time">' + fmtMinutes(pt[idx], h24) + '</span>' +
      '</div>';
  }).join('');
}

async function pollStatus() {
  try {
    const st = await apiCall('/api/status');
    lastStatus = st;
    document.getElementById('stClock').textContent = st.clock || '--:--:--';
    document.getElementById('stHijri').textContent = st.hijri || '--';
    document.getElementById('stDate').textContent = st.date || '--';
    document.getElementById('stNext').textContent = st.next || '--';
    document.getElementById('stCity').textContent = st.city || '--';
    document.getElementById('stWifi').textContent = st.wifi || 'Wi-Fi';
    document.getElementById('stBat').textContent = (st.bat >= 0 ? st.bat + '%' : '--');
    document.getElementById('stTrackTitle').textContent = st.title || t('noTrack');
    const stateLabel = st.playing ? t('playing') : (st.paused ? t('paused') : t('stopped'));
    document.getElementById('stPlayState').textContent = stateLabel;
    if (typeof st.vol === 'number' && !volDebounce) {
      document.getElementById('volSlider').value = st.vol;
      document.getElementById('volVal').textContent = st.vol;
    }
    if (typeof st.childLockEnabled === 'boolean' && !childLockPending) {
      renderChildLockState(st.childLockEnabled);
    }
    renderPrayerGrid(st);
  } catch (e) {}
}

async function sendCmd(c, v = '') {
  try {
    const r = await apiCall('/api/cmd', { c, v });
    if (r.err) showToast(r.err);
    pollStatus();
  } catch (e) {}
}

function onVolInput(val) {
  document.getElementById('volVal').textContent = val;
  clearTimeout(volDebounce);
  volDebounce = setTimeout(async () => {
    await sendCmd('vol', String(val));
    volDebounce = null;
  }, 180);
}

function stepVol(delta) {
  const slider = document.getElementById('volSlider');
  const next = Math.max(0, Math.min(21, parseInt(slider.value || '15', 10) + delta));
  slider.value = next;
  onVolInput(next);
}

async function loadPlaylist() {
  try {
    const list = await apiCall('/api/list');
    const box = document.getElementById('playlistBox');
    if (!Array.isArray(list) || list.length === 0) {
      box.innerHTML = '<div style="color:var(--dim);padding:12px;">' + t('emptyFolder') + '</div>';
      return;
    }
    box.innerHTML = list.map((title, idx) =>
      '<div class="track-item" onclick="sendCmd(\'track\', \'' + idx + '\')">' +
      '<span class="track-name">' + escapeHtml(title) + '</span>' +
      '<span style="color:var(--gold);font-weight:800;">▶</span>' +
      '</div>'
    ).join('');
  } catch (e) {}
}

async function testAzan(k) {
  try {
    const r = await apiCall('/api/azan/test', { k: String(k) });
    if (r.err) showToast(r.err);
    else showToast(lang === 'ar' ? 'تم بدء تجربة الأذان' : 'Azan test started');
  } catch (e) {}
}

async function loadSettings() {
  try {
    const d = await apiCall('/api/settings');
    const az = d.az || [true, true, true];
    document.getElementById('cfgAz0').checked = !!az[0];
    document.getElementById('cfgAz1').checked = !!az[1];
    document.getElementById('cfgAz2').checked = !!az[2];
    document.getElementById('cfgAzVol').value = d.azvol ?? 15;
    document.getElementById('cfgLead').value = d.lead ?? 0;
    document.getElementById('cfgCity').value = d.city || '';
    document.getElementById('cfgHijri').value = d.hijri ?? 0;
    document.getElementById('cfgH24').checked = !!d.h24;
    document.getElementById('cfgAzBg').checked = !!d.azbg;
    document.getElementById('cfgClockBg').checked = !!d.clockbg;
    const sel = document.getElementById('cfgMethod');
    if (Array.isArray(d.methods)) {
      sel.innerHTML = d.methods.map((m, i) => '<option value="' + i + '">' + escapeHtml(m) + '</option>').join('');
      sel.value = String(d.method ?? 0);
    }
  } catch (e) {}
}

async function saveSettings() {
  try {
    const params = {
      az0: document.getElementById('cfgAz0').checked ? '1' : '0',
      az1: document.getElementById('cfgAz1').checked ? '1' : '0',
      az2: document.getElementById('cfgAz2').checked ? '1' : '0',
      azvol: document.getElementById('cfgAzVol').value,
      lead: document.getElementById('cfgLead').value,
      city: document.getElementById('cfgCity').value,
      method: document.getElementById('cfgMethod').value,
      hijri: document.getElementById('cfgHijri').value,
      h24: document.getElementById('cfgH24').checked ? '1' : '0',
      azbg: document.getElementById('cfgAzBg').checked ? '1' : '0',
      clockbg: document.getElementById('cfgClockBg').checked ? '1' : '0'
    };
    const r = await apiCall('/api/settings', params, true);
    if (r.err) showToast(r.err);
    else showToast(lang === 'ar' ? 'تم حفظ الإعدادات بنجاح' : 'Settings saved');
    pollStatus();
  } catch (e) {}
}

function selectFolder(folder) {
  currentFolder = folder;
  document.querySelectorAll('.folder-pill').forEach(btn => {
    btn.classList.toggle('active', btn.textContent.includes('/' + folder));
  });
  loadFolderFiles(folder);
}

async function loadFsInfo() {
  try {
    const d = await apiCall('/api/fs/info');
    if (d.ok && d.total > 0) {
      const usedGb = (d.used / (1024 * 1024 * 1024)).toFixed(1);
      const totalGb = (d.total / (1024 * 1024 * 1024)).toFixed(1);
      document.getElementById('fsStorageInfo').textContent = usedGb + ' / ' + totalGb + ' GB';
    }
  } catch (e) {}
}

function fmtSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

async function loadFolderFiles(folder) {
  try {
    const d = await apiCall('/api/fs/list', { path: '/' + folder });
    const box = document.getElementById('fileListBox');
    const files = d.files || [];
    if (files.length === 0) {
      box.innerHTML = '<div style="color:var(--dim);padding:12px;text-align:center;">' + t('emptyFolder') + '</div>';
      return;
    }
    const canPlay = ['audios', 'videos', 'alarms', 'azan'].includes(folder);
    box.innerHTML = files.map(f => {
      const fullPath = '/' + folder + '/' + f.n;
      const dlUrl = '/api/fs/get?path=' + encodeURIComponent(fullPath) + (pin ? '&pin=' + encodeURIComponent(pin) : '');
      return '<div class="file-row">' +
        '<div class="file-info">' +
          '<div class="file-name">' + escapeHtml(f.n) + '</div>' +
          '<div class="file-size">' + fmtSize(f.s || 0) + '</div>' +
        '</div>' +
        '<div class="file-actions">' +
          (canPlay ? '<button class="icon-btn" onclick="playFsFile(\'' + escapeJs(fullPath) + '\')">▶</button>' : '') +
          '<a class="icon-btn" href="' + dlUrl + '" download>⬇</a>' +
          '<button class="icon-btn del" onclick="deleteFsFile(\'' + escapeJs(fullPath) + '\')">🗑</button>' +
        '</div>' +
      '</div>';
    }).join('');
  } catch (e) {}
}

async function playFsFile(path) {
  try {
    const r = await apiCall('/api/fs/play', { path });
    if (r.err) showToast(r.err);
    else showToast(lang === 'ar' ? 'جاري التشغيل على الجهاز' : 'Playing on appliance');
  } catch (e) {}
}

async function deleteFsFile(path) {
  try {
    const r = await apiCall('/api/fs/delete', { path });
    if (r.err) showToast(r.err);
    else {
      showToast(lang === 'ar' ? 'تم حذف الملف' : 'File deleted');
      loadFolderFiles(currentFolder);
      loadFsInfo();
    }
  } catch (e) {}
}

async function uploadSelectedFiles(fileList) {
  if (!fileList || fileList.length === 0) return;
  const files = Array.from(fileList);
  const wrap = document.getElementById('uploadProgressWrap');
  const txt = document.getElementById('uploadStatusText');
  const fill = document.getElementById('uploadProgressFill');
  wrap.style.display = 'block';

  let uploaded = 0;
  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    txt.textContent = (lang === 'ar' ? 'جاري رفع ' : 'Uploading ') + (i + 1) + ' / ' + files.length + ': ' + file.name;
    fill.style.width = Math.round((i / files.length) * 100) + '%';
    try {
      const begin = await apiCall('/api/fs/upbegin', { dir: '/' + currentFolder, name: file.name });
      if (!begin.ok) { showToast(begin.err || 'Upload rejected'); continue; }
      const putQ = new URLSearchParams({ dir: '/' + currentFolder, name: file.name });
      if (pin) putQ.set('pin', pin);
      const res = await fetch('/api/fs/put?' + putQ.toString(), {
        method: 'POST',
        headers: { 'Content-Type': 'application/octet-stream' },
        body: file
      });
      const out = await res.json();
      if (out.ok) uploaded++;
      else showToast(out.err || 'Upload failed');
    } catch (e) {
      showToast('Upload error: ' + file.name);
    }
  }
  fill.style.width = '100%';
  setTimeout(() => { wrap.style.display = 'none'; fill.style.width = '0%'; }, 1200);
  document.getElementById('bulkFileInput').value = '';
  showToast((lang === 'ar' ? 'تم رفع ' : 'Uploaded ') + uploaded + ' / ' + files.length);
  loadFolderFiles(currentFolder);
  loadFsInfo();
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
function escapeJs(s) {
  return String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

async function initAll() {
  applyLang();
  await pollStatus();
  await loadPlaylist();
}

initAll();
setInterval(pollStatus, 2000);
</script>
</body>
</html>
""".trimIndent()
    }
}

/** The media player's volume (0..1), shared with the media screens. */
private object MediaHubVolume { fun get(): Float = com.nouralwelayah.clock.media.MediaHub.volume.value }
