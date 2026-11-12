package com.movtery.zalithlauncher.game.record

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * [Maou] Tidak dipakai lagi: rekam layar sekarang menyalin frame game langsung (tanpa MediaProjection
 * dan tanpa foreground service). Kelas kosong ini hanya dipertahankan agar file lama yang tertimpa tetap
 * kompatibel; tidak terdaftar di manifest.
 */
class RecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
