// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The colours a [RaisedButton] is given apart from the brand yellow. */
object RaisedButtonDefaults {
    /** The face of a button that is not the main action of its screen. */
    val Neutral = Color(0xFF3A3B41)

    /** The face and the label of a button that cannot be pressed. */
    val DisabledFace = Color(0xFF2A2B2F)
    val DisabledContent = Color(0x61FFFFFF)

    /** The ink that reads on [face]: dark on a light face, white on a dark one. */
    fun contentFor(face: Color): Color = if (face.luminance() > 0.5f) Color.Black else Color.White
}

// How far the face stands above its base, and sinks when pressed.
private val Depth = 3.dp
private val Lift = 4.dp

/**
 * A button drawn raised, as a key: its face is lit from above (lighter at the top, darker at the
 * bottom, a light rim along its upper edge) and stands on a darker base that shows below it, which a
 * shadow alone would not on a black page. Pressed, the face sinks onto the base. [color] is the face,
 * the brand yellow by default. Not [enabled], it is greyed and takes no press. [border] is drawn
 * around the face, as the focus ring of a TV button.
 */
@Composable
fun RaisedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = RaisedButtonDefaults.contentFor(color),
    shape: Shape = CircleShape,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val face = if (enabled) color else RaisedButtonDefaults.DisabledFace
    val ink = if (enabled) contentColor else RaisedButtonDefaults.DisabledContent
    val sunk = pressed && enabled
    // A width or size given to the button is the face's; the base takes the room below it, so pressing
    // moves nothing around the button.
    Box(Modifier.padding(bottom = Depth).then(modifier), propagateMinConstraints = true) {
        Box(Modifier.matchParentSize().offset(y = Depth).background(lerp(face, Color.Black, 0.45f), shape))
        Box(
            Modifier
                .offset(y = if (sunk) Depth else 0.dp)
                .shadow(if (sunk || !enabled) 0.dp else Lift, shape, clip = false)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(lerp(face, Color.White, 0.22f), face, lerp(face, Color.Black, 0.2f))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.4f), Color.Transparent)), shape)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .clickable(interactions, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
                .defaultMinSize(ButtonDefaults.MinWidth, ButtonDefaults.MinHeight)
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides ink) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
                }
            }
        }
    }
}
