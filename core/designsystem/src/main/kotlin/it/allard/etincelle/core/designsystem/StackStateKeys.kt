// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder

/**
 * A key for each page of a stack, given the [ids] of what its pages show, under which [holder] keeps
 * what a page holds (its scroll, a choice made on it) while another page or the player covers it. A
 * page's state is dropped once it has left the stack: the same programme opened anew starts afresh.
 */
@Composable
fun rememberStackStateKeys(holder: SaveableStateHolder, ids: List<String>): List<String> {
    val keys = ids.mapIndexed { depth, id -> "STACK#$depth#$id" }
    val kept = remember { mutableSetOf<String>() }
    LaunchedEffect(keys) {
        (kept - keys.toSet()).forEach(holder::removeState)
        kept.clear()
        kept += keys
    }
    return keys
}
