// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import java.io.ByteArrayOutputStream

/**
 * A Cast v2 message, the envelope of everything exchanged with a Chromecast: [payload] is a JSON text,
 * or [binary] data for device authentication. Messages travel as a 4-byte big-endian length followed by
 * this protobuf (`CastMessage` in Open Screen's cast_channel.proto).
 */
internal class CastMessage(
    val source: String,
    val destination: String,
    val namespace: String,
    val payload: String? = null,
    val binary: ByteArray? = null,
) {
    fun encode(): ByteArray = ProtoWriter().apply {
        varint(1, 0) // CASTV2_1_0
        string(2, source)
        string(3, destination)
        string(4, namespace)
        if (binary != null) {
            varint(5, 1)
            bytes(7, binary)
        } else {
            varint(5, 0)
            string(6, payload.orEmpty())
        }
    }.toByteArray()

    companion object {
        /** Decodes a message; null when it is malformed or lacks a required field. */
        fun decode(data: ByteArray): CastMessage? {
            var source: String? = null
            var destination: String? = null
            var namespace: String? = null
            var payloadType = -1
            var payload: String? = null
            var binary: ByteArray? = null
            val ok = ProtoReader(data).forEachField { number, value ->
                when (number) {
                    2 -> source = value.string()
                    3 -> destination = value.string()
                    4 -> namespace = value.string()
                    5 -> payloadType = value.int()
                    6 -> payload = value.string()
                    7 -> binary = value.bytes()
                }
            }
            if (!ok || source == null || destination == null || namespace == null) return null
            return when (payloadType) {
                0 -> CastMessage(source!!, destination!!, namespace!!, payload = payload.orEmpty())
                1 -> CastMessage(source!!, destination!!, namespace!!, binary = binary ?: ByteArray(0))
                else -> null
            }
        }
    }
}

/** A device's answer to an authentication challenge (`AuthResponse` in cast_channel.proto). */
internal class AuthResponse(
    val signature: ByteArray,
    val deviceCertificate: ByteArray,
    val intermediateCertificates: List<ByteArray>,
    val signatureAlgorithm: Int,
    val senderNonce: ByteArray,
    val hashAlgorithm: Int,
    /** The device's revocation list (`CrlBundle`), empty when it sent none. */
    val crl: ByteArray = ByteArray(0),
)

/** The device authentication messages (`DeviceAuthMessage` in cast_channel.proto). */
internal object DeviceAuthMessages {
    const val SIGNATURE_RSASSA_PKCS1V15 = 1
    const val HASH_SHA1 = 0
    const val HASH_SHA256 = 1

    /** A challenge carrying [nonce], asking for an RSA PKCS#1 v1.5 signature over SHA-256. */
    fun challenge(nonce: ByteArray): ByteArray {
        val challenge = ProtoWriter().apply {
            varint(1, SIGNATURE_RSASSA_PKCS1V15.toLong())
            bytes(2, nonce)
            varint(3, HASH_SHA256.toLong())
        }.toByteArray()
        return ProtoWriter().apply { bytes(1, challenge) }.toByteArray()
    }

    /** The response in a device's reply; null when the reply is an error or is malformed. */
    fun response(data: ByteArray): AuthResponse? {
        var response: ByteArray? = null
        if (!ProtoReader(data).forEachField { number, value -> if (number == 2) response = value.bytes() }) return null
        val body = response ?: return null
        var signature: ByteArray? = null
        var certificate: ByteArray? = null
        val intermediates = ArrayList<ByteArray>()
        // Defaults from cast_channel.proto.
        var signatureAlgorithm = SIGNATURE_RSASSA_PKCS1V15
        var nonce = ByteArray(0)
        var hashAlgorithm = HASH_SHA1
        var crl = ByteArray(0)
        val ok = ProtoReader(body).forEachField { number, value ->
            when (number) {
                1 -> signature = value.bytes()
                2 -> certificate = value.bytes()
                3 -> intermediates += value.bytes()
                4 -> signatureAlgorithm = value.int()
                5 -> nonce = value.bytes()
                6 -> hashAlgorithm = value.int()
                7 -> crl = value.bytes()
            }
        }
        if (!ok) return null
        return AuthResponse(
            signature ?: return null,
            certificate ?: return null,
            intermediates,
            signatureAlgorithm,
            nonce,
            hashAlgorithm,
            crl,
        )
    }
}

/** Writes the two protobuf wire types Cast messages use: varints and length-delimited fields. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) {
        rawVarint((field shl 3).toLong())
        rawVarint(value)
    }

    fun bytes(field: Int, value: ByteArray) {
        rawVarint(((field shl 3) or 2).toLong())
        rawVarint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/** A field value read by [ProtoReader]: a varint, or the bytes of a length-delimited field. */
internal class ProtoValue(private val number: Long, private val data: ByteArray?) {
    fun int(): Int = number.toInt()
    fun long(): Long = number
    fun bytes(): ByteArray = data ?: ByteArray(0)
    fun string(): String = String(bytes(), Charsets.UTF_8)
}

/**
 * Reads the fields of a protobuf message from untrusted input. Every length is checked against the
 * data, and anything unexpected (a 32 or 64-bit field, a group, an overlong varint) makes it stop.
 */
internal class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    /** Calls [onField] for each field; false when the message is malformed. */
    fun forEachField(onField: (number: Int, value: ProtoValue) -> Unit): Boolean {
        while (pos < data.size) {
            val key = varint() ?: return false
            val number = (key ushr 3).toInt()
            if (number <= 0) return false
            when ((key and 7).toInt()) {
                0 -> onField(number, ProtoValue(varint() ?: return false, null))
                2 -> {
                    val length = varint() ?: return false
                    if (length < 0 || length > data.size - pos) return false
                    onField(number, ProtoValue(0, data.copyOfRange(pos, pos + length.toInt())))
                    pos += length.toInt()
                }
                else -> return false
            }
        }
        return true
    }

    private fun varint(): Long? {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= data.size) return null
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
}
