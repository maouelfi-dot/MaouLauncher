package id.maou.chunktuner;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

/**
 * Menyesuaikan jarak render otomatis berdasarkan waktu per frame.
 * - Rata-rata frame lambat (> SLOW_MS) selama beberapa detik: jarak render turun 1 (minimal MIN_DIST).
 * - Rata-rata frame lancar (< FAST_MS) cukup lama: naik 1 lagi, sampai batas pilihan pemain.
 * Pilihan pemain dipulihkan saat keluar dunia. Tidak memakai mixin (aman lintas mod).
 */
public class MaouChunkTuner implements ClientModInitializer {
    private static final int MIN_DIST = 3;
    private static final double SLOW_MS = Double.parseDouble(System.getProperty("maou.chunk.slowMs", "45"));
    private static final double FAST_MS = Double.parseDouble(System.getProperty("maou.chunk.fastMs", "25"));
    private static final long SLOW_NEED_NS = 3_000_000_000L;
    private static final long FAST_NEED_NS = 25_000_000_000L;
    private static final long COOLDOWN_NS = 6_000_000_000L;

    private long last;
    private double avgMs = 16;
    private long slowFor, fastFor, cooldownUntil;
    private int playerChoice = -1; // pilihan pemain
    private int lastSet = -1;      // nilai terakhir yang kita set

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> onFrame());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> restore(client));
    }

    private void onFrame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            last = 0;
            return;
        }
        if (last == 0) { last = now; return; }
        long dt = now - last;
        last = now;
        if (dt > 1_000_000_000L) return; // layar loading / jeda panjang: abaikan

        avgMs = avgMs * 0.95 + (dt / 1_000_000.0) * 0.05;

        int current = mc.options.renderDistance().get();
        if (playerChoice < 0 || (lastSet >= 0 && current != lastSet)) {
            // pemain mengubah sendiri: itu menjadi batas atas baru
            playerChoice = current;
            lastSet = current;
        }
        if (now < cooldownUntil) return;

        if (avgMs > SLOW_MS) {
            slowFor += dt;
            fastFor = 0;
            if (slowFor >= SLOW_NEED_NS && current > MIN_DIST) {
                apply(mc, current - 1, now);
            }
        } else if (avgMs < FAST_MS) {
            fastFor += dt;
            slowFor = 0;
            if (fastFor >= FAST_NEED_NS && current < playerChoice) {
                apply(mc, current + 1, now);
            }
        } else {
            slowFor = 0;
            fastFor = 0;
        }
    }

    private void apply(Minecraft mc, int value, long now) {
        mc.options.renderDistance().set(value);
        lastSet = value;
        slowFor = 0;
        fastFor = 0;
        cooldownUntil = now + COOLDOWN_NS;
    }

    private void restore(Minecraft mc) {
        if (playerChoice > 0 && lastSet >= 0) {
            mc.options.renderDistance().set(playerChoice);
        }
        last = 0;
        playerChoice = -1;
        lastSet = -1;
        slowFor = 0;
        fastFor = 0;
        avgMs = 16;
    }
}
