// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import org.junit.Assert.assertFalse
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
}
