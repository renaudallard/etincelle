// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.io.encoding.Base64

/**
 * Google's Cast root certificates, which every genuine Chromecast's device certificate chains to. They
 * are public; these copies come from Open Screen (chromium.googlesource.com/openscreen,
 * cast/common/certificate/cast_root_ca_cert_der-inc.h and eureka_root_ca_der-inc.h).
 *
 * - Cast Root CA, valid until 2034-03-28, SHA-256
 *   80:9A:F1:47:00:B3:FE:26:11:AD:59:7E:B1:58:4D:63:54:31:3B:64:CC:DB:33:90:F0:97:FA:3E:58:26:D6:CA
 * - Eureka Root CA, valid until 2032-12-12, SHA-256
 *   CA:F6:D1:E3:7B:53:22:03:E0:1A:76:BF:07:18:7B:B7:31:CC:D3:88:01:56:5A:B2:21:1A:2C:0A:B7:F3:BC:46
 *
 * And the root that signs the Cast revocation lists (cast_crl_root_ca_cert_der-inc.h):
 *
 * - Cast CRL Root CA, valid until 2036-07-27, SHA-256
 *   8B:77:C6:02:91:F9:D6:27:64:61:30:83:F4:97:50:B1:C5:CA:AC:2D:9E:FB:E5:A2:8E:DF:30:1F:9D:84:83:1D
 */
internal object CastRootCertificates {
    val all: List<X509Certificate> by lazy { listOf(CAST_ROOT_CA, EUREKA_ROOT_CA).map(::parse) }

    /** What a revocation list's signer must chain to. */
    val crl: List<X509Certificate> by lazy { listOf(parse(CAST_CRL_ROOT_CA)) }

