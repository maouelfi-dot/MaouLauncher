/*
 * Maou Launcher - Mabar Hotspot
 *
 * Mabar LAN lewat hotspot tanpa ketik IP/port:
 *  1. HP host: mendengar pengumuman "Open to LAN" bawaan game, lalu menyiarkannya lewat
 *     broadcast UDP ke seluruh antarmuka (termasuk antarmuka hotspot).
 *  2. HP teman: menerima siaran itu, lalu menjalankan proxy lokal 127.0.0.1:[PROXY_PORT].
 *     Entri "Mabar Hotspot" di daftar server game menunjuk ke proxy ini. Proxy menjawab
 *     status server dengan ikon WiFi dan ping asli ke host, lalu menyambungkan login ke host.
 *  3. Sinkron skin & cape: tiap HP menyiarkan skin/cape-nya, HP lain mengunduh dan
 *     menyajikannya lewat server Yggdrasil offline lokal, sehingga terlihat oleh semua pemain.
 */

package com.movtery.zalithlauncher.game.mabar

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Process
import android.util.Base64
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.offline.OfflineYggdrasilServer
import com.movtery.zalithlauncher.game.account.wardrobe.SkinModelType
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jackhuang.hmcl.util.DigestUtils
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "MabarLan"

object MabarLan {
    /** Port siaran antar-HP (bukan port bawaan game, supaya tidak bentrok) */
    const val BEACON_PORT = 24445

    /** Port proxy lokal yang dituju entri "Mabar Hotspot" di daftar server */
    const val PROXY_PORT = 25599

    /** Batas pemain LAN bawaan Minecraft (IntegratedServer = 8). Ditampilkan apa adanya. */
    const val VANILLA_MAX_PLAYERS = 8

    private const val VANILLA_MULTICAST = "224.0.2.60"
    private const val VANILLA_PORT = 4445
    private const val HOST_TTL_MS = 8_000L
    private const val LAN_TTL_MS = 12_000L
    private const val PEER_TTL_MS = 20_000L
    private const val STICKY_HOST_MS = 90_000L
    private const val MAX_TEXTURE_BYTES = 512 * 1024

    data class LocalIp(val iface: String, val address: String)
    data class HostInfo(val ip: String, val port: Int, val motd: String)

