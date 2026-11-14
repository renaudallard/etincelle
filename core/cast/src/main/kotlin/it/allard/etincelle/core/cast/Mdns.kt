// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import java.io.ByteArrayOutputStream

/** A resource record from an mDNS answer; names are lower case, as DNS names compare that way. */
internal sealed interface MdnsRecord {
    val name: String
}

internal data class PtrRecord(override val name: String, val target: String) : MdnsRecord

internal data class SrvRecord(override val name: String, val port: Int, val target: String) : MdnsRecord

internal data class TxtRecord(override val name: String, val entries: Map<String, String>) : MdnsRecord

internal data class ARecord(override val name: String, val address: String) : MdnsRecord

/**
 * The few mDNS messages Chromecast discovery needs: a PTR query, and a parser for the answers. The
 * parser works on untrusted network input, so every read is bounds checked, name compression pointers
 * may only point backwards and are followed a limited number of times, and a malformed packet yields
 * no records rather than an error.
 */
internal object Mdns {
    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33
    private const val CLASS_IN_UNICAST = 0x8001
    private const val FLAG_RESPONSE = 0x8000
    private const val MAX_POINTER_JUMPS = 16
    private const val MAX_NAME_LENGTH = 255

    /** A query for the PTR records of [names], each asking for a unicast answer. */
    fun query(names: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: id 0, standard query, one question per name, no records.
        out.write(byteArrayOf(0, 0, 0, 0, (names.size shr 8).toByte(), names.size.toByte(), 0, 0, 0, 0, 0, 0))
        for (name in names) {
            for (label in name.split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..63) { "bad DNS label in $name" }
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            out.write(byteArrayOf(0, TYPE_PTR.toByte(), (CLASS_IN_UNICAST shr 8).toByte(), CLASS_IN_UNICAST.toByte()))
        }
        return out.toByteArray()
    }

    /** The A, PTR, SRV and TXT records of the response in the first [length] bytes of [packet]. */
    fun parse(packet: ByteArray, length: Int = packet.size): List<MdnsRecord> =
        try {
            Reader(packet, length.coerceIn(0, packet.size)).records()
        } catch (e: MalformedPacketException) {
            emptyList()
        }

    private class MalformedPacketException : Exception()

    private class Reader(private val data: ByteArray, private val limit: Int) {
        private var pos = 0

        fun records(): List<MdnsRecord> {
            if (u16(2) and FLAG_RESPONSE == 0) return emptyList()
            val questions = u16(4)
            val records = u16(6) + u16(8) + u16(10)
            pos = 12
            repeat(questions) {
                pos = nameAt(pos).second + 4
                ensure(pos <= limit)
            }
            val out = ArrayList<MdnsRecord>()
            repeat(records) {
                val (name, afterName) = nameAt(pos)
                val type = u16(afterName)
                val ttl = u32(afterName + 4)
                val rdLength = u16(afterName + 8)
                val rdStart = afterName + 10
                ensure(rdStart + rdLength <= limit)
                pos = rdStart + rdLength
                // A zero TTL announces that the record is gone: leave it out.
                if (ttl == 0L) return@repeat
                when (type) {
                    TYPE_PTR -> out += PtrRecord(name, nameAt(rdStart).first)
                    TYPE_SRV -> {
                        ensure(rdLength >= 7)
                        out += SrvRecord(name, u16(rdStart + 4), nameAt(rdStart + 6).first)
                    }
                    TYPE_TXT -> out += TxtRecord(name, txt(rdStart, rdStart + rdLength))
                    TYPE_A -> if (rdLength == 4) {
                        out += ARecord(name, (0 until 4).joinToString(".") { u8(rdStart + it).toString() })
                    }
                }
            }
            return out
        }

        // The name at [start], and the position just after it in the packet (after the first pointer
        // when the name is compressed).
        private fun nameAt(start: Int): Pair<String, Int> {
            val name = StringBuilder()
            var p = start
            var end = -1
            var jumps = 0
            while (true) {
                val len = u8(p)
                when {
                    len == 0 -> {
                        if (end < 0) end = p + 1
                        return name.toString().lowercase() to end
                    }
                    len and 0xC0 == 0xC0 -> {
                        val target = ((len and 0x3F) shl 8) or u8(p + 1)
                        ensure(target < p && ++jumps <= MAX_POINTER_JUMPS)
                        if (end < 0) end = p + 2
                        p = target
                    }
                    len and 0xC0 != 0 -> throw MalformedPacketException()
                    else -> {
                        ensure(p + 1 + len <= limit)
                        if (name.isNotEmpty()) name.append('.')
                        name.append(String(data, p + 1, len, Charsets.UTF_8))
                        ensure(name.length <= MAX_NAME_LENGTH)
                        p += 1 + len
                    }
                }
            }
        }

        // TXT data: length-prefixed "key=value" strings; a key is matched case-insensitively.
        private fun txt(start: Int, end: Int): Map<String, String> {
            val entries = LinkedHashMap<String, String>()
            var p = start
            while (p < end) {
                val len = u8(p)
                ensure(p + 1 + len <= end)
                val entry = String(data, p + 1, len, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) entries.putIfAbsent(entry.substring(0, eq).lowercase(), entry.substring(eq + 1))
                p += 1 + len
            }
            return entries
        }

        private fun u8(at: Int): Int {
            ensure(at in 0 until limit)
            return data[at].toInt() and 0xFF
        }

        private fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

        private fun u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        private fun ensure(ok: Boolean) {
            if (!ok) throw MalformedPacketException()
        }
    }
}
