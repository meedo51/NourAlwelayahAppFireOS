package com.nouralwelayah.clock.media

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.nouralwelayah.clock.Sys
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.MediaEntry
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.lockTabletLandscape
import com.nouralwelayah.clock.ui.AdminDashboard
import com.nouralwelayah.clock.ui.AdminPinOverlay
import com.nouralwelayah.clock.ui.C_ACCENT
import com.nouralwelayah.clock.ui.C_BG
import com.nouralwelayah.clock.ui.C_CARD
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.ChildLockOverlay
import com.nouralwelayah.clock.ui.Hint
import com.nouralwelayah.clock.ui.NourAlwelayahLocaleProvider
import com.nouralwelayah.clock.ui.PrayerClockTheme
import com.nouralwelayah.clock.ui.RoundSlider
import com.nouralwelayah.clock.ui.Section
import com.nouralwelayah.clock.ui.isArabic
import com.nouralwelayah.clock.ui.p4.fmtMs
import com.nouralwelayah.clock.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Strict Brand Palette Constants
private val BrandNavy = Color(0xFF0C1823)
private val BrandCard = Color(0xFF142433)
private val BrandGold = Color(0xFFCC9C56)
private val BrandText = C_TEXT
private val BrandDim = C_DIM

private val TIMEOUT_OPTIONS_SEC = listOf(15, 30, 60, 120)

/**
 * Built-in vector definition for [Icons.Default.TimerOff] so we don't require the 30MB
 * material-icons-extended artifact while still providing a crisp Material TimerOff icon.
 */
