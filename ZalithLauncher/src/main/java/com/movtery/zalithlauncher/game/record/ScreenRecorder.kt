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
            //[Maou] HP RAM kecil (<3GB): video 854 px & bitrate lebih rendah supaya game tidak ikut berat saat merekam
            val totalMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .let { am -> android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024) }
            val lowEnd = totalMb < 3072
            val maxSide = if (lowEnd) 854f else 1280f
            val scale = minOf(1f, maxSide / maxOf(view.width, view.height))
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
                rec.setVideoEncodingBitRate(if (lowEnd) 2_500_000 else 5_000_000)
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

            val t = HandlerThread("MaouRecFrames", Process.THREAD_PRIORITY_LESS_FAVORABLE).also { it.start() }
            thread = t
            handler = Handler(t.looper).also { it.post(frameTick) }
            nextAt = SystemClock.elapsedRealtime()
            interval = if (lowEnd) 50L else FRAME_INTERVAL_MS
            gotFrame = false
            Logger.info(TAG, "Recording game frames ${w}x$h -> ${file.absolutePath}")
            true
        } catch (e: Throwable) {
            Logger.error(TAG, "Failed to start recording", e)
            release()
            false
        }
    }

    /**
     * [Maou 2.10.16] Penangkap frame baru.
     *
     * Cara lama (TextureView.getBitmap dari thread latar) balapan dengan thread render: hasilnya garis-garis warna,
     * blok hijau/magenta acak, dan langit hitam di awal video; lambat pula (~12 fps) sehingga video patah-patah
     * walau game 30 fps. Sekarang memakai PixelCopy: salinan dilakukan GPU secara sinkron dengan buffer game
     * (tanpa tearing), langsung diskalakan ke ukuran video, dan tidak membebani thread UI. Frame dikirim ke
     * encoder lewat canvas hardware. Bila PixelCopy gagal berkali-kali, otomatis kembali ke cara lama di thread utama.
     */
    private var copySurface: Surface? = null
    private var copySource: android.graphics.SurfaceTexture? = null
    private var nextAt = 0L
    private var gotFrame = false
    @Volatile private var frameStart = 0L
    @Volatile private var interval = 40L

    private fun scheduleNext() {
        val h = handler ?: return
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        //[Maou] Rekam tidak boleh membuat GAME patah-patah: ukur berapa lama satu frame dikerjakan lalu atur jarak antar
        //frame agar perekam memakai paling banyak ~1/3 waktu (HP kuat: tetap ~30 fps; HP lemah: turun ke 10-20 fps).
        val spent = now - frameStart
        interval = ((interval * 3 + (spent * 3).coerceIn(FRAME_INTERVAL_MS, 100L)) / 4)
        if (nextAt < now - interval) nextAt = now //tertinggal: jangan mengejar dengan ledakan frame
        nextAt += interval
        h.postDelayed(frameTick, maxOf(0L, nextAt - now))
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (!active) return
            frameStart = SystemClock.elapsedRealtime()
            try {
                if (captureOnMain) captureLegacy() else capturePixelCopy()
            } catch (e: Throwable) {
                onCaptureFailure(e)
                scheduleNext()
            }
        }
    }

    private fun onCaptureFailure(e: Throwable?) {
        failures++
        if (failures == 1 && e != null) Logger.warning(TAG, "Frame capture failed", e)
        if (failures == 8 && !captureOnMain) {
            Logger.warning(TAG, "PixelCopy unavailable, falling back to getBitmap")
            captureOnMain = true
            failures = 0
        }
        if (failures >= 90) {
            Logger.error(TAG, "Too many capture failures, stopping")
            Handler(Looper.getMainLooper()).post { stop(null) }
        }
    }

    private fun capturePixelCopy() {
        val view = gameView?.get() ?: run { scheduleNext(); return }
        val bmp = bitmap ?: return
        val st = view.surfaceTexture
        if (!view.isAvailable || st == null) { scheduleNext(); return }
        if (copySource !== st || copySurface?.isValid != true) {
            runCatching { copySurface?.release() }
            copySurface = Surface(st)
            copySource = st
        }
        val src = copySurface ?: return
        android.view.PixelCopy.request(src, bmp, { result ->
            if (result == android.view.PixelCopy.SUCCESS) {
                failures = 0
                gotFrame = true
                runCatching { pushFrame(bmp) }.onFailure { onCaptureFailure(it) }
            } else if (result == android.view.PixelCopy.ERROR_SOURCE_NO_DATA && !gotFrame) {
                //game belum menggambar apa pun: lewati (jangan tulis frame hitam)
            } else {
                onCaptureFailure(null)
            }
            scheduleNext()
        }, handler ?: return)
    }

    private fun captureLegacy() {
        val view = gameView?.get()
        val bmp = bitmap
        if (view != null && bmp != null && view.isAvailable) {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    view.getBitmap(bmp)
                } catch (e: Throwable) {
                    failures++
                } finally {
                    latch.countDown()
                }
            }
            if (latch.await(500, TimeUnit.MILLISECONDS)) pushFrame(bmp)
        }
        scheduleNext()
    }

    /** Frame game (tanpa kontrol/overlay) -> encoder. */
    private fun pushFrame(bmp: Bitmap) {
        val surf = surface ?: return
        val canvas = try {
            surf.lockHardwareCanvas()
        } catch (_: Throwable) {
            surf.lockCanvas(null)
        }
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
        runCatching { copySurface?.release() }
        copySurface = null
        copySource = null
        runCatching { bitmap?.recycle() }
        thread = null
        handler = null
        recorder = null
        surface = null
        bitmap = null
        _recording.value = false
    }
}
