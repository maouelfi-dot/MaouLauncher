/*
 * Maou Launcher - Diagnosa masalah game (tidak resmi, bagian dari fork Zalith Launcher 2; GPLv3).
 *
 * Membaca log game & isi folder mods lalu menjelaskan DALAM BAHASA INDONESIA apa yang terjadi
 * (mod kurang library, mod tidak cocok versi Minecraft, mod ganda, Java salah, RAM habis, dll) dan apa yang perlu dilakukan.
 * Kode ini murni Kotlin (tanpa Android) supaya mudah diuji.
 */
package com.movtery.zalithlauncher.game.diagnose

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

enum class Severity { ERROR, WARN, INFO }

data class Finding(
    val severity: Severity,
    val title: String,
    val detail: String,
    val advice: String
)

object CrashDiagnoser {
    /** Jar infrastruktur: bukan "mod penyebab" walau muncul di stack trace. */
    private val infra = Regex(
        "^(fabric-loader|MioLaunchWrapper|client-intermediary|server-intermediary|intermediary|sponge-mixin|mixinextras|guava|gson|netty|lwjgl|" +
            "log4j|commons|jopt|asm|authlib|datafixerupper|brigadier|joml|fastutil|oshi|jna|slf4j|fabric-[a-z0-9-]+-v\\d+|fabric-api|fabric-language-kotlin|java\\.|jdk\\.)",
        RegexOption.IGNORE_CASE
    )
    private val jarInTrace = Regex("""\[?([A-Za-z0-9_.+\-]+?)\.jar:""")

