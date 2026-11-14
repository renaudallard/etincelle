// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel2

/**
 * A button for the remote. The stock Material button only shows focus as a 10% overlay, which cannot
 * be seen from the couch on a yellow button, so a focused TvButton gets a white ring and grows a
 * little. Focus is read from the focus state rather than from interaction events, since a screen
 * that focuses its first button on entry does so before an event collector would be listening.
 * [highlighted] gives the brand yellow fill; false gives a neutral one, so a row of choices
 * (season, grid density) shows which one is current apart from which one has focus.
 */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus }.scale(if (focused) 1.08f else 1f),
        enabled = enabled,
        colors = if (highlighted) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(containerColor = BackgroundLevel2, contentColor = Color.White)
        },
        border = if (focused) BorderStroke(3.dp, Color.White) else null,
        content = content,
    )
}
