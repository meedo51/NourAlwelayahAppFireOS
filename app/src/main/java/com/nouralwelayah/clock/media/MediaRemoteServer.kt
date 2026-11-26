package com.nouralwelayah.clock.media

import com.nouralwelayah.clock.Sys
import android.net.Uri
import com.nouralwelayah.clock.alert.AlertService
import com.nouralwelayah.clock.alert.AlertState
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.web.WebPlayer
import com.nouralwelayah.clock.web.WebRemote
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.data.MediaEntry
import com.nouralwelayah.clock.data.MediaLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a connected BLE companion remote client.
 */
data class BleClientInfo(
    val address: String,
    val maskedAddress: String,
    val displayName: String,
)

/**
 * Live state of the Nour Alwelayah Appliance BLE GATT Server & Advertiser.
 */
data class BleServerState(
    val hasPermission: Boolean = false,
    val isBluetoothEnabled: Boolean = false,
    val isAdvertising: Boolean = false,
    val advertisedName: String = "NourAlwelayah",
    val connectedClients: List<BleClientInfo> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastRefreshMs: Long = 0L,
)

/**
 * The media player's Bluetooth side (tablet): advertises [MediaProtocol.SERVICE], takes commands from the
 * Media remote on a phone and reports the lists and what is playing. Files are played through MediaService,
 * YouTube through the media player screen (MediaHub). Runs while this device is the media player.
 */
