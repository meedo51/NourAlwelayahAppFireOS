package com.nouralwelayah.clock.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nouralwelayah.clock.data.YouTubeVideoItem
import com.nouralwelayah.clock.media.MediaHub

/**
 * Kiosk-Safe Embedded YouTube Player using YouTube's official IFrame Player API
 * loaded with base URL `https://www.youtube-nocookie.com` to prevent Error 150/152/153
 * embedded playback restrictions.
 *
 * Strict Kiosk Lockdown Guarantees:
 * - `WebSettings`: `javaScriptEnabled = true`, `domStorageEnabled = true`,
 *   `mediaPlaybackRequiresUserGesture = false`, `setSupportMultipleWindows(false)`.
 * - `WebViewClient.shouldOverrideUrlLoading`: ALWAYS returns `true` for any URL navigation
 *   outside the initial embed page so tapping the YouTube watermark, channel name, or
 *   "Watch on YouTube" never opens Chrome, the YouTube app, or breaks LockTask Kiosk Mode.
 * - Supports in-app fullscreen via both the player header **Fullscreen** toggle and
 *   `WebChromeClient.onShowCustomView` / `onHideCustomView` rendered inside a full-window
 *   Compose overlay within `MainActivity` (never launching an external Activity).
 * - Cleanly pauses and destroys the `WebView` (`onPause()`, `loadUrl("about:blank")`, `destroy()`)
 *   when closed or disposed so audio never leaks in the background.
 */
object EmbeddedYouTubeController {
    @Volatile
    private var activeWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    internal fun attach(webView: WebView) {
        activeWebView = webView
    }

    internal fun detach(webView: WebView) {
        if (activeWebView === webView) {
            activeWebView = null
        }
    }

    /**
     * Immediately pauses any active embedded YouTube playback (invoked automatically when an Azan triggers).
     */
    fun pauseYouTubePlayback() {
        MediaHub.pauseYouTubePlayback()
        mainHandler.post {
            val wv = activeWebView ?: return@post
            runCatching {
                wv.evaluateJavascript("if(window.pauseEmbedPlayer){window.pauseEmbedPlayer();}", null)
            }
        }
    }
}

/**
 * Convenience top-level hook exposed for Azan / Alarm preemption.
 */
