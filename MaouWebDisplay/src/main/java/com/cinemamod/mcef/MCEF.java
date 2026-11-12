package com.cinemamod.mcef;

import com.cinemamod.mcef.listeners.MCEFInitListener;
import com.maou.mcef.Bridge;

/**
 * Pengganti kelas MCEF asli. WebDisplays memanggilnya lewat refleksi:
 * isInitialized(), scheduleForInit(listener), createBrowser(url, transparent, w, h).
 */
public final class MCEF {
    private MCEF() {}

    public static boolean isInitialized() {
        return Bridge.isReady();
    }

    public static void scheduleForInit(MCEFInitListener listener) {
        Bridge.scheduleReady(listener);
    }

    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        return new MCEFBrowser(url, transparent, width, height);
    }
}
