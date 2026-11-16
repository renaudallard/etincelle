// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDiscoveryTest {

    @Test
    fun aTvAnswerNamesWhereToConnect() {
        val answer = RemoteDiscovery.answer(43_543, "c21d17ac", "jardin")
        assertEquals(RemoteTv("c21d17ac", "jardin", "172.20.0.185", 43_543), RemoteDiscovery.parseAnswer(answer, answer.size, "172.20.0.185"))
    }

    @Test
    fun aNameWithANewlineStaysOnOneLine() {
        val answer = RemoteDiscovery.answer(1, "id", "salon\nTV")
        assertEquals("salon TV", RemoteDiscovery.parseAnswer(answer, answer.size, "h")?.name)
    }

    @Test
    fun anOversizedAnswerIsCutDownOrDropped() {
        val longName = RemoteDiscovery.answer(1, "id", "n".repeat(500))
        assertEquals(64, RemoteDiscovery.parseAnswer(longName, longName.size, "h")?.name?.length)
        val longId = RemoteDiscovery.answer(1, "i".repeat(65), "tv")
        assertNull(RemoteDiscovery.parseAnswer(longId, longId.size, "h"))
    }

    @Test
    fun theSubnetIsAskedAddressByAddress() {
        val hosts = RemoteDiscovery.subnetHosts(byteArrayOf(172.toByte(), 20, 0, 94), 24).map { it.joinToString(".") { b -> (b.toInt() and 0xff).toString() } }
        assertEquals(253, hosts.size)
        assertEquals("172.20.0.1", hosts.first())
        assertEquals("172.20.0.254", hosts.last())
        assertTrue("172.20.0.180" in hosts)
        assertFalse("172.20.0.94" in hosts)
        assertTrue(RemoteDiscovery.subnetHosts(byteArrayOf(10, 0, 0, 1), 16).isEmpty())
        assertTrue(RemoteDiscovery.subnetHosts(ByteArray(16), 64).isEmpty())
    }

    @Test
    fun anythingElseIsNoTv() {
        val request = RemoteDiscovery.request()
        assertTrue(RemoteDiscovery.isRequest(request, request.size))
        assertNull(RemoteDiscovery.parseAnswer(request, request.size, "h"))
        val badPort = "etincelle-tv\n${RemoteProtocol.VERSION}\n70000\nid\nname".toByteArray()
        assertNull(RemoteDiscovery.parseAnswer(badPort, badPort.size, "h"))
        // A TV app of the version before, whose messages were not encrypted, is not listed.
        val otherVersion = "etincelle-tv\n${RemoteProtocol.VERSION - 1}\n80\nid\nname".toByteArray()
        assertNull(RemoteDiscovery.parseAnswer(otherVersion, otherVersion.size, "h"))
        val answer = RemoteDiscovery.answer(80, "id", "n")
        assertFalse(RemoteDiscovery.isRequest(answer, answer.size))
    }
}
