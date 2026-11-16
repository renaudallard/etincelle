// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** Covers device authentication against the test PKI, and the Cast message encoding. */
class CastDeviceAuthTest {

    private val nonce = ByteArray(16) { it.toByte() }
    private val roots = listOf(cert(TestPki.ROOT))
    private val now = date("2026-01-02")

    private fun cert(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun date(day: String): Date = SimpleDateFormat("yyyy-MM-dd").apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(day)!!

    private fun sign(data: ByteArray, algorithm: String = "SHA256withRSA"): ByteArray {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(TestPki.DEVICE_KEY))
        return Signature.getInstance(algorithm).run {
            initSign(key)
            update(data)
            sign()
        }
    }

    private fun response(
        peer: ByteArray = TestPki.PEER,
        signedNonce: ByteArray = nonce,
        device: ByteArray = TestPki.DEVICE,
        intermediates: List<ByteArray> = listOf(TestPki.ICA),
        hash: Int = DeviceAuthMessages.HASH_SHA256,
        signature: ByteArray = sign(signedNonce + peer, if (hash == DeviceAuthMessages.HASH_SHA1) "SHA1withRSA" else "SHA256withRSA"),
        crl: ByteArray = crl(),
    ) = AuthResponse(signature, device, intermediates, DeviceAuthMessages.SIGNATURE_RSASSA_PKCS1V15, signedNonce, hash, crl)

    // The revocation lists here are signed with the test device key, whose certificate the test
    // intermediate issued: that intermediate stands for the Cast CRL root.
    private val crlRoots = listOf(cert(TestPki.ICA))

    private fun spkiHash(der: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert(der).publicKey.encoded)

    // A `CrlBundle` holding one list valid from [from] to [until], signed over [signed].
    private fun crl(
        from: String = "2026-01-01",
        until: String = "2026-01-08",
        revokedKeys: List<ByteArray> = emptyList(),
        // Issuer key hash, first and last serial number.
        revokedSerials: List<Triple<ByteArray, Long, Long>> = emptyList(),
        version: Long = 0,
        tamper: Boolean = false,
    ): ByteArray {
        val tbs = ProtoWriter().apply {
            if (version != 0L) varint(1, version)
            varint(2, date(from).time / 1000)
            varint(3, date(until).time / 1000)
            revokedKeys.forEach { bytes(4, it) }
            revokedSerials.forEach { (issuer, first, last) ->
                bytes(5, ProtoWriter().apply { bytes(1, issuer); varint(2, first); varint(3, last) }.toByteArray())
            }
        }.toByteArray()
        val signature = sign(tbs).also { if (tamper) it[0] = (it[0] + 1).toByte() }
        val list = ProtoWriter().apply { bytes(1, tbs); bytes(2, TestPki.DEVICE); bytes(3, signature) }.toByteArray()
        return ProtoWriter().apply { bytes(1, list) }.toByteArray()
    }

    private fun verify(response: AuthResponse, peer: ByteArray = TestPki.PEER, at: Date = now, trusted: List<X509Certificate> = roots, crlTrusted: List<X509Certificate> = crlRoots) =
        CastDeviceAuth.verify(response, peer, nonce, trusted, at, crlTrusted)

    private fun assertRefused(
        response: AuthResponse,
        peer: ByteArray = TestPki.PEER,
        at: Date = now,
        trusted: List<X509Certificate> = roots,
        crlTrusted: List<X509Certificate> = crlRoots,
    ) {
        try {
            verify(response, peer, at, trusted, crlTrusted)
            fail("accepted")
        } catch (e: CastDeviceAuth.Failure) {
            // Expected.
        }
    }

    @Test
    fun aGenuineDeviceIsAccepted() {
        verify(response())
    }

    @Test
    fun aSha1SignatureIsAccepted() {
        verify(response(hash = DeviceAuthMessages.HASH_SHA1))
    }

    @Test
    fun aDeviceWithoutARevocationListIsRefused() = assertRefused(response(crl = ByteArray(0)))

    @Test
    fun aRevocationListOutOfDateIsRefused() {
        assertRefused(response(crl = crl(until = "2026-01-01")))
        assertRefused(response(crl = crl(from = "2026-01-03")))
    }

    @Test
    fun aRevocationListNotSignedUnderTheCrlRootIsRefused() {
        assertRefused(response(), crlTrusted = listOf(cert(TestPki.FAKE_ROOT)))
        assertRefused(response(crl = crl(tamper = true)))
    }

    @Test
    fun aRevocationListOfAnUnknownVersionIsRefused() = assertRefused(response(crl = crl(version = 1)))

    @Test
    fun aRevokedKeyIsRefused() {
        assertRefused(response(crl = crl(revokedKeys = listOf(spkiHash(TestPki.DEVICE)))))
        assertRefused(response(crl = crl(revokedKeys = listOf(spkiHash(TestPki.ICA)))))
        // Another key revoked changes nothing.
        verify(response(crl = crl(revokedKeys = listOf(spkiHash(TestPki.PEER)))))
    }

