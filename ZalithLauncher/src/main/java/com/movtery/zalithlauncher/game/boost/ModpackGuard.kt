/*
 * Maou Launcher - penjaga modpack besar.
 * Menghitung jumlah mod dan menyesuaikan JVM supaya modpack besar tidak FC di HP RAM kecil.
 */
package com.movtery.zalithlauncher.game.boost

import java.io.File

object ModpackGuard {
    /** Jumlah file .jar di folder mods (0 bila tidak ada). */
    fun countMods(gameDir: File): Int = runCatching {
        File(gameDir, "mods").listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.size ?: 0
    }.getOrDefault(0)

    /**
     * Tambahkan argumen JVM tambahan untuk modpack besar.
     * Hanya aktif di HP RAM < 3 GB dengan >= 40 mod; argumen yang sudah diisi pengguna tidak ditimpa.
     */
    fun apply(args: MutableList<String>, gameDir: File, lowRam: Boolean): Int {
        val mods = countMods(gameDir)
        if (!lowRam || mods < 40) return mods
        fun addIfAbsent(prefix: String, arg: String) { if (args.none { it.startsWith(prefix) }) args.add(arg) }
        //Kompilasi JIT paling boros RAM/CPU saat memuat ratusan class mod: pakai compiler ringan (C1) dan batasi code cache
        addIfAbsent("-XX:TieredStopAtLevel", "-XX:TieredStopAtLevel=1")
        addIfAbsent("-XX:ReservedCodeCacheSize", "-XX:ReservedCodeCacheSize=96m")
        //modpack besar memuat banyak class: jangan biarkan metaspace tumbuh pelan-pelan lewat GC penuh
        addIfAbsent("-XX:MaxMetaspaceSize", "-XX:MaxMetaspaceSize=384m")
        addIfAbsent("-Xss", "-Xss1m")
        return mods
    }
}
