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
            //log launcher/native lebih dari 7 hari
            listOf(PathManager.DIR_LAUNCHER_LOGS, PathManager.DIR_NATIVE_LOGS).forEach { dir ->
                dir.listFiles()?.filter { it.isFile && now - it.lastModified() > 7 * DAY }?.forEach { it.delete() }
            }
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

    private fun purgeOld(dir: File, before: Long) {
        dir.listFiles()?.forEach { f -> if (f.lastModified() < before) f.deleteRecursively() }
    }
}
