/*
 * Maou Launcher - rekam layar "frame game saja".
 *
 * Yang direkam HANYA gambar game (TextureView game), jadi tombol kontrol, tombol melayang, menu, dan
 * penanda REC tetap tampil di layar tetapi TIDAK ikut masuk ke video. Tidak memakai MediaProjection
 * (tanpa dialog izin & tanpa foreground service): frame game disalin ~30x per detik lalu dikirim ke
 * encoder H.264 hardware (MediaRecorder). Lebih berat sedikit daripada merekam layar biasa karena ada
 * salinan GPU->CPU per frame; ukuran dibatasi 1280 px sisi terpanjang. Video saja, tanpa suara.
 */
package com.movtery.zalithlauncher.game.record

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "ScreenRecorder"
private const val FRAME_INTERVAL_MS = 33L

object ScreenRecorder {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** TextureView tempat game digambar; diisi oleh VMActivity. */
    @Volatile
    var gameView: WeakReference<TextureView>? = null

    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var bitmap: Bitmap? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var active = false
    @Volatile private var captureOnMain = false
    private var failures = 0

    var lastFile: File? = null
        private set

    /** Waktu mulai rekam (ms, elapsedRealtime) untuk penunjuk durasi */
    @Volatile
    var startedAt: Long = 0L
        private set

    @Synchronized
    fun start(context: Context): Boolean {
        if (_recording.value) return true
        val view = gameView?.get()
        if (view == null || view.width <= 0 || view.height <= 0 || !view.isAvailable) {
            Logger.warning(TAG, "Game view not ready")
            return false
        }
        return try {
            //batasi sisi terpanjang 1280 px, angka genap untuk encoder; rasio sama dengan layar game
            val scale = minOf(1f, 1280f / maxOf(view.width, view.height))
            val w = (view.width * scale).toInt() and 1.inv()
            val h = (view.height * scale).toInt() and 1.inv()

            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, "Maou_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")

            @Suppress("DEPRECATION")
            val rec = MediaRecorder()
            try {
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                rec.setVideoEncodingBitRate(5_000_000)
                rec.setVideoFrameRate(30)
                rec.setVideoSize(w, h)
                rec.setOutputFile(file.absolutePath)
                rec.prepare()
            } catch (e: Throwable) {
                runCatching { rec.release() }
                throw e
            }
            val surf = rec.surface
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            recorder = rec
            surface = surf
            lastFile = file
            failures = 0
            captureOnMain = false

            rec.start()
            active = true
            startedAt = SystemClock.elapsedRealtime()
            _recording.value = true

            val t = HandlerThread("MaouRecFrames", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            thread = t
            handler = Handler(t.looper).also { it.post(frameTick) }
            Logger.info(TAG, "Recording game frames ${w}x$h -> ${file.absolutePath}")
            true
        } catch (e: Throwable) {
            Logger.error(TAG, "Failed to start recording", e)
            release()
            false
        }
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (!active) return
            val begin = SystemClock.elapsedRealtime()
            try {
                captureFrame()
                failures = 0
            } catch (e: Throwable) {
                failures++
                if (failures == 1) Logger.warning(TAG, "Frame capture failed", e)
                //kalau salinan dari thread latar ditolak, coba lewat thread utama
                if (failures == 3 && !captureOnMain) {
                    captureOnMain = true
                    failures = 0
                }
                if (failures >= 60) {
                    Logger.error(TAG, "Too many capture failures, stopping")
                    Handler(Looper.getMainLooper()).post { stop(null) }
                    return
                }
            }
            val spent = SystemClock.elapsedRealtime() - begin
            handler?.postDelayed(this, maxOf(1L, FRAME_INTERVAL_MS - spent))
        }
    }

    private fun captureFrame() {
        val view = gameView?.get() ?: return
        val bmp = bitmap ?: return
        val surf = surface ?: return
        if (!view.isAvailable) return

        if (captureOnMain) {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    view.getBitmap(bmp)
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(500, TimeUnit.MILLISECONDS)) return
        } else {
            view.getBitmap(bmp)
        }

        //frame game (tanpa kontrol/overlay) -> encoder
        val canvas = surf.lockCanvas(null)
        try {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            surf.unlockCanvasAndPost(canvas)
        }
    }

    /** Berhenti merekam; [context] tidak null = tampilkan lokasi file. */
    @Synchronized
    fun stop(context: Context?) {
        if (!_recording.value && recorder == null) return
        val file = lastFile
        val wasRecording = _recording.value
        active = false
        handler?.removeCallbacksAndMessages(null)
        runCatching { recorder?.stop() }
        release()
        if (wasRecording && context != null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    context.getString(com.movtery.zalithlauncher.R.string.maou_record_saved, file?.absolutePath ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun release() {
        active = false
        runCatching { thread?.quitSafely() }
        runCatching { recorder?.release() }
        runCatching { surface?.release() }
        runCatching { bitmap?.recycle() }
        thread = null
        handler = null
        recorder = null
        surface = null
        bitmap = null
        _recording.value = false
    }
}
