// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Checks that the other end of a TLS connection is a genuine Cast device, the way Open Screen's sender
 * does (cast/sender/channel/cast_auth_util.cc). A Chromecast's TLS certificate is self-signed, so TLS
 * alone proves nothing. Instead the device signs our nonce followed by that TLS certificate with its
 * device key, whose certificate chains to one of Google's Cast roots.
 */
internal object CastDeviceAuth {
    // A device's TLS certificate is short-lived and regenerated; a longer one is refused.
    private const val MAX_TLS_CERT_LIFETIME_MS = 4L * 24 * 60 * 60 * 1000
    private const val MAX_INTERMEDIATES = 4

    class Failure(message: String) : Exception(message)

    /**
     * Verifies [response] for the [nonce] we sent and the TLS certificate [peerCertificate] (DER) the
     * device presented. Throws [Failure] when anything does not hold.
     */
    fun verify(
        response: AuthResponse,
        peerCertificate: ByteArray,
        nonce: ByteArray,
        roots: List<X509Certificate> = CastRootCertificates.all,
        now: Date = Date(),
    ) {
        val factory = CertificateFactory.getInstance("X.509")
        fun parse(der: ByteArray) = runCatching {
            factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrElse { throw Failure("unreadable certificate") }

        // The TLS certificate is not checked as X.509, but its validity bounds this signature's life.
        val peer = parse(peerCertificate)
        if (now.before(peer.notBefore) || now.after(peer.notAfter)) throw Failure("TLS certificate out of date")
        if (peer.notAfter.time - now.time > MAX_TLS_CERT_LIFETIME_MS) throw Failure("TLS certificate lives too long")

        if (!MessageDigest.isEqual(response.senderNonce, nonce)) throw Failure("nonce mismatch")
        if (response.signatureAlgorithm != DeviceAuthMessages.SIGNATURE_RSASSA_PKCS1V15) {
            throw Failure("unsupported signature algorithm")
        }
        val algorithm = when (response.hashAlgorithm) {
            DeviceAuthMessages.HASH_SHA256 -> "SHA256withRSA"
            DeviceAuthMessages.HASH_SHA1 -> "SHA1withRSA"
            else -> throw Failure("unsupported hash algorithm")
        }

        if (response.intermediateCertificates.size > MAX_INTERMEDIATES) throw Failure("chain too long")
        val chain = (listOf(response.deviceCertificate) + response.intermediateCertificates).map(::parse)
        verifyChain(chain, roots, now)

        val device = chain.first()
        // keyUsage, when present, must allow signing.
        if (device.keyUsage?.getOrNull(0) == false) throw Failure("device key may not sign")
        val signed = runCatching {
            Signature.getInstance(algorithm).run {
                initVerify(device.publicKey)
                update(response.senderNonce)
                update(peerCertificate)
                verify(response.signature)
            }
        }.getOrDefault(false)
        if (!signed) throw Failure("bad signature")
    }

    // Each certificate must be in date, issued and signed by the next one; intermediates must be CAs;
    // the last one must be issued and signed by one of [roots].
    private fun verifyChain(chain: List<X509Certificate>, roots: List<X509Certificate>, now: Date) {
        for ((i, cert) in chain.withIndex()) {
            runCatching { cert.checkValidity(now) }.getOrElse { throw Failure("certificate out of date") }
            if (i > 0 && cert.basicConstraints < 0) throw Failure("intermediate is not a CA")
            val issuer = chain.getOrNull(i + 1)
                ?: roots.firstOrNull { it.subjectX500Principal == cert.issuerX500Principal }
                ?: throw Failure("not issued by a Cast root")
            if (cert.issuerX500Principal != issuer.subjectX500Principal) throw Failure("broken chain")
            runCatching { cert.verify(issuer.publicKey) }.getOrElse { throw Failure("bad certificate signature") }
            if (issuer in roots) {
                runCatching { issuer.checkValidity(now) }.getOrElse { throw Failure("root out of date") }
            }
        }
    }
}
