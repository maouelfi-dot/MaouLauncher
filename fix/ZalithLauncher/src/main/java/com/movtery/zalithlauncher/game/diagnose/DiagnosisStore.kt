/*
 * Maou Launcher - penghubung diagnosa ke Android (cari log, simpan hasil pemeriksaan mod, status "sudah ditampilkan").
 */
package com.movtery.zalithlauncher.game.diagnose

import android.content.Context
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

object DiagnosisStore {
    private const val TAG = "Diagnosis"
    private const val PREFS = "maou_diag"
    private const val PRE_FILE = "maou_diag_pre.txt"

    private fun ctx(): Context = GlobalContext.applicationContext

    /** Dipanggil saat game akan dijalankan: periksa folder mods di thread terpisah dan simpan hasilnya. */
    fun onLaunch(args: List<String>) {
        val gi = args.indexOf("--gameDir")
        if (gi < 0 || gi + 1 >= args.size) return
        val gameDir = File(args[gi + 1])
        val vi = args.indexOf("--version")
        //"fabric-loader-0.20.1-1.21.1" -> "1.21.1"; "1.21.1" -> "1.21.1"
        val mc = args.getOrNull(vi + 1)?.takeIf { vi >= 0 }?.substringAfterLast('-')?.takeIf { it.firstOrNull()?.isDigit() == true }
        Thread({
            runCatching {
                val found = ModChecker.check(File(gameDir, "mods"), mc)
                File(ctx().filesDir, PRE_FILE).writeText(found.joinToString("\n") {
                    listOf(it.severity.name, it.title, it.detail, it.advice).joinToString("\t") { s -> s.replace('\t', ' ').replace('\n', ' ') }
                })
                Logger.info(TAG, "Pemeriksaan mod: ${found.size} temuan")
            }.onFailure { Logger.warning(TAG, "Pemeriksaan mod gagal", it) }
        }, "MaouModCheck").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun readPre(): List<Finding> = runCatching {
        File(ctx().filesDir, PRE_FILE).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 4) null else Finding(runCatching { Severity.valueOf(p[0]) }.getOrDefault(Severity.WARN), p[1], p[2], p[3])
        }
    }.getOrDefault(emptyList())

    /** Log terbaru yang bisa ditemukan (latest_game*.log / latestlog*.txt) di folder data launcher. */
    private fun newestLog(): File? {
        val roots = listOfNotNull(ctx().getExternalFilesDir(null), ctx().filesDir)
        var best: File? = null
        for (r in roots) {
            r.walkTopDown().maxDepth(3).onEnter { it.name != "mods" && it.name != "assets" && it.name != "libraries" }
                .filter { it.isFile && (Regex("latest_game.*\\.log").matches(it.name) || Regex("latestlog.*\\.txt").matches(it.name)) }
                .forEach { if (best == null || it.lastModified() > best!!.lastModified()) best = it }
        }
        return best
    }

    /**
     * Temuan untuk ditampilkan sekali setelah game berhenti (tutup/crash). null = tidak ada yang perlu ditampilkan
     * atau log ini sudah pernah ditampilkan.
     */
    fun pending(): List<Finding>? = runCatching {
        val log = newestLog() ?: return null
        val prefs = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (log.lastModified() <= prefs.getLong("shown", 0L)) return null
        val fromLog = CrashDiagnoser.analyze(log.readText())
        if (fromLog.none { it.severity != Severity.INFO }) {
            prefs.edit().putLong("shown", log.lastModified()).apply()
            return null
        }
        val tuned = HeapTuner.learn(fromLog)
        val titles = fromLog.map { it.title }.toSet()
        val pre = readPre().filter { it.severity == Severity.ERROR && it.title !in titles }
        prefs.edit().putLong("shown", log.lastModified()).apply()
        fromLog + listOfNotNull(tuned) + pre
    }.onFailure { Logger.warning(TAG, "Diagnosa gagal", it) }.getOrNull()

    fun asText(list: List<Finding>): String = list.joinToString("\n\n") {
        "[${it.severity}] ${it.title}\n${it.detail}\nSaran: ${it.advice}"
    }
}
