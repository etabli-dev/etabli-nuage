// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.raban.etabli.probe.desktop.AppSettings
import com.raban.etabli.probe.desktop.BuildInfo
import com.raban.etabli.probe.desktop.NuageState
import com.raban.etabli.probe.desktop.ThemePref
import com.raban.etabli.probe.desktop.theme.Coder
import com.raban.etabli.probe.desktop.theme.DesignTokens
import kotlin.math.roundToLong

@Composable
fun SettingsPane(state: NuageState, settingsLocation: String) {
    val t = Coder.tokens
    val s = state.settings
    ScrollPane(rememberScrollState()) {
        PromptHeader(listOf("nuage", "settings"))
        state.settingsNotice?.let { NoticeLabel(it) }

        Card("politeness") {
            var delay by remember(s.delayMs) { mutableFloatStateOf(s.delayMs.toFloat()) }
            var timeout by remember(s.timeoutMs) { mutableFloatStateOf(s.timeoutMs.toFloat()) }
            val sliderColors = SliderDefaults.colors(
                thumbColor = t.color.accentStrong, activeTrackColor = t.color.accent,
                inactiveTrackColor = t.color.surfaceAlt,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            )
            MonoLabel("delay between checks · ${delay.roundToLong()} ms")
            Slider(
                value = delay,
                onValueChange = { delay = it },
                onValueChangeFinished = { state.setDelayMs(delay.roundToLong()) },
                valueRange = AppSettings.DELAY_RANGE.first.toFloat()..AppSettings.DELAY_RANGE.last.toFloat(),
                steps = 98,
                enabled = !state.running,
                colors = sliderColors,
                modifier = Modifier.semantics { contentDescription = "Delay between checks in milliseconds" },
            )
            MonoLabel("timeout per URL, including redirects · ${timeout.roundToLong() / 1000} s")
            Slider(
                value = timeout,
                onValueChange = { timeout = it },
                onValueChangeFinished = { state.setTimeoutMs((timeout / 1000).roundToLong() * 1000) },
                valueRange = AppSettings.TIMEOUT_RANGE.first.toFloat()..AppSettings.TIMEOUT_RANGE.last.toFloat(),
                steps = 116,
                enabled = !state.running,
                colors = sliderColors,
                modifier = Modifier.semantics { contentDescription = "Timeout per URL in milliseconds" },
            )
            if (state.running) MonoLabel("Locked while a run is in progress.", color = t.color.faint)
        }

        Card("appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ThemePref.entries.forEach { pref ->
                    ChipButton(pref.name.lowercase(), s.theme == pref) { state.setTheme(pref) }
                }
            }
        }

        Card("Nextcloud (optional)") {
            var server by remember(s.serverUrl) { mutableStateOf(s.serverUrl.ifEmpty { "https://" }) }
            var user by remember(s.username) { mutableStateOf(s.username) }
            var dir by remember(s.uploadDir) { mutableStateOf(s.uploadDir) }
            BodyText(
                "Use an app password (Nextcloud → Personal settings → Security → Devices & sessions), " +
                    "not your login password. Only HTTPS servers with valid certificates are accepted. " +
                    "The app password is asked once per session, kept in memory only and never written " +
                    "to disk; server, username and upload folder are remembered. Not affiliated with Nextcloud GmbH.",
                color = t.color.faint,
            )
            LabeledField("Server URL", server, { server = it }, placeholder = "https://cloud.example.com")
            LabeledField("Username", user, { user = it })
            LabeledField("Upload folder for results", dir, { dir = it }, placeholder = "EtabliProbe")
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                val cleanServer = server.trim().takeUnless { it == "https://" }.orEmpty()
                PrimaryButton("Save", enabled = cleanServer.isNotEmpty() && user.isNotBlank()) {
                    state.saveAccount(cleanServer, user, dir)
                }
                SecondaryButton("Test connection", enabled = state.hasAccount && !state.davBusy) { state.testConnection() }
                SecondaryButton("Forget password", enabled = state.sessionPassword.isNotEmpty()) { state.forgetSessionPassword() }
                SecondaryButton("Forget account", enabled = state.hasAccount) { state.forgetAccount() }
            }
            MonoLabel(
                if (state.sessionPassword.isNotEmpty()) "app password: held for this session" else "app password: not entered this session",
                color = t.color.faint,
            )
            state.accountNotice?.let { NoticeLabel(it) }
        }

        Card("about") {
            MonoLabel("Établi Nuage desktop ${BuildInfo.VERSION} · development preview", color = t.color.faint)
            MonoLabel("install id · ${s.installId}", color = t.color.faint)
            MonoLabel("settings file · $settingsLocation", color = t.color.faint)
            MonoLabel("design tokens · Coder Design System ${DesignTokens.DESIGN_VERSION}", color = t.color.faint)
            MonoLabel("Apache-2.0 · © 2026 R. Heller", color = t.color.faint)
        }
    }
}
