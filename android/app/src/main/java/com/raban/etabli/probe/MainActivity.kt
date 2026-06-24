// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.raban.etabli.probe.prefs.ThemePref
import com.raban.etabli.probe.ui.RootScreen
import com.raban.etabli.probe.ui.theme.CoderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as EtabliProbeApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val pref by app.settings.theme.collectAsState(initial = ThemePref.System)
            CoderTheme(darkOverride = pref.isDark) {
                RootScreen(app = app, themePref = pref, onThemeChange = { p ->
                    app.applicationScope.launch { app.settings.setTheme(p) }
                })
            }
        }
    }
}
