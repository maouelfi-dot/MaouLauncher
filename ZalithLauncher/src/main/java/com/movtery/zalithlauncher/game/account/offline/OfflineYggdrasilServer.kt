/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.account.offline

import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.wardrobe.SkinModelType
import com.movtery.zalithlauncher.utils.logging.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jackhuang.hmcl.util.DigestUtils
import java.security.KeyPair
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "OfflineYggdrasil"

/** [Maou] port server skin yang sama di semua HP (lihat [OfflineYggdrasilServer.start]) */
private const val SHARED_PORT = 24447

private const val SHARED_KEY_PKCS8 =
        "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCkPazpRSR0P3fFfMvOZQQgHpzN+Df6k1PrcxR0eZF4Y2glqI0C" +
        "R3XyEvzjUb7+qdNE+7By/auhs+LJaArgFTS/8JAR3b2IwMlezAfH7KCA62GUZIGrcXHua0cF2uwLAR9OisXYQUo3EvUE1NuXEopD" +
        "FHFq/8PEmHcJZNUj785f0AJd9oMDR1uNNSWSKBFYh+ahnTxLdIbBRCN2FjhZWetoAFefr8jMHgRaE696DyrF0v5U9sFwWIdEPrBR" +
        "xXbXiH36IAtfk3uIVb8nANNSK2Nh65IkzhICtOKx3NrBcyFLniYJxN0u9mucatPEV6ajh9YJR9BFGnfs+uNF4KdRhWSTAgMBAAEC" +
        "ggEAAkjXvOIfVBafhEaGHZ4K5QxH9hcVZqdIXyZzCsoeG34wFqxpIBz9jc51Ag+pbnVorbVdvcIiWKKquhtEyEH2dK0HjZgRNab1" +
        "phuEbpcWBg6mhTl/EqY5uK1GIaJRiJ2m+weAu2jwSIlukXcJTukNHBdbgVEcpTSeg0NWmHD5F+mqrUVm0fanZf81ehlU1CB8Kata" +
        "qkUqBFB6Q7gaUcqUEA/IaykiaywfOt2cGes4FWnsZhDNTvcUVEVQuZ+h9gtgUXHR7i7DH/AsLPyMIFAMaywc4I5p+CU5yWke6wgN" +
        "TEBqhe/H07gakE0DLyWYbKFHllkIR1hVM9DQFoe/Q6KusQKBgQDb2Zks/HPdvpq/1pNKn+cbuh5mV3GI4EF58FmBliOWi0NV4C7P" +
        "0kOa/UWDFXsN2KhnYsbCNW7LkqdonyD/O5hY68HdpL0z49LLMkxHQeNiMWunLehFiRTU7QweVZPJYUP4jLGNG6JhQUVXUlNhWBub" +
        "d8isglVF/Uke75xyhUbXrwKBgQC/P0J2qxaBx84ax0LaFO5xjwC1JV9b0igEkRd3Nqjy9Jh6IB7qD/FThI8cA0Rbp0uOEzZZ1V7B" +
        "J9ylHgqxGbWE3iO3yFjG8MGZ4PTvy7X+tlwu2TeKJ9mytxS3NiGGjzh8W4QLzpoOD8ecTWchD6JPkPxS8h04aOTndMEQA8AWXQKB" +
        "gGJBjA3o/je6ehsG4WlWsclYk0GWDy3nXBwNaVNbMKiVsRBuUz2acZCRZUHCle6ISCt8n+woSU4+SwedJvgrQFltdIWEnPLV/V3H" +
        "KwQl1GQMRlq/T7HUJDVNhD8CbDxydhhdePThxt7EdyMhrhYxdJU1IcDHtyjCnK1w1XDxU8oNAoGAOnuUzocpHczE+SlgByKJfA53" +
        "7+0ayRBuDl90Jo9/gLRNgGMcMQVpDdomjTFukPyKvUqgvz3heP3AmnmiWV/8Q0JO9997TnLHMVA6dq373IVVkTn5ovTmvKEB47jY" +
        "7bYUVusBaT7nm082AlUlKVYHk39Jue+yAdMJ1MG+XDBZaD0CgYAXrBIyolGbpg71syNiSUIIwL4NBay4T5ZwdUcmKglB0+wawYwa" +
        "hmdWtYycoamqmKxfU85IecFT+KUxc7/xm3PbAEAO/D5wWeDnWBHyK62wQyhD84Ris+ho/9Q1SFprLrIfgoUaNo3tjQzPAjAWFKQY" +
        "nt3CRBSY5FPxloIJ3pujsQ=="

