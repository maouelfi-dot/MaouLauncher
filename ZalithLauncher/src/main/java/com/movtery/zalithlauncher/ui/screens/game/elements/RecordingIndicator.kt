package com.movtery.zalithlauncher.ui.screens.game.elements

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movtery.zalithlauncher.game.record.RecordService
import com.movtery.zalithlauncher.game.record.ScreenRecorder
import kotlinx.coroutines.delay

/**
 * [Maou] Penanda "sedang merekam": titik merah berkedip + durasi di pojok kiri atas.
 * Tekan lama untuk berhenti merekam (tombol menu melayang disembunyikan selama merekam
 * supaya tidak ikut terekam).
 */
@Composable
fun RecordingIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsed = (SystemClock.elapsedRealtime() - ScreenRecorder.startedAt) / 1000L
            delay(1000)
        }
    }
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recBlink"
    )
    Row(
        modifier = modifier
            .padding(8.dp)
            .background(Color(0x88000000), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    context.startService(
                        Intent(context, RecordService::class.java).setAction(RecordService.ACTION_STOP)
                    )
                })
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).alpha(blink).background(Color(0xFFFF3B3B), CircleShape))
        Text(
            text = "  REC %02d:%02d".format(elapsed / 60, elapsed % 60),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
