/*
 * Maou Launcher - penyetel heap otomatis berdasarkan hasil sesi sebelumnya.
 *
 * Batas heap game di HP RAM kecil adalah persentase dari RAM HP. Persentase tetap tidak cocok untuk semua modpack:
 * terlalu kecil -> OutOfMemoryError (game crash di mod yang di launcher lain jalan), terlalu besar -> Android mematikan
 * game (RAM habis). Maka persentasenya belajar dari hasil sesi terakhir.
 */
package com.movtery.zalithlauncher.game.diagnose

import android.content.Context
import com.movtery.zalithlauncher.context.GlobalContext

object HeapTuner {
    private const val PREFS = "maou_heap"
    private const val KEY = "percent"
    const val DEFAULT = 42
    private const val MIN = 30
    private const val MAX = 56

    private fun prefs() = GlobalContext.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun percent(): Int = runCatching { prefs().getInt(KEY, DEFAULT) }.getOrDefault(DEFAULT).coerceIn(MIN, MAX)

    /** @return pesan untuk pengguna bila persentase diubah, atau null. */
    fun learn(findings: List<Finding>): Finding? {
        val titles = findings.map { it.title }
        val old = percent()
        val new = when {
            titles.any { it.startsWith("RAM game habis") } -> minOf(MAX, old + 6)           // heap kurang
            titles.any { it.startsWith("Game berhenti tiba-tiba") } -> maxOf(MIN, old - 4)   // Android mematikan game
            else -> return null
        }
        if (new == old) return null
        runCatching { prefs().edit().putInt(KEY, new).apply() }
        val up = new > old
        return Finding(
            Severity.INFO,
            if (up) "Alokasi RAM game dinaikkan otomatis ($old% -> $new% dari RAM HP)" else "Alokasi RAM game diturunkan otomatis ($old% -> $new% dari RAM HP)",
            if (up) "Game kehabisan memori Java pada sesi tadi." else "Game dimatikan Android (RAM sistem habis) pada sesi tadi.",
            "Berlaku di peluncuran berikutnya. Kalau masih crash, kurangi jumlah mod."
        )
    }
}
