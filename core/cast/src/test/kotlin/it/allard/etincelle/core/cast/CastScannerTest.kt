// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Covers the mDNS query, the answer parser and how Chromecasts are assembled from the answers. */
class CastScannerTest {

    private val appId = "9527437F"
    private val instance = "Chromecast-abc._googlecast._tcp.local"
    private val host = "abc.local"

    /** Builds a DNS message; names are written uncompressed unless given as raw bytes. */
    private class Message(private val flags: Int = 0x8400) {
        private val body = ByteArrayOutputStream()
        private var records = 0

        val offset: Int get() = 12 + body.size()

        fun record(name: ByteArray, type: Int, rdata: ByteArray, ttl: Long = 120) {
            body.write(name)
            body.write(u16(type))
            body.write(u16(0x8001))
            body.write(byteArrayOf((ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte()))
            body.write(u16(rdata.size))
            body.write(rdata)
            records++
        }

        fun record(name: String, type: Int, rdata: ByteArray, ttl: Long = 120) = record(name(name), type, rdata, ttl)

        fun bytes(): ByteArray = u16(0) + u16(flags) + u16(0) + u16(records) + u16(0) + u16(0) + body.toByteArray()

        companion object {
            fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

            fun name(n: String): ByteArray {
                val out = ByteArrayOutputStream()
                for (label in n.split('.')) {
                    out.write(label.length)
                    out.write(label.toByteArray())
                }
                out.write(0)
                return out.toByteArray()
            }

            fun txt(vararg entries: String): ByteArray {
                val out = ByteArrayOutputStream()
                for (e in entries) {
                    out.write(e.length)
                    out.write(e.toByteArray())
                }
                return out.toByteArray()
            }

            fun srv(port: Int, target: String) = u16(0) + u16(0) + u16(port) + name(target)
        }
    }

    // The answer to the plain service query: PTR plus the instance's TXT, SRV and A records.
    private fun serviceAnswer(ttl: Long = 120) = Message().apply {
        record("_googlecast._tcp.local", 12, Message.name(instance))
        record(instance, 16, Message.txt("id=abc", "md=Chromecast", "fn=jardin", "rs="), ttl)
        record(instance, 33, Message.srv(8009, host), ttl)
        record(host, 1, byteArrayOf(10, 0, 0, 5), ttl)
    }.bytes()

    // The answer to the receiver's subtype query; upper case, as Chromecasts write the app id.
    private fun subtypeAnswer() = Message().apply {
        record("_$appId._sub._googlecast._tcp.local", 12, Message.name(instance))
    }.bytes()

    private fun devices(vararg packets: ByteArray) = CastScanner.devicesFrom(packets.flatMap { Mdns.parse(it) }, appId)

    @Test
    fun aDeviceIsAssembledFromSeveralPackets() {
        assertEquals(listOf(DirectCastDevice("abc", "jardin", "10.0.0.5", 8009)), devices(serviceAnswer(), subtypeAnswer()))
    }

    @Test
    fun aDeviceThatCannotRunTheReceiverIsLeftOut() {
        assertTrue(devices(serviceAnswer()).isEmpty())
    }

    @Test
    fun aDeviceWhoseRecordsWereWithdrawnIsLeftOut() {
        assertTrue(devices(serviceAnswer(ttl = 0), subtypeAnswer()).isEmpty())
    }

    @Test
    fun compressedNamesAreFollowed() {
        val message = Message()
        val instanceAt = message.offset
        message.record("_$appId._sub._googlecast._tcp.local", 12, Message.name(instance))
        // The PTR's data (the instance name) starts 10 bytes past the record's own name; later records
        // name the instance by pointing back to it.
        val dataAt = instanceAt + Message.name("_$appId._sub._googlecast._tcp.local").size + 10
        val pointer = byteArrayOf((0xC0 or (dataAt shr 8)).toByte(), dataAt.toByte())
        message.record(pointer, 16, Message.txt("id=abc", "fn=jardin"))
        message.record(pointer, 33, Message.srv(8009, host))
        message.record(host, 1, byteArrayOf(10, 0, 0, 5))
        assertEquals(listOf(DirectCastDevice("abc", "jardin", "10.0.0.5", 8009)), devices(message.bytes()))
    }

    @Test
    fun aPointerLoopYieldsNoRecords() {
        val message = Message()
        val at = message.offset
        // A name made of a pointer to itself.
        message.record(byteArrayOf((0xC0 or (at shr 8)).toByte(), at.toByte()), 1, byteArrayOf(10, 0, 0, 5))
        assertTrue(Mdns.parse(message.bytes()).isEmpty())
    }

    @Test
    fun aTruncatedPacketYieldsNoRecords() {
        val packet = serviceAnswer()
        for (cut in listOf(5, 20, packet.size / 2, packet.size - 1)) {
            assertTrue("cut at $cut", Mdns.parse(packet, cut).isEmpty())
        }
    }

    @Test
    fun aQueryIsNotTakenForAnAnswer() {
        val query = Message(flags = 0).apply { record(host, 1, byteArrayOf(10, 0, 0, 5)) }.bytes()
        assertTrue(Mdns.parse(query).isEmpty())
    }

    @Test
    fun theQueryAsksForUnicastAnswers() {
        val expected = Message.u16(0) + Message.u16(0) + Message.u16(2) + Message.u16(0) + Message.u16(0) + Message.u16(0) +
            Message.name("a.bc") + byteArrayOf(0, 12, 0x80.toByte(), 1) +
            Message.name("x") + byteArrayOf(0, 12, 0x80.toByte(), 1)
        assertArrayEquals(expected, Mdns.query(listOf("a.bc", "x")))
    }
}