    fun analyze(log: String): List<Finding> {
        val out = LinkedHashMap<String, Finding>()
        fun add(key: String, f: Finding) { out.putIfAbsent(key, f) }
        val lines = log.lines()

        // 1) Fabric: daftar dependensi yang tidak terpenuhi
        val unmet = lines.filter { l ->
            l.contains("requires", true) && (l.contains("which is missing") || l.contains("wrong version is present") ||
                l.contains("but only the wrong") || l.contains("which is incompatible"))
        }.map { it.trim().trimStart('-', ' ', '\t') }.distinct()
        unmet.take(12).forEach { l ->
            val mod = Regex("""Mod '([^']+)'""").find(l)?.groupValues?.get(1) ?: "sebuah mod"
            val dep = Regex("""(?:mod|of) '([^']+)'""").findAll(l).map { it.groupValues[1] }.lastOrNull()
            when {
                l.contains("minecraft", true) && l.contains("wrong version") -> add(
                    "mcver:$mod", Finding(
                        Severity.ERROR, "Mod \"$mod\" tidak mendukung versi Minecraft ini",
                        l, "Ganti mod \"$mod\" dengan versi yang sesuai Minecraft kamu, atau hapus dari folder mods."
                    )
                )
                l.contains("wrong version", true) -> add(
                    "depver:$mod:$dep", Finding(
                        Severity.ERROR, "Library \"${dep ?: "?"}\" untuk mod \"$mod\" salah versi",
                        l, "Perbarui atau turunkan versi \"${dep ?: "library itu"}\" sesuai yang diminta mod \"$mod\"."
                    )
                )
                else -> add(
                    "missing:$mod:$dep", Finding(
                        Severity.ERROR, "Mod \"$mod\" kekurangan library: ${dep ?: "?"}",
                        l, "Pasang mod/library \"${dep ?: "yang diminta"}\" (cek Modrinth/CurseForge, pilih versi Fabric untuk Minecraft kamu) atau hapus mod \"$mod\"."
                    )
                )
            }
        }
        if (unmet.isNotEmpty() || log.contains("Some of your mods are incompatible")) {
            lines.filter { it.trim().startsWith("- Install ") }.take(8).forEach { l ->
                add("hint:${l.trim()}", Finding(Severity.INFO, "Saran dari Fabric", l.trim().trimStart('-', ' '), "Ikuti saran ini."))
            }
        }

        // 2) Mod ganda
        Regex("""(?:Duplicate mod|Found duplicate|has been loaded from multiple|duplicate .*mod).*""", RegexOption.IGNORE_CASE)
            .find(log)?.let {
                add("dup", Finding(Severity.ERROR, "Ada mod ganda (dua file untuk mod yang sama)", it.value.take(200),
                    "Hapus salah satu file jar yang sama di folder mods (biasanya versi lama)."))
            }

        // 3) Java salah versi
        Regex("""UnsupportedClassVersionError: (\S+).*?class file version (\d+)\.\d+.*?up to (\d+)\.\d+""").find(log)?.let { m ->
            val need = m.groupValues[2].toInt() - 44
            val have = m.groupValues[3].toInt() - 44
            add("java", Finding(Severity.ERROR, "Versi Java tidak cocok", "Butuh Java $need, yang dipakai Java $have (${m.groupValues[1]}).",
                "Di pengaturan versi/launcher pilih runtime Java $need atau lebih baru."))
        }

        // 4) RAM habis
        if (log.contains("java.lang.OutOfMemoryError") || log.contains("GC overhead limit exceeded")) {
            val direct = log.contains("Direct buffer memory")
            add("oom", Finding(Severity.ERROR, "RAM game habis (OutOfMemoryError)",
                if (direct) "Memori langsung (direct buffer) habis." else "Heap Java penuh.",
                "Kurangi jumlah mod, kecilkan render distance, nyalakan FPS Boost, tutup aplikasi lain, atau naikkan alokasi RAM bila HP-mu punya sisa."))
        }

        // 5) Mixin gagal (dua mod bentrok / mod tidak cocok versi)
        Regex("""Mixin apply for mod (\S+) failed (\S+)""").findAll(log).map { it.groupValues[1] }.distinct().take(5).forEach { id ->
            add("mixin:$id", Finding(Severity.ERROR, "Mod \"$id\" gagal dipasang ke game (Mixin)",
                "Biasanya karena mod ini tidak cocok dengan versi Minecraft/mod lain, atau bentrok dengan mod lain.",
                "Perbarui mod \"$id\" ke versi yang sesuai, atau hapus mod itu / mod yang mengubah bagian game yang sama."))
        }
        if (out.keys.none { it.startsWith("mixin:") }) {
            Regex("""(InvalidInjectionException|MixinApplyError|Critical injection failure|InjectionError)[^\n]*""").find(log)?.let {
                val mixinJson = Regex("""([A-Za-z0-9_.\-]+)\.mixins\.json""").find(log)?.groupValues?.get(1)
                add("mixin", Finding(Severity.ERROR, "Mod gagal dipasang ke game (Mixin)" + (mixinJson?.let { " — $it" } ?: ""),
                    it.value.take(220), "Hapus atau perbarui mod yang disebut; sering terjadi karena mod tidak cocok versi."))
            }
        }

        // 6) Class/method hilang = mod tidak cocok versi atau library kurang
        //hanya exception SUNGGUHAN (awal baris / "Caused by"); baris WARN "Error loading class ... ClassNotFoundException" dari Mixin itu normal
        Regex("""(?m)^(?:Caused by: )?(?:java\.lang\.)?(NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError): ([^\n]{1,160})""").find(log)?.let {
            val culprit = firstModJars(log, it.range.first).firstOrNull()
            add("nodef", Finding(Severity.ERROR, "Ada bagian mod yang hilang atau beda versi",
                "${it.groupValues[1]}: ${it.groupValues[2]}",
                "Mod ${culprit?.let { c -> "\"$c\" " } ?: ""}kemungkinan butuh library yang belum terpasang atau versinya beda. Perbarui mod itu dan library-nya."))
        }

        // 7) Jar rusak
        Regex("""(ZipException|Failed to read mod|is not a valid zip|invalid (?:CEN|LOC) header|Not a jar)[^\n]{0,160}""").find(log)?.let {
            add("zip", Finding(Severity.ERROR, "Ada file mod yang rusak", it.value.take(200),
                "Unduh ulang mod yang rusak (unduhan sering terputus), lalu ganti file lamanya."))
        }

        // 8) Native / grafis
        if (log.contains("UnsatisfiedLinkError")) {
            add("native", Finding(Severity.ERROR, "Library native tidak bisa dimuat",
                Regex("""UnsatisfiedLinkError[^\n]{0,160}""").find(log)?.value ?: "",
                "Pastikan renderer/mod native cocok dengan HP (arm64), atau hapus mod yang memakainya."))
        }
        if (Regex("""(Failed to compile shader|GLFW error|OpenGL error|GL_INVALID|glCompileShader)""").containsMatchIn(log)) {
            add("gl", Finding(Severity.WARN, "Masalah grafis (OpenGL/shader)",
                Regex("""(Failed to compile shader|GLFW error|OpenGL error|GL_INVALID)[^\n]{0,140}""").find(log)?.value ?: "",
                "Coba ganti renderer di pengaturan, matikan shader/resource pack berat, atau nyalakan FPS Boost."))
        }

        // 9) Paket jaringan gagal diproses (join server/Mabar gagal)
        if (log.contains("Failed to handle packet")) {
            val jars = firstModJars(log, log.indexOf("Failed to handle packet"))
            add("packet", Finding(Severity.ERROR, "Gagal masuk server/dunia teman: mod bermasalah saat sinkron data",
                "Game gagal memproses paket dari server" + (jars.takeIf { it.isNotEmpty() }?.let { " (mod terlibat: ${it.joinToString(", ")})" } ?: "") + ".",
                "Hapus atau perbarui mod yang disebut, lalu pastikan daftar mod di kedua HP sama persis."))
        }
        if (log.contains("Network Protocol Error") && !out.containsKey("packet")) {
            add("netproto", Finding(Severity.WARN, "Error protokol jaringan saat bergabung",
                "Server dan HP-mu tidak cocok (versi atau daftar mod berbeda).",
                "Samakan versi Minecraft dan daftar mod dengan host."))
        }

        // 10) Mod ditolak karena server minta mod yang tidak ada / versi beda
        Regex("""(Incompatible client|mismatch|Mod rejections|Unknown (?:registry|payload)|Missing registr)[^\n]{0,140}""", RegexOption.IGNORE_CASE)
            .find(log)?.let {
                add("mismatch", Finding(Severity.WARN, "Daftar mod tidak sama dengan server", it.value.take(180),
                    "Samakan daftar mod dan versinya dengan host."))
            }

        // 11) Crash umum + mod tersangka
        if (out.none { it.value.severity == Severity.ERROR }) {
            Regex("""(Crash report saved to|Game crashed|---- Minecraft Crash Report ----|Exception in thread "[^"]+")""").find(log)?.let {
                val at = Regex("""(Exception in thread|Caused by:|Unable to|Exception)""").find(log)?.range?.first ?: 0
                val jars = firstModJars(log, at)
                add("crash", Finding(Severity.ERROR, "Game crash",
                    "Penyebab pasti tidak dikenali otomatis." + (jars.takeIf { it.isNotEmpty() }?.let { " Mod yang paling mungkin terlibat: ${it.joinToString(", ")}." } ?: ""),
                    "Coba nonaktifkan mod yang disebut (pindahkan keluar dari folder mods), lalu kirim log ke pembuat mod/ke aku."))
            }
        }

        // 12) Tidak ada error Java sama sekali tapi game berhenti tiba-tiba
        if (out.isEmpty() && log.length > 2000 && !log.contains("Stopping!") && !log.contains("Saving worlds")) {
            add("killed", Finding(Severity.WARN, "Game berhenti tiba-tiba tanpa pesan error",
                "Tidak ada error Java di log. Pada HP RAM kecil ini hampir selalu berarti Android mematikan game karena RAM habis.",
                "Kurangi mod, nyalakan FPS Boost, tutup aplikasi lain, jangan buka layar web (WebDisplays) dan rekam layar bersamaan."))
        }

        return out.values.sortedBy { it.severity.ordinal }
    }