fun pauseYouTubePlayback() {
    EmbeddedYouTubeController.pauseYouTubePlayback()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedYouTubePlayer(
    video: YouTubeVideoItem,
    volume: Float = 1f,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onClose: () -> Unit = {},
    onEnded: () -> Unit = {},
    onError: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var playerErrorCode by remember(video.videoId) { mutableStateOf<Int?>(null) }

    // Listen to MediaHub.youTubeCommands ("pause", "play", "toggle") for Azan priority and remote commands
    LaunchedEffect(webViewRef) {
        MediaHub.youTubeCommands.collect { cmd ->
            val wv = webViewRef ?: return@collect
            when (cmd) {
                "pause" -> wv.evaluateJavascript("if(window.pauseEmbedPlayer){window.pauseEmbedPlayer();}", null)
                "play" -> wv.evaluateJavascript("if(window.playEmbedPlayer){window.playEmbedPlayer();}", null)
                "toggle" -> wv.evaluateJavascript("if(window.toggleEmbedPlayer){window.toggleEmbedPlayer();}", null)
            }
        }
    }

    // Sync volume changes reactively
    LaunchedEffect(webViewRef, volume) {
        val volInt = (volume.coerceIn(0f, 1f) * 100f).toInt()
        webViewRef?.evaluateJavascript("if(window.setEmbedVolume){window.setEmbedVolume($volInt);}", null)
    }

    // Guarantee full teardown when composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            MediaHub.youTubePlaying.value = false
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
            webViewRef?.let { wv ->
                EmbeddedYouTubeController.detach(wv)
                teardownWebView(wv)
            }
            webViewRef = null
        }
    }

    BackHandler(enabled = isFullscreen || customView != null) {
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()
            customView = null
            customViewCallback = null
        }
        if (isFullscreen) {
            currentOnToggleFullscreen()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0C1823), RoundedCornerShape(if (isFullscreen) 0.dp else 12.dp))
            .border(
                width = if (isFullscreen) 0.dp else 1.5.dp,
                color = C_GOLD.copy(alpha = 0.7f),
                shape = RoundedCornerShape(if (isFullscreen) 0.dp else 12.dp),
            )
            .clip(RoundedCornerShape(if (isFullscreen) 0.dp else 12.dp))
            .testTag("embedded_youtube_player_card"),
    ) {
        // Player Header Bar: Now Playing Title, In-App Fullscreen Toggle, and Close Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(C_CARD)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "▶",
                color = Color(0xFFE53935),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    color = C_TEXT,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (video.channelName.isNotBlank()) {
                    Text(
                        text = video.channelName,
                        color = C_DIM,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Button(
                onClick = onToggleFullscreen,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E3246),
                    contentColor = C_GOLD,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(40.dp)
                    .testTag("youtube_player_fullscreen_toggle"),
            ) {
                Text(
                    text = if (isFullscreen) {
                        tr("⤡ Exit Fullscreen", "⤡ إنهاء ملء الشاشة")
                    } else {
                        tr("⛶ Fullscreen", "⛶ ملء الشاشة")
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Button(
                onClick = {
                    webViewRef?.let { wv ->
                        EmbeddedYouTubeController.detach(wv)
                        teardownWebView(wv)
                    }
                    webViewRef = null
                    MediaHub.youTubePlaying.value = false
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(40.dp)
                    .testTag("youtube_player_close_button"),
            ) {
                Text(
                    text = tr("✕ Close", "✕ إغلاق"),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Embedded WebView Surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isFullscreen) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.height(230.dp)
                    }
                )
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(AndroidColor.BLACK)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            setSupportMultipleWindows(false)
                            javaScriptCanOpenWindowsAutomatically = false
                            loadsImagesAutomatically = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            userAgentString =
                                "Mozilla/5.0 (Linux; Android 13; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        }

                        // Strict Kiosk Lockdown WebViewClient: blocks all external navigation
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                // Always return true to block any top-level or external navigation
                                // (e.g. tapping YouTube logo, channel link, or "Watch on YouTube")
                                return true
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                return true
                            }
                        }

                        // Support in-app HTML5 fullscreen custom view inside MainActivity without leaving Kiosk Mode
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                if (view == null) return
                                if (customView != null) {
                                    callback?.onCustomViewHidden()
                                    return
                                }
                                customView = view
                                customViewCallback = callback
                                if (!isFullscreen) {
                                    currentOnToggleFullscreen()
                                }
                            }

                            override fun onHideCustomView() {
                                customViewCallback?.onCustomViewHidden()
                                customView = null
                                customViewCallback = null
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?,
                            ): Boolean {
                                // Block popup windows in Kiosk Mode
                                return false
                            }
                        }

                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onState(stateCode: Int) {
                                    mainHandler.post {
                                        MediaHub.youTubePlaying.value = (stateCode == 1)
                                        if (stateCode == 1) {
                                            playerErrorCode = null
                                        }
                                        if (stateCode == 0) {
                                            currentOnEnded()
                                        }
                                    }
                                }

                                @JavascriptInterface
                                fun onError(errorCode: Int) {
                                    mainHandler.post {
                                        MediaHub.youTubePlaying.value = false
                                        playerErrorCode = errorCode
                                        currentOnError(errorCode)
                                    }
                                }
                            },
                            "NourYtBridge",
                        )

                        EmbeddedYouTubeController.attach(this)
                        webViewRef = this
                    }
                },
                update = { wv ->
                    val volInt = (volume.coerceIn(0f, 1f) * 100f).toInt()
                    if (wv.tag != video.videoId) {
                        wv.tag = video.videoId
                        playerErrorCode = null
                        val html = buildYouTubeIframeHtml(video.videoId, volInt)
                        wv.loadDataWithBaseURL(
                            YOUTUBE_NOCOOKIE_BASE_URL,
                            html,
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // If WebChromeClient emitted a custom fullscreen HTML5 video view, host it inside the container
            val activeCustomView = customView
            if (activeCustomView != null) {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            setBackgroundColor(AndroidColor.BLACK)
                            (activeCustomView.parent as? ViewGroup)?.removeView(activeCustomView)
                            addView(
                                activeCustomView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    update = { container ->
                        if (activeCustomView.parent !== container) {
                            (activeCustomView.parent as? ViewGroup)?.removeView(activeCustomView)
                            container.removeAllViews()
                            container.addView(
                                activeCustomView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Friendly error banner overlay if a video restricts embedding
            val errCode = playerErrorCode
            if (errCode != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xDD0C1823))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatYouTubeEmbedError(errCode),
                        color = C_GOLD,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Full-window Compose overlay wrapper so when the user toggles **Fullscreen** on the embedded
 * YouTube player, it expands to fill the entire app window inside `MainActivity` without
 * launching any external Activity or breaking LockTask Kiosk Mode.
 */
@Composable
fun EmbeddedYouTubeFullscreenDialog(
    content: @Composable () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            content()
        }
    }
}

private const val YOUTUBE_NOCOOKIE_BASE_URL = "https://www.youtube-nocookie.com"

internal fun buildYouTubeIframeHtml(videoId: String, initialVolume: Int): String {
    val safeVideoId = videoId.replace(Regex("[^A-Za-z0-9_-]"), "")
    val safeVol = initialVolume.coerceIn(0, 100)
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>
  html, body {
    margin: 0;
    padding: 0;
    width: 100%;
    height: 100%;
    background-color: #000000;
    overflow: hidden;
  }
  #yt-player {
    position: fixed;
    top: 0;
    left: 0;
    width: 100vw;
    height: 100vh;
    border: 0;
  }
</style>
</head>
<body>
<div id="yt-player"></div>
<script>
  var player = null;
  var currentVol = $safeVol;

  window.pauseEmbedPlayer = function() {
    try {
      if (player && typeof player.pauseVideo === 'function') {
        player.pauseVideo();
      }
    } catch (e) {}
  };

  window.playEmbedPlayer = function() {
    try {
      if (player && typeof player.playVideo === 'function') {
        player.playVideo();
      }
    } catch (e) {}
  };

  window.toggleEmbedPlayer = function() {
    try {
      if (!player || typeof player.getPlayerState !== 'function') return;
      if (player.getPlayerState() === 1) {
        player.pauseVideo();
      } else {
        player.playVideo();
      }
    } catch (e) {}
  };

  window.setEmbedVolume = function(v) {
    currentVol = v;
    try {
      if (player && typeof player.setVolume === 'function') {
        player.setVolume(v);
      }
    } catch (e) {}
  };

  // Prevent any anchor clicks from navigating away inside the document
  document.addEventListener('click', function(e) {
    var target = e.target;
    while (target && target !== document) {
      if (target.tagName === 'A') {
        e.preventDefault();
        e.stopPropagation();
        return false;
      }
      target = target.parentNode;
    }
  }, true);

  function onYouTubeIframeAPIReady() {
    player = new YT.Player('yt-player', {
      height: '100%',
      width: '100%',
      videoId: '$safeVideoId',
      host: 'https://www.youtube-nocookie.com',
      playerVars: {
        autoplay: 1,
        playsinline: 1,
        rel: 0,
        modestbranding: 1,
        fs: 0,
        iv_load_policy: 3,
        disablekb: 1,
        origin: 'https://www.youtube-nocookie.com'
      },
      events: {
        onReady: function(event) {
          try {
            event.target.setVolume(currentVol);
            event.target.playVideo();
          } catch (e) {}
        },
        onStateChange: function(event) {
          if (window.NourYtBridge && window.NourYtBridge.onState) {
            window.NourYtBridge.onState(event.data);
          }
        },
        onError: function(event) {
          if (window.NourYtBridge && window.NourYtBridge.onError) {
            window.NourYtBridge.onError(event.data);
          }
        }
      }
    });
  }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body>
</html>
    """.trimIndent()
}

@Composable
private fun formatYouTubeEmbedError(code: Int): String = when (code) {
    101, 150, 152, 153 -> tr(
        "The uploader of this video has restricted embedded playback. Please choose another video.",
        "صاحب هذا الفيديو قيّد التشغيل المضمّن. يرجى اختيار مقطع آخر."
    )
    100 -> tr(
        "This video is unavailable or has been removed.",
        "هذا الفيديو غير متاح أو تمت إزالته."
    )
    2 -> tr(
        "Invalid YouTube video ID.",
        "معرّف فيديو يوتيوب غير صالح."
    )
    else -> tr(
        "Unable to play this video (Error $code).",
        "تعذّر تشغيل هذا الفيديو (خطأ $code)."
    )
}

private fun teardownWebView(webView: WebView) {
    runCatching {
        webView.stopLoading()
        webView.onPause()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}