    private fun parse(base64: String) =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.decode(base64))) as X509Certificate

    private const val CAST_ROOT_CA =
        "MIIDxTCCAq2gAwIBAgIBAjANBgkqhkiG9w0BAQUFADB1MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQG" +
        "A1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzENMAsGA1UECwwEQ2FzdDEVMBMGA1UEAwwMQ2FzdCBS" +
        "b290IENBMB4XDTE0MDQwMjE3MzQyNloXDTM0MDMyODE3MzQyNlowdTELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3Ju" +
        "aWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxEzARBgNVBAoMCkdvb2dsZSBJbmMxDTALBgNVBAsMBENhc3QxFTATBgNVBAMM" +
        "DENhc3QgUm9vdCBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALrZZZ3aOdPBd/bU0K6PWAhoOUqV7XDP/XkI" +
        "qarl6binLaBnR4qeyc9wswWHaRHscJiXw+bDw+u9xrA9/E/BXjif2s9zMAZbeTfBXoyHR5SaQZIq1pXEcVwnXQixgMaSvRvj" +
        "QZeh7HWfVZ4+n48cx2VkB9OzlqEEn5HE3gp7bNnIwHgxoBlCqeiD48788c7CLiRGlQkZysBGsuUButdP87/2aa2ZBPqgBzkO" +
        "5t9RRwfA5KlcS5TFL7OgMH/nlWuyrzIN8YzVbct7R6cIq8sno03PSlrxBdH4YsUQKnRpquZLlvub2GPkWGbTrYpu/3te+aVW" +
        "Hi2CMVvw4iTmQUofrhMCAwEAAaNgMF4wDwYDVR0TBAgwBgEB/wIBAjAdBgNVHQ4EFgQUfJoefd95VLzXzF7KmYZFeWV0KBkw" +
        "HwYDVR0jBBgwFoAUfJoefd95VLzXzF7KmYZFeWV0KBkwCwYDVR0PBAQDAgEGMA0GCSqGSIb3DQEBBQUAA4IBAQCA9Fr7PSgZ" +
        "USDX1PsSl0pl8lg1kncwavHXtlEaf5rNx3sDQq1VagCv8OEGwr1reHXb/kERU0o5u5o6xlk0Lywz47LWXH/deOtxWznag5DF" +
        "MeI/I+/a6ystd17ew0PSyWtZgsrV7fqhZFvL8Q0aYuGc6KcYcPBfF5b47Ybbrh3gzz5dLu4WbZUrPP2X8wVaJGhNObb45Fi6" +
        "9eAmeFHFW11OCeVsR4t6Wi6JU+bMNlsmPPhyQwKC0ivN8NOj7BM+UtWDPQfcHUNlejMCAaPOt9ZgUTsJwiOKMv6YGWBik4XN" +
        "NEbb1SMPedp3ACoCbYNYzgN3NeGjIJPCSqKkRhx1LB9N"

    private const val EUREKA_ROOT_CA =
        "MIIDwzCCAqugAwIBAgIBATANBgkqhkiG9w0BAQUFADB8MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQG" +
        "A1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzESMBAGA1UECwwJR29vZ2xlIFRWMRcwFQYDVQQDDA5F" +
        "dXJla2EgUm9vdCBDQTAeFw0xMjEyMTcyMjM5MzNaFw0zMjEyMTIyMjM5MzNaMHwxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApD" +
        "YWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRMwEQYDVQQKDApHb29nbGUgSW5jMRIwEAYDVQQLDAlHb29nbGUg" +
        "VFYxFzAVBgNVBAMMDkV1cmVrYSBSb290IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuRHQ6hLcMuHfXDNr" +
        "GXMdnZ7QOXa/pYQJpv1ubencjzZO6YgCvZ/06ET9TPWaAlZqRypjbFhFzHxmJNx5ecMqpLKLoPeitc0Gftu+7AyG8g0kYHSE" +
        "yikjhALYp+078ewmR1TjsS3mZA/2csXpmFIXwPzyLCDIQPhHyTKeO5exi/WYJHBjZhnBUugEBT1fjbzYS693mG8feNG2UCdN" +
        "5OwUaWcfWK+poBEmPJQyB3/X6Wkfrj9PY4qPidbyGXhcIY6xtlfYwOHufW7d8ToKavG6//mDL9y1pCAXYzbvyGIZzFbOsuox" +
        "iUt4WMG/AxOZ4BLyiKqblNrddnkXHjTRCsQHRQIDAQABo1AwTjAdBgNVHQ4EFgQURE4qR1jYuUiR9k/OdKkdMpqNjekwHwYD" +
        "VR0jBBgwFoAURE4qR1jYuUiR9k/OdKkdMpqNjekwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUFAAOCAQEAP8gmoG5cBUB5" +
        "oZipM95odIXurrccM1mwEd6f9E/T61EJfUd+blGF9FTNg5glsbqwV+yT2xLi7FFJepZzm8iWbYWM0+E8+jLiWAx3bYcMNAGq" +
        "MKl24MDn214b6RAwpOAJSSa5WM1aB+VQdd6aO/ZTfrFTXkUnTxfjCDOyUAq79PwllyneQXUw+nc4qmWKc0/qEXvrfBdgJw68" +
        "PnZS2IvtGvjrN7sR/a5wFwr+4K0Gsx9pinIEwsAzC9YvY0wzERS4YjaIxQNlARmj7wC7bw6S/zQcodYx0Fxen5l9x8q9fHIL" +
        "9Fylfm4EqNKZLFEBFP6iSPB+voQNtNPi8w593ov1Mw=="

    private const val CAST_CRL_ROOT_CA =
        "MIIDzjCCAragAwIBAgICAJkwDQYJKoZIhvcNAQELBQAweTELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAU" +
        "BgNVBAcMDU1vdW50YWluIFZpZXcxEzARBgNVBAoMCkdvb2dsZSBJbmMxDTALBgNVBAsMBENhc3QxGTAXBgNVBAMMEENhc3Qg" +
        "Q1JMIFJvb3QgQ0EwHhcNMTYwODAxMjE0NzQ3WhcNMzYwNzI3MjE0NzQ3WjB5MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2Fs" +
        "aWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzENMAsGA1UECwwEQ2FzdDEZMBcG" +
        "A1UEAwwQQ2FzdCBDUkwgUm9vdCBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMJ/wAkh02CJKLWWbv6mrf6u" +
        "4GY1vZlu6JOFKbreRF2oa/zmzDfdHQ/PHjoyLH/gG8m7TDSpHJe1+G1CnE0GaqAtlVU/eB1cq+k6pgg7Wq/0q1N3FJprsjcu" +
        "zW7qvCJdVlVz/b0DL1Ref4vBdDYaGB9k3r8IgEoSDElTuMc721/cWXcvuDoFiva3RyqbdGMIMRLme0TRwXzIh7hQY22f17o2" +
        "U3JHX9xDQ+vXLhHRinqkA/Jq04jmp7idgbKwiCTIofqwqtsIZD6LKgdcWoIFmcLVylJ1Iaf6xaHarPf+0MdEdprra9O99Hox" +
        "pq0vWsQxOm3x3XtEgTfPE4Vdlq57lisCAwEAAaNgMF4wDwYDVR0TBAgwBgEB/wIBATAdBgNVHQ4EFgQUGmUStKm5tPyRDJ5n" +
        "4FvZya1EHLkwHwYDVR0jBBgwFoAUGmUStKm5tPyRDJ5n4FvZya1EHLkwCwYDVR0PBAQDAgIEMA0GCSqGSIb3DQEBCwUAA4IB" +
        "AQCvX4vA98UmiLms9+xND3ar4nSaRDwz9nQ9BCpZdqIFJ8TjosjCr379vrnK6VuoKs2nHg438W+EXqpCH7rwRLrbh2Fokbsd" +
        "XDrwjgIgdqpHmcdzDZAySrnj/RGLXb0iTQV1F2Gipk+wPVKOqsm0jQVaHDbBe4f3+OSBNifsNa65zhVH4RDJFmk6Io5jGDHM" +
        "O1ZpxtQk3ZUlzzTmAK7hhx7uDBTcDYKBMR+PbdLA4XwS953KAuN2NkRTOodxfe0yTKSW5uUsxw23lsDzfeVYMvclJcATdtB2" +
        "bHOrPRXNxeiFFZoCUulhQeJmAcVx5dvApbNMHqyTijVMTdpXIiQdOva9"
}
