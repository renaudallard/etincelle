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
 * A Cast revocation list: the device keys and certificates Google no longer vouches for, such as keys
 * extracted from a device. A device hands the current list, signed under the Cast CRL root, with its
 * authentication answer (`CrlBundle` in Open Screen's revocation.proto). Read and checked the way Open
 * Screen does (cast/common/certificate/cast_crl.cc).
 */
internal class CastCrl private constructor(
    // SHA-256 of the SubjectPublicKeyInfo of each revoked key.
    private val revokedKeys: Set<Hash>,
    // Per issuer key (same hash), the revoked ranges of the serial numbers it issued (unsigned, inclusive).
    private val revokedSerials: Map<Hash, List<Pair<Long, Long>>>,
) {
    /** A hash, compared by content. */
    private class Hash(private val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Hash && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    /** Whether any certificate of [chain] (the device's first, the root last) is revoked. */
    fun revokes(chain: List<X509Certificate>): Boolean {
        for ((i, cert) in chain.withIndex()) {
            val key = Hash(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))
            if (key in revokedKeys) return true
            if (i == 0) continue
            // The serial number of the certificate this one issued; only positive ones within 64 bits
            // can be revoked this way.
            val serial = chain[i - 1].serialNumber
            if (serial.signum() <= 0 || serial.bitLength() > 64) continue
            val issued = serial.toLong()
            val ranges = revokedSerials[key] ?: continue
            if (ranges.any { (first, last) -> unsignedLessOrEqual(first, issued) && unsignedLessOrEqual(issued, last) }) return true
        }
        return false
    }

    companion object {
        private const val VERSION_0 = 0L

        /**
         * The list in [bundle] when it is valid at [now]: signed by a certificate that one of [roots]
         * issued, and in date. Null otherwise.
         */
        fun parse(bundle: ByteArray, roots: List<X509Certificate>, now: Date): CastCrl? {
            val crls = ArrayList<ByteArray>()
            if (!ProtoReader(bundle).forEachField { number, value -> if (number == 1) crls += value.bytes() }) return null
            for (crl in crls) {
                var tbs: ByteArray? = null
                var signerCert: ByteArray? = null
                var signature: ByteArray? = null
                val read = ProtoReader(crl).forEachField { number, value ->
                    when (number) {
                        1 -> tbs = value.bytes()
                        2 -> signerCert = value.bytes()
                        3 -> signature = value.bytes()
                    }
                }
                if (!read) continue
                val body = tbs ?: continue
                val list = Tbs.parse(body) ?: continue
                // Another version signs and hashes another way: look for one this code knows.
                if (list.version != VERSION_0) continue
                if (!signed(body, signerCert ?: return null, signature ?: return null, roots, now)) return null
                if (!list.valid(now)) return null
                return CastCrl(list.keys.mapTo(HashSet(), ::Hash), list.serials.groupBy({ Hash(it.first) }, { it.second to it.third }))
            }
            return null
        }

        // The signer's certificate is in date, issued and signed by one of [roots], and [signature] is its
        // RSA PKCS#1 v1.5 SHA-256 signature of [tbs].
        private fun signed(tbs: ByteArray, signerCert: ByteArray, signature: ByteArray, roots: List<X509Certificate>, now: Date): Boolean = runCatching {
            val signer = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(signerCert)) as X509Certificate
            signer.checkValidity(now)
            val root = roots.first { it.subjectX500Principal == signer.issuerX500Principal }
            root.checkValidity(now)
            signer.verify(root.publicKey)
            Signature.getInstance("SHA256withRSA").run {
                initVerify(signer.publicKey)
                update(tbs)
                verify(signature)
            }
        }.getOrDefault(false)

        private fun unsignedLessOrEqual(a: Long, b: Long) = java.lang.Long.compareUnsigned(a, b) <= 0
    }

    /** `TbsCrl` in revocation.proto; its numbers are unsigned 64-bit. */
    private class Tbs(
        val version: Long,
        val notBeforeSeconds: Long,
        val notAfterSeconds: Long,
        val keys: List<ByteArray>,
        // Issuer key hash, first and last serial number.
        val serials: List<Triple<ByteArray, Long, Long>>,
    ) {
        /** In date at [now], with no range running backwards. */
        fun valid(now: Date): Boolean {
            val seconds = now.time / 1000
            // A date past the signed range is none this code can hold.
            if (notBeforeSeconds < 0 || notAfterSeconds < 0 || seconds !in notBeforeSeconds..notAfterSeconds) return false
            return serials.all { (_, first, last) -> unsignedLessOrEqual(first, last) }
        }

        companion object {
            fun parse(data: ByteArray): Tbs? {
                var version = 0L
                var notBefore = -1L
                var notAfter = -1L
                val keys = ArrayList<ByteArray>()
                val serials = ArrayList<Triple<ByteArray, Long, Long>>()
                var rangesRead = true
                val read = ProtoReader(data).forEachField { number, value ->
                    when (number) {
                        1 -> version = value.long()
                        2 -> notBefore = value.long()
                        3 -> notAfter = value.long()
                        4 -> keys += value.bytes()
                        5 -> {
                            var issuer = ByteArray(0)
                            var first = 0L
                            var last = 0L
                            rangesRead = rangesRead && ProtoReader(value.bytes()).forEachField { n, v ->
                                when (n) {
                                    1 -> issuer = v.bytes()
                                    2 -> first = v.long()
                                    3 -> last = v.long()
                                }
                            }
                            serials += Triple(issuer, first, last)
                        }
                    }
                }
                return if (read && rangesRead) Tbs(version, notBefore, notAfter, keys, serials) else null
            }
        }
    }
}
