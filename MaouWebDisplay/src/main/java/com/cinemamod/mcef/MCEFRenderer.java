package com.cinemamod.mcef;

public final class MCEFRenderer {
    private final MCEFBrowser browser;

    MCEFRenderer(MCEFBrowser browser) {
        this.browser = browser;
    }

    /** Dipanggil di thread render: unggah frame terbaru lalu kembalikan id tekstur OpenGL. */
    public int getTextureID() {
        return browser.textureForRender();
    }
}
