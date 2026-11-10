package com.movtery.zalithlauncher.components.jre

import com.movtery.zalithlauncher.path.PathManager
import java.io.File

/**
 * Daftar Java bawaan (Internal-8/17/21/25) yang sengaja dihapus pengguna.
 * Java di daftar ini tidak dipasang ulang saat launcher dibuka, sehingga penyimpanan tetap hemat.
 */
object SkippedJre {
    private fun file() = File(PathManager.DIR_FILES_PRIVATE, "skipped_internal_jre.txt")

    private fun read(): Set<String> = runCatching {
        val f = file()
        if (f.isFile) f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    }.getOrDefault(emptySet())

    private fun write(names: Set<String>) {
        runCatching {
            val f = file()
            if (names.isEmpty()) f.delete() else f.writeText(names.joinToString("\n"))
        }
    }

    @JvmStatic
    fun isSkipped(name: String): Boolean = name in read()

    fun hasAny(): Boolean = read().isNotEmpty()

    /** Catat penghapusan; hanya nama yang cocok dengan Java bawaan yang disimpan. */
    fun markDeleted(name: String) {
        if (Jre.entries.none { it.jreName == name }) return
        write(read() + name)
    }

    /** Pulihkan semua: Java bawaan dipasang ulang saat launcher dibuka berikutnya. */
    fun clear() = write(emptySet())
}
