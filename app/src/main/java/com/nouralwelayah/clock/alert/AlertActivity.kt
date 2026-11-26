package com.nouralwelayah.clock.alert

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nouralwelayah.clock.data.SNOOZE_MIN
import com.nouralwelayah.clock.lockTabletLandscape
import com.nouralwelayah.clock.data.Store
import com.nouralwelayah.clock.ui.ClockPicture
import androidx.compose.ui.platform.LocalConfiguration
import com.nouralwelayah.clock.ui.C_ACCENT
import com.nouralwelayah.clock.ui.C_BG
import com.nouralwelayah.clock.ui.C_DIM
import com.nouralwelayah.clock.ui.C_GOLD
import com.nouralwelayah.clock.ui.C_GREY
import com.nouralwelayah.clock.ui.PrayerClockTheme

/** Full screen azan / alarm, also over the lock screen; shows the video of a video alarm. Closes when the sound stops. */
class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockTabletLandscape()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Store.init(this)
        setContent { PrayerClockTheme { AlertScreen(onDone = { finish() }) } }
    }

    @Composable
    private fun AlertScreen(onDone: () -> Unit) {
        val alert by AlertState.current.collectAsState()
        val video by AlertState.videoSize.collectAsState()
        LaunchedEffect(alert) { if (alert == null) onDone() }
        val a = alert ?: return
        val v = video
        if (v != null) {
            // video alarm: the picture fills the screen (kept in proportion), name and buttons on top
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) = AlertState.setDisplay(h)
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                                override fun surfaceDestroyed(h: SurfaceHolder) = AlertState.setDisplay(null)
                            })
                        }
                    },
                    modifier = Modifier.align(Alignment.Center).fillMaxSize()
                        .wrapContentSize().aspectRatio(v.first.toFloat() / v.second),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color(0xAA000000)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(a.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = C_GOLD, textAlign = TextAlign.Center)
                    Text(a.subtitle, fontSize = 18.sp, color = C_DIM, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Buttons(a, onDone)
                }
            }
            return
        }
        // tablet: the same picture as behind the clock (azan picture / wallpaper)
        val s by Store.settings.collectAsState()
        Box(Modifier.fillMaxSize().background(C_BG)) {
            if (LocalConfiguration.current.screenWidthDp >= 600) ClockPicture(s, a)
            AlertText(a, onDone)
        }
    }

    @Composable
    private fun AlertText(a: ActiveAlert, onDone: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(a.title, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = C_GOLD, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(a.subtitle, fontSize = 22.sp, color = C_DIM, textAlign = TextAlign.Center)
            Spacer(Modifier.height(48.dp))
            Buttons(a, onDone)
        }
    }

    @Composable
    private fun Buttons(a: ActiveAlert, onDone: () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { startService(AlertService.stopIntent(this@AlertActivity)); onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = C_ACCENT),
                modifier = Modifier.size(width = 160.dp, height = 64.dp),
            ) { Text("Stop", fontSize = 22.sp) }
            if (a.isAlarm) Button(
                onClick = { startService(AlertService.snoozeIntent(this@AlertActivity)); onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = C_GREY),
                modifier = Modifier.size(width = 160.dp, height = 64.dp),
            ) { Text("Snooze $SNOOZE_MIN", fontSize = 22.sp) }
        }
    }

    override fun onDestroy() {
        AlertState.setDisplay(null)
        super.onDestroy()
    }

    companion object {
        fun intent(c: Context): Intent = Intent(c, AlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