    /** Nama jar mod (bukan infrastruktur) pada stack trace pertama yang mengandung exception, urut dari atas. */
    fun firstModJars(log: String, fromChar: Int = 0, max: Int = 3): List<String> {
        val lines = log.substring(fromChar.coerceIn(0, log.length)).lines()
        val start = lines.take(6).indexOfFirst { it.startsWith("\tat") } - 1
        if (start < -1 || lines.take(6).none { it.startsWith("\tat") }) return emptyList()
        val names = LinkedHashSet<String>()
        var i = start + 1
        var misses = 0
        while (i < lines.size && names.size < max && misses < 4) {
            val l = lines[i]
            if (l.startsWith("\tat") || l.startsWith("Caused by") || l.startsWith("\t...")) {
                misses = 0
                jarInTrace.find(l)?.groupValues?.get(1)?.let { j ->
                    val name = j.removePrefix("knot/")
                    if (!infra.containsMatchIn(name)) names.add(name)
                }
            } else misses++
            i++
        }
        return names.toList()
    }
}

/** Pemeriksa folder mods (Fabric) sebelum game jalan: dependensi hilang, mod ganda, versi Minecraft tidak cocok. */
object ModChecker {
    private class ModInfo(val file: String, val id: String, val version: String, val provides: List<String>,
                          val depends: Map<String, String>, val nestedIds: List<String>)

