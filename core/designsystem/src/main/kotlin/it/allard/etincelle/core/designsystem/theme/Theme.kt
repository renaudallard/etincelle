// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The pages are pure black, which an OLED screen shows by turning its pixels off; what stands on them
// (cards, rows, menus) keeps the dark greys of the brand palette.
private val EtincelleDarkColors = darkColorScheme(
    primary = BrandYellow,
    onPrimary = BrandBlack,
    secondary = BrandYellow,
    background = BrandBlack,
    onBackground = BrandWhite,
    surface = BrandBlack,
    onSurface = BrandWhite,
    surfaceVariant = BackgroundLevel2,
    onSurfaceVariant = TextSecondary,
    outline = TextSecondary,
)

@Composable
fun EtincelleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EtincelleDarkColors,
        typography = EtincelleTypography,
        content = content,
    )
}
