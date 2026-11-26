package com.nouralwelayah.clock.alert

import com.nouralwelayah.clock.Sys
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nouralwelayah.clock.R
import com.nouralwelayah.clock.core.AzanPrayer
import com.nouralwelayah.clock.core.fmtMinutes
import com.nouralwelayah.clock.data.ALARM_MAX_RING_SEC
import com.nouralwelayah.clock.data.AzanLibrary
import com.nouralwelayah.clock.data.SNOOZE_MIN
import com.nouralwelayah.clock.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What is ringing now (shown by AlertActivity and the Clock screen). */
data class ActiveAlert(
    val title: String, val subtitle: String, val isAlarm: Boolean, val isTest: Boolean,
    val azan: Int = -1,              // azan (incl. its test): 0 Fajr, 1 Dhuhr, 2 Maghrib; -1 = alarm / sound preview
)

object AlertState {
    internal val _current = MutableStateFlow<ActiveAlert?>(null)
    val current: StateFlow<ActiveAlert?> get() = _current

    // A video alarm: the service's player shows its picture on AlertActivity's surface while that
    // screen is up; without it (screen off) the sound simply plays on.
    internal val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> get() = _videoSize
    private var videoPlayer: MediaPlayer? = null

    // what plays now, for the tablet's mini player: position / length (ms), volume 0..21
    @Volatile internal var soundPlayer: MediaPlayer? = null
    internal val _volume = MutableStateFlow(15)
    val volume: StateFlow<Int> get() = _volume

    /** Position and length of the azan / alarm sound in ms (0, 0 when unknown). */
    fun progress(): Pair<Int, Int> = runCatching { soundPlayer?.let { it.currentPosition to it.duration } }.getOrNull() ?: (0 to 0)

    internal val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> get() = _paused

    /** Pause / resume the ringing azan / alarm (the P4 mini player's play button). */
    fun togglePause() {
        val mp = soundPlayer ?: return
        runCatching {
            if (mp.isPlaying) { mp.pause(); _paused.value = true } else { mp.start(); _paused.value = false }
        }
    }

    /** Volume of the ringing azan / alarm (0..21), like the P4's mini player slider. */
    fun setVolume(v: Int) {
        val x = v.coerceIn(0, 21)
        _volume.value = x
        runCatching { soundPlayer?.setVolume(x / 21f, x / 21f) }
    }
    private var display: SurfaceHolder? = null

    /** Called by AlertActivity when its video surface appears (holder) or goes away (null). */
    fun setDisplay(holder: SurfaceHolder?) {
        display = holder
        runCatching { videoPlayer?.setDisplay(holder) }
    }

    internal fun setVideoPlayer(mp: MediaPlayer?) {
        videoPlayer = mp
        _videoSize.value = mp?.let { it.videoWidth to it.videoHeight }
        if (mp != null) runCatching { mp.setDisplay(display) }
    }
}

/**
 * Plays the azan or an alarm sound as a foreground service, with a full-screen notification.
 * Azan: plays the file once. Alarm: loops for up to ALARM_MAX_RING_SEC, can be snoozed.
 */