    @Test
    fun aRevokedSerialNumberIsRefused() {
        val intermediateSerial = cert(TestPki.ICA).serialNumber.toLong()
        val rootKey = spkiHash(TestPki.ROOT)
        assertRefused(response(crl = crl(revokedSerials = listOf(Triple(rootKey, intermediateSerial, intermediateSerial)))))
        assertRefused(response(crl = crl(revokedSerials = listOf(Triple(rootKey, 0L, -1L)))))
        // The same number issued by another key, or a range beside it, revokes nothing.
        verify(response(crl = crl(revokedSerials = listOf(Triple(spkiHash(TestPki.PEER), 0L, -1L)))))
        verify(response(crl = crl(revokedSerials = listOf(Triple(rootKey, intermediateSerial + 1, intermediateSerial + 9)))))
        // A range running backwards voids the list.
        assertRefused(response(crl = crl(revokedSerials = listOf(Triple(rootKey, 9L, 3L)))))
    }

    @Test
    fun aChainToAnotherRootIsRefused() = assertRefused(response(), trusted = listOf(cert(TestPki.FAKE_ROOT)))

    @Test
    fun anImpostorRootInTheChainIsRefused() =
        assertRefused(response(intermediates = listOf(TestPki.ICA, TestPki.FAKE_ROOT)))

    @Test
    fun aMissingIntermediateIsRefused() = assertRefused(response(intermediates = emptyList()))

    @Test
    fun aTamperedSignatureIsRefused() {
        val good = response()
        assertRefused(response(signature = good.signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }))
    }

    @Test
    fun aSignatureForAnotherTlsCertificateIsRefused() = assertRefused(response(peer = TestPki.PEER_LONG))

    @Test
    fun anotherNonceIsRefused() = assertRefused(response(signedNonce = ByteArray(16) { 7 }))

    @Test
    fun anExpiredTlsCertificateIsRefused() = assertRefused(response(), at = date("2026-01-05"))

    @Test
    fun aLongLivedTlsCertificateIsRefused() = assertRefused(response(peer = TestPki.PEER_LONG), peer = TestPki.PEER_LONG)

    @Test
    fun aDeviceKeyThatMayNotSignIsRefused() = assertRefused(response(device = TestPki.DEVICE_NO_SIGN))

    @Test
    fun theCastRootsLoad() {
        assertEquals(
            listOf("CN=Cast Root CA", "CN=Eureka Root CA"),
            CastRootCertificates.all.map { it.subjectX500Principal.name.substringBefore(',') },
        )
        assertEquals(listOf("CN=Cast CRL Root CA"), CastRootCertificates.crl.map { it.subjectX500Principal.name.substringBefore(',') })
    }

    @Test
    fun aMessageSurvivesARoundTrip() {
        val text = CastMessage.decode(CastMessage("sender-0", "receiver-0", CastChannel.NS_RECEIVER, payload = """{"type":"GET_STATUS"}""").encode())
        assertNotNull(text)
        assertEquals("sender-0", text!!.source)
        assertEquals("receiver-0", text.destination)
        assertEquals(CastChannel.NS_RECEIVER, text.namespace)
        assertEquals("""{"type":"GET_STATUS"}""", text.payload)
        val binary = CastMessage.decode(CastMessage("a", "b", CastChannel.NS_DEVICE_AUTH, binary = byteArrayOf(1, 2, 3)).encode())
        assertArrayEquals(byteArrayOf(1, 2, 3), binary!!.binary)
    }

    @Test
    fun aMalformedMessageIsRejected() {
        val good = CastMessage("sender-0", "receiver-0", CastChannel.NS_RECEIVER, payload = "{}").encode()
        assertNull(CastMessage.decode(good.copyOf(good.size - 1)))
        // A length-delimited field claiming more bytes than there are.
        assertNull(CastMessage.decode(byteArrayOf(0x12, 0x7F, 0x41)))
        // A 64-bit field, which Cast messages never use.
        assertNull(CastMessage.decode(byteArrayOf(0x09, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun theChallengeAndTheResponseUseTheProtoLayout() {
        // DeviceAuthMessage { challenge = 1 { signature_algorithm = 1: 1, sender_nonce = 2, hash_algorithm = 3: 1 } }
        val expected = byteArrayOf(0x0A, 22, 0x08, 1, 0x12, 16) + nonce + byteArrayOf(0x18, 1)
        assertArrayEquals(expected, DeviceAuthMessages.challenge(nonce))
        val auth = ProtoWriter().apply {
            bytes(1, byteArrayOf(9))
            bytes(2, byteArrayOf(8))
            bytes(3, byteArrayOf(7))
            bytes(3, byteArrayOf(6))
            bytes(5, nonce)
            varint(6, 1)
            bytes(7, byteArrayOf(5))
        }.toByteArray()
        val parsed = DeviceAuthMessages.response(ProtoWriter().apply { bytes(2, auth) }.toByteArray())!!
        assertArrayEquals(byteArrayOf(9), parsed.signature)
        assertArrayEquals(byteArrayOf(8), parsed.deviceCertificate)
        assertEquals(2, parsed.intermediateCertificates.size)
        assertArrayEquals(nonce, parsed.senderNonce)
        assertEquals(DeviceAuthMessages.HASH_SHA256, parsed.hashAlgorithm)
        assertArrayEquals(byteArrayOf(5), parsed.crl)
        assertEquals(DeviceAuthMessages.SIGNATURE_RSASSA_PKCS1V15, parsed.signatureAlgorithm)
    }
}
