/*
 * Maou Launcher - penyetelan sekali pakai options.txt untuk HP RAM kecil.
 * Mengurangi kerja CPU saat chunk dimuat (biome blend, simulasi, partikel, bayangan entitas)
 * tanpa menyentuh render distance pilihan pemain. Hanya dijalankan sekali per folder game.
 */
package com.movtery.zalithlauncher.game.launch

import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

private const val TAG = "LowRamTweaks"

internal object LowRamTweaks {
    fun applyOnce(gameDir: File) {
        runCatching {
            val marker = File(gameDir, ".maou_lowram_tuned")
            if (marker.exists()) return
            gameDir.mkdirs()
            val options = File(gameDir, "options.txt")
            val map = LinkedHashMap<String, String>()
            if (options.isFile) {
                options.readLines().forEach { line ->
                    val i = line.indexOf(':')
                    if (i > 0) map[line.substring(0, i)] = line.substring(i + 1)
                }
            }
            fun lower(key: String, max: Int) {
                val cur = map[key]?.toIntOrNull()
                if (cur == null || cur > max) map[key] = max.toString()
            }
            lower("biomeBlendRadius", 0)   //meshing chunk jauh lebih murah
            lower("simulationDistance", 6)
            lower("particles", 2)          //minimal
            map["entityShadows"] = "false"
            map["mipmapLevels"] = map["mipmapLevels"]?.toIntOrNull()?.coerceAtMost(2)?.toString() ?: "2"
            options.writeText(map.entries.joinToString("\n") { "${it.key}:${it.value}" } + "\n")
            marker.createNewFile()
            Logger.info(TAG, "Applied low-RAM options to ${options.absolutePath}")
        }.onFailure { Logger.warning(TAG, "Failed to apply tweaks", it) }
    }

    /** Tulis batas FPS ke options.txt (maxFps). 0/<10 = tanpa batas (260 = nilai "unlimited" Minecraft). */
    fun applyFpsLimit(gameDir: File, limit: Int) {
        runCatching {
            gameDir.mkdirs()
            val options = File(gameDir, "options.txt")
            val lines = if (options.isFile) options.readLines().toMutableList() else mutableListOf()
            val value = if (limit < 10) 260 else limit
            val idx = lines.indexOfFirst { it.startsWith("maxFps:") }
            if (limit < 10 && idx < 0) return
            if (idx >= 0) lines[idx] = "maxFps:$value" else lines.add("maxFps:$value")
            options.writeText(lines.joinToString("\n") + "\n")
        }.onFailure { Logger.warning(TAG, "Failed to apply FPS limit", it) }
    }
}