/** [Maou] Pasangan publik (X.509) dari [SHARED_KEY_PKCS8]. Disimpan terpisah supaya tidak perlu mengubah kunci privat ke tipe CRT (gagal di Android 8.1). */
private const val SHARED_KEY_X509 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApD2s6UUkdD93xXzLzmUEIB6czfg3+pNT63MUdHmReGNoJaiNAkd18hL8" +
        "41G+/qnTRPuwcv2robPiyWgK4BU0v/CQEd29iMDJXswHx+yggOthlGSBq3Fx7mtHBdrsCwEfTorF2EFKNxL1BNTblxKKQxRxav/D" +
        "xJh3CWTVI+/OX9ACXfaDA0dbjTUlkigRWIfmoZ08S3SGwUQjdhY4WVnraABXn6/IzB4EWhOveg8qxdL+VPbBcFiHRD6wUcV214h9" +
        "+iALX5N7iFW/JwDTUitjYeuSJM4SArTisdzawXMhS54mCcTdLvZrnGrTxFemo4fWCUfQRRp37PrjReCnUYVkkwIDAQAB"

/**
 * 离线账号 Yggdrasil 服务器，用于本地加载玩家皮肤、披风
 * [Reference from HMCL](https://github.com/HMCL-dev/HMCL/blob/15e490f/HMCLCore/src/main/java/org/jackhuang/hmcl/auth/offline/YggdrasilServer.java)
 */
