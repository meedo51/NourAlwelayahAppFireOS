package com.nouralwelayah.clock.ui.p4

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.nouralwelayah.clock.data.AppFolders
import com.nouralwelayah.clock.data.Folder
import com.nouralwelayah.clock.data.MediaEntry
import com.nouralwelayah.clock.data.MediaLibrary
import com.nouralwelayah.clock.data.YouTubeVideoItem
import com.nouralwelayah.clock.media.MediaActivity
import com.nouralwelayah.clock.media.MediaHub
import com.nouralwelayah.clock.ui.C_ACCENT
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_TEXT
import com.nouralwelayah.clock.ui.RoundSlider
import com.nouralwelayah.clock.ui.YouTubePlayerSection
import com.nouralwelayah.clock.ui.isArabic
import com.nouralwelayah.clock.ui.pauseYouTubePlayback
import com.nouralwelayah.clock.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PlayerTab: folder browser / YouTube search & embedded player (left) and now playing + controls (right).
 */
@Composable
fun PlayerTab(c: MediaController?, now: PlayerNow) {
    val ctx = LocalContext.current
    val ar = isArabic()
    val root = remember { AppFolders.dir(ctx, Folder.AUDIOS).parentFile ?: File("/") }
    var cwd by remember { mutableStateOf(AppFolders.dir(ctx, Folder.AUDIOS)) }
    val tick by AppFolders.refreshed.collectAsState()
    val items = remember(cwd, tick) {
        val all = cwd.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
        all.filter { it.isDirectory }.sortedBy { it.name.lowercase() } +
            all.filter { it.isFile && (AppFolders.isAudio(it) || AppFolders.isVideo(it)) }.sortedBy { it.name.lowercase() }
    }
    var msg by remember { mutableStateOf("") }
    var youTube by remember { mutableStateOf(false) }
    var addLink by remember { mutableStateOf(false) }
    val tubes by MediaLibrary.youTube.collectAsState()
    val embeddedYt by MediaHub.embeddedYouTubeVideo.collectAsState()
    val ytPlaying by MediaHub.youTubePlaying.collectAsState()
    val requestedSection by MediaHub.requestedPlayerSection.collectAsState()
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(requestedSection) {
        when (requestedSection) {
            "AUDIOS" -> {
                youTube = false
                cwd = AppFolders.dir(ctx, Folder.AUDIOS)
                MediaHub.requestedPlayerSection.value = null
            }
            "VIDEOS" -> {
                youTube = false
                cwd = AppFolders.dir(ctx, Folder.VIDEOS)
                MediaHub.requestedPlayerSection.value = null
            }
            "YOUTUBE" -> {
                youTube = true
                MediaHub.requestedPlayerSection.value = null
            }
            "YOUTUBE_ADD" -> {
                youTube = true
                addLink = true
                MediaHub.requestedPlayerSection.value = null
            }
        }
    }

    /** Plays a saved YouTube entry directly inside the in-app EmbeddedYouTubePlayer in PlayerTab. */
    fun playYouTubeInApp(e: MediaEntry) {
        c?.pause()
        val vid = e.youTubeId.ifBlank { MediaLibrary.youTubeId(e.id).orEmpty() }
        if (vid.isNotBlank()) {
            youTube = true
            MediaHub.youTube.value = null
            MediaHub.embeddedYouTubeVideo.value = YouTubeVideoItem(
                videoId = vid,
                title = e.title.ifBlank { "YouTube ($vid)" },
                channelName = "",
                durationText = "",
                thumbnailUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
            )
            MediaHub.youTubePlaying.value = true
        }
    }

    /** Plays the files of this folder from [f] on (Next / Prev go through the folder). */
    fun play(f: File) {
        val p = c ?: run { msg = if (ar) "المشغل يبدأ الآن - حاول مرة أخرى" else "The player is starting - try again"; return }
        val list = items.filter { it.isFile }
        val i = list.indexOf(f)
        // Pause any active YouTube playback when local audio/video starts
        pauseYouTubePlayback()
        MediaHub.embeddedYouTubeVideo.value = null
        MediaHub.youTube.value = null
        p.setMediaItems(
            list.map {
                MediaItem.Builder()
                    .setMediaId(Uri.fromFile(it).toString())
                    .setUri(Uri.fromFile(it))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.nameWithoutExtension).build())
                    .build()
            },
            i,
            0L,
        )
        p.prepare()
        p.play()
        if (AppFolders.isVideo(f)) {
            MediaHub.fullScreen.value = true
            ctx.startActivity(Intent(ctx, MediaActivity::class.java))
        }
    }

    if (addLink) {
        YouTubeLinkDialog(onDismiss = { addLink = false }) { link ->
            addLink = false
            scope.launch {
                val e = withContext(Dispatchers.IO) { MediaLibrary.addYouTube(link) }
                if (e == null) {
                    msg = if (ar) "الرابط ليس رابط يوتيوب صالح" else "Not a YouTube link"
                } else {
                    msg = ""
                    playYouTubeInApp(e)
                }
            }
        }
    }

    val s4 = LocalP4.current
    with(s4) {
        Row(Modifier.fillMaxWidth().fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(16.p)) {
            // ---- folder browser / YouTube search & embedded player 680 px
            P4Card(Modifier.width(680.p).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.p)) {
                    P4Text(
                        if (youTube) tr("▶  YouTube", "▶  يوتيوب") else "📁  /${AppFolders.ROOT}" + cwd.path.removePrefix(root.path).ifEmpty { "/" },
                        20,
                        C_DIM,
                        Modifier.weight(1f),
                        maxLines = 1,
                    )
                    // quick jumps: the two media folders (/NourAlwelayah/audios and /NourAlwelayah/videos) and the YouTube mode
                    P4Button(
                        tr("♪  Audios", "♪  الصوتيات"),
                        Modifier.size(135.p, 52.p),
                        color = if (!youTube && cwd.name == Folder.AUDIOS.dir) C_ACCENT else GREY,
                    ) {
                        youTube = false
                        cwd = AppFolders.dir(ctx, Folder.AUDIOS)
                    }
                    P4Button(
                        tr("🎬  Videos", "🎬  المرئيات"),
                        Modifier.size(140.p, 52.p),
                        color = if (!youTube && cwd.name == Folder.VIDEOS.dir) C_ACCENT else GREY,
                    ) {
                        youTube = false
                        cwd = AppFolders.dir(ctx, Folder.VIDEOS)
                    }
                    P4Button(
                        tr("▶  YouTube", "▶  يوتيوب"),
                        Modifier.size(150.p, 52.p),
                        color = if (youTube) Color(0xFFE53935) else GREY,
                    ) {
                        youTube = true
                    }
                }
                if (youTube) {
                    YouTubePlayerSection(
                        volume = now.volume,
                        savedLinks = tubes,
                        onAddLinkClick = { addLink = true },
                        onBeforeStartYouTube = { c?.pause() },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().padding(top = 8.p)) {
                        if (cwd != root) {
                            item { Row1("📁  ..", false) { cwd = cwd.parentFile ?: root } }
                        }
                        items(items, key = { it.path }) { f ->
                            val playing = now.id == Uri.fromFile(f).toString()
                            Row1((if (f.isDirectory) "📁  " else if (AppFolders.isVideo(f)) "🎬  " else "♪  ") + f.name, playing) {
                                if (f.isDirectory) cwd = f else play(f)
                            }
                        }
                        if (items.isEmpty()) {
                            item { P4Text(tr("(empty folder)", "(مجلد فارغ)"), 20, C_DIM) }
                        }
                    }
                }
            }
            // ---- now playing 550 px
            P4Card(Modifier.weight(1f).fillMaxHeight()) {
                val activeEmbedded = embeddedYt
                Column(verticalArrangement = Arrangement.spacedBy(10.p)) {
                    P4Text(tr("Now playing", "قيد التشغيل الآن"), 20, C_DIM)
                    val displayTitle = when {
                        activeEmbedded != null -> "▶ ${activeEmbedded.title}"
                        now.title.isNotEmpty() -> now.title
                        else -> tr("Stopped", "متوقف")
                    }
                    P4Text(displayTitle, 28, C_TEXT, maxLines = 2)
                    P4Text(
                        if (activeEmbedded != null) {
                            if (ytPlaying) tr("YouTube • Playing", "يوتيوب • قيد التشغيل")
                            else tr("YouTube • Paused", "يوتيوب • متوقف مؤقتاً")
                        } else {
                            "${fmtMs(now.pos)} / ${if (now.dur > 0) fmtMs(now.dur) else "0:00"}"
                        },
                        20,
                        C_DIM,
                    )
                    RoundSlider(
                        if (activeEmbedded == null && now.dur > 0) now.pos.toFloat() / now.dur else 0f,
                        { v -> if (activeEmbedded == null) c?.seekTo((v * now.dur).toLong()) },
                        Modifier.fillMaxWidth(),
                        enabled = activeEmbedded == null && now.dur > 0,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(15.p)) {
                        P4Button("⏮", Modifier.size(115.p, 90.p), size = 34) {
                            if (activeEmbedded == null) c?.seekToPrevious()
                        }
                        val isCurrentlyPlaying = if (activeEmbedded != null) ytPlaying else now.playing
                        P4Button(if (isCurrentlyPlaying) "❚❚" else "▶", Modifier.size(115.p, 90.p), size = 34) {
                            if (activeEmbedded != null) {
                                MediaHub.youTubeCommands.tryEmit("toggle")
                            } else {
                                c?.let {
                                    if (it.isPlaying) {
                                        it.pause()
                                    } else {
                                        pauseYouTubePlayback()
                                        if (it.mediaItemCount > 0) it.play()
                                        else items.firstOrNull { f -> f.isFile }?.let(::play)
                                    }
                                }
                            }
                        }
                        P4Button("⏭", Modifier.size(115.p, 90.p), size = 34) {
                            if (activeEmbedded == null) c?.seekToNextMediaItem()
                        }
                        P4Button("■", Modifier.size(115.p, 90.p), size = 34, color = GREY) {
                            if (activeEmbedded != null) {
                                pauseYouTubePlayback()
                                MediaHub.embeddedYouTubeVideo.value = null
                            }
                            c?.let {
                                it.stop()
                                it.clearMediaItems()
                            }
                        }
                    }
                    var v by remember(now.volume) { mutableFloatStateOf(now.volume) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        P4Text(tr("Volume", "مستوى الصوت"), 20, C_DIM, Modifier.weight(1f))
                        P4Text("${(v * 21).toInt()}", 20, C_TEXT)
                    }
                    RoundSlider(v, { v = it; c?.volume = it; MediaHub.volume.value = it }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.p)) {
                        P4Button(tr("▶  Media player", "▶  مشغل الوسائط"), Modifier.fillMaxWidth().height(64.p), color = Color(0xFF3949AB)) {
                            ctx.startActivity(Intent(ctx, MediaActivity::class.java))
                        }
                    }
                    if (msg.isNotEmpty()) P4Text(msg, 20, C_GOLD)
                    P4Text(
                        tr(
                            "Browse and play audios and videos from /NourAlwelayah/audios and /NourAlwelayah/videos, or switch to YouTube to search and watch embedded videos.",
                            "تصفح وشغّل الصوتيات والمرئيات من /NourAlwelayah/audios و /NourAlwelayah/videos، أو اختر يوتيوب للبحث ومشاهدة المقاطع مباشرة داخل الساعة."
                        ),
                        20,
                        C_DIM,
                    )
                }
            }
        }
    }
}

@Composable
private fun YouTubeLinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val clip = LocalClipboardManager.current
    var text by remember { mutableStateOf(clip.getText()?.text?.takeIf { MediaLibrary.youTubeId(it) != null }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("YouTube link", "رابط يوتيوب")) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                singleLine = true,
                label = { Text("https://youtu.be/...") },
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }) { Text(tr("Add and play", "إضافة وتشغيل")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "إلغاء")) } },
    )
}

@Composable
private fun Row1(text: String, on: Boolean, onClick: () -> Unit) {
    val s4 = LocalP4.current
    with(s4) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.p)
                .background(if (on) C_ACCENT else Color.Transparent, RoundedCornerShape(8.p))
                .clickable(onClick = onClick)
                .height(56.p)
                .padding(horizontal = 10.p),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            P4Text(text, 20, if (on) Color.White else C_TEXT, maxLines = 1)
        }
    }
}
