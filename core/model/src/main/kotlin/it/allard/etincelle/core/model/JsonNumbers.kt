// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/**
 * Converts whole-number Doubles back to Long, recursively through maps and lists. Moshi decodes every
 * JSON number into a Double, so a server-supplied template echoed back (e.g. the playhead payload)
 * would otherwise re-serialize integers as "6054.0"; this restores them. Genuine fractional values are
 * left untouched.
 */
fun coerceWholeNumbers(value: Any?): Any? = when (value) {
    is Double -> if (value.isFinite() && value == kotlin.math.floor(value)) value.toLong() else value
    is Map<*, *> -> value.mapValues { coerceWholeNumbers(it.value) }
    is List<*> -> value.map { coerceWholeNumbers(it) }
    else -> value
}
