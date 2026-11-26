package com.nouralwelayah.clock.media

import com.nouralwelayah.clock.data.MediaEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the media player screen (MediaActivity) and the Bluetooth remote server (MediaRemoteServer) share:
 * the YouTube video on screen and the volume. YouTube plays in the screen's WebView, so the server asks the
 * screen to play / pause it through [youTubeCommands].
 */
object MediaHub {
    /** The YouTube video shown in the media player, or null (files). */
    val youTube = MutableStateFlow<MediaEntry?>(null)
    /** Whether that YouTube video is playing (reported by the embedded player). */
    val youTubePlaying = MutableStateFlow(false)
    /** "play", "pause" or "toggle", for the YouTube player on screen. */
    val youTubeCommands = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Stop from the remote: the media player screen closes (back to the clock). */
    val closeScreen = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Persistent flag set when MediaActivity times out or exits to force P4Tablet onto P4Tab.CLOCK. */
    val forceClockTab = MutableStateFlow(false)
    /** Programmatic tab navigation requests emitted by VoiceCommandDispatcher (e.g., "CLOCK", "PRAYER", "ALARMS", "PLAYER", "WIFI", "SETTINGS"). */
    val requestedTab = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Programmatic PlayerTab sub-section request ("AUDIOS", "VIDEOS", "YOUTUBE", "YOUTUBE_ADD"). */
    val requestedPlayerSection = MutableStateFlow<String?>(null)
    /** Programmatic trigger to open the Wi-Fi Network Manager modal in WifiTab. */
    val openWifiManager = MutableStateFlow(false)
    /** Programmatic trigger to open the Wallpaper Gallery Picker modal in ClockPicturesSection. */
    val openWallpaperGallery = MutableStateFlow(false)
    /** Programmatic trigger to open the Alarm Editor modal for a specific slot in AlarmsTab. */
    val requestedAlarmEditorSlot = MutableStateFlow<Int?>(null)
    /** Programmatic trigger for SettingsTab actions ("GPS_SYNC", "ONLINE_CITY"). */
    val requestedSettingsAction = MutableStateFlow<String?>(null)
    /** Video (file or YouTube) fills the whole screen. */
    val fullScreen = MutableStateFlow(false)
    /** Player volume 0..1, for files and YouTube. */
    val volume = MutableStateFlow(1f)

    /** Currently active in-app embedded YouTube video inside PlayerTab (MainActivity). */
    val embeddedYouTubeVideo = MutableStateFlow<com.nouralwelayah.clock.data.YouTubeVideoItem?>(null)
    /** Whether the in-app embedded YouTube player is expanded to full-window mode inside MainActivity. */
    val embeddedYouTubeFullscreen = MutableStateFlow(false)

    /**
     * Immediately pauses any active YouTube playback (both in-app `EmbeddedYouTubePlayer` in `PlayerTab`
     * and `MediaActivity`'s YouTube player). Called automatically whenever an Azan or Alarm triggers
     * so the call to prayer always takes immediate priority.
     */
    fun pauseYouTubePlayback() {
        youTubeCommands.tryEmit("pause")
        youTubePlaying.value = false
        embeddedYouTubeFullscreen.value = false
    }
}
