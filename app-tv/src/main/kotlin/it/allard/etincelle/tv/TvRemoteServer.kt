// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.content.Context
import android.os.Build
import android.provider.Settings
import it.allard.etincelle.core.remote.RemoteDiscovery
import it.allard.etincelle.core.remote.RemoteLink
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Lets a phone signed in to the same account drive this TV app while it is open (see
 * docs/tv-remote-design.md). It takes connections on a free port, announces itself as it starts and
 * answers the phones looking for TV apps (see [RemoteDiscovery]) with the TV's name, and keeps the latest
 * phone that proves the account key: its
 * commands reach [onCommand] on the main thread, and [send] tells it how playback goes. [start], [stop]
 * and [send] are main-thread calls.
 */
class TvRemoteServer(
    private val context: Context,
    private val accountKey: suspend () -> ByteArray?,
    private val onCommand: (JSONObject) -> Unit,
) {
    private var scope: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    // Messages to the phone leave in order, off the main thread, while the server runs.
    private var writer: ExecutorService? = null

    @Volatile
    private var link: RemoteLink? = null

    // The account key, worked out once while the server runs (main thread): a connection must cost no
    // call to the backend, or any device on the network could flood the app's own calls with them.
    private var key: ByteArray? = null
    private val keyLock = Mutex()

    // The addresses with a handshake in progress (main thread): each holds a thread, so a few at a time
    // is all phones need, and one per address, so a single device cannot keep them all busy.
    private val handshakes = HashSet<InetAddress?>()

    /** A phone is connected. */
    val connected: Boolean get() = link != null

    fun start() {
        if (scope != null) return
        // Bound here, so stop() always finds them to close (binding sends nothing on the network).
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return
        val discovery = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(RemoteDiscovery.PORT))
            }
        }.getOrNull()
        this.server = server
        this.discovery = discovery
        writer = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        // Ready before the first phone connects.
        scope.launch { accountKeyOnce() }
        discovery?.let { socket -> scope.launch(Dispatchers.IO) { answerDiscovery(socket, server.localPort) } }
        scope.launch {
            while (isActive) {
                val socket = runCatching { withContext(Dispatchers.IO) { server.accept() } }.getOrNull() ?: break
                launch { serve(socket) }
            }
        }
    }

    fun stop() {
        runCatching { discovery?.close() }
        discovery = null
        runCatching { server?.close() }
        server = null
        link?.close()
        link = null
        scope?.cancel()
        scope = null
        // Worked out afresh on the next start, should the account or its profiles have changed.
        key = null
        writer?.shutdown()
        writer = null
    }

    fun send(message: JSONObject) {
        val link = link ?: return
        val text = message.toString()
        runCatching { writer?.execute { runCatching { link.send(text) } } }
    }

    // One phone: the handshake, then its commands until it leaves. A newer phone takes over.
    private suspend fun serve(socket: Socket) {
        val key = accountKeyOnce()
        if (key == null) {
            runCatching { socket.close() }
            return
        }
        // Its read timeout only bounds each read: a device trickling bytes must not hold a thread for long.
        val address = socket.inetAddress
        if (handshakes.size >= MAX_HANDSHAKES || !handshakes.add(address)) {
            runCatching { socket.close() }
            return
        }
        val deadline = scope?.launch {
            delay(HANDSHAKE_DEADLINE_MS)
            runCatching { socket.close() }
        }
        // Not cancellable, so a handshake ending as the server stops still has its connection closed.
        val phone = try {
            runCatching { withContext(Dispatchers.IO + NonCancellable) { RemoteLink.accept(socket, key) } }.getOrNull()
        } finally {
            handshakes.remove(address)
            deadline?.cancel()
        } ?: return
        if (!currentCoroutineContext().isActive) {
            phone.close()
            return
        }
        link?.close()
        link = phone
        try {
            while (true) {
                val message = withContext(Dispatchers.IO) { phone.receive() }
                runCatching { JSONObject(message) }.getOrNull()?.let(onCommand)
            }
        } catch (e: Exception) {
            // The phone left, or broke the protocol: drop it.
        } finally {
            phone.close()
            if (link === phone) link = null
        }
    }

    private suspend fun accountKeyOnce(): ByteArray? = keyLock.withLock {
        key ?: runCatching { accountKey() }.getOrNull()?.also { key = it }
    }

    // Tells the phones already listening that this TV app opened, then each phone looking for TV apps where
    // to connect, on [socket], until the server stops.
    private fun answerDiscovery(socket: DatagramSocket, port: Int) {
        val answer = RemoteDiscovery.answer(port, installId(), deviceName())
        RemoteDiscovery.announce(socket, answer)
        val buffer = ByteArray(512)
        runCatching {
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (RemoteDiscovery.isRequest(packet.data, packet.length)) {
                    socket.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                }
            }
        }
        socket.close()
    }

    // The name the TV goes by in its settings, else its model.
    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // A random id of this install, so the phone tells two TVs of the same name apart.
    private fun installId(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }
    }

    private companion object {
        const val PREFS = "remote"
        const val KEY_ID = "install_id"
        const val MAX_HANDSHAKES = 4
        const val HANDSHAKE_DEADLINE_MS = 5_000L
    }
}