class AlertService : Service() {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    // Fire OS 5 (before Android 8): the old audio focus call
    private var focusOld = false
    private val oldFocus = AudioManager.OnAudioFocusChangeListener { }
    // tablet: the alarm volume before the azan / alarm turned it up (-1 = not changed)
    private var savedAlarmVol = -1
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isAlarm = false
    private var alarmId = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_SNOOZE -> {
                if (isAlarm && alarmId >= 0) {
                    Store.snoozeUntil = System.currentTimeMillis() + SNOOZE_MIN * 60_000L
                    Store.snoozeAlarmId = alarmId
                    AlertScheduler.reschedule(this)
                }
                stopAll(); return START_NOT_STICKY
            }
            ACTION_AZAN -> startAzan(intent)
            ACTION_ALARM -> startAlarm(intent)
            else -> { if (player == null) stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun startAzan(intent: Intent) {
        val s = Store.settings.value
        val rawAzanIdx = intent.getIntExtra(EXTRA_AZAN, 0)
        val k = rawAzanIdx.coerceIn(0, 2)
        val test = intent.getBooleanExtra(EXTRA_TEST, false)
        val pm = intent.getIntExtra(EXTRA_PRAYER_MIN, -1)
        val name = AzanPrayer.entries[k].prayer.label
        val preview = if (test) intent.getStringExtra(EXTRA_SOUND) else null
        // an alarm's test (its editor, the web page): its name and its own volume
        val alarmTest = preview != null && intent.hasExtra(EXTRA_TITLE)
        val sub = if (alarmTest) "Alarm test" else if (test) "Azan test" else "$name prayer time ${fmtMinutes(pm, s.use24h)}"
        isAlarm = false; alarmId = -1
        // Per-prayer custom Azan first, then global selected Azan (azanDefault), else (in play) the phone alarm sound
        val sounds = when {
            alarmTest -> listOfNotNull(preview)
            !preview.isNullOrBlank() -> listOf(preview) + s.azanCandidatesFor(rawAzanIdx)
            else -> s.azanCandidatesFor(rawAzanIdx)
        }
        begin(ActiveAlert(if (alarmTest) intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Alarm" }
                          else if (preview != null) "Azan" else name, sub, isAlarm = alarmTest, isTest = test,
            azan = if (preview != null) -1 else k),
            sounds, if (alarmTest) intent.getIntExtra(EXTRA_VOLUME, s.azanVolume) else s.azanVolume, loop = false)
    }

    private fun startAlarm(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val a = Store.alarms.value.firstOrNull { it.id == id }
        if (a == null) {                       // deleted meanwhile: must still enter the foreground once
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(ActiveAlert("Alarm", "", true, true)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            stopAll(); return
        }
        isAlarm = true; alarmId = id
        val now = java.time.LocalTime.now(Store.settings.value.zone())
        val sub = fmtMinutes(now.hour * 60 + now.minute, Store.settings.value.use24h)
        // like the firmware: the alarm's file plays once, to its end; only the device's own (short) alarm tone
        // repeats, for at most ALARM_MAX_RING_SEC
        begin(ActiveAlert(a.label.ifBlank { "Alarm" }, sub, isAlarm = true, isTest = false), listOf(a.sound), a.volume, loop = false)
    }

    private fun begin(alert: ActiveAlert, sounds: List<String>, volume: Int, loop: Boolean) {
        // Strict Azan / Alarm Preemption: synchronously abort any active microphone capture, STT, TTS, or YouTube playback
        com.nouralwelayah.clock.voice.VoiceAssistantService.abortVoiceIoImmediately()
        com.nouralwelayah.clock.ui.pauseYouTubePlayback()
        releasePlayer()
        handler.removeCallbacksAndMessages(null)
        AlertState._current.value = alert
        val notification = buildNotification(alert)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (wakeLock == null) {
            wakeLock = Sys.svc(applicationContext, PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrayerClock:alert").apply { acquire(15 * 60_000L) }
        }
        // tablet: the clock comes to the front (screen on) and its mini player shows the azan / alarm;
        // phone: the full-screen notification opens AlertActivity when locked, this covers the unlocked case
        if (com.nouralwelayah.clock.isTablet(this)) runCatching {
            com.nouralwelayah.clock.wakeScreen(this)
            startActivity(Intent(this, com.nouralwelayah.clock.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } else if (!alert.isTest) runCatching { startActivity(AlertActivity.intent(this)) }
        play(sounds, volume, loop, alert.isAlarm)
    }

    private fun play(sounds: List<String>, volume: Int, loop: Boolean, alarm: Boolean) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(if (alarm) AudioAttributes.CONTENT_TYPE_SONIFICATION else AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val v = (volume.coerceIn(0, 21) / 21f)
        AlertState._volume.value = volume.coerceIn(0, 21)
        // pauses the media player (or other apps) while the azan / alarm sounds
        val am = Sys.svc(applicationContext, AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
                .also { am.requestAudioFocus(it) }
        else @Suppress("DEPRECATION") {   // Fire OS 5
            am.requestAudioFocus(oldFocus, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            focusOld = true
        }
        // tablet = the clock: the azan volume (0-21) is the real loudness, like the firmware, not a share of
        // the device's alarm volume (the Fire's is 6 / 25 out of the box) - full alarm volume while it plays
        if (com.nouralwelayah.clock.isTablet(this) && savedAlarmVol < 0) runCatching {
            val was = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            savedAlarmVol = was
        }

        val nonBlankCandidates = sounds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (ref in nonBlankCandidates) {
            val file = AzanLibrary.fileFor(this, ref)
            if (file != null && file.exists() && file.canRead() && file.length() > 0L) {
                var mp: MediaPlayer? = null
                try {
                    Log.i(AZAN_PLAYBACK_TAG, "Playing Azan file: ${file.absolutePath}")
                    mp = MediaPlayer()
                    mp.setAudioAttributes(attrs)
                    mp.setDataSource(file.absolutePath)
                    mp.isLooping = loop
                    mp.setVolume(v, v)
                    mp.setOnCompletionListener { if (!loop) stopAll() }
                    mp.setOnErrorListener { _, _, _ -> stopAll(); true }
                    mp.prepare()
                    if (mp.videoWidth > 0 && mp.videoHeight > 0) AlertState.setVideoPlayer(mp)
                    mp.start()
                    player = mp
                    AlertState.soundPlayer = mp
                    AlertState._paused.value = false
                    return
                } catch (e: Exception) {
                    runCatching { mp?.release() }
                    Log.e(AZAN_PLAYBACK_TAG, "Azan playback failed; falling back to default alarm", e)
                }
            } else if ("://" in ref && !ref.startsWith("file://", ignoreCase = true)) {
                val uri = AzanLibrary.uriFor(this, ref)
                if (uri != null) {
                    var mp: MediaPlayer? = null
                    try {
                        Log.i(AZAN_PLAYBACK_TAG, "Playing Azan URI: $uri")
                        mp = MediaPlayer()
                        mp.setAudioAttributes(attrs)
                        mp.setDataSource(this, uri)
                        mp.isLooping = loop
                        mp.setVolume(v, v)
                        mp.setOnCompletionListener { if (!loop) stopAll() }
                        mp.setOnErrorListener { _, _, _ -> stopAll(); true }
                        mp.prepare()
                        if (mp.videoWidth > 0 && mp.videoHeight > 0) AlertState.setVideoPlayer(mp)
                        mp.start()
                        player = mp
                        AlertState.soundPlayer = mp
                        AlertState._paused.value = false
                        return
                    } catch (e: Exception) {
                        runCatching { mp?.release() }
                        Log.e(AZAN_PLAYBACK_TAG, "Azan playback failed; falling back to default alarm", e)
                    }
                }
            } else {
                Log.e(
                    AZAN_PLAYBACK_TAG,
                    "Azan playback failed; falling back to default alarm",
                    java.io.FileNotFoundException("Azan file not found or unreadable for ref: $ref")
                )
            }
        }

        // Only fall back to default alarm sound if no valid custom or built-in Azan file exists
        if (defaultUri != null) {
            var mp: MediaPlayer? = null
            try {
                mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, defaultUri)
                // the device's alarm tone (no file, or the file is missing) is short: an alarm repeats it, 5 min max
                val tone = alarm
                val repeat = loop || tone
                if (tone) handler.postDelayed({ stopAll() }, ALARM_MAX_RING_SEC * 1000L)
                mp.isLooping = repeat
                mp.setVolume(v, v)
                mp.setOnCompletionListener { if (!repeat) stopAll() }
                mp.setOnErrorListener { _, _, _ -> stopAll(); true }
                mp.prepare()
                if (mp.videoWidth > 0 && mp.videoHeight > 0) AlertState.setVideoPlayer(mp)
                mp.start()
                player = mp
                AlertState.soundPlayer = mp
                AlertState._paused.value = false
                return
            } catch (e: Exception) {
                runCatching { mp?.release() }
                Log.w(TAG, "cannot play $defaultUri", e)
            }
        }
        // nothing playable: keep the notification for a minute so the alert is still seen
        handler.postDelayed({ stopAll() }, 60_000L)
    }

    private fun buildNotification(a: ActiveAlert): Notification {
        createChannels(this)
        val full = PendingIntent.getActivity(this, 3, AlertActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 4, Intent(this, AlertService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val tablet = com.nouralwelayah.clock.isTablet(this)
        val b = NotificationCompat.Builder(this,
            if (tablet) com.nouralwelayah.clock.quietChannel(this) else if (a.isTest) CHANNEL_TEST else CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(a.title)
            .setContentText(a.subtitle)
            .setCategory(if (a.isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (tablet) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(full)
            .addAction(0, "Stop", stop)
        if (!a.isTest && !tablet) b.setFullScreenIntent(full, true)
        if (tablet) b.setContentIntent(PendingIntent.getActivity(this, 6,
            Intent(this, com.nouralwelayah.clock.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (a.isAlarm) {
            val snooze = PendingIntent.getService(this, 5, Intent(this, AlertService::class.java).setAction(ACTION_SNOOZE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Snooze $SNOOZE_MIN min", snooze)
        }
        return b.build()
    }

    private fun releasePlayer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            focus?.let { Sys.svc(applicationContext, AudioManager::class.java).abandonAudioFocusRequest(it) }
        @Suppress("DEPRECATION")
        if (focusOld) Sys.svc(applicationContext, AudioManager::class.java).abandonAudioFocus(oldFocus)
        focus = null
        focusOld = false
        if (savedAlarmVol >= 0) runCatching {      // the device's own alarm volume again
            Sys.svc(applicationContext, AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVol, 0)
        }
        savedAlarmVol = -1
        AlertState.setVideoPlayer(null)
        AlertState.soundPlayer = null
        AlertState._paused.value = false
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        AlertState._current.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        AlertState._current.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlertService"
        private const val AZAN_PLAYBACK_TAG = "AzanPlayback"
        const val CHANNEL_ALERT = "alerts"
        const val CHANNEL_TEST = "tests"
        private const val NOTIF_ID = 42
        const val ACTION_AZAN = "com.nouralwelayah.clock.AZAN"
        const val ACTION_ALARM = "com.nouralwelayah.clock.ALARM"
        const val ACTION_STOP = "com.nouralwelayah.clock.STOP"
        const val ACTION_SNOOZE = "com.nouralwelayah.clock.SNOOZE"
        private const val EXTRA_AZAN = "azan"
        private const val EXTRA_TEST = "test"
        private const val EXTRA_PRAYER_MIN = "prayer_min"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_SOUND = "sound"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_VOLUME = "volume"

        fun azanIntent(c: Context, azan: Int, prayerMinute: Int, test: Boolean): Intent =
            Intent(c, AlertService::class.java).setAction(ACTION_AZAN)
                .putExtra(EXTRA_AZAN, azan).putExtra(EXTRA_PRAYER_MIN, prayerMinute).putExtra(EXTRA_TEST, test)

        /** Test-plays one AzanLibrary file (or "" for the phone alarm sound). */
        fun previewIntent(c: Context, sound: String): Intent =
            azanIntent(c, 1, -1, test = true).putExtra(EXTRA_SOUND, sound)

        /** Test-plays an alarm's sound with its name and volume (0..21). */
        fun alarmTestIntent(c: Context, sound: String, title: String, volume: Int): Intent =
            previewIntent(c, sound).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_VOLUME, volume.coerceIn(0, 21))

        fun alarmIntent(c: Context, id: Long): Intent =
            Intent(c, AlertService::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_ALARM_ID, id)

        fun stopIntent(c: Context): Intent = Intent(c, AlertService::class.java).setAction(ACTION_STOP)
        fun snoozeIntent(c: Context): Intent = Intent(c, AlertService::class.java).setAction(ACTION_SNOOZE)

        fun createChannels(c: Context) = com.nouralwelayah.clock.ifChannels {
            val nm = Sys.svc(c, NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Azan and alarms",
                NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)               // the service plays the sound itself
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_TEST, "Azan test",
                NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        }
    }
}
