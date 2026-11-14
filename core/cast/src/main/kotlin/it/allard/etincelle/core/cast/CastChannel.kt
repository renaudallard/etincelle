// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.annotation.SuppressLint
import android.net.Network
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * A Cast v2 connection to a Chromecast (TLS on port 8009). [open] authenticates the device before
 * returning, so nothing is ever sent to a device that is not a genuine Cast device. Heartbeats are
 * handled here; every other message goes to the listener given to [start], on the reader thread.
 * Writes happen on a single writer thread, in order, so [send] may be called from any thread.
 */
internal class CastChannel private constructor(
    private val socket: SSLSocket,
    private val peerCertificate: ByteArray,
) : Closeable {

    private val input = DataInputStream(socket.inputStream.buffered())
    private val output = socket.outputStream
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // [closing]: no new message is accepted; [closed]: the socket is closed. Closing goes through the
    // writer thread so that messages queued before it still go out and the TLS close stays off the
    // caller's thread.
    @Volatile
    private var closing = false

    @Volatile
    private var closed = false

    // The I/O failure that ended the connection, whichever thread (reader or writer) saw it first; null
    // when the connection was closed on purpose.
    @Volatile
    private var lostConnection: IOException? = null

    /** Queues a JSON [payload] on [namespace]; false when the connection is gone. */
    fun send(namespace: String, payload: String, source: String = PLATFORM_SENDER, destination: String = PLATFORM_RECEIVER): Boolean =
        enqueue(CastMessage(source, destination, namespace, payload = payload))

    /**
     * Starts reading: [onMessage] gets every message but heartbeats, and [onClosed] is called once when
     * the connection ends, with the error that ended it, if any. Both run on the reader thread.
     */
    fun start(onMessage: (CastMessage) -> Unit, onClosed: (Throwable?) -> Unit) {
        socket.soTimeout = READ_TIMEOUT_MS
        writer.scheduleWithFixedDelay(
            { send(NS_HEARTBEAT, """{"type":"PING"}""") },
            HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS,
        )
        Thread({
            try {
                while (!closing) {
                    val message = read()
                    if (message.namespace == NS_HEARTBEAT && message.payload?.contains("\"PING\"") == true) {
                        enqueue(CastMessage(message.destination, message.source, NS_HEARTBEAT, payload = """{"type":"PONG"}"""))
                    } else if (message.namespace != NS_HEARTBEAT) {
                        onMessage(message)
                    }
                }
            } catch (e: IOException) {
                // A read timeout means the device stopped answering heartbeats.
                lost(e)
            }
            close()
            onClosed(lostConnection)
        }, "cast-channel").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (closing) return
        closing = true
        val closeSocket = Runnable {
            closed = true
            runCatching { socket.close() }
        }
        // The writer is gone if it was never usable; close right here then.
        if (runCatching { writer.execute(closeSocket) }.isFailure) closeSocket.run()
        writer.shutdown()
    }

    private fun authenticate() {
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        if (!write(CastMessage(PLATFORM_SENDER, PLATFORM_RECEIVER, NS_DEVICE_AUTH, binary = DeviceAuthMessages.challenge(nonce)))) {
            throw IOException("connection lost")
        }
        val reply = read()
        if (reply.namespace != NS_DEVICE_AUTH) throw CastDeviceAuth.Failure("no authentication reply")
        val response = reply.binary?.let(DeviceAuthMessages::response) ?: throw CastDeviceAuth.Failure("authentication refused")
        CastDeviceAuth.verify(response, peerCertificate, nonce)
    }

    private fun enqueue(message: CastMessage): Boolean {
        if (closing) return false
        return runCatching { writer.execute { write(message) } }.isSuccess
    }

    // Blocking: only on the writer thread, or while [open] authenticates on the caller's thread.
    private fun write(message: CastMessage): Boolean {
        if (closed) return false
        val body = message.encode()
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size ushr 24).toByte()
        frame[1] = (body.size ushr 16).toByte()
        frame[2] = (body.size ushr 8).toByte()
        frame[3] = body.size.toByte()
        body.copyInto(frame, 4)
        return try {
            synchronized(output) {
                output.write(frame)
                output.flush()
            }
            true
        } catch (e: IOException) {
            lost(e)
            close()
            false
        }
    }

    // Record [e] as the end of the connection, unless it was closed on purpose or already lost.
    @Synchronized
    private fun lost(e: IOException) {
        if (!closing && lostConnection == null) lostConnection = e
    }

    private fun read(): CastMessage {
        val length = input.readInt()
        if (length <= 0 || length > MAX_MESSAGE) throw IOException("bad message length $length")
        val body = ByteArray(length)
        input.readFully(body)
        return CastMessage.decode(body) ?: throw IOException("malformed message")
    }

    // A Chromecast presents a self-signed certificate, so TLS cannot verify it. Every certificate is
    // accepted here; the device's identity is then proven by [authenticate] before anything is sent.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object DeviceAuthenticatedLater : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_DEVICE_AUTH = "urn:x-cast:com.google.cast.tp.deviceauth"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        const val PLATFORM_SENDER = "sender-0"
        const val PLATFORM_RECEIVER = "receiver-0"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val AUTH_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val HEARTBEAT_MS = 5_000L
        private const val NONCE_SIZE = 16
        private const val MAX_MESSAGE = 64 * 1024

        /**
         * Connects to [host]:[port] over [network] (the default network when null) and authenticates
         * the device. Blocking; throws [IOException] or [CastDeviceAuth.Failure].
         */
        fun open(host: String, port: Int, network: Network?): CastChannel {
            val raw = (network?.socketFactory ?: SocketFactory.getDefault()).createSocket()
            try {
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(DeviceAuthenticatedLater), SecureRandom()) }
                val socket = tls.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                socket.soTimeout = AUTH_TIMEOUT_MS
                socket.startHandshake()
                val peer = socket.session.peerCertificates.firstOrNull()?.encoded ?: throw IOException("no certificate")
                val channel = CastChannel(socket, peer)
                try {
                    channel.authenticate()
                } catch (e: Exception) {
                    channel.close()
                    throw e
                }
                return channel
            } catch (e: Exception) {
                runCatching { raw.close() }
                throw e
            }
        }
    }
}
