// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** A Chromecast found by [CastScanner], reachable at [host]:[port]. */
data class DirectCastDevice(val id: String, val name: String, val host: String, val port: Int)

/**
 * Finds Chromecasts without Play Services. Play Services relies on multicast answers, which some phones
 * stop receiving until Wi-Fi is turned off and on again. A query sent from an ordinary port is answered
 * by unicast to that port (mDNS "legacy unicast"), which those phones still receive.
 */
internal object CastScanner {
    private const val MDNS_GROUP = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val LISTEN_MS = 1_500L
    private const val RECEIVE_TIMEOUT_MS = 300
    private const val MAX_PACKET = 9_000
    private const val MAX_RECORDS = 2_048

    /**
     * Asks the network for Chromecasts that can run [appId] and returns those that answer. Blocking for
     * about [LISTEN_MS]; never throws. [network] is the Wi-Fi network to ask on; null asks on the default.
     */
    fun scan(appId: String, network: Network?): List<DirectCastDevice> {
        val query = Mdns.query(listOf("_googlecast._tcp.local", subtype(appId)))
        val records = ArrayList<MdnsRecord>()
        runCatching {
            DatagramSocket(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 0)).use { socket ->
                network?.bindSocket(socket)
                socket.soTimeout = RECEIVE_TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(MDNS_GROUP), MDNS_PORT))
                val buffer = ByteArray(MAX_PACKET)
                val end = System.nanoTime() + LISTEN_MS * 1_000_000
                while (System.nanoTime() < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    records += Mdns.parse(packet.data, packet.length)
                    // Far more than a home network answers: a device flooding the scan must not fill
                    // the memory, and what was heard before it is enough to go on.
                    if (records.size >= MAX_RECORDS) break
                }
            }
        }
        return devicesFrom(records, appId)
    }

    /**
     * The devices described by [records]: an instance listed under [appId]'s subtype, with its SRV
     * (port and host), its TXT (`id`, `fn` name) and the A record of that host. Answers often spread
     * these over several packets, so [records] holds everything received during one scan.
     */
    fun devicesFrom(records: List<MdnsRecord>, appId: String): List<DirectCastDevice> {
        val subtype = subtype(appId)
        val instances = records.filterIsInstance<PtrRecord>().filter { it.name == subtype }.map { it.target }.distinct()
        val srv = records.filterIsInstance<SrvRecord>().associateBy { it.name }
        val txt = records.filterIsInstance<TxtRecord>().associateBy { it.name }
        val addresses = records.filterIsInstance<ARecord>().associate { it.name to it.address }
        return instances.mapNotNull { instance ->
            val service = srv[instance] ?: return@mapNotNull null
            val entries = txt[instance]?.entries ?: return@mapNotNull null
            val id = entries["id"]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val host = addresses[service.target] ?: return@mapNotNull null
            val name = entries["fn"]?.takeIf { it.isNotEmpty() } ?: entries["md"]?.takeIf { it.isNotEmpty() } ?: id
            DirectCastDevice(id, name, host, service.port)
        }.distinctBy { it.id }
    }

    private fun subtype(appId: String) = "_${appId.lowercase()}._sub._googlecast._tcp.local"
}
