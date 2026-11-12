package com.cinemamod.mcef.listeners;

/** Kompatibel dengan antarmuka MCEF asli yang dipanggil WebDisplays lewat refleksi. */
public interface MCEFInitListener {
    void onInit(boolean success);
}
