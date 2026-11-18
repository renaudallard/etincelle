// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.net.Network
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.remote.RemoteLink
import it.allard.etincelle.core.remote.RemoteMessages
import it.allard.etincelle.core.remote.RemoteTv
import it.allard.etincelle.core.remote.StatusPacer
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
 * What this is to a TV app, as the kinds and ids that name it (see RemoteMessages): the first is the
 * one to ask for, and the TV may name what it plays by any of them. A recording played from the page of
 * its episode is asked for by that page, which is where the TV finds the episode that follows, and comes
 * back named as the recording the page led to.
 */
internal fun PlaybackSource.remoteTargets(): List<Pair<String, String>> = listOfNotNull(
    originChannelId?.let { RemoteMessages.KIND_LIVE to it },
    originPageVodId?.let { RemoteMessages.KIND_VOD to it },
    originRecordingAssetId?.let { RemoteMessages.KIND_RECORDING to it },
    originVodId?.let { RemoteMessages.KIND_VOD to it },
)

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
        // Not cancellable, or a socket opened meanwhile would be dropped without being closed. The link is
        // kept from in there: a caller cancelled meanwhile is not handed it, and can only [close].
        val connected = withContext(Dispatchers.IO + NonCancellable) {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                socket.connect(InetSocketAddress(tv.host, tv.port), CONNECT_TIMEOUT_MS)
                val connected = RemoteLink.connect(socket, accountKey)
                // The TV says how playback goes every ten seconds at least (see StatusPacer): silence
                // this long means it is gone.
                socket.soTimeout = READ_TIMEOUT_MS
                synchronized(this@TvRemoteSession) {
                    if (closed) {
                        connected.close()
                        throw IOException("session closed")
                    }
                    link = connected
                }
                connected
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }
        scope.launch(Dispatchers.IO) { read(connected) }
    }

    fun send(message: JSONObject) {
        val link = link ?: return
        val text = message.toString()
        runCatching { writer.execute { runCatching { link.send(text) } } }
    }

    fun close() {
        val link = synchronized(this) {
            closed = true
            link
        }
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
        // Room for a status held up on the way, as while the phone moves to another access point.
        const val READ_TIMEOUT_MS = 3 * StatusPacer.KEEP_ALIVE_MS.toInt()
    }
}
