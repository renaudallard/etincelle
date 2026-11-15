// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.net.Network
import it.allard.etincelle.core.remote.RemoteLink
import it.allard.etincelle.core.remote.RemoteTv
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A connection from the phone to an etincelle TV app (see core/remote), to drive it as a Chromecast is.
 * [onMessage] gets each message from the TV, [onEnded] the end of a connection that was up; both on the
 * main thread, and never after [close]. Main thread only.
 */
internal class TvRemoteSession(
    val tv: RemoteTv,
    private val network: Network?,
    private val onMessage: (JSONObject) -> Unit,
    private val onEnded: (Throwable?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Messages to the TV leave in order, off the main thread.
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var link: RemoteLink? = null

    @Volatile
    private var closed = false

    /** Connects and checks that the TV is signed in to the same account ([accountKey]). Throws otherwise. */
    suspend fun start(accountKey: ByteArray) {
        // Not cancellable, or a socket opened meanwhile would be dropped without being closed.
        val connected = withContext(Dispatchers.IO + NonCancellable) {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                socket.connect(InetSocketAddress(tv.host, tv.port), CONNECT_TIMEOUT_MS)
                RemoteLink.connect(socket, accountKey).also {
                    // The TV says how playback goes every second: silence this long means it is gone.
                    socket.soTimeout = READ_TIMEOUT_MS
                }
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }
        if (closed) {
            connected.close()
            throw IOException("session closed")
        }
        link = connected
        scope.launch(Dispatchers.IO) { read(connected) }
    }

    fun send(message: JSONObject) {
        val link = link ?: return
        val text = message.toString()
        runCatching { writer.execute { runCatching { link.send(text) } } }
    }

    fun close() {
        closed = true
        val link = link
        // After the messages already queued, such as a last stop, so they are not lost.
        if (runCatching { writer.execute { link?.close() } }.isFailure) link?.close()
        writer.shutdown()
        scope.cancel()
    }

    private suspend fun read(link: RemoteLink) {
        // Only an error or the end of the connection leaves the loop.
        var error: Exception? = null
        try {
            while (true) {
                val message = JSONObject(link.receive())
                withContext(Dispatchers.Main) { if (!closed) onMessage(message) }
            }
        } catch (e: Exception) {
            error = e
        }
        withContext(Dispatchers.Main + NonCancellable) {
            if (closed) return@withContext
            closed = true
            link.close()
            onEnded(error)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
    }
}
