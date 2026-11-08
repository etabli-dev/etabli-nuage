// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.raban.etabli.probe.desktop.NuageState
import com.raban.etabli.probe.desktop.Screen
import com.raban.etabli.probe.desktop.theme.Coder

@Composable
fun RunPane(state: NuageState) {
    val t = Coder.tokens
    ScrollPane(rememberScrollState()) {
        PromptHeader(listOf("nuage", "run", "http-status"))
        val entries = state.prepared
        if (entries.isEmpty()) {
            Card("no list prepared") {
                BodyText("Open a CSV in Source and choose the URL column first.", color = t.color.faint)
                SecondaryButton("Go to Source") { state.screen = Screen.Source }
            }
        } else {
            val valid = remember(entries) { entries.count { it.valid } }
            Card("ready", icon = Icons.Default.PlayArrow) {
                state.preparedLabel?.let { MonoLabel(it) }
                MonoLabel(
                    "${entries.size} rows · $valid valid" +
                        if (valid < entries.size) " · ${entries.size - valid} will be recorded as skipped" else "",
                    color = t.color.faint,
                )
                MonoLabel(
                    "delay ${state.settings.delayMs} ms between checks · timeout ${state.settings.timeoutMs / 1000} s " +
                        "per URL including redirects · change both in Settings",
                    color = t.color.faint,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                    if (!state.running) {
                        PrimaryButton("Start", icon = Icons.Default.PlayArrow, enabled = valid > 0) { state.requestStart() }
                    } else {
                        PrimaryButton("Cancel", icon = Icons.Default.Close) { state.cancelRun() }
                    }
                    SecondaryButton("See results", icon = Icons.AutoMirrored.Filled.List) { state.screen = Screen.Results }
                }
            }
        }

        if (state.running || state.done > 0) {
            Card("progress") {
                val fraction = if (state.total == 0) 0f else state.done.toFloat() / state.total
                LinearProgressIndicator(
                    progress = { fraction },
                    color = t.color.accent,
                    trackColor = t.color.surfaceAlt,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "${state.done} of ${state.total} checked"
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("${state.done} of ${state.total}")
                    Spacer(Modifier.weight(1f))
                    MonoLabel("pass ${state.passCount}", color = t.color.accentStrong)
                    Spacer(Modifier.weight(0.05f))
                    MonoLabel("fail ${state.failCount}", color = if (state.failCount > 0) t.color.danger else t.color.faint)
                }
                state.current?.let { MonoLabel("checking $it", color = t.color.faint, maxLines = 1) }
            }
        }

        state.runNotice?.let { NoticeLabel(it) }

        Card("how the check works", icon = Icons.Default.Info) {
            BodyText(
                "One HTTP GET per URL, strictly in file order, with the delay above between checks. " +
                    "Redirects (301, 302, 303, 307, 308) are followed for up to ten attempts within the " +
                    "timeout; response bodies are not downloaded. Status 200–399 passes; other statuses, " +
                    "network failures, timeouts and invalid URLs fail. This is the mobile background " +
                    "(HTTP status) mode; rendering pages in a web view and scheduled runs are mobile-only.",
                color = t.color.faint,
            )
            BodyText(
                "The run stops when you cancel or close the window. Completed rows stay in Results until " +
                    "the next run starts; export them to keep them.",
                color = t.color.faint,
            )
        }
    }
}
