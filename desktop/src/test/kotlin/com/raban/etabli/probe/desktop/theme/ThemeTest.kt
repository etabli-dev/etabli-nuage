// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class ThemeTest {
    private val design: String = Files.readString(Paths.get(System.getProperty("nuage.designFile")))

    private fun hex(block: String?, key: String): Color {
        val scope = if (block == null) design else Regex("\"$block\"\\s*:\\s*\\{([^}]*)\\}").find(design)!!.groupValues[1]
        val value = Regex("\"$key\"\\s*:\\s*(?:\\{\\s*\"value\"\\s*:\\s*)?\"#([0-9A-Fa-f]{6})\"").find(scope)!!.groupValues[1]
        return Color(0xFF000000 or value.toLong(16))
    }

    @Test fun generatedTokensMatchTheSuiteDesignFile() {
        assertEquals(hex(null, "accent"), DesignTokens.accent)
        assertEquals(hex(null, "accentDark"), DesignTokens.accentDark)
        assertEquals(hex(null, "accentLight"), DesignTokens.accentLight)
        with(DesignTokens.Light) {
            assertEquals(hex("light", "background"), background)
            assertEquals(hex("light", "surface"), surface)
            assertEquals(hex("light", "surfaceAlt"), surfaceAlt)
            assertEquals(hex("light", "textPrimary"), textPrimary)
            assertEquals(hex("light", "textSecondary"), textSecondary)
            assertEquals(hex("light", "border"), border)
            assertEquals(hex("light", "error"), error)
        }
        with(DesignTokens.Dark) {
            assertEquals(hex("dark", "background"), background)
            assertEquals(hex("dark", "surface"), surface)
            assertEquals(hex("dark", "surfaceAlt"), surfaceAlt)
            assertEquals(hex("dark", "textPrimary"), textPrimary)
            assertEquals(hex("dark", "textSecondary"), textSecondary)
            assertEquals(hex("dark", "border"), border)
            assertEquals(hex("dark", "error"), error)
        }
        assertEquals(16f, DesignTokens.Spacing.md.value)
        assertEquals(10f, DesignTokens.Radius.md.value)
        assertEquals(15f, DesignTokens.TypeScale.body.value)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun textColoursMeetWcagAaContrastInBothModes() {
        for ((mode, p) in listOf("light" to lightPalette(), "dark" to darkPalette())) {
            val selected = p.accentMuted.compositeOver(p.surface)
            for ((name, fg, bg) in listOf(
                Triple("ink on paper", p.ink, p.paper),
                Triple("ink on surface", p.ink, p.surface),
                Triple("ink on selected", p.ink, selected),
                Triple("faint on paper", p.faint, p.paper),
                Triple("faint on surface", p.faint, p.surface),
                Triple("accent text on paper", p.accentStrong, p.paper),
                Triple("accent text on surface", p.accentStrong, p.surface),
                Triple("button text", p.onAccentStrong, p.accentStrong),
                Triple("danger on surface", p.danger, p.surface),
            )) {
                val ratio = contrast(fg, bg)
                assertTrue("$mode $name: %.2f".format(ratio), ratio >= 4.5)
            }
            // Non-text indicators (progress, focus borders) need 3:1.
            assertTrue("$mode accent indicator", contrast(p.accent, p.paper) >= 3.0)
        }
    }
}
