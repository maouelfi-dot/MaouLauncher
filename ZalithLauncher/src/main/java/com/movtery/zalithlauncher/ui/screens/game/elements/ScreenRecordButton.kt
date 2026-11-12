package com.movtery.zalithlauncher.ui.screens.game.elements

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.record.ScreenRecorder
import com.movtery.zalithlauncher.ui.components.MenuTextButton

/**
 * Tombol Mulai/Berhenti rekam layar di menu game. Yang direkam hanya frame game:
 * tombol, kontrol, dan menu tetap tampil di layar tetapi tidak masuk ke video.
 */
@Composable
fun ScreenRecordButton(
    modifier: Modifier = Modifier,
    color: Color,
    contentColor: Color
) {
    val context = LocalContext.current
    val recording by ScreenRecorder.recording.collectAsState()

    MenuTextButton(
        modifier = modifier,
        text = stringResource(if (recording) R.string.maou_record_stop else R.string.maou_record_start),
        onClick = {
            if (recording) {
                ScreenRecorder.stop(context)
            } else if (!ScreenRecorder.start(context)) {
                Toast.makeText(context, "Gagal memulai rekaman", Toast.LENGTH_SHORT).show()
            }
        },
        color = color,
        contentColor = contentColor,
    )
}
