/*
 * Maou Launcher - Web Bridge
 *
 * Jembatan lokal (127.0.0.1) antara mod "Maou WebDisplay (MCEF untuk Android)" di dalam game dan
 * WebView bawaan Android. Mod meminta halaman web, launcher merendernya dengan WebView lalu mengirim
 * gambar (RGBA, dikompres zlib) kembali ke game sebagai tekstur layar WebDisplays. Sentuhan/klik/ketikan
 * dari game diteruskan ke WebView. Tidak butuh PC dan tidak butuh Chromium/MCEF desktop.
 *
 * Protokol (big-endian). Bingkai: int panjang | byte tipe | isi. String = int panjang + UTF-8.
 *  Mod -> launcher: 1 CREATE(id,w,h,url) 2 RESIZE(id,w,h) 3 LOAD(id,url) 4 MOUSE(id,aksi,x,y,tombol)
 *                   5 WHEEL(id,x,y,delta:double) 6 KEYCHAR(id,char) 7 KEYEV(id,turun,vk) 8 JS(id,kode)
 *                   9 CLOSE(id) 10 RES(path,mime,bytes) 11 CONFIG(maxPixels,fps) 12 VISIBLE(id,tampil)
 *  Launcher -> mod: 1 FRAME(id,w,h,panjangZlib,data) 2 URL(id,url)
 *                   3 BAND(id,w,h,y0,baris,panjangZlib,data)  // hanya baris yang berubah
 */
package com.movtery.zalithlauncher.game.webbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.Deflater
import kotlin.math.sqrt

private const val TAG = "WebBridge"

object WebBridgeServer {
    const val PORT = 28501

