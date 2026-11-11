/*
 * Maou Launcher - rekam layar ringan: MediaProjection + MediaRecorder (encoder H.264 hardware),
 * maks 1280x720 @30fps ~4 Mbps, tanpa audio. Tidak menambah beban CPU/GPU berarti.
 */
package com.movtery.zalithlauncher.game.record

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ScreenRecorder"

object ScreenRecorder {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    var lastFile: File? = null
        private set

    @Synchronized
    fun start(context: Context, resultCode: Int, data: Intent): Boolean {
        if (_recording.value) return true
        return try {
            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(resultCode, data) ?: return false
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            }, Handler(Looper.getMainLooper()))

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            //batasi sisi terpanjang 1280 px (HD 720p), angka genap untuk encoder
            val scale = minOf(1f, 1280f / maxOf(metrics.widthPixels, metrics.heightPixels))
            val w = (metrics.widthPixels * scale).toInt() and 1.inv()
            val h = (metrics.heightPixels * scale).toInt() and 1.inv()

            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, "Maou_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")

            @Suppress("DEPRECATION")
            val rec = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(4_000_000)
                setVideoFrameRate(30)
                setVideoSize(w, h)
                setOutputFile(file.absolutePath)
                prepare()
            }
            display = proj.createVirtualDisplay(
                "MaouRec", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null
            )
            rec.start()
            projection = proj
            recorder = rec
            lastFile = file
            _recording.value = true
            Logger.info(TAG, "Recording ${w}x$h -> ${file.absolutePath}")
            true
        } catch (e: Throwable) {
            Logger.error(TAG, "Failed to start recording", e)
            release()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!_recording.value && recorder == null) return
        runCatching { recorder?.stop() }
        release()
    }

    private fun release() {
        runCatching { display?.release() }
        runCatching { recorder?.release() }
        runCatching { projection?.stop() }
        display = null
        recorder = null
        projection = null
        _recording.value = false
    }
}
