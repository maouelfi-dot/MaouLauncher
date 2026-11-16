/*
 * Maou Launcher - Paket Performa.
 * Mengunduh mod performa Fabric (Sodium, FerriteCore, ModernFix, ImmediatelyFast, Entity Culling) dari Modrinth
 * sesuai versi Minecraft, hanya yang belum terpasang. Berjalan di thread latar.
 */
package com.movtery.zalithlauncher.game.boost

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

object PerformancePack {
    val SLUGS = listOf("sodium", "ferrite-core", "modernfix", "immediatelyfast", "entityculling")

    class Result(val installed: List<String>, val already: List<String>, val failed: List<String>)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun norm(s: String) = s.lowercase().replace("-", "").replace("_", "")

    private fun isInstalled(modsDir: File, slug: String): Boolean {
        val key = norm(slug)
        return modsDir.listFiles()?.any { it.isFile && it.name.endsWith(".jar") && norm(it.name).contains(key) } == true
    }

    fun install(mcVersion: String, modsDir: File): Result {
        modsDir.mkdirs()
        val installed = mutableListOf<String>()
        val already = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (slug in SLUGS) {
            if (isInstalled(modsDir, slug)) { already += slug; continue }
            val ok = runCatching { downloadOne(slug, mcVersion, modsDir) }.getOrDefault(false)
            if (ok) installed += slug else failed += slug
        }
        return Result(installed, already, failed)
    }

    private fun downloadOne(slug: String, mcVersion: String, modsDir: File): Boolean {
        val url = "https://api.modrinth.com/v2/project/$slug/version".toHttpUrl().newBuilder()
            .addQueryParameter("game_versions", "[\"$mcVersion\"]")
            .addQueryParameter("loaders", "[\"fabric\"]")
            .build()
        val body = client.newCall(request(url.toString())).execute().use { r ->
            if (!r.isSuccessful) return false
            r.body?.string() ?: return false
        }
        val arr = JSONArray(body)
        if (arr.length() == 0) return false
        //pilih rilis stabil terbaru; jika tidak ada, versi terbaru apa pun
        var chosen = arr.getJSONObject(0)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("version_type") == "release") { chosen = o; break }
        }
        val files = chosen.getJSONArray("files")
        if (files.length() == 0) return false
        var file = files.getJSONObject(0)
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (f.optBoolean("primary")) { file = f; break }
        }
        val name = file.getString("filename").replace("/", "_")
        val target = File(modsDir, name)
        val tmp = File(modsDir, "$name.part")
        client.newCall(request(file.getString("url"))).execute().use { r ->
            if (!r.isSuccessful) return false
            val src = r.body?.byteStream() ?: return false
            tmp.outputStream().use { out -> src.copyTo(out) }
        }
        if (target.exists()) target.delete()
        return tmp.renameTo(target)
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "maouelfi-dot/MaouLauncher")
        .build()
}
