package com.maou.mcef;

import com.cinemamod.mcef.MCEFBrowser;
import com.cinemamod.mcef.listeners.MCEFInitListener;
import org.lwjgl.opengl.GL11;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.Inflater;

/**
 * Klien jembatan ke Maou Launcher (WebView Android) lewat soket lokal 127.0.0.1.
 * Protokolnya didokumentasikan di WebBridgeServer.kt pada Maou Launcher.
 */
public final class Bridge {
    private Bridge() {}

    private static final int PORT = Integer.getInteger("maou.webbridge.port", 28501);
    private static final String WEB_HOST = "https://webdisplays.mod/";
    private static final String MOD_PREFIX = "mod://webdisplays/";
    private static final String[] RESOURCES = {
            "main.html", "io.html", "blacklisted.html", "wdlib.js", "jquery.js",
            "webdisplays.png", "front.png", "side.png"
    };

    private static final Object LOCK = new Object();
    private static volatile Socket socket;
    private static volatile DataOutputStream out;
    private static volatile boolean connecting;
    private static volatile long lastAttempt;
    private static final ConcurrentHashMap<Integer, MCEFBrowser> BROWSERS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Integer> TEXTURE_DELETES = new ConcurrentLinkedQueue<>();

    // ---- status -------------------------------------------------------------------

    /** true bila sudah tersambung ke Maou Launcher. Bila belum, mencoba menyambung di latar belakang. */
    public static boolean isReady() {
        if (socket != null && !socket.isClosed()) return true;
        connectAsync();
        return false;
    }

    public static void scheduleReady(MCEFInitListener listener) {
        Thread t = new Thread(() -> {
            boolean ok = false;
            for (int i = 0; i < 8 && !ok; i++) {
                ok = connect();
                if (!ok) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            }
            listener.onInit(ok);
        }, "MaouMcef-init");
        t.setDaemon(true);
        t.start();
    }

    private static void connectAsync() {
        long now = System.currentTimeMillis();
        if (connecting || now - lastAttempt < 2000) return;
        connecting = true;
        lastAttempt = now;
        Thread t = new Thread(() -> {
            try { connect(); } finally { connecting = false; }
        }, "MaouMcef-connect");
        t.setDaemon(true);
        t.start();
    }

