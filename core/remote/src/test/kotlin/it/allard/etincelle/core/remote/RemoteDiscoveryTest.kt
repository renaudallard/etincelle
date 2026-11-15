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
    fun anythingElseIsNoTv() {
        val request = RemoteDiscovery.request()
        assertTrue(RemoteDiscovery.isRequest(request, request.size))
        assertNull(RemoteDiscovery.parseAnswer(request, request.size, "h"))
        val badPort = "etincelle-tv\n1\n70000\nid\nname".toByteArray()
        assertNull(RemoteDiscovery.parseAnswer(badPort, badPort.size, "h"))
        val otherVersion = "etincelle-tv\n2\n80\nid\nname".toByteArray()
        assertNull(RemoteDiscovery.parseAnswer(otherVersion, otherVersion.size, "h"))
        val answer = RemoteDiscovery.answer(80, "id", "n")
        assertFalse(RemoteDiscovery.isRequest(answer, answer.size))
    }
}