class OfflineYggdrasilServer(
    private val port: Int = 0,
    val serverName: String = "${BuildKeys.LAUNCHER_IDENTIFIER}_Offline",
    val implementationName: String = BuildKeys.LAUNCHER_SHORT_NAME,
    val implementationVersion: String = "1.0"
) {
    private val charactersByUuid = ConcurrentHashMap<String, Character>()
    private val charactersByName = ConcurrentHashMap<String, Character>()
    /**
     * [Maou] Kunci penanda tangan TETAP (sama di semua HP). Profil pemain lain dijawab oleh server skin HOST dan
     * ditandatangani kunci host; tiap HP hanya mempercayai kunci server skin miliknya sendiri. Kunci acak per HP
     * membuat tanda tangan di HP lain tidak cocok -> skin ditolak. Kunci ini hanya untuk skin offline lokal (bukan rahasia).
     */
    private val keyPair: KeyPair = runCatching {
        val kf = java.security.KeyFactory.getInstance("RSA")
        //[Maou] JANGAN di-cast ke RSAPrivateCrtKey: di Android 8.1 (Conscrypt) hasilnya OpenSSLRSAPrivateKey sehingga
        //ClassCastException dan game tidak bisa dibuka sama sekali. Kunci publik dibaca dari pasangannya (X.509).
        val priv = kf.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(SHARED_KEY_PKCS8))
        )
        val pub = kf.generatePublic(
            java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(SHARED_KEY_X509))
        )
        KeyPair(pub, priv)
    }.getOrElse { e ->
        //cadangan: kunci acak (skin lintas-HP bisa tidak cocok, tapi game tetap bisa dibuka)
        Logger.warning(TAG, "Kunci tetap gagal dimuat, memakai kunci acak", e)
        java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()
    }

    /**
     * [Maou] Dipanggil (di thread IO) saat game menanyakan profil UUID yang belum dikenal. Pemanggil boleh menunggu
     * beberapa detik sambil mengunduh skin teman, supaya pencarian PERTAMA langsung berhasil. (Game menyimpan
     * hasil pencarian yang gagal, sehingga skin tidak muncul lagi sampai game dimulai ulang.)
     */
    @Volatile
    var unknownProfileResolver: ((String) -> Unit)? = null

    /** [Maou] Lapisan cadangan: dipanggil bila game meminta tekstur (hash) yang belum ada; unduh dari teman lalu jawab. */
    @Volatile
    var unknownTextureResolver: ((String) -> Unit)? = null

    private var serverStartedLatch = CountDownLatch(1)
    private var isServerRunning = false

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * [Maou] Port TETAP yang sama di semua HP. URL skin/cape di profil memakai "http://localhost:<port>/textures/<hash>",
     * dan profil pemain lain dijawab oleh server skin HOST (port milik HP host). Dengan port acak, HP lain akan
     * mengakses "localhost" miliknya sendiri di port yang salah -> skin host dan skin sendiri tidak pernah muncul.
     * Karena setiap HP menyimpan semua skin yang disinkronkan, URL yang sama berlaku di semua HP.
     */
    fun start() {
        val first = if (port == 0) SHARED_PORT else port
        if (!tryStart(first) && first != 0) {
            Logger.warning(TAG, "Port $first tidak bisa dipakai, memakai port acak (skin lintas-HP mungkin tidak muncul)")
            runCatching { server?.stop(200, 500) }
            serverStartedLatch = CountDownLatch(1)
            tryStart(0)
        }
    }

    private fun tryStart(port: Int): Boolean {
        server = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    encodeDefaults = true
                })
            }

            routing {
                suspend fun RoutingContext.runCatched(
                    block: suspend RoutingContext.() -> Unit
                ) {
                    runCatching {
                        block()
                    }.onFailure { e ->
                        Logger.error(TAG, "Internal server error", e)
                    }
                }

                get("/") {
                    runCatched { call.respondText(root(), ContentType.Application.Json) }
                }
                get("/status") {
                    runCatched { call.respondText(status(), ContentType.Application.Json) }
                }
                post("/api/profiles/minecraft") {
                    runCatched { call.respondText(profiles(call), ContentType.Application.Json) }
                }
                get("/sessionserver/session/minecraft/hasJoined") {
                    runCatched { call.respondText(withContext(Dispatchers.IO) { hasJoined(call) }, ContentType.Application.Json) }
                }
                post("/sessionserver/session/minecraft/join") {
                    runCatched { call.respond(HttpStatusCode.NoContent) }
                }
                get("/sessionserver/session/minecraft/profile/{uuid}") {
                    runCatched { call.respondText(withContext(Dispatchers.IO) { profile(call) }, ContentType.Application.Json) }
                }
                get("/textures/{hash}") {
                    runCatched { call.respond(texture(call)) }
                }
            }
        }.apply {
            monitor.subscribe(ApplicationStarted) {
                //服务器成功启动
                serverStartedLatch.countDown()
            }
        }

        try {
            server?.start(wait = false)
        } catch (e: Throwable) {
            Logger.warning(TAG, "Gagal memulai server skin di port $port", e)
            return false
        }

        //等待服务器启动完成
        val startTimeout = 10L //10秒超时
        if (serverStartedLatch.await(startTimeout, TimeUnit.SECONDS)) {
            isServerRunning = true
        }
        return isServerRunning
    }

    fun stop() {
        isServerRunning = false
        server?.stop(1000, 5000)
        serverStartedLatch.countDown()
    }

    fun getPort(): Int? {
        if (!isServerRunning) {
            return null
        }

        val engine = server?.engine ?: return null
        return runBlocking {
            try {
                engine.resolvedConnectors().firstOrNull()?.port
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 添加玩家角色
     * @param account 离线账号对象
     */
    fun addCharacter(account: Account) {
        val skinFile = account.getSkinFile()
        val capeFile = account.getCapeFile()

        val skinBytes = skinFile.takeIf { it.exists() }?.readBytes()
        val skinHash = skinBytes?.let { DigestUtils.digestToString("SHA-256", it) }
        val capeBytes = capeFile.takeIf { it.exists() }?.readBytes()
        val capeHash = capeBytes?.let { DigestUtils.digestToString("SHA-256", it) }

        val character = Character(
            uuid = account.profileId.replace("-", ""),
            name = account.username,
            skin = LoadedSkin(
                skinHash = skinHash,
                skinBytes = skinBytes,
                capeHash = capeHash,
                capeBytes = capeBytes,
                model = account.skinModelType
            )
        )

        charactersByUuid[character.uuid.lowercase()] = character
        charactersByName[character.name.lowercase()] = character

        //[Maou] Server LAN host memberi UUID dari NAMA ("OfflinePlayer:nama") kepada pemain yang bergabung, jadi game di HP teman
        //mencari skin DIRINYA SENDIRI dengan UUID itu. Tanpa entri ini skin sendiri di HP teman tidak terlihat.
        val offlineUuid = java.util.UUID.nameUUIDFromBytes("OfflinePlayer:${account.username}".toByteArray(Charsets.UTF_8))
            .toString().replace("-", "")
        if (!offlineUuid.equals(character.uuid, ignoreCase = true)) {
            charactersByUuid[offlineUuid.lowercase()] = character.copy(uuid = offlineUuid)
        }

        Logger.info(TAG, "Added character ${character.name} (${character.uuid}), skin hash = ${character.skin?.skinHash}, cape hash = ${character.skin?.capeHash}")
    }

    /**
     * [Maou] Tambahkan pemain lain (HP teman di hotspot) agar skin/cape-nya terlihat di game ini.
     * Server LAN offline memberi UUID dari nama ("OfflinePlayer:nama"), sedangkan host memakai UUID
     * akunnya sendiri, jadi karakter didaftarkan di kedua UUID.
     */
    fun addRemoteCharacter(
        name: String,
        uuid: String,
        model: SkinModelType,
        skinBytes: ByteArray?,
        capeBytes: ByteArray?
    ) {
        val skin = LoadedSkin(
            skinHash = skinBytes?.let { DigestUtils.digestToString("SHA-256", it) },
            skinBytes = skinBytes,
            capeHash = capeBytes?.let { DigestUtils.digestToString("SHA-256", it) },
            capeBytes = capeBytes,
            model = model
        )
        val offlineUuid = java.util.UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
            .toString().replace("-", "")

        for (id in setOf(uuid.replace("-", ""), offlineUuid)) {
            val character = Character(uuid = id, name = name, skin = skin)
            charactersByUuid[id.lowercase()] = character
        }
        charactersByName[name.lowercase()] = Character(uuid = offlineUuid, name = name, skin = skin)

        Logger.info(TAG, "Added remote character $name ($uuid / $offlineUuid)")
    }

    private fun PublicKey.toPEMPublicKey(): String {
        val base64Key = Base64.getEncoder().encodeToString(this.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$base64Key\n-----END PUBLIC KEY-----"
    }

    private fun root(): String {
        return buildJsonObject {
            put("skinDomains", JsonArray(listOf(JsonPrimitive("127.0.0.1"), JsonPrimitive("localhost"))))
            put("meta", buildJsonObject {
                put("serverName", JsonPrimitive(serverName))
                put("implementationName", JsonPrimitive(implementationName))
                put("implementationVersion", JsonPrimitive(implementationVersion))
                put("feature.non_email_login", JsonPrimitive(true))
            })
            put("signaturePublickey", JsonPrimitive(keyPair.public.toPEMPublicKey()))
        }.toString()
    }

    private fun status(): String {
        return buildJsonObject {
            put("user.count", JsonPrimitive(charactersByUuid.size))
            put("token.count", JsonPrimitive(0))
        }.toString()
    }

    private suspend fun profiles(call: ApplicationCall): String {
        val names = call.receive<List<String>>()
        return buildJsonArray {
            names.distinct().mapNotNull { charactersByName[it.lowercase()] }.forEach { character ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(character.uuid))
                    put("name", JsonPrimitive(character.name))
                })
            }
        }.toString()
    }

    private fun hasJoined(call: ApplicationCall): String {
        val username = call.request.queryParameters["username"] ?: return buildJsonObject {
            put("error", JsonPrimitive("Missing username"))
        }.toString()
        Logger.debug(TAG, "Try find profile with username $username")

        val offlineId = java.util.UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8))
            .toString().replace("-", "")
        if (!charactersByName.containsKey(username.lowercase())) {
            //lapisan 1: coba unduh skin pemain ini sekarang juga dari siaran temannya
            runCatching { unknownProfileResolver?.invoke(offlineId) }
        }
        //lapisan 2: jika tetap belum ada, JANGAN menolak login (dulu `{}` = gagal masuk); beri profil polos tanpa skin
        val character = charactersByName[username.lowercase()] ?: Character(uuid = offlineId, name = username)

        return character.toCompleteResponse("http://localhost:${getPort()}", this::sign).also {
            Logger.debug(TAG, "Found profile with username $username")
        }
    }

    private fun profile(call: ApplicationCall): String {
        val uuid = call.parameters["uuid"] ?: return buildJsonObject {
            put("error", JsonPrimitive("Missing uuid"))
        }.toString()
        Logger.debug(TAG, "Try find profile with uuid $uuid")

        if (!charactersByUuid.containsKey(uuid.lowercase().replace("-", ""))) {
            runCatching { unknownProfileResolver?.invoke(uuid.lowercase().replace("-", "")) }
        }
        val character = charactersByUuid[uuid.lowercase().replace("-", "")] ?: return buildJsonObject { }.toString().also {
            Logger.debug(TAG, "Profile with uuid $uuid not found")
        }

        return character.toCompleteResponse("http://localhost:${getPort()}", this::sign).also {
            Logger.debug(TAG, "Found profile with uuid $uuid")
        }
    }

    private suspend fun texture(call: ApplicationCall) {
        val hash = call.parameters["hash"] ?: return call.respond(HttpStatusCode.NotFound)
        Logger.debug(TAG, "Try find skin with hash $hash")

        fun lookup(): ByteArray? = charactersByUuid.values
            .firstNotNullOfOrNull { char ->
                when (hash) {
                    char.skin?.skinHash -> char.skin.skinBytes
                    char.skin?.capeHash -> char.skin.capeBytes
                    else -> null
                }
            }
        var match = lookup()
        if (match == null) {
            //lapisan 3: tekstur belum disinkronkan -> unduh dari teman lalu coba lagi
            withContext(Dispatchers.IO) { runCatching { unknownTextureResolver?.invoke(hash) } }
            match = lookup()
        }

        if (match != null) {
            Logger.debug(TAG, "Skin with hash $hash found")
            call.response.header("Cache-Control", "max-age=2592000, public")
            call.response.header("Etag", "\"$hash\"")
            call.respondBytes(match, ContentType.Image.PNG)
        } else {
            Logger.debug(TAG, "Skin with hash $hash not found")
            call.respond(HttpStatusCode.NotFound)
        }
    }

    /**
     * 签名工具
     */
    private fun sign(data: String): String {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /**
     * 玩家角色模型
     */
    data class Character(
        val uuid: String,
        val name: String,
        val skin: LoadedSkin? = null
    ) {
        fun toCompleteResponse(rootUrl: String, signer: (String) -> String): String {
            val texturesObject = buildJsonObject {
                put("timestamp", JsonPrimitive(System.currentTimeMillis()))
                put("profileId", JsonPrimitive(uuid))
                put("profileName", JsonPrimitive(name))
                put("textures", buildJsonObject {
                    skin?.skinHash?.let { hash ->
                        put("SKIN", buildJsonObject {
                            put("url", JsonPrimitive("$rootUrl/textures/$hash"))
                            //仅在玩家模型为细臂时，才会存在metadata字段，否则为粗臂
                            //Wiki：https://zh.minecraft.wiki/w/Mojang_API#%E8%8E%B7%E5%8F%96%E7%8E%A9%E5%AE%B6%E7%9A%84%E7%9A%AE%E8%82%A4%E5%92%8C%E6%8A%AB%E9%A3%8E
                            if (skin.model == SkinModelType.ALEX) {
                                put("metadata", buildJsonObject {
                                    put("model", JsonPrimitive("slim"))
                                })
                            }
                        })
                    }
                    skin?.capeHash?.let { hash ->
                        put("CAPE", buildJsonObject {
                            put("url", JsonPrimitive("$rootUrl/textures/$hash"))
                        })
                    }
                })
            }

            val jsonString = Json.encodeToString(texturesObject)
            val encoded = Base64.getEncoder().encodeToString(jsonString.toByteArray(Charsets.UTF_8))

            return buildJsonObject {
                put("id", JsonPrimitive(uuid))
                put("name", JsonPrimitive(name))
                put("properties", buildJsonArray {
                    add(buildJsonObject {
                        put("name", JsonPrimitive("textures"))
                        put("value", JsonPrimitive(encoded))
                        put("signature", JsonPrimitive(signer(encoded)))
                    })
                })
            }.toString()
        }
    }
}
