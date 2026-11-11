package com.movtery.zalithlauncher.ui.screens.game.elements

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.record.RecordService
import com.movtery.zalithlauncher.game.record.ScreenRecorder
import com.movtery.zalithlauncher.ui.components.MenuTextButton

/** Tombol Mulai/Berhenti rekam layar (HD 720p, ringan) di menu game. */
@Composable
fun ScreenRecordButton(
    modifier: Modifier = Modifier,
    color: Color,
    contentColor: Color
) {
    val context = LocalContext.current
    val recording by ScreenRecorder.recording.collectAsState()

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(context, RecordService::class.java)
                .setAction(RecordService.ACTION_START)
                .putExtra(RecordService.EXTRA_CODE, result.resultCode)
                .putExtra(RecordService.EXTRA_DATA, data)
            context.startForegroundService(intent)
        }
    }

    fun askProjection() {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    MenuTextButton(
        modifier = modifier,
        text = stringResource(if (recording) R.string.maou_record_stop else R.string.maou_record_start),
        onClick = {
            if (recording) {
                context.startService(Intent(context, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            } else {
                askProjection()
            }
        },
        color = color,
        contentColor = contentColor,
    )
}