    /** Batas piksel per layar web & fps; diatur mod lewat CONFIG (Hemat/Normal/Tajam). Default = Normal. */
    @Volatile private var maxPixels = 800 * 450
    @Volatile private var fps = 8

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "WebBridgeIO").apply { isDaemon = true } }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var activity: Activity? = null
    @Volatile private var current: Connection? = null
    private val resources = ConcurrentHashMap<String, Pair<String, ByteArray>>()
    private var container: FrameLayout? = null

    @Synchronized
    fun start(act: Activity) {
        stop()
        activity = act
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 2)
            }
        } catch (e: IOException) {
            Logger.warning(TAG, "Web bridge port $PORT unavailable", e)
            return
        }
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                current?.close()
                val c = Connection(s)
                current = c
                Thread({ c.run() }, "WebBridgeConn").apply { isDaemon = true }.start()
            }
        }, "WebBridgeAccept").apply { isDaemon = true }.start()
        Logger.info(TAG, "Web bridge listening on 127.0.0.1:$PORT")
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        current?.close()
        current = null
        activity = null
        container = null
    }

    private fun ensureContainer(act: Activity): FrameLayout {
        container?.let { return it }
        val c = FrameLayout(act).apply {
            clipChildren = true
            isClickable = false
            isFocusable = false
        }
        //1x1 piksel: tidak terlihat dan tidak menangkap sentuhan, tapi WebView tetap dianggap "tampil" sehingga JS/animasi jalan
        (act.window.decorView as ViewGroup).addView(c, FrameLayout.LayoutParams(1, 1))
        container = c
        return c
    }

    // ---------------------------------------------------------------------------------------

    private class Connection(private val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())
        private val surfaces = HashMap<Int, WebSurface>() // hanya diakses di thread utama
        @Volatile private var closed = false

        fun run() {
            try {
                while (!closed) {
                    val len = input.readInt()
                    if (len < 1 || len > 8 * 1024 * 1024) throw IOException("bad frame $len")
                    val type = input.readUnsignedByte()
                    val payload = ByteArray(len - 1)
                    input.readFully(payload)
                    handle(type, DataInputStream(ByteArrayInputStream(payload)))
                }
            } catch (_: Throwable) {
            } finally {
                close()
            }
        }

        private fun str(d: DataInputStream): String {
            val n = d.readInt()
            val b = ByteArray(n)
            d.readFully(b)
            return String(b, Charsets.UTF_8)
        }

        private fun handle(type: Int, d: DataInputStream) {
            when (type) {
                11 -> {
                    maxPixels = d.readInt().coerceIn(64 * 64, 1280 * 720)
                    fps = d.readInt().coerceIn(1, 20)
                }
                12 -> {
                    val id = d.readInt(); val visible = d.readByte().toInt() != 0
                    post { surfaces[id]?.setVisible(visible) }
                }
                10 -> { // RES tidak perlu thread utama
                    val path = str(d)
                    val mime = str(d)
                    val n = d.readInt()
                    val b = ByteArray(n)
                    d.readFully(b)
                    resources[path] = mime to b
                }
                1 -> {
                    val id = d.readInt(); val w = d.readInt(); val h = d.readInt(); val url = str(d)
                    post { create(id, w, h, url) }
                }
                2 -> {
                    val id = d.readInt(); val w = d.readInt(); val h = d.readInt()
                    post { surfaces[id]?.resize(w, h) }
                }
                3 -> {
                    val id = d.readInt(); val url = str(d)
                    post { surfaces[id]?.load(url) }
                }
                4 -> {
                    val id = d.readInt(); val action = d.readByte().toInt()
                    val x = d.readInt(); val y = d.readInt(); val button = d.readByte().toInt()
                    post { surfaces[id]?.mouse(action, x.toFloat(), y.toFloat(), button) }
                }
                5 -> {
                    val id = d.readInt(); val x = d.readInt(); val y = d.readInt(); val delta = d.readDouble()
                    post { surfaces[id]?.wheel(x.toFloat(), y.toFloat(), delta.toFloat()) }
                }
                6 -> {
                    val id = d.readInt(); val ch = d.readChar()
                    post { surfaces[id]?.typed(ch) }
                }
                7 -> {
                    val id = d.readInt(); val down = d.readByte().toInt() != 0; val vk = d.readInt()
                    post { surfaces[id]?.key(down, vk) }
                }
                8 -> {
                    val id = d.readInt(); val js = str(d)
                    post { surfaces[id]?.js(js) }
                }
                9 -> {
                    val id = d.readInt()
                    post { surfaces.remove(id)?.destroy() }
                }
            }
        }

        private fun post(block: () -> Unit) {
            main.post { if (!closed) runCatching(block).onFailure { Logger.warning(TAG, "bridge op failed", it) } }
        }

        private fun create(id: Int, w: Int, h: Int, url: String) {
            val act = activity ?: return
            surfaces.remove(id)?.destroy()
            surfaces[id] = WebSurface(this, act, id, w.coerceIn(16, 4096), h.coerceIn(16, 4096), url)
        }

        fun sendFrame(id: Int, w: Int, h: Int, zdata: ByteArray, zlen: Int) {
            if (closed) return
            synchronized(output) {
                try {
                    output.writeInt(1 + 4 + 4 + 4 + 4 + zlen)
                    output.writeByte(1)
                    output.writeInt(id); output.writeInt(w); output.writeInt(h); output.writeInt(zlen)
                    output.write(zdata, 0, zlen)
                    output.flush()
                } catch (_: IOException) {
                    close()
                }
            }
        }

        fun sendBand(id: Int, w: Int, h: Int, y0: Int, rows: Int, zdata: ByteArray, zlen: Int) {
            if (closed) return
            synchronized(output) {
                try {
                    output.writeInt(1 + 4 * 6 + zlen)
                    output.writeByte(3)
                    output.writeInt(id); output.writeInt(w); output.writeInt(h)
                    output.writeInt(y0); output.writeInt(rows); output.writeInt(zlen)
                    output.write(zdata, 0, zlen)
                    output.flush()
                } catch (_: IOException) {
                    close()
                }
            }
        }

        fun sendUrl(id: Int, url: String) {
            if (closed) return
            val b = url.toByteArray(Charsets.UTF_8)
            synchronized(output) {
                try {
                    output.writeInt(1 + 4 + 4 + b.size)
                    output.writeByte(2)
                    output.writeInt(id); output.writeInt(b.size); output.write(b)
                    output.flush()
                } catch (_: IOException) {
                    close()
                }
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            main.post {
                surfaces.values.forEach { it.destroy() }
                surfaces.clear()
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
    private class WebSurface(
        val conn: Connection,
        val act: Activity,
        val id: Int,
        var w: Int,
        var h: Int,
        initialUrl: String
    ) {
        private val web = WebView(act)
        private var bitmap: Bitmap? = null
        private var pixels: ByteBuffer? = null
        private var rowHash: IntArray? = null
        private var needFull = true
        private var visible = true
        private var unchanged = 0
        private var destroyed = false
        private var downTime = 0L
        private var pressedButtons = 0

        private val tick = object : Runnable {
            override fun run() {
                if (destroyed) return
                if (visible) capture()
                val base = 1000L / fps
                main.postDelayed(this, if (!visible) 500L else if (unchanged > 12) maxOf(base * 4, 500L) else base)
            }
        }

        init {
            web.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            web.setBackgroundColor(Color.WHITE)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                //tampilan desktop supaya halaman memakai tata letak lebar (layar WebDisplays berbentuk lebar)
                userAgentString = userAgentString.replace("; wv", "").replace(" Mobile", "")
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    if (u.host == "webdisplays.mod") {
                        val res = resources[u.path ?: "/"] ?: return null
                        return WebResourceResponse(res.first, "utf-8", ByteArrayInputStream(res.second))
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    //halaman WebDisplays memanggil window.cefQuery (khusus MCEF); sediakan penampung agar tidak error
                    view.evaluateJavascript(
                        "if(!window.cefQuery)window.cefQuery=function(o){if(o&&o.onFailure)o.onFailure(-1,'tidak didukung di Android')};",
                        null
                    )
                    conn.sendUrl(id, url)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    conn.sendUrl(id, url)
                }
            }
            ensureContainer(act).addView(web, FrameLayout.LayoutParams(w, h))
            layoutNow()
            if (initialUrl.isNotBlank()) web.loadUrl(initialUrl)
            main.postDelayed(tick, 200L)
        }

        private fun layoutNow() {
            web.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, w, h)
        }

        fun resize(nw: Int, nh: Int) {
            w = nw.coerceIn(16, 4096)
            h = nh.coerceIn(16, 4096)
            web.layoutParams = FrameLayout.LayoutParams(w, h)
            layoutNow()
            needFull = true
        }

        /** Layar tidak terlihat pemain: berhenti menangkap gambar & jeda WebView (hemat CPU/baterai/RAM). */
        fun setVisible(v: Boolean) {
            if (v == visible) return
            visible = v
            if (v) {
                web.onResume()
                needFull = true
                unchanged = 0
            } else {
                web.onPause()
            }
        }

        fun load(url: String) {
            web.loadUrl(url)
        }

        fun js(code: String) {
            web.evaluateJavascript(code, null)
        }

        // ---- entrada ------------------------------------------------------------------

        private fun obtain(action: Int, x: Float, y: Float, buttons: Int): MotionEvent {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = 1f
            })
            return MotionEvent.obtain(
                downTime.takeIf { it != 0L } ?: now, now, action, 1, props, coords,
                0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
            )
        }

        /** aksi: 0 = gerak, 1 = tekan, 2 = lepas. tombol: 0 kiri, 1 kanan, 2 tengah */
        fun mouse(action: Int, x: Float, y: Float, button: Int) {
            val mask = when (button) {
                1 -> MotionEvent.BUTTON_SECONDARY
                2 -> MotionEvent.BUTTON_TERTIARY
                else -> MotionEvent.BUTTON_PRIMARY
            }
            when (action) {
                1 -> {
                    web.requestFocus()
                    pressedButtons = pressedButtons or mask
                    web.dispatchTouchEvent(obtain(MotionEvent.ACTION_DOWN, x, y, pressedButtons))
                }
                2 -> {
                    web.dispatchTouchEvent(obtain(MotionEvent.ACTION_UP, x, y, 0))
                    pressedButtons = pressedButtons and mask.inv()
                }
                else -> {
                    if (pressedButtons != 0) web.dispatchTouchEvent(obtain(MotionEvent.ACTION_MOVE, x, y, pressedButtons))
                    else web.dispatchGenericMotionEvent(obtain(MotionEvent.ACTION_HOVER_MOVE, x, y, 0))
                }
            }
        }

        fun wheel(x: Float, y: Float, delta: Float) {
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, delta)
            })
            val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0)
            web.dispatchGenericMotionEvent(ev)
        }

        /** karakter yang diketik (huruf/angka/simbol) lewat InputConnection seperti keyboard Android */
        fun typed(ch: Char) {
            if (ch.code < 32 || ch.code == 127) return
            web.requestFocus()
            val ic = web.onCreateInputConnection(EditorInfo())
            if (ic != null) {
                ic.commitText(ch.toString(), 1)
            } else {
                @Suppress("DEPRECATION")
                web.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), ch.toString(), 0, 0))
            }
        }

        /** hanya tombol non-karakter (hapus, enter, panah, dst.); huruf datang lewat [typed] */
        fun key(down: Boolean, vk: Int) {
            val code = when (vk) {
                8 -> KeyEvent.KEYCODE_DEL
                10, 13 -> KeyEvent.KEYCODE_ENTER
                9 -> KeyEvent.KEYCODE_TAB
                27 -> KeyEvent.KEYCODE_ESCAPE
                127 -> KeyEvent.KEYCODE_FORWARD_DEL
                37 -> KeyEvent.KEYCODE_DPAD_LEFT
                38 -> KeyEvent.KEYCODE_DPAD_UP
                39 -> KeyEvent.KEYCODE_DPAD_RIGHT
                40 -> KeyEvent.KEYCODE_DPAD_DOWN
                36 -> KeyEvent.KEYCODE_MOVE_HOME
                35 -> KeyEvent.KEYCODE_MOVE_END
                33 -> KeyEvent.KEYCODE_PAGE_UP
                34 -> KeyEvent.KEYCODE_PAGE_DOWN
                else -> return
            }
            val now = SystemClock.uptimeMillis()
            web.dispatchKeyEvent(KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0))
        }

        // ---- tangkap gambar -----------------------------------------------------------

        private fun capture() {
            val scale = minOf(1f, sqrt(maxPixels.toFloat() / (w.toFloat() * h)))
            val sw = (w * scale).toInt().coerceAtLeast(1)
            val sh = (h * scale).toInt().coerceAtLeast(1)
            var bmp = bitmap
            if (bmp == null || bmp.width != sw || bmp.height != sh) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                bitmap = bmp
                pixels = ByteBuffer.allocate(sw * sh * 4)
                rowHash = IntArray(sh)
                needFull = true
            }
            layoutNow()
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            canvas.scale(sw.toFloat() / w, sh.toFloat() / h)
            web.draw(canvas)
            val buf = pixels ?: return
            buf.clear()
            bmp.copyPixelsToBuffer(buf)
            val raw = buf.array()

            //hash tiap baris -> cari baris pertama & terakhir yang berubah
            val hashes = rowHash ?: return
            val ints = ByteBuffer.wrap(raw).asIntBuffer()
            var first = -1
            var last = -1
            for (y in 0 until sh) {
                var hsh = 17
                val base = y * sw
                for (x in 0 until sw) hsh = hsh * 31 + ints.get(base + x)
                if (hsh != hashes[y]) {
                    hashes[y] = hsh
                    if (first < 0) first = y
                    last = y
                }
            }
            if (first < 0 && !needFull) {
                unchanged++
                return
            }
            unchanged = 0
            val full = needFull
            needFull = false
            val y0 = if (full) 0 else first
            val rows = if (full) sh else last - first + 1
            val data = raw.copyOfRange(y0 * sw * 4, (y0 + rows) * sw * 4)
            io.execute {
                runCatching {
                    val deflater = Deflater(1)
                    deflater.setInput(data)
                    deflater.finish()
                    val out = ByteArrayOutputStream(data.size / 4 + 64)
                    val tmp = ByteArray(32 * 1024)
                    while (!deflater.finished()) {
                        val n = deflater.deflate(tmp)
                        out.write(tmp, 0, n)
                    }
                    deflater.end()
                    val z = out.toByteArray()
                    if (full) conn.sendFrame(id, sw, sh, z, z.size)
                    else conn.sendBand(id, sw, sh, y0, rows, z, z.size)
                }
            }
        }

        fun destroy() {
            if (destroyed) return
            destroyed = true
            main.removeCallbacks(tick)
            runCatching {
                (web.parent as? ViewGroup)?.removeView(web)
                web.stopLoading()
                web.destroy()
            }
            bitmap?.recycle()
            bitmap = null
        }
    }
}
