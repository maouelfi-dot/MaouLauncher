/*
 * Maou Launcher - penghemat penyimpanan.
 * Membersihkan file sementara dan log lama secara otomatis; tidak menyentuh dunia, mod, atau pengaturan.
 */
package com.movtery.zalithlauncher.game.boost

import com.movtery.zalithlauncher.path.PathManager
import java.io.File

object StorageSaver {
    private const val DAY = 24L * 60 * 60 * 1000

    fun cleanup() {
        runCatching {
            val now = System.currentTimeMillis()
            //[2.10.26] log launcher: sisakan HANYA 1 log terbaru per proses (utama / game / jvm ...), sisanya dihapus
            keepNewestLogPerProcess(PathManager.DIR_LAUNCHER_LOGS)
            //log native (crash dump): sisakan 1 file terbaru
            keepNewestFile(PathManager.DIR_NATIVE_LOGS)
            //cache unduhan sementara lebih dari 1 hari
            listOf(
                PathManager.DIR_CACHE_GAME_DOWNLOADER,
                PathManager.DIR_CACHE_MODPACK_DOWNLOADER,
                PathManager.DIR_CACHE_MODPACK_EXPORTER,
                PathManager.DIR_CACHE_SAVE_EXPORTER,
                PathManager.DIR_CACHE_MOD_UPDATER
            ).forEach { dir -> purgeOld(dir, now - DAY) }
            //rekaman layar tidak disentuh (milik pengguna)
        }
    }

    /** Nama log launcher: log_<tanggal>_<proses>[.n].log . File lain (mis. launcher_crash.log, latest_game.log) tidak disentuh. */
    private val LAUNCHER_LOG = Regex("""^log_\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}_(.+?)(\.\d+)?\.log$""")

    /** Hapus semua log launcher lama; sisakan 1 yang terbaru untuk tiap proses. Aman dipanggil berkali-kali. */
    @JvmStatic
    fun keepNewestLogPerProcess(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            files.mapNotNull { f -> LAUNCHER_LOG.matchEntire(f.name)?.let { it.groupValues[1] to f } }
                .groupBy({ it.first }, { it.second })
                .values
                .forEach { group -> group.sortedByDescending { it.lastModified() }.drop(1).forEach { it.delete() } }
        }
    }

    /** Sisakan hanya file terbaru di folder ini. */
    @JvmStatic
    fun keepNewestFile(dir: File) {
        runCatching {
            dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
        }
    }

    /**
     * Dipanggil tepat sebelum game dijalankan: hapus arsip log Minecraft lama (folder logs, debug.log, dll)
     * dan sisakan hanya latest.log + 1 crash-report terbaru. Dunia, mod, dan pengaturan tidak disentuh.
     */
    @JvmStatic
    fun cleanGameDir(gameDir: File) {
        runCatching {
            File(gameDir, "logs").listFiles()?.filter { it.isFile && it.name != "latest.log" }?.forEach { it.delete() }
            keepNewestFile(File(gameDir, "crash-reports"))
        }
    }

    private fun purgeOld(dir: File, before: Long) {
        dir.listFiles()?.forEach { f -> if (f.lastModified() < before) f.deleteRecursively() }
    }
}