private val Icons.Filled.TimerOff: ImageVector
    get() {
        if (_timerOff != null) return _timerOff!!
        _timerOff = ImageVector.Builder(
            name = "Filled.TimerOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.White),
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            ) {
                // Top stopwatch bar
                moveTo(9f, 1f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(2f)
                horizontalLineTo(9f)
                close()
                // Timer body + diagonal slash
                moveTo(13f, 8f)
                verticalLineToRelative(2.17f)
                lineToRelative(6.98f, 6.98f)
                curveTo(20.63f, 15.91f, 21f, 14.5f, 21f, 13f)
                curveToRelative(0f, -2.12f, -0.74f, -4.07f, -1.97f, -5.61f)
                lineToRelative(1.42f, -1.42f)
                curveToRelative(-0.43f, -0.51f, -0.9f, -0.99f, -1.41f, -1.41f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(16.07f, 4.74f, 14.12f, 4f, 12f, 4f)
                curveToRelative(-1.5f, 0f, -2.91f, 0.37f, -4.15f, 1.02f)
                lineTo(10.83f, 8f)
                horizontalLineTo(13f)
                close()
                moveTo(2.81f, 2.81f)
                lineTo(1.39f, 4.22f)
                lineToRelative(3.4f, 3.4f)
                curveTo(3.67f, 9.12f, 3f, 10.98f, 3f, 13f)
                curveToRelative(0f, 4.97f, 4.02f, 9f, 9f, 9f)
                curveToRelative(2.02f, 0f, 3.88f, -0.67f, 5.38f, -1.79f)
                lineToRelative(2.4f, 2.4f)
                lineToRelative(1.41f, -1.41f)
                lineTo(2.81f, 2.81f)
                close()
            }
        }.build()
        return _timerOff!!
    }

private var _timerOff: ImageVector? = null

/**
 * Nour Alwelayah Appliance Media Player: plays audio/video files from /NourAlwelayah/audios and
 * /NourAlwelayah/videos via MediaService with a custom Jetpack Compose video overlay (useController = false)
 * styled with BrandNavy (#0C1823), BrandCard (#142433), and BrandGold (#CC9C56), featuring an inactivity
 * timeout engine and Stay Awake override.
 */
class MediaActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private val controller = mutableStateOf<MediaController?>(null)
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockTabletLandscape()
        Store.init(this)
        MediaLibrary.init(this)
        MediaLibrary.rescan(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (takeShare(intent)) return
        setContent {
            val settings by Store.settings.collectAsState()
            NourAlwelayahLocaleProvider(languageCode = settings.appLanguage) {
                PrayerClockTheme {
                    MediaScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
    }

    /** A shared link: played directly on the appliance media player. */
    private fun takeShare(i: Intent?): Boolean {
        if (i?.action != Intent.ACTION_SEND) return false
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return false
        sharedText.value = text
        return false
    }

    override fun onStart() {
        super.onStart()
        isMediaForeground = true
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, MediaService::class.java))).buildAsync()
        future = f
        f.addListener({ controller.value = runCatching { f.get() }.getOrNull() }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        isMediaForeground = false
        controller.value = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun MediaScreen() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val ar = isArabic()
        val settings by Store.settings.collectAsState()
        val c = controller.value
        val files by MediaLibrary.files.collectAsState()
        val tubes by MediaLibrary.youTube.collectAsState()
        val yt by MediaHub.youTube.collectAsState()
        var msg by remember { mutableStateOf("") }
        val volume by MediaHub.volume.collectAsState()
        var showLink by remember { mutableStateOf(false) }

        var tick by remember { mutableIntStateOf(0) }
        var posMs by remember { mutableLongStateOf(0L) }
        var durMs by remember { mutableLongStateOf(0L) }
        var isPlaying by remember { mutableStateOf(false) }

        // Hidden Technician Trigger state (5 rapid taps in top-left 48.dp x 48.dp corner)
        var tapCount by remember { mutableIntStateOf(0) }
        var lastTapTimeMs by remember { mutableLongStateOf(0L) }
        var showAdminAuth by remember { mutableStateOf(false) }
        var showAdminDashboard by remember { mutableStateOf(false) }

        DisposableEffect(c) {
            val l = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    tick++
                    isPlaying = player.isPlaying
                    posMs = player.currentPosition.coerceAtLeast(0L)
                    durMs = if (player.duration == C.TIME_UNSET) 0L else player.duration.coerceAtLeast(0L)
                }
            }
            c?.addListener(l)
            onDispose { c?.removeListener(l) }
        }

        // Poll current position every 250ms for smooth custom timeline slider updates
        LaunchedEffect(c, tick) {
            while (true) {
                val p = c
                if (p != null) {
                    isPlaying = p.isPlaying
                    posMs = p.currentPosition.coerceAtLeast(0L)
                    durMs = if (p.duration == C.TIME_UNSET) 0L else p.duration.coerceAtLeast(0L)
                }
                delay(250L)
            }
        }

        LaunchedEffect(c, volume) { c?.volume = volume }
        @Suppress("UNUSED_EXPRESSION") tick
        val currentId = c?.currentMediaItem?.mediaId
        val currentVideo = files.firstOrNull { it.id == currentId }?.video == true

        fun addYouTube(link: String) {
            msg = if (ar) "جاري إضافة فيديو يوتيوب..." else "Adding YouTube video..."
            scope.launch {
                val e = withContext(Dispatchers.IO) { MediaLibrary.addYouTube(link) }
                if (e == null) {
                    msg = if (ar) "هذا ليس رابط يوتيوب صالح." else "That is not a YouTube link."
                } else {
                    msg = ""
                    c?.pause()
                    MediaHub.youTube.value = e
                }
            }
        }

        LaunchedEffect(Unit) { MediaHub.closeScreen.collect { finish() } }
        DisposableEffect(ctx) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == MediaService.ACTION_SELF_HEAL_CLOSE) {
                        finish()
                    }
                }
            }
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                android.content.IntentFilter(MediaService.ACTION_SELF_HEAL_CLOSE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            onDispose {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }

        fun stopToClock() {
            MediaHub.youTube.value = null
            MediaHub.fullScreen.value = false
            c?.let { it.stop(); it.clearMediaItems() }
            MediaHub.forceClockTab.value = true
            MediaHub.closeScreen.tryEmit(Unit)
            finish()
        }

        fun navigateBackToClock() {
            MediaHub.youTube.value = null
            MediaHub.fullScreen.value = false
            if (currentVideo) {
                c?.pause()
            }
            MediaHub.forceClockTab.value = true
            MediaHub.closeScreen.tryEmit(Unit)
            finish()
        }

        // Custom video overlay visibility state: fades out automatically after 3 seconds of inactivity
        var overlayVisible by remember { mutableStateOf(true) }
        var lastOverlayInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

        // Inactivity Timeout Engine State (default 30s, customizable via AppSettings.mediaTimeoutSec)
        var inactivityResetTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var remainingTimeoutSec by remember(settings.mediaTimeoutSec) {
            mutableIntStateOf(settings.mediaTimeoutSec.coerceAtLeast(5))
        }

        fun resetInactivityTimer(showOverlay: Boolean = false) {
            val nowMs = System.currentTimeMillis()
            lastOverlayInteractionMs = nowMs
            inactivityResetTick = nowMs
            remainingTimeoutSec = settings.mediaTimeoutSec.coerceAtLeast(5)
            if (showOverlay) {
                overlayVisible = true
            }
        }

        // Countdown LaunchedEffect: counts down from mediaTimeoutSec and forces app back to Main Clock tab when reaching 0
        LaunchedEffect(settings.mediaTimeoutSec, settings.mediaStayAwake, inactivityResetTick) {
            val totalSec = settings.mediaTimeoutSec.coerceAtLeast(5)
            remainingTimeoutSec = totalSec
            if (!settings.mediaStayAwake) {
                var secLeft = totalSec
                while (secLeft > 0) {
                    delay(1_000L)
                    secLeft--
                    remainingTimeoutSec = secLeft
                }
                if (!Store.settings.value.mediaStayAwake) {
                    // Pause video if playing so P4Tablet does not re-open MediaActivity, force Clock tab, and finish
                    navigateBackToClock()
                }
            }
        }

        var ytDone by remember { mutableStateOf(false) }
        val filePlaying = c?.isPlaying == true
        LaunchedEffect(yt, filePlaying) { if (yt != null || filePlaying) ytDone = false }
        val allEnded = yt == null && (ytDone || c?.playbackState == Player.STATE_ENDED)
        LaunchedEffect(allEnded) {
            if (allEnded && com.nouralwelayah.clock.isTablet(ctx)) {
                msg = if (ar) "انتهى التشغيل - العودة للساعة خلال دقيقة" else "Finished - back to the clock in 1 minute"
                delay(END_TO_CLOCK_MS)
                stopToClock()
            } else if (msg.startsWith("Finished - back") || msg.startsWith("انتهى التشغيل")) {
                msg = ""
            }
        }

        val shared by sharedText
        LaunchedEffect(shared) { shared?.let { sharedText.value = null; addYouTube(it) } }

        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) scope.launch {
                val n = withContext(Dispatchers.IO) { MediaLibrary.addFiles(ctx, uris) }
                msg = if (ar) "تمت إضافة $n ملف(ات)." else "Added $n file(s)."
            }
        }
        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree != null) scope.launch {
                val n = withContext(Dispatchers.IO) { MediaLibrary.addFolder(ctx, tree) }
                msg = if (n == 0) {
                    if (ar) "لا توجد ملفات صوت أو فيديو جديدة في هذا المجلد." else "No new audio or video files in that folder."
                } else {
                    if (ar) "تمت إضافة $n ملف(ات)." else "Added $n file(s)."
                }
            }
        }

        fun playFile(i: Int) {
            val p = c ?: return
            resetInactivityTimer(showOverlay = true)
            MediaHub.youTube.value = null
            p.setMediaItems(files.map { it.toMediaItem() }, i, 0L)
            p.prepare()
            p.play()
        }

        val fullWanted by MediaHub.fullScreen.collectAsState()
        val full = fullWanted && (yt != null || currentVideo)
        DisposableEffect(full, settings.mediaStayAwake) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            if (full) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                bars.show(WindowInsetsCompat.Type.systemBars())
            }
            if (full || settings.mediaStayAwake) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose { }
        }
        BackHandler(enabled = full) { MediaHub.fullScreen.value = false }

        LaunchedEffect(overlayVisible, lastOverlayInteractionMs, isPlaying, currentVideo, yt) {
            if (overlayVisible && yt == null && (currentVideo || isPlaying)) {
                delay(OVERLAY_AUTO_HIDE_MS)
                if (System.currentTimeMillis() - lastOverlayInteractionMs >= OVERLAY_AUTO_HIDE_MS) {
                    overlayVisible = false
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(if (full) Color.Black else BrandNavy)
                .then(if (full) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            resetInactivityTimer(showOverlay = false)
                        }
                    }
                },
        ) {
            val wide = maxWidth > maxHeight
            val half = maxHeight * 0.5f

            val player: @Composable (Modifier) -> Unit = { m ->
                Column(m.padding(if (full) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.Black, RoundedCornerShape(if (full) 0.dp else 12.dp))
                            .pointerInput(Unit) {
                                // Track any pointer interaction inside the video canvas to reset the fade & inactivity timers
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial)
                                        resetInactivityTimer(showOverlay = false)
                                    }
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        overlayVisible = !overlayVisible
                                        resetInactivityTimer(showOverlay = overlayVisible)
                                    },
                                )
                            },
                    ) {
                        val y = yt
                        if (y != null) {
                            YouTubePlayer(
                                y,
                                volume,
                                onEnded = {
                                    val next = tubes.getOrNull(tubes.indexOfFirst { it.id == y.id } + 1)
                                    if (next == null) ytDone = true
                                    MediaHub.youTube.value = next
                                },
                                onError = { code ->
                                    android.util.Log.e("MediaActivity", "Self-healing YouTube player error ($code): ${youTubeError(code)}")
                                    scope.launch {
                                        delay(3_000L)
                                        stopToClock()
                                    }
                                },
                            )
                            // Top navigation bar over YouTube view
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                IconButton(
                                    onClick = { navigateBackToClock() },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = BrandCard.copy(alpha = 0.9f),
                                        contentColor = BrandGold,
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .border(1.dp, BrandGold, RoundedCornerShape(10.dp)),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = tr("Back", "رجوع"),
                                    )
                                }
                                TextButton(
                                    onClick = { stopToClock() },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .background(BrandCard.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                        .border(1.dp, BrandGold, RoundedCornerShape(10.dp)),
                                ) {
                                    Text(tr("■  Stop", "■  إيقاف"), color = BrandGold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { MediaHub.fullScreen.value = !full },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .background(BrandCard.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                                        .border(1.dp, BrandGold, RoundedCornerShape(10.dp)),
                                ) {
                                    Text(
                                        text = if (full) tr("⤡ ESC", "⤡ تصغير") else tr("⛶ Full", "⛶ ملء"),
                                        color = BrandGold,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        } else if (c != null) {
                            // ExoPlayer video surface with default controller disabled (useController = false)
                            AndroidView(
                                factory = { vctx ->
                                    PlayerView(vctx).apply {
                                        useController = false
                                    }
                                },
                                update = { v ->
                                    v.useController = false
                                    v.player = c
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("custom_exoplayer_surface"),
                            )

                            if (!currentVideo) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text("♪", color = BrandGold, fontSize = 42.sp)
                                    Text(
                                        c.currentMediaItem?.mediaMetadata?.title?.toString()
                                            ?: tr("Choose an audio or video file", "اختر ملف صوتي أو فيديو"),
                                        color = BrandText,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            // Custom Jetpack Compose Player Overlay styled with BrandNavy, BrandCard, and BrandGold
                            androidx.compose.animation.AnimatedVisibility(
                                visible = overlayVisible || !currentVideo,
                                enter = fadeIn(animationSpec = tween(220)),
                                exit = fadeOut(animationSpec = tween(350)),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                CustomApplianceVideoOverlay(
                                    title = c.currentMediaItem?.mediaMetadata?.title?.toString()
                                        ?: tr("Nour Alwelayah Media Player", "مشغل وسائط نور الولاية"),
                                    isPlaying = isPlaying,
                                    positionMs = posMs,
                                    durationMs = durMs,
                                    isFullScreen = full,
                                    isVideo = currentVideo,
                                    mediaTimeoutSec = settings.mediaTimeoutSec,
                                    mediaStayAwake = settings.mediaStayAwake,
                                    remainingTimeoutSec = remainingTimeoutSec,
                                    onUserInteraction = {
                                        resetInactivityTimer(showOverlay = true)
                                    },
                                    onBack = {
                                        navigateBackToClock()
                                    },
                                    onToggleStayAwake = {
                                        resetInactivityTimer(showOverlay = true)
                                        Store.saveSettings(
                                            Store.settings.value.copy(
                                                mediaStayAwake = !settings.mediaStayAwake,
                                            ),
                                        )
                                    },
                                    onSelectTimeoutSec = { newTimeoutSec ->
                                        resetInactivityTimer(showOverlay = true)
                                        Store.saveSettings(
                                            Store.settings.value.copy(
                                                mediaTimeoutSec = newTimeoutSec,
                                            ),
                                        )
                                    },
                                    onTogglePlayPause = {
                                        resetInactivityTimer(showOverlay = true)
                                        if (c.isPlaying) c.pause() else c.play()
                                    },
                                    onPrevious = {
                                        resetInactivityTimer(showOverlay = true)
                                        c.seekToPrevious()
                                    },
                                    onNext = {
                                        resetInactivityTimer(showOverlay = true)
                                        c.seekToNextMediaItem()
                                    },
                                    onSeekTo = { fraction ->
                                        resetInactivityTimer(showOverlay = true)
                                        if (durMs > 0L) {
                                            val target = (fraction * durMs).toLong().coerceIn(0L, durMs)
                                            posMs = target
                                            c.seekTo(target)
                                        }
                                    },
                                    onStopToClock = { stopToClock() },
                                    onToggleFullScreen = {
                                        resetInactivityTimer(showOverlay = true)
                                        MediaHub.fullScreen.value = !full
                                    },
                                )
                            }
                        }
                    }
                    if (full) return@Column
                    if (yt != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                yt!!.title,
                                color = BrandText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { MediaHub.youTube.value = null }) { Text(tr("Close", "إغلاق")) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Volume", "مستوى الصوت"), color = BrandDim, fontSize = 14.sp)
                        RoundSlider(
                            value = volume,
                            onValueChange = {
                                resetInactivityTimer(showOverlay = false)
                                MediaHub.volume.value = it
                                c?.volume = it
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                    }
                    OutputRow(ctx)
                    if (msg.isNotEmpty()) Text(msg, color = BrandGold, fontSize = 14.sp)
                }
            }

            val lists: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Section(tr("Audios & Videos", "الصوتيات والمرئيات")) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { pickFiles.launch(arrayOf("audio/*", "video/*")) }) {
                                Text(tr("+ Files", "+ ملفات"))
                            }
                            OutlinedButton(onClick = { pickFolder.launch(null) }) {
                                Text(tr("+ Folder", "+ مجلد"))
                            }
                        }
                        if (files.isEmpty()) {
                            Hint(
                                tr(
                                    "Add audio or video files from this device. They stay where they are.",
                                    "أضف ملفات صوتية أو مرئية من هذا الجهاز. تبقى الملفات في مكانها.",
                                ),
                            )
                        }
                        Hint(
                            tr(
                                "📁 = in Internal storage/${AppFolders.ROOT}/audios or videos: copy files there and they show here.",
                                "📁 = في وحدة التخزين الداخلية/${AppFolders.ROOT}/audios أو videos: انسخ الملفات هناك وستظهر هنا.",
                            ),
                        )
                        files.forEachIndexed { i, e ->
                            EntryRow(
                                e,
                                playing = yt == null && e.id == currentId,
                                onPlay = { playFile(i) },
                                onRemove = if (MediaLibrary.inFolder(e)) null else {
                                    {
                                        MediaLibrary.removeFile(e)
                                        c?.let { p ->
                                            (0 until p.mediaItemCount)
                                                .firstOrNull { p.getMediaItemAt(it).mediaId == e.id }
                                                ?.let(p::removeMediaItem)
                                        }
                                    }
                                },
                            )
                        }
                    }
                    Section(tr("YouTube", "يوتيوب")) {
                        OutlinedButton(onClick = { showLink = true }) { Text(tr("+ YouTube link", "+ رابط يوتيوب")) }
                        Hint(tr("Or share a YouTube link from the companion remote.", "أو شارك رابط يوتيوب من تطبيق التحكم المرافق."))
                        tubes.forEach { e ->
                            EntryRow(
                                e,
                                playing = yt?.id == e.id,
                                onPlay = { c?.pause(); msg = ""; MediaHub.youTube.value = e },
                                onRemove = {
                                    MediaLibrary.removeYouTube(e)
                                    if (yt?.id == e.id) MediaHub.youTube.value = null
                                },
                            )
                        }
                    }
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    player(Modifier.weight(1.5f).fillMaxHeight())
                    if (!full) lists(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    player(Modifier.fillMaxWidth().then(if (full) Modifier.weight(1f) else Modifier.height(half)))
                    if (!full) lists(Modifier.fillMaxWidth().weight(1f))
                }
            }

            // Client Child Lock full-screen touch-absorbing overlay rendered ABOVE the custom Media Player
            // (absorbs all taps/drags on play/pause, seek, close, volume, and lists while providing 3-second hold-to-unlock)
            if (settings.childLockEnabled) {
                ChildLockOverlay(
                    isArabic = ar,
                    onUnlock = {
                        Store.saveSettings(Store.settings.value.copy(childLockEnabled = false))
                    },
                )
            }

            // Hidden 48.dp x 48.dp transparent technician trigger in the top-left corner (5 rapid taps)
            // Always sits above ChildLockOverlay so a technician can access AdminDashboard even when Child Lock is active
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val nowMs = System.currentTimeMillis()
                                if (nowMs - lastTapTimeMs > 2_000L) {
                                    tapCount = 1
                                } else {
                                    tapCount += 1
                                }
                                lastTapTimeMs = nowMs
                                if (tapCount >= 5) {
                                    tapCount = 0
                                    showAdminAuth = true
                                }
                            }
                        )
                    }
                    .testTag("admin_secret_corner_trigger"),
            )
        }

        if (showLink && !settings.childLockEnabled) {
            var link by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showLink = false },
                title = { Text(tr("YouTube link", "رابط يوتيوب")) },
                text = { OutlinedTextField(link, { link = it }, singleLine = true, label = { Text("https://youtu.be/...") }) },
                confirmButton = { TextButton(onClick = { showLink = false; addYouTube(link) }) { Text(tr("Add and play", "إضافة وتشغيل")) } },
                dismissButton = { TextButton(onClick = { showLink = false }) { Text(tr("Cancel", "إلغاء")) } },
            )
        }

        if (showAdminAuth) {
            AdminPinOverlay(
                expectedPin = settings.adminPin,
                onAuthenticated = {
                    showAdminAuth = false
                    showAdminDashboard = true
                },
                onDismiss = {
                    showAdminAuth = false
                },
            )
        }

        if (showAdminDashboard) {
            AdminDashboard(
                onClose = {
                    showAdminDashboard = false
                },
            )
        }
    }

    @Composable
    private fun EntryRow(e: MediaEntry, playing: Boolean, onPlay: () -> Unit, onRemove: (() -> Unit)?) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (playing) BrandGold else BrandCard, RoundedCornerShape(10.dp))
                .clickable(onClick = onPlay)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (e.isYouTube) "▶" else if (e.video) "🎬" else "♪",
                color = if (playing) BrandNavy else BrandDim,
            )
            Text(
                e.title,
                color = if (playing) BrandNavy else BrandText,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text("✕", color = if (playing) BrandNavy else BrandDim) }
            } else {
                Text("📁", color = BrandDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }

    @Composable
    private fun OutputRow(ctx: Context) {
        val am = remember { Sys.svc(ctx, AudioManager::class.java) }
        var out by remember { mutableStateOf(outputName(am)) }
        if (Build.VERSION.SDK_INT >= 23) {
            DisposableEffect(am) {
                val cb = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { out = outputName(am) }
                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { out = outputName(am) }
                }
                am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
                onDispose { am.unregisterAudioDeviceCallback(cb) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${tr("Sound", "الصوت")}: $out", color = BrandDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }) { Text(tr("Bluetooth speaker...", "مكبر صوت بلوتوث...")) }
        }
    }

    private fun outputName(am: AudioManager): String {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 23) return if (am.isBluetoothA2dpOn) "Bluetooth speaker" else "this device's speaker"
        val bt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 && (it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET))
        }
        return bt?.let { "Bluetooth - ${it.productName}" } ?: "this device's speaker"
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun YouTubePlayer(e: MediaEntry, volume: Float, onEnded: () -> Unit, onError: (Int) -> Unit) {
        val ended by rememberUpdatedState(onEnded)
        val failed by rememberUpdatedState(onError)
        val main = remember { Handler(Looper.getMainLooper()) }
        var web by remember { mutableStateOf<WebView?>(null) }
        LaunchedEffect(web) {
            val w = web ?: return@LaunchedEffect
            MediaHub.youTubeCommands.collect { cmd -> w.evaluateJavascript("doCmd('$cmd')", null) }
        }
        DisposableEffect(Unit) { onDispose { MediaHub.youTubePlaying.value = false } }
        AndroidView(
            factory = { wctx ->
                WebView(wctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface fun ended() { main.post { ended() } }
                            @JavascriptInterface fun error(code: Int) { main.post { failed(code) } }
                            @JavascriptInterface fun state(code: Int) { main.post { MediaHub.youTubePlaying.value = code == 1 } }
                        },
                        "App",
                    )
                }.also { web = it }
            },
            update = { w ->
                val vol = (volume * 100).toInt()
                if (w.tag != e.id) {
                    w.tag = e.id
                    w.loadDataWithBaseURL("$YT_ORIGIN/", youTubeHtml(e.youTubeId, vol, e.isPlaylist), "text/html", "UTF-8", null)
                } else {
                    w.evaluateJavascript("setVol($vol)", null)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun youTubeError(code: Int) = when (code) {
        101, 150 -> "The owner of this video does not allow it to play in other apps."
        100 -> "Video not found (removed or private)."
        2 -> "Not a valid YouTube video."
        else -> "YouTube could not play this video (error $code)."
    }

    companion object {
        @Volatile
        var isMediaForeground: Boolean = false

        private const val YT_ORIGIN = "https://com.nouralwelayah.clock"
        private const val END_TO_CLOCK_MS = 60_000L
        private const val OVERLAY_AUTO_HIDE_MS = 3_000L

        private fun youTubeHtml(id: String, vol: Int, playlist: Boolean) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;background:#000;overflow:hidden}#p{position:fixed;left:0;top:0;width:100vw;height:100vh;border:0}</style></head>
<body><div id="p"></div><script>
var player, vol = $vol;
var playlist = $playlist;
function doCmd(c){ if (!player || !player.getPlayerState) return;
  if (c == 'next') { player.nextVideo(); return; }
  if (c == 'prev') { player.previousVideo(); return; }
  if (c == 'play' || (c == 'toggle' && player.getPlayerState() != 1)) player.playVideo(); else player.pauseVideo(); }
function lastOfList(){ if (!playlist || !player.getPlaylist) return true;
  var l = player.getPlaylist(); return !l || player.getPlaylistIndex() >= l.length - 1; }
function setVol(v){ vol = v; if (player && player.setVolume) player.setVolume(v); }
function onYouTubeIframeAPIReady(){
  player = new YT.Player('p', { ${if (playlist) "" else "videoId: '$id',"} width: '100%', height: '100%',
    playerVars: { autoplay: 1, playsinline: 1, rel: 0, fs: 0, origin: '$YT_ORIGIN'${if (playlist) ", listType: 'playlist', list: '$id'" else ""} },
    events: {
      onReady: function(e){ e.target.setVolume(vol); e.target.playVideo(); },
      onStateChange: function(e){ App.state(e.data); if (e.data === 0 && lastOfList()) App.ended(); },
      onError: function(e){ App.error(e.data); }
    } });
}
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
    }
}

/**
 * Custom Jetpack Compose video overlay strictly styled with [BrandNavy] (#0C1823), [BrandCard] (#142433),
 * [BrandGold] (#CC9C56), [BrandText], and [BrandDim].
 *
 * Includes:
 * - Root touch interceptor resetting the inactivity countdown timer on any pointer interaction.
 * - Top navigation bar with Back button (`Icons.AutoMirrored.Filled.ArrowBack`), Stay Awake toggle
 *   (`Icons.Default.TimerOff`), and a discrete timeout Dropdown (`15s`, `30s`, `60s`, `120s`).
 * - Center transport controls and bottom scrubber timeline bar.
 */
@Composable
private fun CustomApplianceVideoOverlay(
    title: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isFullScreen: Boolean,
    isVideo: Boolean,
    mediaTimeoutSec: Int,
    mediaStayAwake: Boolean,
    remainingTimeoutSec: Int,
    onUserInteraction: () -> Unit,
    onBack: () -> Unit,
    onToggleStayAwake: () -> Unit,
    onSelectTimeoutSec: (Int) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Float) -> Unit,
    onStopToClock: () -> Unit,
    onToggleFullScreen: () -> Unit,
) {
    var timeoutMenuExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        onUserInteraction()
                    }
                }
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        BrandNavy.copy(alpha = 0.85f),
                        BrandNavy.copy(alpha = 0.30f),
                        BrandNavy.copy(alpha = 0.90f),
                    ),
                ),
            )
            .padding(16.dp)
            .testTag("custom_video_overlay"),
    ) {
        // Top Header Bar: Back IconButton, Stop button, Now Playing Title, Stay Awake toggle, Timeout Dropdown, Fullscreen
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(BrandCard.copy(alpha = 0.88f), RoundedCornerShape(12.dp))
                .border(1.dp, BrandGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Back IconButton mapped to finish() / return to Clock
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = BrandNavy,
                    contentColor = BrandGold,
                ),
                modifier = Modifier
                    .size(48.dp)
                    .border(1.dp, BrandGold, RoundedCornerShape(10.dp))
                    .testTag("overlay_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = tr("Back", "رجوع"),
                    tint = BrandGold,
                )
            }

            // Stop to Clock Button
            Button(
                onClick = onStopToClock,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandNavy,
                    contentColor = BrandGold,
                ),
                modifier = Modifier
                    .height(48.dp)
                    .border(1.dp, BrandGold, RoundedCornerShape(10.dp))
                    .testTag("overlay_stop_button"),
            ) {
                Text(tr("■ Stop", "■ إيقاف"), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            // Title & live inactivity status subtitle
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    color = BrandText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (mediaStayAwake) {
                        tr("Stay Awake Active", "البقاء نشطاً مفعّل")
                    } else {
                        tr("Auto-return in ${remainingTimeoutSec}s", "العودة للساعة خلال ${remainingTimeoutSec}ث")
                    },
                    color = if (mediaStayAwake) BrandGold else BrandDim,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }

            // Stay Awake Toggle Button (highlights in BrandGold when active)
            IconButton(
                onClick = onToggleStayAwake,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (mediaStayAwake) BrandGold else BrandNavy,
                    contentColor = if (mediaStayAwake) BrandNavy else BrandDim,
                ),
                modifier = Modifier
                    .size(48.dp)
                    .border(
                        width = if (mediaStayAwake) 2.dp else 1.dp,
                        color = if (mediaStayAwake) BrandGold else BrandDim,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .testTag("overlay_stay_awake_toggle"),
            ) {
                Icon(
                    imageVector = Icons.Default.TimerOff,
                    contentDescription = tr("Stay Awake", "إبقاء الشاشة نشطة"),
                    tint = if (mediaStayAwake) BrandNavy else BrandGold,
                )
            }

            // Discrete Customizable Timeout Dropdown (15s, 30s, 60s, 120s)
            Box {
                Button(
                    onClick = {
                        onUserInteraction()
                        timeoutMenuExpanded = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandNavy,
                        contentColor = if (mediaStayAwake) BrandDim else BrandGold,
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .border(
                            width = 1.dp,
                            color = if (mediaStayAwake) BrandDim else BrandGold,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .testTag("overlay_timeout_dropdown_button"),
                ) {
                    Text(
                        text = "${mediaTimeoutSec}s",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = tr("Timeout duration", "مهلة عدم النشاط"),
                    )
                }

                DropdownMenu(
                    expanded = timeoutMenuExpanded,
                    onDismissRequest = { timeoutMenuExpanded = false },
                    modifier = Modifier
                        .background(BrandCard)
                        .border(1.dp, BrandGold, RoundedCornerShape(8.dp)),
                ) {
                    TIMEOUT_OPTIONS_SEC.forEach { optionSec ->
                        val isSelected = optionSec == mediaTimeoutSec
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${optionSec}s",
                                    color = if (isSelected) BrandGold else BrandText,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                )
                            },
                            onClick = {
                                timeoutMenuExpanded = false
                                onSelectTimeoutSec(optionSec)
                            },
                            modifier = Modifier
                                .background(if (isSelected) BrandNavy else BrandCard)
                                .testTag("overlay_timeout_option_${optionSec}s"),
                        )
                    }
                }
            }

            if (isVideo) {
                Button(
                    onClick = onToggleFullScreen,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandNavy,
                        contentColor = BrandGold,
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .border(1.dp, BrandGold, RoundedCornerShape(10.dp))
                        .testTag("overlay_fullscreen_button"),
                ) {
                    Text(
                        text = if (isFullScreen) tr("⤡ ESC", "⤡ تصغير") else tr("⛶ Full", "⛶ ملء"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Center Transport Controls: Previous, Play/Pause, Next
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Button(
                onClick = onPrevious,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandCard,
                    contentColor = BrandGold,
                ),
                modifier = Modifier
                    .size(56.dp)
                    .border(1.5.dp, BrandGold, CircleShape)
                    .testTag("overlay_prev_button"),
            ) {
                Text("⏮", fontSize = 20.sp)
            }

            Button(
                onClick = onTogglePlayPause,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandGold,
                    contentColor = BrandNavy,
                ),
                modifier = Modifier
                    .size(72.dp)
                    .border(2.dp, BrandGold, CircleShape)
                    .testTag("overlay_play_pause_button"),
            ) {
                Text(
                    text = if (isPlaying) "❚❚" else "▶",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Button(
                onClick = onNext,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandCard,
                    contentColor = BrandGold,
                ),
                modifier = Modifier
                    .size(56.dp)
                    .border(1.5.dp, BrandGold, CircleShape)
                    .testTag("overlay_next_button"),
            ) {
                Text("⏭", fontSize = 20.sp)
            }
        }

        // Bottom Timeline Bar: Current Time, Scrubber Slider, and Total Duration
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(BrandCard.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .border(1.dp, BrandGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = fmtMs(positionMs),
                    color = BrandGold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(64.dp),
                )
                val progress = if (durationMs > 0L) {
                    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                RoundSlider(
                    value = progress,
                    onValueChange = onSeekTo,
                    enabled = durationMs > 0L,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("overlay_timeline_slider"),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (durationMs > 0L) fmtMs(durationMs) else "--:--",
                    color = BrandGold,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun MediaEntry.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
    .build()