    data class State(
        val running: Boolean = false,
        val localIps: List<LocalIp> = emptyList(),
        /** Dunia LAN milik HP ini (mode host) */
        val ownLanPort: Int? = null,
        /** Host hotspot yang ditemukan dari HP lain (mode teman) */
        val host: HostInfo? = null,
        /** Ping asli ke host (ms), -1 jika belum diukur */
        val pingMs: Int = -1,
        /** Jumlah sambungan aktif lewat proxy */
        val activeLinks: Int = 0,
        /** Pemain lain yang skin/cape-nya sudah disinkronkan */
        val syncedPlayers: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: Session? = null

    /** Daftar IPv4 HP ini (selain loopback), untuk ditampilkan di menu. */
    fun localIpv4(): List<LocalIp> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { a -> a.hostAddress?.let { LocalIp(nif.name, it) } }
            }
    }.getOrDefault(emptyList())

    /**
     * Mulai layanan Mabar. Aman dipanggil berulang; sesi lama dihentikan dulu.
     * @param skinServer server Yggdrasil offline (null jika tidak aktif -> sinkron skin dilewati)
     */
    @Synchronized
    fun start(
        context: Context,
        gameDir: File,
        account: Account,
        skinServer: OfflineYggdrasilServer?
    ) {
        stop()
        if (!AllSettings.mabarLan.getValue()) return
        runCatching {
            session = Session(context.applicationContext, account, skinServer).also { it.begin() }
            //tambahkan entri "Mabar Hotspot" ke daftar server game
            ServersDat.ensureMabarEntry(gameDir, loadIcon(context.applicationContext))
        }.onFailure { e ->
            Logger.error(TAG, "Failed to start Mabar LAN", e)
        }
    }

    @Synchronized
    fun stop() {
        session?.close()
        session = null
        _state.value = State()
    }

    private fun loadIcon(context: Context): ByteArray =
        context.assets.open("mabar/wifi_icon.png").use { it.readBytes() }

    // ---------------------------------------------------------------------------------------
    // Sesi
    // ---------------------------------------------------------------------------------------

    private class Session(
        val context: Context,
        val account: Account,
        val skinServer: OfflineYggdrasilServer?
    ) : Closeable {
        @Volatile
        var alive = true

        private val closeables = CopyOnWriteArrayList<Closeable>()
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "MabarLan").apply { isDaemon = true }
        }

        private val hosts = ConcurrentHashMap<String, Seen<HostInfo>>()
        @Volatile private var ownLan: Seen<Pair<Int, String>>? = null
        @Volatile private var pingMs = -1
        private val activeLinks = AtomicInteger(0)

        /** HP lain yang pernah mengirim siaran Mabar (ip -> waktu). Dipakai untuk kirim langsung (unicast). */
        private val peers = ConcurrentHashMap<String, Long>()
        private val scanMisses = ConcurrentHashMap<Int, Long>()

        /** "nama" -> "skinHash/capeHash" yang sudah disinkronkan */
        private val syncedPlayers = ConcurrentHashMap<String, String>()
        private val fetching = ConcurrentHashMap.newKeySet<String>()

        private var multicastLock: WifiManager.MulticastLock? = null
        private lateinit var faviconUri: String

        private class Seen<T>(val value: T, val at: Long = System.currentTimeMillis())

        fun begin() {
            faviconUri = "data:image/png;base64," +
                Base64.encodeToString(context.assets.open("mabar/wifi_icon.png").use { it.readBytes() }, Base64.NO_WRAP)

            runCatching {
                val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                //[Maou] kunci dibuat tapi TIDAK langsung dipegang (hemat baterai); dipegang hanya saat Mabar benar-benar dipakai
                multicastLock = wifi?.createMulticastLock("maou_mabar")?.apply { setReferenceCounted(false) }
            }.onFailure { Logger.warning(TAG, "MulticastLock unavailable", it) }

            pool.execute { guarded("vanillaListener", ::vanillaListener) }
            pool.execute { guarded("beaconSender", ::beaconSender) }
            pool.execute { guarded("beaconReceiver", ::beaconReceiver) }
            pool.execute { guarded("lanScanner", ::lanScanner) }
            pool.execute { guarded("clientProxy", ::clientProxy) }
            pool.execute { guarded("ticker", ::ticker) }
            Logger.info(TAG, "Mabar LAN started")
        }

        override fun close() {
            alive = false
            closeables.forEach { runCatching { it.close() } }
            closeables.clear()
            runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            pool.shutdownNow()
            Logger.info(TAG, "Mabar LAN stopped")
        }

        private fun guarded(name: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                if (alive) Logger.error(TAG, "$name stopped unexpectedly", e)
            }
        }

        private fun <T : Closeable> track(c: T): T = c.also { closeables.add(it) }

        private fun sleepAlive(ms: Long) {
            var left = ms
            while (alive && left > 0) {
                val step = minOf(left, 250L)
                Thread.sleep(step)
                left -= step
            }
        }

        // ---- status -> UI --------------------------------------------------------------

        /** Host terakhir yang pernah terlihat: dipertahankan sebentar supaya refresh daftar server tidak "kehilangan" host. */
        @Volatile private var lastHost: Seen<HostInfo>? = null
        @Volatile private var lastStatusJson: String? = null

        private fun currentHost(): HostInfo? {
            val now = System.currentTimeMillis()
            hosts.entries.removeIf { now - it.value.at > HOST_TTL_MS }
            val fresh = hosts.values.maxByOrNull { it.at }
            if (fresh != null) {
                lastHost = fresh
                return fresh.value
            }
            return lastHost?.takeIf { now - it.at <= STICKY_HOST_MS }?.value
        }

        private fun ticker() {
            while (alive) {
                val now = System.currentTimeMillis()
                val lan = ownLan?.takeIf { now - it.at <= LAN_TTL_MS }?.value?.first
                val host = currentHost()
                if (host == null) pingMs = -1
                //pegang kunci multicast hanya saat ada host/teman yang terdeteksi
                val busy = lan != null || host != null || peers.isNotEmpty()
                runCatching {
                    multicastLock?.let { l ->
                        if (busy && !l.isHeld) l.acquire() else if (!busy && l.isHeld) l.release()
                    }
                }
                _state.value = State(
                    running = true,
                    localIps = localIpv4(),
                    ownLanPort = lan,
                    host = host,
                    pingMs = pingMs,
                    activeLinks = activeLinks.get(),
                    syncedPlayers = syncedPlayers.keys.sorted()
                )
                sleepAlive(2000)
            }
        }

        // ---- 1. dengar pengumuman LAN bawaan game (sisi host) ----------------------------

        private fun multicastInterfaces(): List<NetworkInterface> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        }.getOrDefault(emptyList())

        private fun vanillaListener() {
            val group = InetAddress.getByName(VANILLA_MULTICAST)
            val sock = track(MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(VANILLA_PORT))
                soTimeout = 1500
            })
            val joined = HashSet<String>()
            var lastJoinCheck = 0L
            val buf = ByteArray(1024)
            while (alive) {
                val now = System.currentTimeMillis()
                if (now - lastJoinCheck > 4000) {
                    lastJoinCheck = now
                    //hotspot bisa dinyalakan kapan saja, jadi cek antarmuka baru berkala
                    multicastInterfaces().forEach { nif ->
                        if (joined.add(nif.name)) {
                            runCatching { sock.joinGroup(InetSocketAddress(group, 0), nif) }
                        }
                    }
                }
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val port = Regex("\\[AD](\\d+)\\[/AD]").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                val motd = Regex("\\[MOTD](.*?)\\[/MOTD]").find(text)?.groupValues?.get(1).orEmpty()
                val src = packet.address.hostAddress ?: continue
                if (isLocal(src)) ownLan = Seen(port to motd)
            }
        }

        @Volatile private var localCache: Set<String> = emptySet()
        @Volatile private var localCacheAt = 0L

        private fun isLocal(ip: String): Boolean {
            if (ip == "127.0.0.1") return true
            val now = System.currentTimeMillis()
            if (now - localCacheAt > 5000) {
                localCache = localIpv4().map { it.address }.toSet()
                localCacheAt = now
            }
            return ip in localCache
        }

        // ---- 2. siaran sendiri (host + skin) --------------------------------------------

        private fun ipToInetAddress(v: Int): InetAddress =
            InetAddress.getByAddress(
                byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            )

        /** Pasangan (alamat lokal, alamat broadcast) untuk tiap antarmuka aktif, termasuk hotspot. */
        private fun broadcastRoutes(): List<Pair<InetAddress, InetAddress>> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.interfaceAddresses.mapNotNull { ia ->
                        val local = ia.address as? Inet4Address ?: return@mapNotNull null
                        val bc = ia.broadcast ?: run {
                            val prefix = ia.networkPrefixLength.toInt()
                            if (prefix !in 8..30) return@run null
                            val ip = local.address
                            val mask = -1 shl (32 - prefix)
                            val v = (((ip[0].toInt() and 255) shl 24) or ((ip[1].toInt() and 255) shl 16) or
                                ((ip[2].toInt() and 255) shl 8) or (ip[3].toInt() and 255)) or mask.inv()
                            ipToInetAddress(v)
                        } ?: return@mapNotNull null
                        local to bc
                    }
                }.distinctBy { it.first.hostAddress }
        }.getOrDefault(emptyList())

        /** Alamat gateway: HP yang memberi hotspot hampir selalu berada di sini. */
        private fun gatewayTargets(): List<InetAddress> {
            val out = LinkedHashSet<String>()
            runCatching {
                val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val gw = wifi?.dhcpInfo?.gateway ?: 0
                if (gw != 0) {
                    out += "${gw and 255}.${(gw shr 8) and 255}.${(gw shr 16) and 255}.${(gw shr 24) and 255}"
                }
            }
            //tebakan umum: x.y.z.1 di setiap subnet (hotspot Android biasanya 192.168.43.1 / 192.168.x.1)
            localIpv4().forEach { l ->
                val p = l.address.split('.')
                if (p.size == 4 && p[3] != "1") out += "${p[0]}.${p[1]}.${p[2]}.1"
            }
            return out.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }

        private fun clean(s: String) = s.replace('|', '/').replace('\n', ' ').take(60)

        private val hashCache = ConcurrentHashMap<String, Pair<Long, String>>()

        private fun hashOf(f: File): String {
            val stamp = f.lastModified() xor f.length()
            hashCache[f.absolutePath]?.let { if (it.first == stamp) return it.second }
            val h = DigestUtils.digestToString("SHA-256", f.readBytes())
            hashCache[f.absolutePath] = stamp to h
            return h
        }

        private fun buildPayloads(): List<ByteArray> {
            val payloads = ArrayList<ByteArray>(2)
            val lan = ownLan?.takeIf { System.currentTimeMillis() - it.at <= LAN_TTL_MS }?.value
            if (lan != null) {
                payloads += "MAOU1|HOST|${clean(lan.second.ifBlank { account.username })}|${lan.first}".toByteArray()
            }
            val skinServer = skinServer
            if (skinServer != null) {
                val ygg = skinServer.getPort()
                val skinF = account.getSkinFile().takeIf { it.exists() }
                val capeF = account.getCapeFile().takeIf { it.exists() }
                if (ygg != null && (skinF != null || capeF != null)) {
                    //hash dihitung ulang hanya bila file berubah (sebelumnya tiap 2 detik)
                    val sh = skinF?.let { hashOf(it) } ?: ""
                    val ch = capeF?.let { hashOf(it) } ?: ""
                    val model = if (account.skinModelType == SkinModelType.ALEX) "slim" else "wide"
                    payloads += "MAOU1|SKIN|${clean(account.username)}|${account.profileId.replace("-", "")}|$model|$sh|$ch|$ygg"
                        .toByteArray()
                }
            }
            return payloads
        }

        private fun sendTo(sock: DatagramSocket, target: InetAddress, payloads: List<ByteArray>) {
            for (p in payloads) {
                runCatching { sock.send(DatagramPacket(p, p.size, target, BEACON_PORT)) }
            }
        }

        private fun beaconSender() {
            val sock = track(DatagramSocket().apply { broadcast = true })
            val limited = InetAddress.getByName("255.255.255.255")
            val ask = "MAOU1|ASK|-|0".toByteArray()
            while (alive) {
                val payloads = buildPayloads()
                val all = payloads + ask
                val now = System.currentTimeMillis()
                peers.entries.removeIf { now - it.value > PEER_TTL_MS }

                //1) broadcast lewat SETIAP antarmuka (socket diikat ke alamat antarmuka supaya keluar lewat hotspot/WiFi yang benar)
                for ((local, bc) in broadcastRoutes()) {
                    runCatching {
                        DatagramSocket(0, local).use { s ->
                            s.broadcast = true
                            sendTo(s, bc, all)
                            sendTo(s, limited, all)
                        }
                    }
                    sendTo(sock, bc, all)
                }
                sendTo(sock, limited, all)

                //2) kirim langsung ke gateway (pemberi hotspot) dan HP yang pernah terdengar:
                //   tetap jalan walau router/hotspot memblokir broadcast
                gatewayTargets().forEach { sendTo(sock, it, all) }
                peers.keys.forEach { ip ->
                    runCatching { sendTo(sock, InetAddress.getByName(ip), payloads) }
                }
                //hemat baterai: sering hanya saat ada host/teman; selain itu jarang
                val active = payloads.any { String(it).startsWith("MAOU1|HOST") } || peers.isNotEmpty()
                sleepAlive(if (active) 2000 else 6000)
            }
        }

        // ---- 3. terima siaran HP lain ---------------------------------------------------

        private fun beaconReceiver() {
            val sock = track(DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(BEACON_PORT))
                soTimeout = 1500
            })
            val buf = ByteArray(1024)
            while (alive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val src = packet.address.hostAddress ?: continue
                if (isLocal(src)) continue
                val parts = String(packet.data, 0, packet.length, Charsets.UTF_8).split('|')
                if (parts.size < 4 || parts[0] != "MAOU1") continue
                peers[src] = System.currentTimeMillis()
                when (parts[1]) {
                    "HOST" -> {
                        val port = parts[3].toIntOrNull() ?: continue
                        hosts[src] = Seen(HostInfo(src, port, parts[2]))
                    }
                    "SKIN" -> if (parts.size >= 8) onSkinBeacon(src, parts)
                    //ada HP yang mencari: jawab langsung ke pengirimnya
                    "ASK" -> {
                        val reply = buildPayloads()
                        if (reply.isNotEmpty()) runCatching {
                            for (p in reply) sock.send(DatagramPacket(p, p.size, packet.address, BEACON_PORT))
                        }
                    }
                }
            }
        }

        // ---- 3b. cari dunia LAN sendiri tanpa bergantung pada multicast ------------------
        //
        // Banyak HP tidak meneruskan pengumuman multicast bawaan game ke antarmuka hotspot
        // (terutama HP yang memberi hotspot dan tidak punya internet WiFi). Maka host dideteksi
        // juga dengan melihat port TCP yang sedang listen milik aplikasi ini lalu menanyakan
        // status Minecraft-nya.

        private fun listeningPorts(): Set<Int> {
            val uid = Process.myUid().toString()
            val out = HashSet<Int>()
            for (path in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
                runCatching {
                    File(path).useLines { lines ->
                        lines.drop(1).forEach { line ->
                            val c = line.trim().split(Regex("\\s+"))
                            if (c.size > 7 && c[3] == "0A" && c[7] == uid) {
                                val port = c[1].substringAfterLast(':').toIntOrNull(16)
                                if (port != null && port >= 1024) out += port
                            }
                        }
                    }
                }
            }
            return out
        }

        private fun probeMinecraft(port: Int): String? = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 400)
                s.soTimeout = 1200
                val hs = ByteArrayOutputStream().apply {
                    writeVarInt(this, 0)       // id handshake
                    writeVarInt(this, 767)     // versi protokol (tidak dipakai server untuk status)
                    write(stringPayload("localhost"))
                    write(byteArrayOf((port shr 8).toByte(), port.toByte()))
                    writeVarInt(this, 1)       // next state = status
                }.toByteArray()
                val out = s.getOutputStream()
                val framedHs = ByteArrayOutputStream().apply { writeVarInt(this, hs.size); write(hs) }.toByteArray()
                out.write(framedHs)
                out.write(byteArrayOf(1, 0)) // status request
                out.flush()
                val resp = ByteArrayInputStream(readPacket(s.getInputStream()))
                readVarInt(resp)
                if (readVarInt(resp) != 0) return@use null
                val json = JSONObject(readString(resp))
                if (!json.has("players") || !json.has("version")) return@use null
                plainText(json.opt("description")).replace(Regex("§."), "").trim()
            }
        }.getOrNull()

        private fun lanScanner() {
            while (alive) {
                val now = System.currentTimeMillis()
                scanMisses.entries.removeIf { now - it.value > 8_000L }
                val skip = HashSet<Int>().apply {
                    add(PROXY_PORT)
                    skinServer?.getPort()?.let { add(it) }
                }
                for (port in listeningPorts()) {
                    if (!alive) break
                    if (port in skip || scanMisses.containsKey(port)) continue
                    val motd = probeMinecraft(port)
                    if (motd != null) {
                        ownLan = Seen(port to motd)
                        break
                    } else {
                        scanMisses[port] = now
                    }
                }
                sleepAlive(5000)
            }
        }

        private fun onSkinBeacon(ip: String, p: List<String>) {
            val server = skinServer ?: return
            val name = p[2]
            val uuid = p[3]
            val slim = p[4] == "slim"
            val skinHash = p[5]
            val capeHash = p[6]
            val port = p[7].toIntOrNull() ?: return
            if (name.equals(account.username, ignoreCase = true)) return
            val version = "$skinHash/$capeHash"
            if (syncedPlayers[name] == version) return
            if (!fetching.add("$name:$version")) return

            pool.execute {
                try {
                    val skin = if (skinHash.isNotEmpty()) download(ip, port, skinHash) else null
                    val cape = if (capeHash.isNotEmpty()) download(ip, port, capeHash) else null
                    if (skin == null && cape == null) return@execute
                    server.addRemoteCharacter(
                        name = name,
                        uuid = uuid,
                        model = if (slim) SkinModelType.ALEX else SkinModelType.STEVE,
                        skinBytes = skin,
                        capeBytes = cape
                    )
                    syncedPlayers[name] = version
                    Logger.info(TAG, "Synced skin/cape of $name from $ip")
                } catch (e: Throwable) {
                    Logger.warning(TAG, "Failed to sync skin of $name", e)
                } finally {
                    fetching.remove("$name:$version")
                }
            }
        }

        private fun download(ip: String, port: Int, hash: String): ByteArray? {
            val conn = URL("http://$ip:$port/textures/$hash").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            return try {
                if (conn.responseCode != 200) return null
                val bytes = conn.inputStream.use { it.readLimited(MAX_TEXTURE_BYTES) }
                //verifikasi isi sama dengan hash yang diumumkan
                if (DigestUtils.digestToString("SHA-256", bytes) == hash) bytes else null
            } finally {
                conn.disconnect()
            }
        }

        private fun InputStream.readLimited(limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("texture too large")
            }
            return out.toByteArray()
        }

        // ---- 4. proxy lokal untuk entri "Mabar Hotspot" ----------------------------------

        private fun clientProxy() {
            val server = try {
                track(ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PROXY_PORT), 8)
                })
            } catch (e: IOException) {
                Logger.warning(TAG, "Proxy port $PROXY_PORT unavailable", e)
                return
            }
            while (alive) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute {
                    try {
                        handleClient(client)
                    } catch (_: Throwable) {
                        // sambungan putus: normal
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            client.tcpNoDelay = true
            client.soTimeout = 8000
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            val handshake = readPacket(cin)
            val hs = ByteArrayInputStream(handshake)
            readVarInt(hs) // panjang
            if (readVarInt(hs) != 0) return
            val protocol = readVarInt(hs)
            val addrLen = readVarInt(hs)
            hs.skip(addrLen.toLong())
            hs.skip(2) // port
            val nextState = readVarInt(hs)

            var host = currentHost()
            if (host == null && nextState != 1) {
                //beri waktu sebentar: siaran host bisa baru tiba
                var waited = 0
                while (host == null && waited < 5000 && alive) {
                    Thread.sleep(250); waited += 250
                    host = currentHost()
                }
            }
            when (nextState) {
                1 -> handleStatus(client, cin, cout, handshake, protocol, host)
                else -> handleLogin(client, cin, cout, handshake, host)
            }
        }

        private fun connect(host: HostInfo, timeout: Int): Socket =
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host.ip, host.port), timeout)
                soTimeout = timeout
            }

        private fun handleStatus(
            client: Socket,
            cin: InputStream,
            cout: OutputStream,
            handshake: ByteArray,
            protocol: Int,
            host: HostInfo?
        ) {
            val request = readPacket(cin)
            var upstream: Socket? = null
            var json: String? = null
            if (host != null) {
                try {
                    upstream = connect(host, 2500)
                    upstream.getOutputStream().apply { write(handshake); write(request); flush() }
                    val resp = ByteArrayInputStream(readPacket(upstream.getInputStream()))
                    readVarInt(resp); readVarInt(resp) // len, id
                    json = patchStatus(readString(resp), host)
                    lastStatusJson = json
                } catch (_: Throwable) {
                    upstream?.let { runCatching { it.close() } }
                    upstream = null
                }
            }
            //host tidak menjawab saat refresh: tampilkan status terakhir (ping tetap ada), jangan jadi "mencari"
            if (json == null && host != null) json = lastStatusJson
            if (json == null) json = placeholderStatus(protocol, host)

            cout.write(statusPacket(json)); cout.flush()

            //ping/pong: teruskan ke host supaya garis sinyal di daftar server = latensi WiFi asli
            val ping = try { readPacket(cin) } catch (_: Throwable) { null }
            if (ping != null) {
                var replied = false
                if (upstream != null) {
                    try {
                        val t0 = System.nanoTime()
                        upstream.getOutputStream().apply { write(ping); flush() }
                        val pong = readPacket(upstream.getInputStream())
                        pingMs = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                        cout.write(pong); cout.flush()
                        replied = true
                    } catch (_: Throwable) {
                    }
                }
                if (!replied) {
                    cout.write(ping); cout.flush()
                }
            }
            upstream?.let { runCatching { it.close() } }
        }

        private fun handleLogin(
            client: Socket,
            cin: InputStream,
            cout: OutputStream,
            handshake: ByteArray,
            host: HostInfo?
        ) {
            if (host == null) {
                val msg = JSONObject().put(
                    "text",
                    "Host Mabar belum terdeteksi.\n1) HP host: buka dunia lalu Esc > Open to LAN.\n2) Kedua HP harus tersambung ke hotspot/WiFi yang sama.\n3) Kedua HP harus membuka game dengan Maou Launcher (Mabar aktif)."
                ).toString()
                cout.write(loginDisconnectPacket(msg)); cout.flush()
                return
            }
            var upstreamTry: Socket? = null
            var lastErr: IOException? = null
            for (attempt in 0 until 4) {
                try {
                    upstreamTry = connect(host, 3000)
                    break
                } catch (e: IOException) {
                    lastErr = e
                    Thread.sleep(600)
                }
            }
            val upstream = if (upstreamTry != null) upstreamTry else run {
                val msg = JSONObject().put("text", "Tidak bisa tersambung ke host ${host.ip}:${host.port}").toString()
                cout.write(loginDisconnectPacket(msg)); cout.flush()
                return
            }
            activeLinks.incrementAndGet()
            try {
                client.soTimeout = 0
                upstream.soTimeout = 0
                val uout = upstream.getOutputStream()
                uout.write(handshake); uout.flush()
                val t = Thread({ pipe(upstream.getInputStream(), cout, client, upstream) }, "MabarPipe").apply {
                    isDaemon = true
                    start()
                }
                pipe(cin, uout, client, upstream)
                t.join(1000)
            } finally {
                activeLinks.decrementAndGet()
                runCatching { upstream.close() }
            }
        }

        private fun pipe(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: IOException) {
            } finally {
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }

        // ---- JSON status ---------------------------------------------------------------

        private fun plainText(node: Any?): String = when (node) {
            is String -> node
            is JSONObject -> {
                val sb = StringBuilder(node.optString("text", ""))
                node.optJSONArray("extra")?.let { arr ->
                    for (i in 0 until arr.length()) sb.append(plainText(arr.opt(i)))
                }
                sb.toString()
            }
            else -> ""
        }

        private fun patchStatus(json: String, host: HostInfo): String {
            val obj = JSONObject(json)
            val motd = plainText(obj.opt("description")).replace(Regex("§."), "").trim()
            obj.put(
                "description",
                JSONObject().put("text", "§b[Hotspot] §f${motd.ifEmpty { host.motd }}\n§7${host.ip}:${host.port}")
            )
            obj.put("favicon", faviconUri)
            return obj.toString()
        }

        private fun placeholderStatus(protocol: Int, host: HostInfo?): String {
            val text = if (host == null) {
                "§b[Hotspot] §7Mencari host Mabar...\n§8Host: Esc > Open to LAN, teman: sambung ke hotspot"
            } else {
                "§b[Hotspot] §e${host.motd} §7(host tidak merespons)"
            }
            return JSONObject()
                .put("version", JSONObject().put("name", "Mabar").put("protocol", protocol))
                .put("players", JSONObject().put("max", VANILLA_MAX_PLAYERS).put("online", 0))
                .put("description", JSONObject().put("text", text))
                .put("favicon", faviconUri)
                .toString()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Protokol Minecraft (VarInt + paket)
    // ---------------------------------------------------------------------------------------

    private fun readVarInt(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) throw IOException("VarInt too long")
        }
    }

    private fun writeVarInt(out: OutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    /** Baca satu paket utuh (termasuk awalan panjang) agar bisa diteruskan apa adanya. */
    private fun readPacket(input: InputStream): ByteArray {
        val head = ByteArrayOutputStream()
        var len = 0
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            head.write(b)
            len = len or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("bad packet length")
        }
        if (len < 0 || len > 1 shl 20) throw IOException("bad packet length")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return head.toByteArray() + body
    }

    private fun readString(input: InputStream): String {
        val len = readVarInt(input)
        val bytes = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(bytes, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun framed(id: Int, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            writeVarInt(this, id)
            write(payload)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeVarInt(this, body.size)
            write(body)
        }.toByteArray()
    }

    private fun stringPayload(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().apply {
            writeVarInt(this, bytes.size)
            write(bytes)
        }.toByteArray()
    }

    private fun statusPacket(json: String) = framed(0x00, stringPayload(json))

    /** Paket Disconnect pada fase Login (id 0x00, teks JSON) */
    private fun loginDisconnectPacket(json: String) = framed(0x00, stringPayload(json))
}
