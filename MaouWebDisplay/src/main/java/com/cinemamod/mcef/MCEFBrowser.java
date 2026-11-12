package com.cinemamod.mcef;

import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import com.maou.mcef.Bridge;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pengganti MCEFBrowser: tidak ada Chromium di sini. Semua perintah dikirim ke WebView Android milik
 * Maou Launcher lewat {@link Bridge}; gambar hasilnya diunggah ke tekstur OpenGL.
 * Nama & tanda tangan method sengaja sama dengan MCEF agar WebDisplays asli berjalan tanpa diubah.
 */
public class MCEFBrowser {
    private static final AtomicInteger IDS = new AtomicInteger(1);

    public final int id = IDS.getAndIncrement();
    private final MCEFRenderer renderer = new MCEFRenderer(this);
    private final boolean transparent;
    private volatile int width;
    private volatile int height;
    private volatile String url;
    private volatile boolean closed;

    // frame terbaru dari WebView (diisi thread jaringan, dibaca thread render)
    private final Object frameLock = new Object();

    // frame penuh yang dipelihara + rentang baris yang perlu diunggah ulang
    private byte[] frame;
    private int frameW, frameH;
    private int dirtyY0 = Integer.MAX_VALUE, dirtyY1 = -1;
    private boolean fullDirty;
    private volatile long lastRender = System.currentTimeMillis();
    private boolean lastVisible = true;

    // sisi render
    private int textureId = 0;
    private int texW = 0, texH = 0;
    private ByteBuffer upload;

    public MCEFBrowser(String url, boolean transparent, int width, int height) {
        this.transparent = transparent;
        this.width = Math.max(16, width);
        this.height = Math.max(16, height);
        this.url = Bridge.toWeb(url);
        Bridge.register(this);
        Bridge.sendCreate(this);
    }

    // ---- dipakai Bridge ------------------------------------------------------------

    public int bridgeWidth() { return width; }
    public int bridgeHeight() { return height; }
    public String bridgeUrl() { return url; }

    public void bridgeUrlChanged(String webUrl) {
        this.url = webUrl;
    }

    public void bridgeFrame(int w, int h, byte[] rgba) {
        synchronized (frameLock) {
            frame = rgba;
            frameW = w;
            frameH = h;
            fullDirty = true;
        }
    }

    /** Terapkan potongan baris [y0, y0+rows) ke frame penuh. */
    public void bridgeBand(int w, int h, int y0, int rows, byte[] rgba) {
        synchronized (frameLock) {
            if (frame == null || frameW != w || frameH != h || y0 < 0 || y0 + rows > h) return;
            System.arraycopy(rgba, 0, frame, y0 * w * 4, rows * w * 4);
            dirtyY0 = Math.min(dirtyY0, y0);
            dirtyY1 = Math.max(dirtyY1, y0 + rows);
        }
    }

    /** Dipanggil pengawas: kirim status terlihat/tidak ke launcher bila berubah. */
    public void updateVisibility(long now) {
        boolean visible = now - lastRender < 1500;
        if (visible != lastVisible && !closed) {
            lastVisible = visible;
            Bridge.sendVisible(this, visible);
        }
    }

    // ---- API gaya MCEF -------------------------------------------------------------

    public MCEFRenderer getRenderer() { return renderer; }

    public void setCursorChangeListener(MCEFCursorChangeListener listener) { /* kursor Android tidak dipakai */ }

    public void loadURL(String u) {
        this.url = Bridge.toWeb(u);
        Bridge.sendLoad(this, this.url);
    }

    public String getURL() { return Bridge.fromWeb(url); }

    public void executeJavaScript(String code, String url, int line) {
        Bridge.sendJs(this, code);
    }

    public void resize(int w, int h) {
        this.width = Math.max(16, w);
        this.height = Math.max(16, h);
        Bridge.sendResize(this);
    }

    public void close() {
        if (closed) return;
        closed = true;
        Bridge.sendClose(this);
        Bridge.unregister(this);
        if (textureId != 0) Bridge.queueTextureDelete(textureId);
        textureId = 0;
    }

    public void sendMouseMove(int x, int y) { Bridge.sendMouse(this, 0, x, y, 0); }
    public void sendMousePress(int x, int y, int button) { Bridge.sendMouse(this, 1, x, y, button); }
    public void sendMouseRelease(int x, int y, int button) { Bridge.sendMouse(this, 2, x, y, button); }
    public void sendMouseWheel(int x, int y, double delta, int modifiers) { Bridge.sendWheel(this, x, y, delta); }
    public void sendKeyTyped(char c, int modifiers) { Bridge.sendKeyChar(this, c); }
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) { Bridge.sendKey(this, true, keyCode); }
    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) { Bridge.sendKey(this, false, keyCode); }

    // ---- sisi render (thread render OpenGL) ----------------------------------------

    int textureForRender() {
        Bridge.drainTextureDeletes();
        Bridge.isReady(); // menyambung ulang otomatis bila putus
        if (closed) return 0;
        if (textureId == 0) {
            textureId = GL11.glGenTextures();
            GlStateManager._bindTexture(textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
            // 1x1 putih sebelum frame pertama tiba
            ByteBuffer one = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            one.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, one);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            texW = 1;
            texH = 1;
        }
        lastRender = System.currentTimeMillis();
        int w = 0, h = 0, y0 = 0, rows = 0;
        boolean full = false;
        synchronized (frameLock) {
            if (frame != null) {
                if (fullDirty) {
                    full = true;
                    w = frameW; h = frameH; y0 = 0; rows = h;
                } else if (dirtyY1 > dirtyY0) {
                    w = frameW; h = frameH; y0 = dirtyY0; rows = dirtyY1 - dirtyY0;
                }
                if (full || rows > 0) {
                    int len = w * rows * 4;
                    if (upload == null || upload.capacity() < len) {
                        upload = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder());
                    }
                    upload.clear();
                    upload.put(frame, y0 * w * 4, len);
                    upload.flip();
                }
                fullDirty = false;
                dirtyY0 = Integer.MAX_VALUE;
                dirtyY1 = -1;
            }
        }
        if (full) {
            onPaint(upload, w, h);
        } else if (rows > 0) {
            GlStateManager._bindTexture(textureId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, y0, w, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, upload);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        }
        return textureId;
    }

    /**
     * Unggah satu frame ke tekstur. Nama ini sengaja sama dengan MCEFBrowser.onPaint: WebDisplays
     * memasang mixin di method ini untuk mengembalikan pengaturan GL_UNPACK setelah mengecat.
     */
    public void onPaint(ByteBuffer buffer, int w, int h) {
        GlStateManager._bindTexture(textureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        if (w != texW || h != texH) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
            texW = w;
            texH = h;
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
    }
}
