// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

/**
 * A player that drives another device says here whether that device is in an ad break it offers to
 * skip, for the phone to offer it too: the device finds its breaks itself, the phone only asks.
 */
interface AdBreakOffer {
    val adBreakOffered: Boolean
}
