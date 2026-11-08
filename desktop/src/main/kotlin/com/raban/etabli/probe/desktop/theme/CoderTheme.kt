// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

// Coder look for the desktop app. Every value comes from DesignTokens, which the build
// generates from design/coder-design-system.json. Monospace for headings, labels and
// numbers, the system UI font for prose, one restrained green accent.

data class CoderPalette(
    val paper: Color,          // window background
    val surface: Color,        // cards
    val surfaceAlt: Color,     // table stripes, tracks
    val ink: Color,            // primary text
    val faint: Color,          // secondary text
    val hairline: Color,       // borders
    val accent: Color,         // brand fill: progress, selection, focus
    val accentStrong: Color,   // accent for text and filled buttons (keeps 4.5:1 contrast)
    val onAccentStrong: Color, // text on accentStrong
    val accentMuted: Color,    // selected backgrounds
    val danger: Color,
    val onDanger: Color,
)

data class CoderSpace(val xs: Dp, val sm: Dp, val md: Dp, val lg: Dp, val xl: Dp, val xxl: Dp)
data class CoderRadius(val sm: Dp, val md: Dp, val lg: Dp)

data class CoderFonts(
    val stat: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val prompt: TextStyle,
    val body: TextStyle,
    val mono: TextStyle,
    val caption: TextStyle,
)

data class CoderTokens(
    val color: CoderPalette,
    val font: CoderFonts,
    val space: CoderSpace,
    val radius: CoderRadius,
    val dark: Boolean,
)

val LocalCoder = staticCompositionLocalOf<CoderTokens> { error("CoderTokens not provided") }

object Coder {
    val tokens: CoderTokens
        @Composable get() = LocalCoder.current
}

internal fun lightPalette() = with(DesignTokens.Light) {
    CoderPalette(
        paper = background, surface = surface, surfaceAlt = surfaceAlt,
        ink = textPrimary, faint = textSecondary, hairline = border,
        accent = DesignTokens.accent,
        accentStrong = DesignTokens.accentDark,
        onAccentStrong = background,
        accentMuted = DesignTokens.accent.copy(alpha = 0.14f),
        danger = error, onDanger = background,
    )
}

internal fun darkPalette() = with(DesignTokens.Dark) {
    CoderPalette(
        paper = background, surface = surface, surfaceAlt = surfaceAlt,
        ink = textPrimary, faint = textSecondary, hairline = border,
        accent = DesignTokens.accent,
        accentStrong = DesignTokens.accentLight,
        onAccentStrong = background,
        accentMuted = DesignTokens.accent.copy(alpha = 0.22f),
        danger = error, onDanger = background,
    )
}

// JetBrains Mono is the suite's preferred monospace face; it is not bundled yet, so the
// platform monospace font stands in.
private val Mono = FontFamily.Monospace

private fun fonts(p: CoderPalette) = with(DesignTokens.TypeScale) {
    CoderFonts(
        stat = TextStyle(fontFamily = Mono, fontSize = title1, fontWeight = FontWeight.SemiBold, color = p.ink),
        title = TextStyle(fontFamily = Mono, fontSize = title2, fontWeight = FontWeight.SemiBold, color = p.ink),
        headline = TextStyle(fontFamily = Mono, fontSize = callout, fontWeight = FontWeight.SemiBold, color = p.ink),
        prompt = TextStyle(fontFamily = Mono, fontSize = title3, color = p.ink),
        body = TextStyle(fontFamily = FontFamily.Default, fontSize = body, color = p.ink),
        mono = TextStyle(fontFamily = Mono, fontSize = caption, color = p.ink),
        caption = TextStyle(fontFamily = Mono, fontSize = caption, color = p.faint),
    )
}

@Composable
fun CoderTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val palette = if (dark) darkPalette() else lightPalette()
    val fonts = fonts(palette)
    val tokens = CoderTokens(
        color = palette,
        font = fonts,
        space = with(DesignTokens.Spacing) { CoderSpace(xs, sm, md, lg, xl, xxl) },
        radius = with(DesignTokens.Radius) { CoderRadius(sm, md, lg) },
        dark = dark,
    )
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accentStrong, onPrimary = palette.onAccentStrong,
            secondaryContainer = palette.accentMuted, onSecondaryContainer = palette.ink,
            background = palette.paper, onBackground = palette.ink,
            surface = palette.surface, onSurface = palette.ink,
            surfaceVariant = palette.surfaceAlt, onSurfaceVariant = palette.faint,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceAlt,
            outline = palette.hairline, outlineVariant = palette.hairline,
            error = palette.danger, onError = palette.onDanger,
        )
    } else {
        lightColorScheme(
            primary = palette.accentStrong, onPrimary = palette.onAccentStrong,
            secondaryContainer = palette.accentMuted, onSecondaryContainer = palette.ink,
            background = palette.paper, onBackground = palette.ink,
            surface = palette.surface, onSurface = palette.ink,
            surfaceVariant = palette.surfaceAlt, onSurfaceVariant = palette.faint,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceAlt,
            outline = palette.hairline, outlineVariant = palette.hairline,
            error = palette.danger, onError = palette.onDanger,
        )
    }
    // Material widgets pick their own content colours, so their text styles carry none.
    fun TextStyle.uncoloured() = copy(color = Color.Unspecified)
    val typography = Typography(
        titleLarge = fonts.title.uncoloured(),
        titleMedium = fonts.headline.uncoloured(),
        bodyLarge = fonts.body.uncoloured(),
        bodyMedium = fonts.body.uncoloured(),
        bodySmall = fonts.mono.uncoloured(),
        labelLarge = fonts.body.uncoloured().copy(fontWeight = FontWeight.Medium),
        labelMedium = fonts.caption.uncoloured(),
        labelSmall = fonts.caption.uncoloured(),
    )
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalCoder provides tokens, content = content)
    }
}
