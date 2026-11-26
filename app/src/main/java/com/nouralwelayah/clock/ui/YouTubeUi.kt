package com.nouralwelayah.clock.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nouralwelayah.clock.data.YtResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

private val thumbs = LruCache<String, ImageBitmap>(60)

/** A picture from the internet (YouTube thumbnail), kept in memory; dark box while loading / without one. */
@Composable
fun NetImage(url: String, modifier: Modifier) {
    val img by produceState(thumbs.get(url), url) {
        if (value != null || url.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()?.also { thumbs.put(url, it) }
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFF0B1118))) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** One YouTube search result: thumbnail, title, channel and length (or "Playlist"). */
@Composable
fun YtResultRow(r: YtResult, onClick: () -> Unit, thumbW: Dp = 128.dp, title: TextUnit = 16.sp, small: TextUnit = 13.sp) {
    Row(
        Modifier.fillMaxWidth().background(C_CARD, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            NetImage(r.thumbUrl, Modifier.size(thumbW, thumbW * 9 / 16).clip(RoundedCornerShape(6.dp)))
            if (r.duration.isNotEmpty()) Text(r.duration, color = Color.White, fontSize = small,
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                    .background(if (r.playlist) Color(0xCCE53935) else Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(r.title, color = C_TEXT, fontSize = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(r.channel, color = C_DIM, fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
