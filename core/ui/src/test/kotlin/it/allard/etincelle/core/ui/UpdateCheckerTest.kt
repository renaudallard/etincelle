// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun higherPatchIsNewer() {
        assertTrue(isNewer("0.0.8", "0.0.7"))
    }

    @Test
    fun doubleDigitPatchBeatsSingleDigit() {
        // String compare would rank "0.0.10" below "0.0.7"; the numeric parse must not.
        assertTrue(isNewer("0.0.10", "0.0.7"))
    }

    @Test
    fun leadingVIsIgnored() {
        assertTrue(isNewer("v0.1.0", "0.0.9"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(isNewer("0.0.7", "0.0.7"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(isNewer("0.0.6", "0.0.7"))
    }

    @Test
    fun blankCurrentNeverOffersUpdate() {
        // An unknown running version must not make every release look newer.
        assertFalse(isNewer("9.9.9", ""))
    }

    private val sha = "9de6b5828204cd2b318579c2c8acb7ceb8bcac35c3468024ba537ee70a31bebf"
    private val release = listOf(
        ReleaseAsset("etincelle-0.1.37-mobile.apk", "https://github.com/o/r/releases/download/v0.1.37/etincelle-0.1.37-mobile.apk", 10, sha),
        ReleaseAsset("etincelle-0.1.37-tv.apk", "https://github.com/o/r/releases/download/v0.1.37/etincelle-0.1.37-tv.apk", 10, sha),
    )

    @Test
    fun eachAppGetsItsOwnApk() {
        assertEquals(release[0], apkFor(release, "mobile"))
        assertEquals(release[1], apkFor(release, "tv"))
    }

    @Test
    fun releaseWithoutTheAppsApkOffersNothing() {
        assertNull(apkFor(release.take(1), "tv"))
    }

    @Test
    fun apkServedOutsideGithubIsIgnored() {
        assertNull(apkFor(listOf(release[1].copy(url = "https://example.org/etincelle-0.1.37-tv.apk")), "tv"))
    }

    @Test
    fun apkWithoutDigestIsIgnored() {
        assertNull(apkFor(listOf(release[1].copy(sha256 = null)), "tv"))
    }

    @Test
    fun digestMustBeSha256Hex() {
        assertEquals(sha, sha256Of("sha256:$sha"))
        assertEquals(sha, sha256Of("sha256:" + sha.uppercase()))
        assertNull(sha256Of(sha))
        assertNull(sha256Of("sha512:$sha"))
        assertNull(sha256Of("sha256:" + sha.dropLast(1)))
        assertNull(sha256Of("sha256:" + sha.dropLast(1) + "g"))
        assertNull(sha256Of(""))
    }
}