@SuppressLint("MissingPermission")          // started only with the Bluetooth permissions (canRun)
class MediaRemoteServer : Service(), WebPlayer {
    private class Client(val device: BluetoothDevice, onLine: (String) -> Unit, onPageLine: (String) -> Unit) {
        var mtu = 23
        var subscribed = false
        var sending = false
        /** Pieces to notify, with the characteristic (media TX or the clock page's UART TX): one at a time. */
        val queue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
        val lines = LineCollector(onLine)
        // the clock page (Clock remote, PTclock.html) over Nordic UART, like the ESP32 clock
        val pageLines = LineCollector(onPageLine)
        var pageSubscribed = false
        var pageAuth = false
        var lastPush = ""
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val clients = mutableMapOf<String, Client>()
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var lastState = ""
    private var advertising = false
    private var web: WebRemote? = null
    private val uploads by lazy { BleUploads(this) }
    private var hotspotByRemote = false              // the tablet's own Wi-Fi was started by a phone (it stops it)
    private var bluetooth = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MediaLibrary.init(this)
        MediaLibrary.rescan(this)
        createChannel(this)
        val open = PendingIntent.getActivity(this, 7, Intent(this, MediaActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this,
            if (com.nouralwelayah.clock.isTablet(this)) com.nouralwelayah.clock.quietChannel(this) else CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Media remote on")
            .setContentText("Bluetooth: Nour Alwelayah on a phone. Wi-Fi: " + WebRemote.address(this).ifEmpty { "no network" })
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIF_ID, n, fgsType)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }.onFailure {
            runCatching {
                startForeground(NOTIF_ID, n)
            }
        }
        // Wi-Fi remote (PTclock page) - on by setting; Bluetooth remote when Bluetooth can run
        scope.launch {
            Store.settings.map { it.webRemote }.distinctUntilChanged().collect { on ->
                if (on && web == null) web = WebRemote(this@MediaRemoteServer, this@MediaRemoteServer).also { it.start() }
                if (!on) { web?.stop(); web = null }
                if (!on && !bluetooth) stopSelf()
            }
        }
        if (canRun(this)) com.nouralwelayah.clock.data.ClockName.apply(this)    // [NAME] before advertising
        bluetooth = canRun(this) && openServer()
        publishBleState()
        if (!bluetooth && !Store.settings.value.webRemote) { stopSelf(); return }
        // [NAME] the clock name changed: the Bluetooth name and the Wi-Fi announcement follow
        scope.launch {
            Store.settings.map { it.clockName }.distinctUntilChanged().drop(1).collect {
                if (canRun(this@MediaRemoteServer)) com.nouralwelayah.clock.data.ClockName.apply(this@MediaRemoteServer)
                web?.reannounce()
                publishBleState()
                // the BLE advertisement carries the name: restart it so the new name is broadcast without an app
                // restart (setName is async, so wait a moment for the adapter to report the new name first)
                if (bluetooth) { kotlinx.coroutines.delay(1500); reAdvertise() }
            }
        }
        // Bluetooth turned on later (after a restart the app can be up first; Fire OS): start the Bluetooth remote then
        runCatching { registerReceiver(btState, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)) }

        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, MediaService::class.java))).buildAsync()
        future = f
        f.addListener({
            controller = runCatching { f.get() }.getOrNull()?.also { c ->
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = pushState()
                })
            }
            pushState()
        }, ContextCompat.getMainExecutor(this))

        scope.launch { combine(MediaLibrary.files, MediaLibrary.youTube) { _, _ -> }.collect { clients.values.forEach(::sendList) } }
        scope.launch {
            combine(MediaHub.youTube, MediaHub.youTubePlaying, MediaHub.volume, MediaHub.fullScreen) { _, _, _, _ -> }
                .collect { pushState() }
        }
        main.post(object : Runnable {                     // the clock pages' status once a second
            override fun run() { pushPageStatus(); main.postDelayed(this, 1000) }
        })
        main.post(object : Runnable {                     // playing: the position every 2 s
            override fun run() {
                if (controller?.isPlaying == true) pushState()
                main.postDelayed(this, 2000)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_BLE) {
            performBleRefresh()
        } else {
            publishBleState()
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(
            TAG,
            "Foreground service timeout signaled by OS (startId=$startId, fgsType=$fgsType); shutting down server socket cleanly and stopping service."
        )
        runCatching {
            web?.stop()
            web = null
        }
        runCatching {
            val bt = Sys.svc(applicationContext, BluetoothManager::class.java)
            if (advertising) {
                bt.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
            server?.close()
            server = null
            advertising = false
            bluetooth = false
        }
        stopSelf(startId)
    }

    private fun performBleRefresh() {
        publishBleState(isRefreshing = true)
        if (canRun(this)) {
            com.nouralwelayah.clock.data.ClockName.apply(this)
            val bt = Sys.svc(applicationContext, BluetoothManager::class.java)
            // Re-query active GATT connections and prune stale entries
            val activeDevices = runCatching {
                bt?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
            }.getOrDefault(emptyList())
            val activeAddresses = activeDevices.map { it.address }.toSet()
            if (activeAddresses.isNotEmpty()) {
                val stale = clients.keys.filter { it !in activeAddresses }
                stale.forEach { addr ->
                    clients.remove(addr)
                    uploads.drop(addr)
                }
                activeDevices.forEach { dev ->
                    if (!clients.containsKey(dev.address)) {
                        clients[dev.address] = Client(
                            dev,
                            { line -> handle(dev.address, line) },
                            { line -> pageRequest(dev.address, line) },
                        )
                    }
                }
            }
            if (!bluetooth) {
                bluetooth = openServer()
            } else {
                reAdvertise()
            }
        }
        main.postDelayed({ publishBleState(isRefreshing = false) }, 450)
    }

    private fun publishBleState(isRefreshing: Boolean = false) {
        val hasPerm = canRun(this)
        val bt = runCatching { Sys.svc(applicationContext, BluetoothManager::class.java) }.getOrNull()
        val btOn = bt?.adapter?.isEnabled == true
        val advName = com.nouralwelayah.clock.data.ClockName.shown(this)
        val clientList = clients.values.map { client ->
            val addr = client.device.address.orEmpty()
            val masked = maskMacAddress(addr)
            val rawName = if (hasPerm) {
                runCatching { client.device.name?.trim() }.getOrNull().orEmpty()
            } else ""
            BleClientInfo(
                address = addr,
                maskedAddress = masked,
                displayName = rawName.ifEmpty { "Companion Remote" },
            )
        }
        _bleState.value = BleServerState(
            hasPermission = hasPerm,
            isBluetoothEnabled = btOn,
            isAdvertising = bluetooth && advertising && btOn,
            advertisedName = advName,
            connectedClients = clientList,
            isRefreshing = isRefreshing,
            lastRefreshMs = System.currentTimeMillis(),
        )
    }

    private val btState = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> if (!bluetooth && canRun(c)) {
                    com.nouralwelayah.clock.data.ClockName.apply(c)
                    bluetooth = openServer()
                    publishBleState()
                    Log.i(TAG, "Bluetooth on: remote " + if (bluetooth) "started" else "not started")
                }
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF, android.bluetooth.BluetoothAdapter.STATE_OFF -> if (bluetooth) {
                    runCatching { server?.close() }
                    server = null; tx = null; clients.clear()
                    advertising = false; bluetooth = false
                    publishBleState()
                    Log.i(TAG, "Bluetooth off: remote stopped")
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(btState) }
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        val bt = Sys.svc(applicationContext, BluetoothManager::class.java)
        if (advertising) runCatching { bt.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { server?.close() }
        server = null
        clients.clear()
        advertising = false
        bluetooth = false
        publishBleState()
        web?.stop(); web = null
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    // ---- GATT server and advertising ----

    private fun openServer(): Boolean {
        val bt = Sys.svc(applicationContext, BluetoothManager::class.java)
        val adapter = bt.adapter
        if (adapter == null || !adapter.isEnabled) { Log.w(TAG, "Bluetooth off"); return false }
        if (adapter.bluetoothLeAdvertiser == null) { Log.w(TAG, "this device cannot advertise (no BLE peripheral)"); return false }
        val s = bt.openGattServer(this, callback) ?: return false
        val service = BluetoothGattService(MediaProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(MediaProtocol.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        val t = BluetoothGattCharacteristic(MediaProtocol.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        t.addDescriptor(BluetoothGattDescriptor(MediaProtocol.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(t)
        tx = t
        server = s
        s.addService(service)                            // advertising starts in onServiceAdded
        return true
    }

    private fun advertise(withName: Boolean) {
        val adv = Sys.svc(applicationContext, BluetoothManager::class.java).adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(MediaProtocol.SERVICE)).build()
        // scan response: the clock page's UART service (Clock remote finds "clocks" by it). Both 128-bit
        // services do not fit in one packet. The name comes from the tablet's Bluetooth name.
        // Include the tablet's Bluetooth name (the clock name) so phones show it in the scan list. If the name
        // plus the 128-bit UART UUID overflow the 31-byte packet, onStartFailure retries with withName = false.
        val scanResponse = AdvertiseData.Builder().addServiceUuid(ParcelUuid(UART_SVC))
            .setIncludeDeviceName(withName).build()
        nameInAdvert = withName
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    /** Restart advertising so a changed Bluetooth (clock) name is broadcast without an app restart. */
    private fun reAdvertise() {
        val adv = Sys.svc(applicationContext, BluetoothManager::class.java).adapter?.bluetoothLeAdvertiser ?: return
        runCatching { adv.stopAdvertising(advertiseCallback) }
        advertising = false
        main.post { advertise(withName = true) }
    }

    private var nameInAdvert = true
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            publishBleState()
            Log.i(TAG, "advertising")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && nameInAdvert) {
                main.post { advertise(withName = false) }
            } else {
                publishBleState()
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> if (service.uuid == MediaProtocol.SERVICE) {
                        bluetooth = false
                        publishBleState()
                    } else advertise(true)
                    service.uuid == MediaProtocol.SERVICE -> server?.addService(uartService())   // then the clock page's
                    else -> advertise(withName = true)
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    clients[device.address] = Client(device, { line -> handle(device.address, line) },
                        { line -> pageRequest(device.address, line) })
                    Log.i(TAG, "remote connected: ${device.address}")
                } else { clients.remove(device.address); uploads.drop(device.address) }
                publishBleState()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { clients[device.address]?.mtu = mtu }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val data = value?.copyOf() ?: return
            if (characteristic.uuid == MediaProtocol.RX) main.post { clients[device.address]?.lines?.add(data) }
            if (characteristic.uuid == UART_RX) main.post { clients[device.address]?.pageLines?.add(data) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val on = value != null && value.isNotEmpty() && value[0].toInt() != 0
            val page = descriptor.characteristic?.uuid == UART_TX
            main.post {
                val c = clients[device.address] ?: return@post
                if (page) { c.pageSubscribed = on; return@post }
                c.subscribed = on
                if (on) { sendList(c); sendState(c) }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post { clients[device.address]?.let { it.sending = false; pump(it) } }
        }
    }

    // ---- sending ----

    private fun send(c: Client, msg: JSONObject) {
        if (!c.subscribed) return
        val t = tx ?: return
        c.queue.addAll(MediaProtocol.pieces(msg.toString(), c.mtu - 3).map { t to it })
        pump(c)
    }

    @Suppress("DEPRECATION")
    private fun pump(c: Client) {
        if (c.sending) return
        val s = server ?: return
        val next = c.queue.removeFirstOrNull() ?: return
        val (t, piece) = next
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(c.device, t, false, piece) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            t.value = piece
            s.notifyCharacteristicChanged(c.device, t, false)
        }
        if (ok) c.sending = true else main.postDelayed({ pump(c.also { it.queue.addFirst(next) }) }, 50)
    }

    // ---- the clock page (Clock remote) over Bluetooth: the ESP32 firmware's protocol (remote.cpp bleRpc) ----
    //   {"id":5,"p":"/api/status","m":0,"a":{"pin":"1234"}}  ->  {"id":5,"s":200,"d":{...}}
    //   after a request that passed the PIN: {"ev":"status","d":{...}} once a second while it changes

    private val pageApi by lazy { WebRemote(this, this) }          // the Wi-Fi remote's API, not its HTTP server
    private val pageWork = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun uartService(): BluetoothGattService {
        val svc = BluetoothGattService(UART_SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(BluetoothGattCharacteristic(UART_RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
        val t = BluetoothGattCharacteristic(UART_TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        t.addDescriptor(BluetoothGattDescriptor(MediaProtocol.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(t)
        uartTx = t
        return svc
    }
    private var uartTx: BluetoothGattCharacteristic? = null

    private fun pageSend(c: Client, text: String) {
        val t = uartTx ?: return
        if (!c.pageSubscribed) return
        c.queue.addAll(MediaProtocol.pieces(text, minOf(c.mtu - 3, 240)).map { t to it })
        pump(c)
    }

    /** A request of the clock page: answered off the main thread (files, network), sent back on it. */
    private fun pageRequest(address: String, line: String) {
        if (!line.startsWith("{")) return                    // the firmware's text commands: not on the tablet
        pageWork.execute {
            val req = runCatching { JSONObject(line) }.getOrNull() ?: return@execute
            val id = req.optLong("id")
            val path = req.optString("p")
            val post = req.optInt("m") != 0
            val a = req.optJSONObject("a")
            val args = a?.keys()?.asSequence()?.associateWith { k -> a.opt(k)?.toString().orEmpty() } ?: emptyMap()
            val (st, d) = if (path == "/api/hello") {
                val mtu = main.let { clients[address]?.mtu ?: 23 }
                200 to JSONObject().put("ok", true).put("mtu", minOf(mtu, 243)).put("need", Store.settings.value.webPin.isNotEmpty())
                    .put("name", com.nouralwelayah.clock.data.ClockName.shown(this))
                    .put("board", "Android tablet")
            } else pageApi.api(path, post, args) ?: (404 to JSONObject().put("ok", false).put("err", "Unknown request $path"))
            val out = "{\"id\":$id,\"s\":$st,\"d\":$d}"
            main.post {
                val c = clients[address] ?: return@post
                if (st == 200 && path != "/api/hello") c.pageAuth = true
                pageSend(c, out)
            }
        }
    }

    /** Once a second: the status to the clock pages connected over Bluetooth (when it changed). */
    private fun pushPageStatus() {
        val pages = clients.values.filter { it.pageSubscribed && it.pageAuth }
        if (pages.isEmpty()) return
        pageWork.execute {
            val body = runCatching { pageApi.statusJson().toString() }.getOrNull() ?: return@execute
            main.post {
                pages.forEach { c ->
                    if (c.lastPush != body && c.queue.size < 20) { c.lastPush = body; pageSend(c, "{\"ev\":\"status\",\"d\":$body}") }
                }
            }
        }
    }

    private fun listJson(): JSONObject {
        val files = JSONArray(MediaLibrary.files.value.map { JSONObject().put("title", it.title).put("video", it.video) })
        val yt = JSONArray(MediaLibrary.youTube.value.map { JSONObject().put("title", it.title) })
        return JSONObject().put("t", "list").put("files", files).put("yt", yt)
    }

    private fun sendList(c: Client) = send(c, listJson())

    private fun stateJson(): JSONObject {
        val o = JSONObject().put("t", "state").put("vol", MediaHub.volume.value.toDouble())
            .put("full", MediaHub.fullScreen.value)
        val y = MediaHub.youTube.value
        if (y != null) return o.put("kind", "yt").put("i", MediaLibrary.youTube.value.indexOfFirst { it.id == y.id })
            .put("title", y.title).put("playing", MediaHub.youTubePlaying.value)
        val p = controller
        val id = p?.currentMediaItem?.mediaId
        if (p == null || id == null) return o.put("kind", "none").put("title", "").put("playing", false)
        return o.put("kind", "file").put("i", MediaLibrary.files.value.indexOfFirst { it.id == id })
            .put("title", p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty())
            .put("playing", p.isPlaying).put("pos", p.currentPosition)
            .put("dur", if (p.duration == C.TIME_UNSET) 0 else p.duration)
    }

    private fun sendState(c: Client) = send(c, stateJson())

    private fun pushState() {
        if (clients.isEmpty()) return
        val s = stateJson()
        val text = s.toString()
        if (text == lastState) return
        lastState = text
        clients.values.forEach { send(it, s) }
    }

    // ---- commands ----

    private fun handle(address: String, line: String) {
        val c = clients[address] ?: return
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return
        val cmdName = o.optString("cmd").ifEmpty { o.optString("c") }
        if (cmdName == "wifi_setup") {
            val ssid = o.optString("ssid").trim()
            val pass = o.optString("pass")
            Log.i(TAG, "BLE wifi_setup received from $address for SSID=\"$ssid\"")
            com.nouralwelayah.clock.system.WifiConfigurator.connect(this, ssid, pass)
            send(c, JSONObject().put("wifi_status", "connecting"))
            return
        }
        if (cmdName == "azan_test") {
            val prayerIdx = o.optInt("prayer", 0).coerceIn(0, 2)
            ContextCompat.startForegroundService(this, AlertService.azanIntent(this, prayerIdx, -1, test = true))
            send(c, JSONObject().put("t", "msg").put("text", "Azan test started"))
            return
        }
        if (cmdName == "calc_method") {
            val cur = Store.settings.value
            val nextMethod = if (o.has("val")) {
                o.optInt("val", cur.methodSafe).coerceIn(0, com.nouralwelayah.clock.core.CALC_METHODS.lastIndex)
            } else {
                (cur.methodSafe + 1) % com.nouralwelayah.clock.core.CALC_METHODS.size
            }
            Store.saveSettings(cur.copy(method = nextMethod))
            com.nouralwelayah.clock.alert.AlertScheduler.reschedule(this)
            val methodName = com.nouralwelayah.clock.core.CALC_METHODS[nextMethod].name
            send(c, JSONObject().put("t", "method").put("method", nextMethod).put("name", methodName))
            return
        }
        if (cmdName == "hotspotoff") {             // the phone is done: back to the home Wi-Fi
            if (hotspotByRemote) com.nouralwelayah.clock.web.Hotspot.stop()
            hotspotByRemote = false
            return
        }
        if (cmdName == "hotspot") {                // no-router mode: start the tablet's own Wi-Fi, give its details
            val fresh = !com.nouralwelayah.clock.web.Hotspot.on
            if (fresh) hotspotByRemote = true
            com.nouralwelayah.clock.web.Hotspot.start(this) { i, err ->
                val r = JSONObject().put("t", "hotspot").put("fresh", fresh)   // just started: phones see it a bit later
                if (i == null) r.put("err", err)
                else r.put("ssid", i.ssid).put("pass", i.pass).put("ip", i.ip).put("port", WebRemote.PORT)
                    .put("pin", Store.settings.value.webPin)   // the Bluetooth remote may already do everything
                clients[address]?.let { send(it, r) }
            }
            return
        }
        if (cmdName.startsWith("up")) {            // a file sent over Bluetooth (BleUploads)
            uploads.handle(address, o)?.let { send(c, it) }
            return
        }
        if (cmdName == "hello") { MediaLibrary.rescan(this); sendList(c); lastState = ""; sendState(c); return }
        perform(o) { send(c, it) }
    }

    /** A remote command; [reply] answers the remote that asked (null: the clock page's player commands). */
    private fun perform(o: JSONObject, reply: ((JSONObject) -> Unit)?) {
        val p = controller
        val files = MediaLibrary.files.value
        val tubes = MediaLibrary.youTube.value
        val yt = MediaHub.youTube.value
        val cmdName = o.optString("cmd").ifEmpty { o.optString("c") }
        when (cmdName) {
            "toggle" -> when {
                yt != null -> MediaHub.youTubeCommands.tryEmit("toggle")
                p == null -> Unit
                p.isPlaying -> p.pause()
                p.mediaItemCount == 0 && files.isNotEmpty() -> playFile(0)
                else -> { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            }
            "play" -> when {
                yt != null -> if (!MediaHub.youTubePlaying.value) MediaHub.youTubeCommands.tryEmit("toggle")
                p == null -> Unit
                p.mediaItemCount == 0 && files.isNotEmpty() -> playFile(0)
                !p.isPlaying -> { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            }
            "pause" -> when {
                yt != null -> if (MediaHub.youTubePlaying.value) MediaHub.youTubeCommands.tryEmit("toggle")
                p?.isPlaying == true -> p.pause()
            }
            "next" -> if (yt?.isPlaylist == true) MediaHub.youTubeCommands.tryEmit("next")      // inside the playlist
                      else if (yt != null) tubes.getOrNull(tubes.indexOfFirst { it.id == yt.id } + 1)?.let(::playYouTube)
                      else p?.seekToNextMediaItem()
            "prev" -> if (yt?.isPlaylist == true) MediaHub.youTubeCommands.tryEmit("prev")
                      else if (yt != null) tubes.getOrNull(tubes.indexOfFirst { it.id == yt.id } - 1)?.let(::playYouTube)
                      else p?.seekToPrevious()
            "stop" -> {                      // stop everything (including active test/alert) and go back to the clock
                if (AlertState.current.value != null) {
                    startService(AlertService.stopIntent(this))
                }
                MediaHub.youTube.value = null
                MediaHub.fullScreen.value = false
                p?.stop(); p?.clearMediaItems()
                MediaHub.closeScreen.tryEmit(Unit)
            }
            "file" -> playFile(o.optInt("i", -1))
            "folders" -> scope.launch {
                if (reply == null) return@launch
                val all = withContext(Dispatchers.IO) { deviceMedia() } ?: return@launch reply(noMediaPermission())
                val folders = all.groupBy { it.first }.map { (name, l) -> JSONObject().put("name", name).put("n", l.size) }
                    .sortedBy { it.optString("name").lowercase() }
                reply(JSONObject().put("t", "folders").put("list", JSONArray(folders)))
            }
            "browse" -> scope.launch {
                if (reply == null) return@launch
                val folder = o.optString("folder")
                val all = withContext(Dispatchers.IO) { deviceMedia() } ?: return@launch reply(noMediaPermission())
                browsed = all.filter { it.first == folder }.map { it.second }.sortedBy { it.title.lowercase() }
                reply(JSONObject().put("t", "browse").put("folder", folder).put("files",
                    JSONArray(browsed.map { JSONObject().put("title", it.title).put("video", it.video) })))
            }
            "pick" -> playQueue(browsed, o.optInt("i", -1))
            "full" -> MediaHub.fullScreen.value = o.optBoolean("on", true)
            "yt" -> tubes.getOrNull(o.optInt("i", -1))?.let(::playYouTube)
            // YouTube search for the phone (the key is on this tablet): results, then "ytplay" saves + plays one
            "ytsearch" -> scope.launch {
                val q = o.optString("q")
                val r = JSONObject().put("t", "ytresults").put("q", q)
                runCatching { withContext(Dispatchers.IO) { com.nouralwelayah.clock.data.YouTubeSearch.search(Store.settings.value.youTubeKey, q) } }
                    .onSuccess { list ->
                        r.put("list", JSONArray(list.map {
                            JSONObject().put("id", it.id).put("title", it.title).put("ch", it.channel).put("pl", it.playlist)
                                .put("dur", it.duration).put("th", if (it.playlist) it.thumb else "")
                        }))
                    }
                    .onFailure { r.put("err", it.message ?: "Search failed") }
                reply?.invoke(r)
            }
            "ytplay" -> playYouTube(MediaLibrary.addYouTubeFound(o.optString("id"), o.optString("title"), o.optBoolean("pl")))
            "link" -> {
                val url = o.optString("url")
                scope.launch {
                    val e = withContext(Dispatchers.IO) { MediaLibrary.addYouTube(url) }
                    if (e == null) reply?.invoke(JSONObject().put("t", "msg").put("text", "Not a YouTube link"))
                    else playYouTube(e)
                }
            }
            "vol" -> {
                val raw = if (o.has("val")) o.optDouble("val", 1.0) else o.optDouble("v", 1.0)
                val normalized = if (raw > 1.0) (raw / 100.0).toFloat() else raw.toFloat()
                val v = normalized.coerceIn(0f, 1f)
                MediaHub.volume.value = v
                p?.volume = v
            }
        }
    }

    private fun playFile(i: Int) = playQueue(MediaLibrary.files.value, i)

    // ---- Wi-Fi remote (WebRemote, PTclock page): called on the main thread ----

    override fun webStatus(o: JSONObject) {
        o.put("vol", (MediaHub.volume.value * 21).roundToInt())
        val y = MediaHub.youTube.value
        if (y != null) {
            val yi = MediaLibrary.youTube.value.indexOfFirst { it.id == y.id }
            o.put("src", "music").put("playing", MediaHub.youTubePlaying.value).put("paused", !MediaHub.youTubePlaying.value)
                .put("title", "YouTube: " + y.title).put("pos", 0).put("dur", 0)
                .put("track", if (yi < 0) -1 else MediaLibrary.files.value.size + yi)
            return
        }
        val p = controller ?: return
        val item = p.currentMediaItem ?: return
        o.put("src", "music").put("playing", p.isPlaying).put("paused", !p.isPlaying)
            .put("title", item.mediaMetadata.title?.toString().orEmpty())
            .put("pos", p.currentPosition / 1000).put("dur", if (p.duration == C.TIME_UNSET) 0 else p.duration / 1000)
            .put("track", MediaLibrary.files.value.indexOfFirst { it.id == item.mediaId })
    }

    override fun webCommand(c: String, v: String): String {
        val alert = AlertState.current.value
        val ringing = alert != null && !alert.isTest
        val busy = "An alarm / azan is playing - stop it first"
        fun setVol(x: Int) {
            val f = x.coerceIn(0, 21) / 21f
            MediaHub.volume.value = f
            controller?.volume = f
        }
        val now = (MediaHub.volume.value * 21).roundToInt()
        when (c) {
            "toggle", "pp" -> perform(JSONObject().put("c", "toggle"), null)
            "pause" -> if (controller?.isPlaying == true || MediaHub.youTubePlaying.value) perform(JSONObject().put("c", "toggle"), null)
            "play" -> when {
                v.isNotEmpty() -> return webCommand("track", v)
                controller?.isPlaying != true && !MediaHub.youTubePlaying.value -> perform(JSONObject().put("c", "toggle"), null)
            }
            "stop" -> if (alert != null) startService(AlertService.stopIntent(this)) else perform(JSONObject().put("c", "stop"), null)
            "next", "prev" -> if (ringing) return busy else perform(JSONObject().put("c", c), null)
            "track" -> {
                if (ringing) return busy
                val i = v.toIntOrNull() ?: return "Unknown command"
                val files = MediaLibrary.files.value
                // after the files in the page's playlist: the saved YouTube videos
                if (i >= files.size) MediaLibrary.youTube.value.getOrNull(i - files.size)?.let(::playYouTube) ?: return "No such track"
                else playFile(i)
            }
            "vol" -> when (v) {
                "+" -> setVol(now + 1)
                "-" -> setVol(now - 1)
                else -> setVol(v.toIntOrNull() ?: return "Unknown command")
            }
            "vol+", "up" -> setVol(now + 1)
            "vol-", "down" -> setVol(now - 1)
            "snooze" -> if (alert?.isAlarm == true && !alert.isTest) startService(AlertService.snoozeIntent(this))
            "nosnooze" -> Unit
            else -> return "Unknown command"
        }
        return ""
    }

    override fun webMedia(o: JSONObject, reply: (JSONObject) -> Unit) {
        val c = o.optString("c")
        val alert = AlertState.current.value
        when {
            c == "list" -> {
                MediaLibrary.rescan(this)
                reply(listJson().put("ytkey", Store.settings.value.youTubeKey.isNotEmpty()).put("state", stateJson()))
            }
            c == "state" -> reply(stateJson())
            // the page's YouTube key box (PIN-checked like every /api call): "" removes the key
            c == "ytkey" -> {
                val key = o.optString("key").trim()
                Store.saveSettings(Store.settings.value.copy(youTubeKey = key))
                reply(JSONObject().put("t", "msg").put("ytkey", key.isNotEmpty())
                    .put("text", if (key.isEmpty()) "YouTube key removed" else "YouTube key saved"))
            }
            c == "folders" || c == "browse" || c == "ytsearch" -> perform(o, reply)
            // an alarm / azan ringing: only stop, volume and full screen (like the page's player commands)
            alert != null && !alert.isTest && c != "stop" && c != "vol" && c != "full" ->
                reply(JSONObject().put("t", "msg").put("text", "An alarm / azan is playing - stop it first"))
            c == "link" -> scope.launch {
                val e = withContext(Dispatchers.IO) { MediaLibrary.addYouTube(o.optString("url")) }
                if (e == null) reply(JSONObject().put("t", "msg").put("text", "Not a YouTube link"))
                else { playYouTube(e); reply(stateJson()) }
            }
            c in setOf("toggle", "next", "prev", "stop", "file", "yt", "pick", "full", "vol", "ytplay") -> {
                if (c == "stop" && alert != null) startService(AlertService.stopIntent(this))
                else perform(o, null)
                reply(stateJson())
            }
            else -> reply(JSONObject().put("t", "msg").put("text", "Unknown command"))
        }
    }

    override fun webPlayFile(f: File): String {
        if (controller == null) return "The media player is starting - try again"
        MediaLibrary.rescan(this)
        val id = Uri.fromFile(f).toString()
        val i = MediaLibrary.files.value.indexOfFirst { it.id == id }
        if (i >= 0) playFile(i)
        else playQueue(listOf(MediaEntry(id, f.nameWithoutExtension, AppFolders.isVideo(f))), 0)
        return ""
    }

    /** Plays [list] from item [i]; a video is shown on the tablet's screen. */
    private fun playQueue(list: List<MediaEntry>, i: Int) {
        val p = controller ?: return
        if (i !in list.indices) return
        MediaHub.youTube.value = null
        p.setMediaItems(list.map { it.toMediaItem() }, i, 0L)
        p.prepare()
        p.play()
        if (list[i].video) { MediaHub.fullScreen.value = true; openScreen() }
    }

    private fun openScreen() {
        runCatching { startActivity(Intent(this, MediaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The folder browsed last by the remote ("pick" plays from it). */
    private var browsed: List<MediaEntry> = emptyList()

    private fun noMediaPermission() = JSONObject().put("t", "msg")
        .put("text", "On the tablet, allow Prayer Clock to read music and videos (open the app there).")

    /** All audio and video files on the device as (folder, entry), or null without the permission. */
    private fun deviceMedia(): List<Pair<String, MediaEntry>>? {
        if (!canReadMedia(this)) return null
        val out = mutableListOf<Pair<String, MediaEntry>>()
        for ((collection, video) in listOf(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        )) {
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            runCatching {
                contentResolver.query(collection, cols, null, null, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cur.getLong(0))
                        out += (cur.getString(2) ?: "Other") to MediaEntry(uri.toString(), cur.getString(1) ?: "Media file", video)
                    }
                }
            }
        }
        return out
    }

    private fun playYouTube(e: MediaEntry) {
        controller?.pause()
        MediaHub.youTube.value = e
        MediaHub.fullScreen.value = true
        // YouTube plays on screen: bring up the media player (the app is normally on screen on the tablet)
        openScreen()
    }

    private fun MediaEntry.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id).setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()

    companion object {
        private const val TAG = "MediaRemoteServer"
        // Nordic UART, like the ESP32 clocks: Clock remote (PTclock.html over Bluetooth) finds and uses it
        val UART_SVC: java.util.UUID = java.util.UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_RX: java.util.UUID = java.util.UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val UART_TX: java.util.UUID = java.util.UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private const val CHANNEL = "media_remote"
        private const val NOTIF_ID = 43

        /** Bluetooth permissions this needs (Android 12+), to request before starting it. */
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

        /** Reading the device's music and videos (browse from the remote). */
        fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        fun canReadMedia(c: Context): Boolean = mediaPermissions().all {
            ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
        }

        private fun hasLocation(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun canRun(c: Context): Boolean = permissions().all {
            ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
        }

        /** Starts the remotes: Bluetooth (with its permissions) and / or the Wi-Fi remote (setting). */
        fun start(c: Context) {
            Store.init(c)
            syncBleSnapshot(c)
            if (canRun(c) || Store.settings.value.webRemote)
                runCatching { ContextCompat.startForegroundService(c, Intent(c, MediaRemoteServer::class.java)) }
        }

        /**
         * Restarts/refreshes BLE advertising and re-queries active GATT connections.
         */
        fun refreshBle(c: Context) {
            Store.init(c)
            val hasPerm = canRun(c)
            val bt = runCatching { Sys.svc(c.applicationContext, BluetoothManager::class.java) }.getOrNull()
            val btOn = bt?.adapter?.isEnabled == true
            _bleState.value = _bleState.value.copy(
                hasPermission = hasPerm,
                isBluetoothEnabled = btOn,
                advertisedName = com.nouralwelayah.clock.data.ClockName.shown(c),
                isRefreshing = true,
                lastRefreshMs = System.currentTimeMillis(),
            )
            if (hasPerm) {
                val intent = Intent(c, MediaRemoteServer::class.java).apply {
                    action = ACTION_REFRESH_BLE
                }
                runCatching { ContextCompat.startForegroundService(c, intent) }
                    .onFailure { _bleState.value = _bleState.value.copy(isRefreshing = false) }
            } else {
                _bleState.value = _bleState.value.copy(isRefreshing = false)
            }
        }

        /**
         * Ensures [bleState] has up-to-date permission, adapter, and name metadata on composition entry.
         */
        fun syncBleSnapshot(c: Context) {
            val hasPerm = canRun(c)
            val bt = runCatching { Sys.svc(c.applicationContext, BluetoothManager::class.java) }.getOrNull()
            val btOn = bt?.adapter?.isEnabled == true
            val cur = _bleState.value
            _bleState.value = cur.copy(
                hasPermission = hasPerm,
                isBluetoothEnabled = btOn,
                advertisedName = com.nouralwelayah.clock.data.ClockName.shown(c),
            )
        }

        fun maskMacAddress(mac: String): String {
            val parts = mac.split(":")
            return if (parts.size == 6) {
                "${parts[0]}:${parts[1]}:••:••:${parts[4]}:${parts[5]}"
            } else {
                mac
            }
        }

        const val ACTION_REFRESH_BLE = "com.nouralwelayah.clock.action.REFRESH_BLE"

        private val _bleState = MutableStateFlow(BleServerState())
        val bleState: StateFlow<BleServerState> = _bleState.asStateFlow()

        fun stop(c: Context) { c.stopService(Intent(c, MediaRemoteServer::class.java)) }

        private fun createChannel(c: Context) = com.nouralwelayah.clock.ifChannels {
            Sys.svc(c, NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Media remote", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
