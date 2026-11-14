/*
 * Maou Launcher - FPS Boost Mode (NYATA).
 *
 * Bukan angka palsu: mode ini menurunkan beban render game lewat options.txt sebelum game jalan, sehingga FPS
 * yang ditampilkan game maupun launcher adalah FPS sungguhan. Pengaturan asli pemain disimpan di
 * options.txt.maou-boost dan dikembalikan otomatis saat mode dimatikan.
 */
package com.movtery.zalithlauncher.game.boost

import android.content.Context
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

object FpsBoost {
    private const val TAG = "FpsBoost"
    private const val PREFS = "maou_boost"
    private const val KEY = "fps_boost"
    private const val BACKUP = "options.txt.maou-boost"
    private const val ABSENT = "\u0000absent"

    /** nilai tetap saat boost aktif (jarak render/simulasi dibatasi lewat [capKeys]) */
    private val fixed = linkedMapOf(
        "graphicsMode" to "0",            // grafis cepat
        "ao" to "false",                  // tanpa pencahayaan halus
        "renderClouds" to "\"false\"",
        "particles" to "2",               // partikel minimal
        "entityShadows" to "false",
        "biomeBlendRadius" to "0",
        "mipmapLevels" to "0",
        "entityDistanceScaling" to "0.5"
    )
    private val capKeys = linkedMapOf("renderDistance" to 4, "simulationDistance" to 5)

    private val allKeys get() = fixed.keys + capKeys.keys

    private fun prefs() = GlobalContext.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = runCatching { prefs().getBoolean(KEY, false) }.getOrDefault(false)

    fun setEnabled(v: Boolean) {
        runCatching { prefs().edit().putBoolean(KEY, v).apply() }
    }

    /** Dipanggil sebelum JVM game dijalankan. [args] = argumen akhir (memuat --gameDir). */
    fun applyForLaunch(args: List<String>) {
        val i = args.indexOf("--gameDir")
        if (i < 0 || i + 1 >= args.size) return
        runCatching { apply(File(args[i + 1]), isEnabled()) }
            .onFailure { Logger.warning(TAG, "Gagal menerapkan FPS Boost", it) }
    }

    fun apply(gameDir: File, enabled: Boolean) {
        val options = File(gameDir, "options.txt")
        val backup = File(gameDir, BACKUP)

        if (!enabled) {
            if (backup.exists()) {
                restore(options, backup)
                Logger.info(TAG, "FPS Boost mati: pengaturan asli dikembalikan")
            }
            return
        }

        val lines = if (options.exists()) options.readLines().toMutableList() else mutableListOf()
        fun valueOf(key: String): String? =
            lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')

        //simpan nilai asli SEKALI (jangan menimpa cadangan yang sudah ada)
        if (!backup.exists()) {
            backup.writeText(allKeys.joinToString("\n") { "$it:${valueOf(it) ?: ABSENT}" })
        }

        fun set(key: String, value: String) {
            val idx = lines.indexOfFirst { it.startsWith("$key:") }
            if (idx >= 0) lines[idx] = "$key:$value" else lines.add("$key:$value")
        }
        fixed.forEach { (k, v) -> set(k, v) }
        capKeys.forEach { (k, cap) ->
            val cur = valueOf(k)?.toIntOrNull()
            set(k, (if (cur == null) cap else minOf(cur, cap)).toString())
        }
        options.writeText(lines.joinToString("\n") + "\n")
        Logger.info(TAG, "FPS Boost aktif: grafis cepat, tanpa awan/bayangan entity/partikel, jarak dibatasi")
    }

    private fun restore(options: File, backup: File) {
        val lines = if (options.exists()) options.readLines().toMutableList() else mutableListOf()
        backup.readLines().forEach { row ->
            val key = row.substringBefore(':')
            val value = row.substringAfter(':')
            val idx = lines.indexOfFirst { it.startsWith("$key:") }
            if (value == ABSENT) {
                if (idx >= 0) lines.removeAt(idx)
            } else if (idx >= 0) lines[idx] = "$key:$value" else lines.add("$key:$value")
        }
        options.writeText(lines.joinToString("\n") + "\n")
        backup.delete()
    }
}
