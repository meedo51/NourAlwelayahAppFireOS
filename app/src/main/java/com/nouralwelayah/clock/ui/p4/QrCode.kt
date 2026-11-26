package com.nouralwelayah.clock.ui.p4

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-contrast QR Code composable that encodes [text] asynchronously on [Dispatchers.Default]
 * whenever [text] changes, rendered on a rounded white/cream container with [containerPadding]
 * for instant camera scanning.
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xFFFAF8F5),
    containerPadding: Dp = 8.dp,
    cornerRadius: Dp = 12.dp,
) {
    val qrBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = text) {
        if (text.isBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = QRCodeWriter().encode(
                    text,
                    BarcodeFormat.QR_CODE,
                    256,
                    256,
                    mapOf(
                        EncodeHintType.MARGIN to 1,
                        EncodeHintType.CHARACTER_SET to "UTF-8",
                    ),
                )
                val w = matrix.width
                val h = matrix.height
                val pixels = IntArray(w * h)
                val darkPixel = 0xFF0C1823.toInt() // Deep BrandNavy for ultra-crisp contrast
                val lightPixel = 0xFFFAF8F5.toInt() // Warm white/cream background
                for (y in 0 until h) {
                    val rowOffset = y * w
                    for (x in 0 until w) {
                        pixels[rowOffset + x] = if (matrix[x, y]) darkPixel else lightPixel
                    }
                }
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor, RoundedCornerShape(cornerRadius))
            .padding(containerPadding),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = qrBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "QR code for $text",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
    }
}
