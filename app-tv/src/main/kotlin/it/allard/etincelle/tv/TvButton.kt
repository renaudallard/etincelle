// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
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
import it.allard.etincelle.core.designsystem.RaisedButton
import it.allard.etincelle.core.designsystem.RaisedButtonDefaults

/**
 * A button for the remote, raised as every button of the app (see RaisedButton). Focus would not show
 * on it from the couch, so a focused TvButton gets a white ring and grows a little. Focus is read from
 * the focus state rather than from interaction events, since a screen that focuses its first button on
 * entry does so before an event collector would be listening. [highlighted] gives the brand yellow
 * face; false gives a neutral one, so a row of choices (season, grid density) shows which one is
 * current apart from which one has focus.
 *
 * A button that is not [enabled] looks it and takes no press, but still takes the focus: a disabled one
 * gives it up and nothing else gets it, so OK does nothing until an arrow is pressed, after a press
 * that put the page to work, or on a page that opens on such a button.
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
    val face = if (highlighted) MaterialTheme.colorScheme.primary else RaisedButtonDefaults.Neutral
    RaisedButton(
        onClick = { if (enabled) onClick() },
        modifier = modifier.onFocusChanged { focused = it.hasFocus }.scale(if (focused) 1.08f else 1f),
        color = if (enabled) face else RaisedButtonDefaults.DisabledFace,
        contentColor = if (enabled) RaisedButtonDefaults.contentFor(face) else RaisedButtonDefaults.DisabledContent,
        border = if (focused) BorderStroke(3.dp, Color.White) else null,
        content = content,
    )
}
