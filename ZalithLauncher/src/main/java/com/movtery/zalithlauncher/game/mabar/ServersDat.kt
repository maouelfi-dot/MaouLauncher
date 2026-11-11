/*
 * Maou Launcher - Mabar Hotspot
 * Pembaca/penulis NBT minimal untuk servers.dat (daftar server Minecraft),
 * dipakai menambahkan entri "Mabar Hotspot" yang menunjuk ke proxy lokal.
 */

package com.movtery.zalithlauncher.game.mabar

import android.util.Base64
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

private const val TAG = "ServersDat"

internal object ServersDat {
    private const val ENTRY_NAME = "Mabar Hotspot"
    private val ENTRY_IP = "127.0.0.1:${MabarLan.PROXY_PORT}"

    /** Tag NBT: [id] tipe tag, [v] nilai (lihat readPayload untuk pemetaan tipe). */
    private class Tag(val id: Int, var v: Any?)

    private class ListV(val elemId: Int, val items: MutableList<Any?>)

    /**
     * Pastikan servers.dat di [gameDir] memiliki entri Mabar Hotspot di urutan pertama.
     * Jika file tidak bisa dibaca dengan benar, file tidak disentuh sama sekali.
     */
    fun ensureMabarEntry(gameDir: File, icon: ByteArray) {
        val file = File(gameDir, "servers.dat")
        val root: LinkedHashMap<String, Tag> = if (file.isFile && file.length() > 0) {
            runCatching { readRoot(file.readBytes()) }.getOrElse {
                Logger.warning(TAG, "servers.dat unreadable, leaving as is", it)
                return
            }
        } else {
            LinkedHashMap()
        }

        val serversTag = root.getOrPut("servers") { Tag(9, ListV(10, mutableListOf())) }
        @Suppress("UNCHECKED_CAST")
        val list = (serversTag.v as? ListV)?.takeIf { it.elemId == 10 || it.items.isEmpty() } ?: return
        if (list.items.isEmpty()) {
            serversTag.v = ListV(10, list.items)
        }

        @Suppress("UNCHECKED_CAST")
        val entries = list.items as MutableList<Any?>
        val existing = entries.firstOrNull { e ->
            ((e as? LinkedHashMap<String, Tag>)?.get("ip")?.v as? String) == ENTRY_IP
        } as? LinkedHashMap<String, Tag>

        val entry = existing ?: LinkedHashMap<String, Tag>().also { entries.add(0, it) }
        entry["name"] = Tag(8, ENTRY_NAME)
        entry["ip"] = Tag(8, ENTRY_IP)
        //ikon awal (sebelum ping selesai); setelah itu game memakai favicon dari proxy
        entry["icon"] = Tag(8, Base64.encodeToString(icon, Base64.NO_WRAP))
        entry["acceptTextures"] = Tag(1, 1.toByte())

        val bytes = writeRoot(root)
        val tmp = File(gameDir, "servers.dat.maou_tmp")
        gameDir.mkdirs()
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        Logger.info(TAG, "Mabar Hotspot entry ensured in ${file.absolutePath}")
    }

    // ---- NBT ---------------------------------------------------------------------------

    private fun readRoot(data: ByteArray): LinkedHashMap<String, Tag> {
        val input = DataInputStream(ByteArrayInputStream(data))
        require(input.readUnsignedByte() == 10) { "root is not a compound" }
        input.readUTF()
        @Suppress("UNCHECKED_CAST")
        return readPayload(10, input) as LinkedHashMap<String, Tag>
    }

    private fun readPayload(id: Int, input: DataInputStream): Any? = when (id) {
        1 -> input.readByte()
        2 -> input.readShort()
        3 -> input.readInt()
        4 -> input.readLong()
        5 -> input.readFloat()
        6 -> input.readDouble()
        7 -> ByteArray(input.readInt().also { require(it >= 0) }).also { input.readFully(it) }
        8 -> input.readUTF()
        9 -> {
            val elem = input.readUnsignedByte()
            val len = input.readInt()
            require(len >= 0)
            ListV(elem, MutableList(len) { readPayload(elem, input) })
        }
        10 -> {
            val map = LinkedHashMap<String, Tag>()
            while (true) {
                val t = input.readUnsignedByte()
                if (t == 0) break
                val name = input.readUTF()
                map[name] = Tag(t, readPayload(t, input))
            }
            map
        }
        11 -> IntArray(input.readInt().also { require(it >= 0) }).also { a ->
            for (i in a.indices) a[i] = input.readInt()
        }
        12 -> LongArray(input.readInt().also { require(it >= 0) }).also { a ->
            for (i in a.indices) a[i] = input.readLong()
        }
        else -> throw IllegalArgumentException("unknown NBT tag $id")
    }

    private fun writeRoot(root: Map<String, Tag>): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeByte(10)
        out.writeUTF("")
        writePayload(10, root, out)
        out.flush()
        return bos.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun writePayload(id: Int, v: Any?, out: DataOutputStream) {
        when (id) {
            1 -> out.writeByte((v as Byte).toInt())
            2 -> out.writeShort((v as Short).toInt())
            3 -> out.writeInt(v as Int)
            4 -> out.writeLong(v as Long)
            5 -> out.writeFloat(v as Float)
            6 -> out.writeDouble(v as Double)
            7 -> (v as ByteArray).let { out.writeInt(it.size); out.write(it) }
            8 -> out.writeUTF(v as String)
            9 -> (v as ListV).let { l ->
                out.writeByte(if (l.items.isEmpty()) 0 else l.elemId)
                out.writeInt(l.items.size)
                l.items.forEach { writePayload(l.elemId, it, out) }
            }
            10 -> {
                (v as Map<String, Tag>).forEach { (name, tag) ->
                    out.writeByte(tag.id)
                    out.writeUTF(name)
                    writePayload(tag.id, tag.v, out)
                }
                out.writeByte(0)
            }
            11 -> (v as IntArray).let { out.writeInt(it.size); it.forEach(out::writeInt) }
            12 -> (v as LongArray).let { out.writeInt(it.size); it.forEach(out::writeLong) }
        }
    }
}
