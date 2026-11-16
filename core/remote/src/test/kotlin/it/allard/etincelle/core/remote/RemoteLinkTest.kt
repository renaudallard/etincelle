// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteLinkTest {
    private val account = RemoteProtocol.accountKey("user", listOf("b-profile", "a-profile"))
    private val other = RemoteProtocol.accountKey("user", listOf("a-profile", "c-profile"))
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val tvSide = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        server.close()
        tvSide.shutdownNow()
    }

    private fun tvAccepting(key: ByteArray): Future<RemoteLink> = tvSide.submit(Callable { RemoteLink.accept(server.accept(), key) })

    private fun phoneSocket() = Socket(InetAddress.getLoopbackAddress(), server.localPort)

    @Test
    fun theAccountKeyIgnoresTheProfileOrder() {
        assertTrue(account.contentEquals(RemoteProtocol.accountKey("user", listOf("a-profile", "b-profile"))))
        assertNotEquals(account.toList(), other.toList())
    }

    @Test
    fun devicesOfTheSameAccountTalkBothWays() {
        val tv = tvAccepting(account)
        val phone = RemoteLink.connect(phoneSocket(), account)
        val link = tv.get()
        phone.send("""{"t":"play","id":"VOD_1"}""")
        phone.send("second")
        assertEquals("""{"t":"play","id":"VOD_1"}""", link.receive())
        assertEquals("second", link.receive())
        link.send("status\nwith a newline")
        assertEquals("status\nwith a newline", phone.receive())
        phone.close()
        link.close()
    }

    @Test
    fun aPhoneOfAnotherAccountIsTurnedAway() {
        val tv = tvAccepting(account)
        try {
            RemoteLink.connect(phoneSocket(), other)
            fail("the TV accepted another account")
        } catch (e: RemoteAuthException) {
            // Expected: the TV says no.
        }
        try {
            tv.get()
            fail("the TV kept a phone of another account")
        } catch (e: Exception) {
            assertTrue(e.cause is RemoteAuthException)
        }
    }

    @Test
    fun anAlteredMessageEndsTheConnection() {
        val tv = tvAccepting(account)
        // A phone that does the handshake right, then sends a message whose mac does not match.
        val socket = phoneSocket()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val tvNonce = RemoteLink.readText(input).split('\n')[2]
        val phoneNonce = "00112233445566778899aabbccddeeff"
        RemoteLink.writeText(output, "auth\n$phoneNonce\n${RemoteLink.mac(account, "phone|$tvNonce|$phoneNonce")}")
        RemoteLink.readText(input)
        RemoteLink.writeText(output, "{\"t\":\"stop\"} sent in the clear, as before encryption")
        val link = tv.get()
        try {
            link.receive()
            fail("an altered message went through")
        } catch (e: RemoteAuthException) {
            // Expected.
        }
        socket.close()
        link.close()
    }

    @Test
    fun messagesCrossTheNetworkEncryptedAndInOrderOnly() {
        // A relay between the phone and the TV, as a device of the network could be.
        val tv = tvAccepting(account)
        val toTv = phoneSocket()
        val relay = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val phoneThread = Executors.newSingleThreadExecutor()
        val phoneSide = phoneThread.submit(Callable { RemoteLink.connect(Socket(InetAddress.getLoopbackAddress(), relay.localPort), account) })
        val fromPhone = relay.accept()
        val phoneIn = DataInputStream(BufferedInputStream(fromPhone.getInputStream()))
        val phoneOut = DataOutputStream(BufferedOutputStream(fromPhone.getOutputStream()))
        val tvIn = DataInputStream(BufferedInputStream(toTv.getInputStream()))
        val tvOut = DataOutputStream(BufferedOutputStream(toTv.getOutputStream()))
        RemoteLink.writeFrame(phoneOut, RemoteLink.readFrame(tvIn))
        RemoteLink.writeFrame(tvOut, RemoteLink.readFrame(phoneIn))
        RemoteLink.writeFrame(phoneOut, RemoteLink.readFrame(tvIn))
        val phone = phoneSide.get()
        val link = tv.get()

        phone.send("""{"t":"play","id":"VOD_SECRET"}""")
        phone.send("second")
        val first = RemoteLink.readFrame(phoneIn)
        val second = RemoteLink.readFrame(phoneIn)
        assertTrue("the message is readable on the network", "VOD_SECRET" !in String(first, Charsets.ISO_8859_1))
        // Delivered out of order, the second message is refused.
        RemoteLink.writeFrame(tvOut, second)
        try {
            link.receive()
            fail("a reordered message went through")
        } catch (e: RemoteAuthException) {
            // Expected.
        }
        phone.close()
        link.close()
        fromPhone.close()
        toTv.close()
        relay.close()
        phoneThread.shutdownNow()
    }
}
