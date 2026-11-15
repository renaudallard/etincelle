// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** etincelle's own remote control, between the phone and the TV app (see docs/tv-remote-design.md). */
object RemoteProtocol {
    const val VERSION = 1

    /**
     * The key both apps derive from the account they are signed in to: its user id and profile ids. The
     * profile ids are random UUIDs only a device signed in to the account can read, and they never cross
     * the local network, so a device of another account (or none) cannot compute this key.
     */
    fun accountKey(userId: String, profileIds: Collection<String>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("etincelle-remote-v1\n$userId\n${profileIds.sorted().joinToString(",")}".toByteArray())
}

/** The other side is not signed in to the same account, or broke the protocol. */
class RemoteAuthException(message: String) : IOException(message)

/**
 * One authenticated connection between a phone and a TV app. A frame is a 4-byte big-endian length and
 * UTF-8 text. The handshake proves to each side that the other holds the account key (see
 * [RemoteProtocol.accountKey]) without sending it: each proves an HMAC over both sides' random nonces.
 * After it every message travels as "seq\nmac\npayload", the mac being an HMAC-SHA256 under a key of
 * this connection over the direction, the sequence number and the payload, so a message can be neither
 * forged nor altered, replayed or reordered. [send] may be called from any thread, [receive] from one.
 */
class RemoteLink private constructor(
    private val socket: Socket,
    // The handshake's own streams: anything read ahead past it stays in their buffers.
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val key: ByteArray,
    private val sendDirection: String,
    private val receiveDirection: String,
) : Closeable {
    private var sendSeq = 0L
    private var receiveSeq = 0L

    @Synchronized
    fun send(payload: String) {
        val seq = sendSeq++
        writeFrame(output, "$seq\n${mac(key, "$sendDirection|$seq|$payload")}\n$payload")
    }

    /** The next message; throws once the connection ends or a message fails its check. */
    fun receive(): String {
        val parts = readFrame(input).split('\n', limit = 3)
        if (parts.size != 3) throw RemoteAuthException("malformed message")
        val seq = parts[0].toLongOrNull()
        if (seq != receiveSeq) throw RemoteAuthException("message out of order")
        if (!same(parts[1], mac(key, "$receiveDirection|$seq|${parts[2]}"))) throw RemoteAuthException("message altered")
        receiveSeq++
        return parts[2]
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        // A handshake that stalls this long is dropped.
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val MAX_FRAME = 64 * 1024
        private const val GREETING = "etincelle-remote"
        private val random = SecureRandom()

        /** TV side: greets the phone on [socket], checks that it proves [accountKey] and proves it back. */
        fun accept(socket: Socket, accountKey: ByteArray): RemoteLink = handshake(socket) { input, output ->
            val tvNonce = nonce()
            writeFrame(output, "$GREETING\n${RemoteProtocol.VERSION}\n$tvNonce")
            val auth = readFrame(input).split('\n')
            if (auth.size != 3 || auth[0] != "auth") throw RemoteAuthException("unexpected answer")
            val phoneNonce = auth[1]
            if (!same(auth[2], mac(accountKey, "phone|$tvNonce|$phoneNonce"))) {
                writeFrame(output, "denied")
                throw RemoteAuthException("phone of another account")
            }
            writeFrame(output, "welcome\n${mac(accountKey, "tv|$tvNonce|$phoneNonce")}")
            RemoteLink(socket, input, output, sessionKey(accountKey, tvNonce, phoneNonce), "tv", "phone")
        }

        /** Phone side: answers the TV's greeting on [socket] and checks that the TV proves [accountKey]. */
        fun connect(socket: Socket, accountKey: ByteArray): RemoteLink = handshake(socket) { input, output ->
            val hello = readFrame(input).split('\n')
            if (hello.size != 3 || hello[0] != GREETING) throw RemoteAuthException("not an etincelle TV")
            if (hello[1] != RemoteProtocol.VERSION.toString()) throw RemoteAuthException("other protocol version")
            val tvNonce = hello[2]
            val phoneNonce = nonce()
            writeFrame(output, "auth\n$phoneNonce\n${mac(accountKey, "phone|$tvNonce|$phoneNonce")}")
            val answer = readFrame(input).split('\n')
            if (answer.size != 2 || answer[0] != "welcome") throw RemoteAuthException("TV of another account")
            if (!same(answer[1], mac(accountKey, "tv|$tvNonce|$phoneNonce"))) throw RemoteAuthException("TV of another account")
            RemoteLink(socket, input, output, sessionKey(accountKey, tvNonce, phoneNonce), "phone", "tv")
        }

        // Runs [block] on the socket's streams under a timeout; the socket is closed if it fails.
        private inline fun handshake(socket: Socket, block: (DataInputStream, DataOutputStream) -> RemoteLink): RemoteLink {
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val link = block(
                    DataInputStream(BufferedInputStream(socket.getInputStream())),
                    DataOutputStream(BufferedOutputStream(socket.getOutputStream())),
                )
                socket.soTimeout = 0
                return link
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
        }

        private fun sessionKey(accountKey: ByteArray, tvNonce: String, phoneNonce: String): ByteArray =
            hmac(accountKey, "session|$tvNonce|$phoneNonce")

        private fun nonce(): String = ByteArray(16).also { random.nextBytes(it) }.toHex()

        internal fun mac(key: ByteArray, text: String): String = hmac(key, text).toHex()

        private fun hmac(key: ByteArray, text: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray())

        // Compares in constant time, so a wrong mac reveals nothing about the right one.
        private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        internal fun writeFrame(output: DataOutputStream, text: String) {
            val bytes = text.toByteArray()
            if (bytes.size > MAX_FRAME) throw IOException("message too long")
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        internal fun readFrame(input: DataInputStream): String {
            val length = input.readInt()
            if (length !in 1..MAX_FRAME) throw RemoteAuthException("bad frame length")
            return ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8)
        }
    }
}
