// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.raban.etabli.probe.EtabliProbeApplication
import com.raban.etabli.probe.prefs.ThemePref
import com.raban.etabli.probe.ui.screens.ResultsScreen
import com.raban.etabli.probe.ui.screens.RunScreen
import com.raban.etabli.probe.ui.screens.SettingsScreen
import com.raban.etabli.probe.ui.screens.SourceScreen
import com.raban.etabli.probe.ui.theme.Coder

@Composable
fun RootScreen(
    app: EtabliProbeApplication,
    themePref: ThemePref,
    onThemeChange: (ThemePref) -> Unit,
) {
    val t = Coder.tokens
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = t.color.paper,
        bottomBar = {
            NavigationBar(containerColor = t.color.surface) {
                listOf(
                    Triple("Source",   Icons.Default.Upload,    0),
                    Triple("Run",      Icons.Default.PlayArrow, 1),
                    Triple("Results",  Icons.Default.TableChart,2),
                    Triple("Settings", Icons.Default.Settings,  3),
                ).forEach { (label, icon, idx) ->
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, style = t.font.mono) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = t.color.accent,
                            selectedTextColor = t.color.accent,
                            indicatorColor = t.color.accentMuted,
                            unselectedIconColor = t.color.faint,
                            unselectedTextColor = t.color.faint,
                        ),
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(t.color.paper)) {
            when (tab) {
                0 -> SourceScreen(app, onPrepared = { tab = 1 })
                1 -> RunScreen(app, onSeeResults = { tab = 2 })
                2 -> ResultsScreen(app)
                else -> SettingsScreen(app, themePref, onThemeChange)
            }
        }
    }
}