    fun check(modsDir: File, mcVersion: String?): List<Finding> {
        val jars = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.sortedBy { it.name } ?: return emptyList()
        val infos = ArrayList<ModInfo>()
        val findings = ArrayList<Finding>()
        for (jar in jars) {
            val info = runCatching { read(jar) }.getOrElse {
                findings += Finding(Severity.ERROR, "File mod rusak: ${jar.name}", "Tidak bisa dibaca sebagai jar.", "Unduh ulang atau hapus file ini.")
                null
            } ?: continue
            infos += info
        }
        val present = HashSet<String>().apply {
            addAll(listOf("minecraft", "java", "fabricloader", "fabric-loader", "mixinextras", "mixin"))
            infos.forEach { add(it.id); addAll(it.provides); addAll(it.nestedIds) }
        }
        infos.groupBy { it.id }.filter { it.value.size > 1 }.forEach { (id, list) ->
            findings += Finding(Severity.ERROR, "Mod ganda: $id", list.joinToString(", ") { it.file },
                "Simpan satu saja (biasanya yang versinya terbaru), hapus sisanya dari folder mods.")
        }
        for (m in infos) {
            for ((dep, range) in m.depends) {
                if (dep == "minecraft") {
                    if (mcVersion != null && !McRange.matches(range, mcVersion)) {
                        findings += Finding(Severity.ERROR, "Mod ${m.id} (${m.file}) tidak mendukung Minecraft $mcVersion",
                            "Butuh Minecraft: $range", "Ganti dengan versi mod yang cocok untuk $mcVersion atau hapus.")
                    }
                } else if (dep !in present) {
                    findings += Finding(Severity.ERROR, "Mod ${m.id} butuh library \"$dep\" yang belum ada",
                        "Dibutuhkan: $dep ${range.takeIf { it != "*" } ?: ""}".trim(),
                        "Pasang \"$dep\" (Fabric, versi sesuai Minecraft kamu) atau hapus mod ${m.id}.")
                }
            }
        }
        return findings
    }

    private fun read(jar: File): ModInfo? {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry("fabric.mod.json") ?: return null //bukan mod Fabric: abaikan
            val text = zip.getInputStream(entry).bufferedReader().readText()
            val nested = ArrayList<String>()
            zip.entries().asSequence().filter { it.name.startsWith("META-INF/jars/") && it.name.endsWith(".jar") }.forEach { e ->
                runCatching {
                    ZipInputStream(zip.getInputStream(e)).use { zin ->
                        var n = zin.nextEntry
                        while (n != null) {
                            if (n.name == "fabric.mod.json") {
                                val t = zin.readBytes().toString(Charsets.UTF_8)
                                Json.str(t, "id")?.let { nested += it }
                                nested += Json.strArray(t, "provides")
                                break
                            }
                            n = zin.nextEntry
                        }
                    }
                }
            }
            val id = Json.str(text, "id") ?: return null
            return ModInfo(jar.name, id, Json.str(text, "version") ?: "?", Json.strArray(text, "provides"), Json.depends(text), nested)
        }
    }
}