    private static boolean connect() {
        synchronized (LOCK) {
            if (socket != null && !socket.isClosed()) return true;
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 800);
                DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
                socket = s;
                out = o;
                Thread reader = new Thread(() -> readLoop(s), "MaouMcef-reader");
                reader.setDaemon(true);
                reader.start();
                sendResources();
                sendConfig();
                // sambungan baru (mis. game dimulai ulang): buat ulang semua browser yang masih hidup
                for (MCEFBrowser b : BROWSERS.values()) sendCreate(b);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private static void disconnect(Socket s) {
        synchronized (LOCK) {
            try { s.close(); } catch (IOException ignored) {}
            if (socket == s) {
                socket = null;
                out = null;
            }
        }
    }

    // ---- penerimaan ---------------------------------------------------------------

    private static void readLoop(Socket s) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16))) {
            while (!s.isClosed()) {
                int len = in.readInt();
                int type = in.readUnsignedByte();
                if (len < 1 || len > 64 * 1024 * 1024) throw new IOException("bad frame");
                byte[] payload = new byte[len - 1];
                in.readFully(payload);
                DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(payload));
                if (type == 1) { // FRAME
                    int id = d.readInt(), w = d.readInt(), h = d.readInt(), zlen = d.readInt();
                    MCEFBrowser b = BROWSERS.get(id);
                    if (b == null) continue;
                    byte[] z = new byte[zlen];
                    d.readFully(z);
                    byte[] rgba = inflate(z, w * h * 4);
                    if (rgba != null) b.bridgeFrame(w, h, rgba);
                } else if (type == 3) { // BAND (hanya baris yang berubah)
                    int id = d.readInt(), w = d.readInt(), h = d.readInt(), y0 = d.readInt(), rows = d.readInt(), zlen = d.readInt();
                    MCEFBrowser b = BROWSERS.get(id);
                    if (b == null) continue;
                    byte[] z = new byte[zlen];
                    d.readFully(z);
                    byte[] rgba = inflate(z, w * rows * 4);
                    if (rgba != null) b.bridgeBand(w, h, y0, rows, rgba);
                } else if (type == 2) { // URL
                    int id = d.readInt();
                    int n = d.readInt();
                    byte[] u = new byte[n];
                    d.readFully(u);
                    MCEFBrowser b = BROWSERS.get(id);
                    if (b != null) b.bridgeUrlChanged(new String(u, StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
        } finally {
            disconnect(s);
        }
    }

    private static byte[] inflate(byte[] z, int expected) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            byte[] res = new byte[expected];
            int off = 0;
            while (off < expected && !inf.finished()) {
                int n = inf.inflate(res, off, expected - off);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                off += n;
            }
            return off == expected ? res : null;
        } catch (Exception e) {
            return null;
        } finally {
            inf.end();
        }
    }

    // ---- pengiriman ---------------------------------------------------------------

    private interface Body { void write(DataOutputStream d) throws IOException; }

    private static void send(int type, Body body) {
        DataOutputStream o = out;
        if (o == null) return;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bo);
            d.writeByte(type);
            body.write(d);
            d.flush();
            byte[] bytes = bo.toByteArray();
            synchronized (o) {
                o.writeInt(bytes.length);
                o.write(bytes);
                o.flush();
            }
        } catch (IOException e) {
            Socket s = socket;
            if (s != null) disconnect(s);
        }
    }

    private static void writeStr(DataOutputStream d, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        d.writeInt(b.length);
        d.write(b);
    }

    private static void sendResources() {
        for (String name : RESOURCES) {
            try (InputStream in = Bridge.class.getResourceAsStream("/assets/webdisplays/html/" + name)) {
                if (in == null) continue;
                byte[] data = in.readAllBytes();
                String mime = name.endsWith(".html") ? "text/html" : name.endsWith(".js") ? "application/javascript"
                        : name.endsWith(".png") ? "image/png" : "application/octet-stream";
                send(10, d -> { writeStr(d, "/" + name); writeStr(d, mime); d.writeInt(data.length); d.write(data); });
            } catch (IOException ignored) {
            }
        }
    }

    private static void sendConfig() {
        final int[] c = Quality.current();
        send(11, d -> { d.writeInt(c[0]); d.writeInt(c[1]); });
    }

    public static void sendVisible(MCEFBrowser b, boolean visible) { send(12, d -> { d.writeInt(b.id); d.writeByte(visible ? 1 : 0); }); }

    /** Memberi tahu launcher layar mana yang sedang terlihat; yang tidak terlihat dijeda (hemat RAM/CPU). */
    private static void startVisibilityWatcher() {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(700); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                for (MCEFBrowser b : BROWSERS.values()) b.updateVisibility(now);
            }
        }, "MaouMcef-visibility");
        t.setDaemon(true);
        t.start();
    }
    static { startVisibilityWatcher(); }

    public static void sendCreate(MCEFBrowser b) {
        if (!isReady() && out == null) return;
        send(1, d -> { d.writeInt(b.id); d.writeInt(b.bridgeWidth()); d.writeInt(b.bridgeHeight()); writeStr(d, b.bridgeUrl()); });
    }

    public static void sendResize(MCEFBrowser b) { send(2, d -> { d.writeInt(b.id); d.writeInt(b.bridgeWidth()); d.writeInt(b.bridgeHeight()); }); }
    public static void sendLoad(MCEFBrowser b, String url) { send(3, d -> { d.writeInt(b.id); writeStr(d, url); }); }
    public static void sendMouse(MCEFBrowser b, int action, int x, int y, int button) {
        send(4, d -> { d.writeInt(b.id); d.writeByte(action); d.writeInt(x); d.writeInt(y); d.writeByte(button); });
    }
    public static void sendWheel(MCEFBrowser b, int x, int y, double delta) {
        send(5, d -> { d.writeInt(b.id); d.writeInt(x); d.writeInt(y); d.writeDouble(delta); });
    }
    public static void sendKeyChar(MCEFBrowser b, char c) { send(6, d -> { d.writeInt(b.id); d.writeChar(c); }); }
    public static void sendKey(MCEFBrowser b, boolean down, int vk) { send(7, d -> { d.writeInt(b.id); d.writeByte(down ? 1 : 0); d.writeInt(vk); }); }
    public static void sendJs(MCEFBrowser b, String code) { send(8, d -> { d.writeInt(b.id); writeStr(d, code); }); }
    public static void sendClose(MCEFBrowser b) { send(9, d -> d.writeInt(b.id)); }

    // ---- daftar browser & URL ------------------------------------------------------

    public static void register(MCEFBrowser b) { BROWSERS.put(b.id, b); }
    public static void unregister(MCEFBrowser b) { BROWSERS.remove(b.id); }

    /** mod://webdisplays/x  ->  https://webdisplays.mod/x (dilayani dari aset mod oleh launcher) */
    public static String toWeb(String url) {
        if (url == null) return "";
        return url.startsWith(MOD_PREFIX) ? WEB_HOST + url.substring(MOD_PREFIX.length()) : url;
    }

    public static String fromWeb(String url) {
        if (url == null) return "";
        return url.startsWith(WEB_HOST) ? MOD_PREFIX + url.substring(WEB_HOST.length()) : url;
    }

    // ---- hapus tekstur di thread render -------------------------------------------

    public static void queueTextureDelete(int id) { TEXTURE_DELETES.add(id); }

    public static void drainTextureDeletes() {
        Integer id;
        while ((id = TEXTURE_DELETES.poll()) != null) GL11.glDeleteTextures(id);
    }
}
