// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/**
 * A short code generated for the TV "connect my TV" pairing flow: the TV displays [code] and the user
 * confirms it from a signed-in phone, while the TV polls until it expires after [ttlSeconds]. The code
 * is scoped to [deviceId] (a fresh id per attempt), which the poll must reuse.
 */
data class PairingCode(val code: String, val ttlSeconds: Long, val deviceId: String)
