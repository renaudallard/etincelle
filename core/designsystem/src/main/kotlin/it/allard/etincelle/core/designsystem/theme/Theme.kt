// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EtincelleDarkColors = darkColorScheme(
    primary = BrandYellow,
    onPrimary = BrandBlack,
    secondary = BrandYellow,
    background = BackgroundLevel0,
    onBackground = BrandWhite,
    surface = BackgroundLevel1,
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
