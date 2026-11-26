package com.nouralwelayah.clock.media

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The media player's engine: ExoPlayer in a MediaSessionService, so files keep playing with the screen off
 * or the app closed, with the usual media notification. The azan and alarms take the audio focus for a
 * moment, which pauses the player and resumes it afterwards.
 *
 * Self-Healing Appliance Hardening:
 * Attaches a [Player.Listener] that intercepts [PlaybackException]s (corrupted media files, storage drops,
 * or broken network streams), logs the error silently without any Toast or system dialog, halts playback,
 * and signals [MediaActivity] to close within 3 seconds and return strictly to the idle clock face.
 */
class MediaService : MediaSessionService() {
    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var selfHealJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)          // headphones / speaker disconnected: pause
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(
                            TAG,
                            "Self-healing media engine intercepted PlaybackException " +
                                "[code=${error.errorCode}, name=${error.errorCodeName}]: ${error.message}",
                            error
                        )
                        // Immediately stop and clear broken media item without showing any Toast or system dialog
                        runCatching {
                            stop()
                            clearMediaItems()
                        }
                        MediaHub.youTube.value = null
                        MediaHub.youTubePlaying.value = false
                        MediaHub.fullScreen.value = false

                        // Broadcast state change to MediaActivity to gracefully close within 3 seconds
                        selfHealJob?.cancel()
                        selfHealJob = serviceScope.launch {
                            delay(SELF_HEAL_CLOSE_DELAY_MS)
                            MediaHub.closeScreen.tryEmit(Unit)
                            runCatching {
                                sendBroadcast(
                                    Intent(ACTION_SELF_HEAL_CLOSE).setPackage(packageName)
                                )
                            }
                        }
                    }
                })
            }

        val open = PendingIntent.getActivity(this, 0, Intent(this, MediaActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // tablet: the player's notification in the quiet channel (no pop-up, no status bar icon)
        if (com.nouralwelayah.clock.isTablet(this)) setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(com.nouralwelayah.clock.quietChannel(this)).build())
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // items arrive by id (the file's URI); give the player the URI to play
                override fun onAddMediaItems(
                    mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems.map {
                    it.buildUpon().setUri(Uri.parse(it.mediaId)).build()
                })
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // app swiped away: keep playing only if something is playing
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        selfHealJob?.cancel()
        serviceScope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaService"
        private const val SELF_HEAL_CLOSE_DELAY_MS = 3_000L
        const val ACTION_SELF_HEAL_CLOSE = "com.nouralwelayah.clock.action.MEDIA_SELF_HEAL_CLOSE"
    }
}
