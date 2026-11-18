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
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** etincelle's own remote control, between the phone and the TV app (see docs/tv-remote-design.md). */
object RemoteProtocol {
    const val VERSION = 2

    /**
     * The key both apps derive from the account they are signed in to: its user id and profile ids. The
     * profile ids are random UUIDs only a device signed in to the account can read, and the remote control
     * never sends them, so a device of another account (or none) cannot compute this key.
     */
    fun accountKey(userId: String, profileIds: Collection<String>): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("etincelle-remote-v1\n$userId\n${profileIds.sorted().joinToString(",")}".toByteArray())
}

/** The other side is not signed in to the same account, or broke the protocol. */
class RemoteAuthException(message: String) : IOException(message)

/**
 * One authenticated connection between a phone and a TV app. A frame is a 4-byte big-endian length and
 * its bytes. The handshake, in UTF-8 text, proves to each side that the other holds the account key (see
 * [RemoteProtocol.accountKey]) without sending it: each proves an HMAC over both sides' random nonces.
 * After it every message travels encrypted with AES-256-GCM under a key of this connection, its nonce
 * made of the direction and the message's sequence number, which is not sent: a message can be neither
 * read by others on the network, nor forged, altered, replayed or reordered. [send] may be called from
 * any thread, [receive] from one.
 */
class RemoteLink private constructor(
    private val socket: Socket,
    // The handshake's own streams: anything read ahead past it stays in their buffers.
    private val input: DataInputStream,
    private val output: DataOutputStream,
    key: ByteArray,
    private val sendDirection: Int,
    private val receiveDirection: Int,
) : Closeable {
    private val key = SecretKeySpec(key, "AES")
    private var sendSeq = 0L
    private var receiveSeq = 0L

    @Synchronized
    fun send(payload: String) {
        val bytes = payload.toByteArray()
        // Refused before it takes its place in the sequence: a number used up for a message that never
        // left would put every later one out of step for the other end.
        if (bytes.size + TAG_BITS / 8 > MAX_FRAME) throw IOException("message too long")
        writeFrame(output, cipher(Cipher.ENCRYPT_MODE, sendDirection, sendSeq++).doFinal(bytes))
    }

    /** The next message; throws once the connection ends or a message fails its check. */
    fun receive(): String {
        val frame = readFrame(input)
        // Only the message of this direction and this place in the sequence decrypts.
        val payload = try {
            cipher(Cipher.DECRYPT_MODE, receiveDirection, receiveSeq).doFinal(frame)
        } catch (e: GeneralSecurityException) {
            throw RemoteAuthException("message altered or out of order")
        }
        receiveSeq++
        return payload.toString(Charsets.UTF_8)
    }

    // A nonce never serves twice under the key: the direction, then the sequence number.
    private fun cipher(mode: Int, direction: Int, seq: Long): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_BYTES).putInt(direction).putLong(seq).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(TAG_BITS, nonce)) }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        // A handshake that stalls this long is dropped.
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val MAX_FRAME = 64 * 1024
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TV = 1
        private const val PHONE = 2
        private const val GREETING = "etincelle-remote"
        private val random = SecureRandom()

        /** TV side: greets the phone on [socket], checks that it proves [accountKey] and proves it back. */
        fun accept(socket: Socket, accountKey: ByteArray): RemoteLink = handshake(socket) { input, output ->
            val tvNonce = nonce()
            writeText(output, "$GREETING\n${RemoteProtocol.VERSION}\n$tvNonce")
            val auth = readText(input).split('\n')
            if (auth.size != 3 || auth[0] != "auth") throw RemoteAuthException("unexpected answer")
            val phoneNonce = auth[1]
            if (!same(auth[2], mac(accountKey, "phone|$tvNonce|$phoneNonce"))) {
                writeText(output, "denied")
                throw RemoteAuthException("phone of another account")
            }
            writeText(output, "welcome\n${mac(accountKey, "tv|$tvNonce|$phoneNonce")}")
            RemoteLink(socket, input, output, sessionKey(accountKey, tvNonce, phoneNonce), TV, PHONE)
        }

        /** Phone side: answers the TV's greeting on [socket] and checks that the TV proves [accountKey]. */
        fun connect(socket: Socket, accountKey: ByteArray): RemoteLink = handshake(socket) { input, output ->
            val hello = readText(input).split('\n')
            if (hello.size != 3 || hello[0] != GREETING) throw RemoteAuthException("not an etincelle TV")
            if (hello[1] != RemoteProtocol.VERSION.toString()) throw RemoteAuthException("other protocol version")
            val tvNonce = hello[2]
            val phoneNonce = nonce()
            writeText(output, "auth\n$phoneNonce\n${mac(accountKey, "phone|$tvNonce|$phoneNonce")}")
            val answer = readText(input).split('\n')
            if (answer.size != 2 || answer[0] != "welcome") throw RemoteAuthException("TV of another account")
            if (!same(answer[1], mac(accountKey, "tv|$tvNonce|$phoneNonce"))) throw RemoteAuthException("TV of another account")
            RemoteLink(socket, input, output, sessionKey(accountKey, tvNonce, phoneNonce), PHONE, TV)
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

        internal fun writeText(output: DataOutputStream, text: String) = writeFrame(output, text.toByteArray())

        internal fun readText(input: DataInputStream): String = readFrame(input).toString(Charsets.UTF_8)

        internal fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
            if (bytes.size > MAX_FRAME) throw IOException("message too long")
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        internal fun readFrame(input: DataInputStream): ByteArray {
            val length = input.readInt()
            if (length !in 1..MAX_FRAME) throw RemoteAuthException("bad frame length")
            return ByteArray(length).also { input.readFully(it) }
        }
    }
}