/** Parser JSON sangat kecil (hanya yang dibutuhkan fabric.mod.json) agar tidak bergantung library. */
internal object Json {
    private fun parse(t: String): Any? = P(t.trimStart('﻿')).value()
    @Suppress("UNCHECKED_CAST")
    private fun obj(t: String): Map<String, Any?> = (runCatching { parse(t) }.getOrNull() as? Map<String, Any?>) ?: emptyMap()
    fun str(t: String, k: String): String? = obj(t)[k] as? String
    fun strArray(t: String, k: String): List<String> = (obj(t)[k] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    fun depends(t: String): Map<String, String> {
        val d = obj(t)["depends"] as? Map<*, *> ?: return emptyMap()
        return d.entries.associate { (k, v) ->
            k.toString() to when (v) {
                is String -> v
                is List<*> -> v.joinToString(" || ")
                else -> "*"
            }
        }
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i].isWhitespace())) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> { i++; val m = LinkedHashMap<String, Any?>(); ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) { ws(); val k = str(); ws(); i++ /* : */; m[k] = value(); ws()
                        if (s[i] == ',') { i++; continue }; i++; break }
                    m }
                '[' -> { i++; val l = ArrayList<Any?>(); ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) { l += value(); ws(); if (s[i] == ',') { i++; continue }; i++; break }
                    l }
                '"' -> str()
                else -> { val st = i; while (i < s.length && s[i] !in ",}] \n\r\t") i++; val tok = s.substring(st, i)
                    tok.toDoubleOrNull() ?: when (tok) { "true" -> true; "false" -> false; else -> null } }
            }
        }
        fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') { i++
                    when (val e = s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
    }
}

/** Pencocok rentang versi Minecraft ala Fabric yang umum: ">=1.21", "~1.21.1", "1.21.x", "1.21.1", ">=1.21 <1.21.2", "a || b". */
internal object McRange {
    private fun parts(v: String) = v.split('.', '-').map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    private fun cmp(a: String, b: String): Int {
        val x = parts(a); val y = parts(b)
        for (k in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(k) { 0 }).compareTo(y.getOrElse(k) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun matches(range: String, version: String): Boolean {
        if (range.isBlank() || range == "*") return true
        return range.split("||").any { alt -> alt.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { single(it, version) } }
    }

    private fun single(p: String, v: String): Boolean {
        if (p == "*") return true
        return when {
            p.startsWith(">=") -> cmp(v, p.drop(2)) >= 0
            p.startsWith("<=") -> cmp(v, p.drop(2)) <= 0
            p.startsWith(">") -> cmp(v, p.drop(1)) > 0
            p.startsWith("<") -> cmp(v, p.drop(1)) < 0
            p.startsWith("=") -> cmp(v, p.drop(1)) == 0
            p.startsWith("~") -> { val b = p.drop(1); val bp = parts(b)
                cmp(v, b) >= 0 && parts(v).take(2) == bp.take(2).let { if (bp.size >= 2) it else it + 0 }.take(2) }
            p.startsWith("^") -> { val b = p.drop(1); cmp(v, b) >= 0 && parts(v).first() == parts(b).first() }
            p.contains('x') || p.contains('X') || p.contains('*') -> {
                val pp = p.split('.'); val vp = v.split('.')
                pp.indices.all { k -> pp[k].equals("x", true) || pp[k] == "*" || pp[k] == vp.getOrNull(k) }
            }
            else -> cmp(v, p) == 0
        }
    }
}
