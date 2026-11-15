// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** An etincelle TV app found by [RemoteDiscovery], taking remote connections at [host]:[port]. */
data class RemoteTv(val id: String, val name: String, val host: String, val port: Int)

/**
 * Finds the etincelle TV apps open on the local network. The phone broadcasts a request to
 * [PORT]; each TV app answers it directly with its id, name and the port it takes connections on.
 * Android's own service announcer only answers by multicast, which some phones stop receiving, while a
 * direct answer always arrives (the same reason Chromecasts are also looked for with direct answers).
 * Finding a TV gives nothing away: driving it takes the account handshake of [RemoteLink].
 */
object RemoteDiscovery {
    const val PORT = 47_811
    private const val REQUEST = "etincelle-discover\n1"
    private const val ANSWER = "etincelle-tv"
    private const val LISTEN_MS = 1_200L
    private const val RECEIVE_TIMEOUT_MS = 300
    private const val MAX_PACKET = 1_500

    /** The request a phone broadcasts. */
    fun request(): ByteArray = REQUEST.toByteArray()

    fun isRequest(data: ByteArray, length: Int): Boolean = String(data, 0, length, Charsets.UTF_8) == REQUEST

    /** A TV app's answer: its connection [port], [id] and [name]. */
    fun answer(port: Int, id: String, name: String): ByteArray =
        "$ANSWER\n${RemoteProtocol.VERSION}\n$port\n$id\n${name.replace('\n', ' ')}".toByteArray()

    /** The TV described by an answer received from [host], or null when it is not one. */
    fun parseAnswer(data: ByteArray, length: Int, host: String): RemoteTv? {
        val parts = String(data, 0, length, Charsets.UTF_8).split('\n', limit = 5)
        if (parts.size != 5 || parts[0] != ANSWER || parts[1] != RemoteProtocol.VERSION.toString()) return null
        val port = parts[2].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
        val id = parts[3].takeIf { it.isNotBlank() } ?: return null
        return RemoteTv(id, parts[4].ifBlank { id }, host, port)
    }

    /**
     * Asks the network for TV apps and returns those that answer. Blocking for about [LISTEN_MS]; never
     * throws. [network] is the Wi-Fi network to ask on; null asks on the default.
     */
    fun scan(network: Network?): List<RemoteTv> {
        val found = LinkedHashMap<String, RemoteTv>()
        runCatching {
            DatagramSocket(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 0)).use { socket ->
                network?.bindSocket(socket)
                socket.broadcast = true
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                val request = request()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), PORT))
                val buffer = ByteArray(MAX_PACKET)
                val end = System.nanoTime() + LISTEN_MS * 1_000_000
                while (System.nanoTime() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val host = packet.address?.hostAddress ?: continue
                    parseAnswer(packet.data, packet.length, host)?.let { found[it.id] = it }
                }
            }
        }
        return found.values.toList()
    }
}
