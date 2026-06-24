// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.raban.etabli.probe.EtabliProbeApplication
import com.raban.etabli.probe.engine.CheckResult
import com.raban.etabli.probe.net.WebDavClient
import com.raban.etabli.probe.ui.RunState
import com.raban.etabli.probe.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ResultsScreen(app: EtabliProbeApplication) {
    val t = Coder.tokens
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = RunState.logger?.all().orEmpty()
    val passN = all.count { it.pass }
    val failN = all.size - passN
    var filter by remember { mutableStateOf("all") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(StatusTone.Info) }

    val shown = when (filter) {
        "pass" -> all.filter { it.pass }
        "fail" -> all.filter { !it.pass }
        else -> all
    }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("probe", "results", all.size.toString()))

        Card(title = "totals", icon = Icons.Default.Insights) {
            Row(horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier.fillMaxWidth()) {
                stat(t, "total", all.size.toString())
                stat(t, "pass", passN.toString(), color = t.color.accent)
                stat(t, "fail", failN.toString(), color = t.color.danger)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
            ChipButton("all",  filter == "all")  { filter = "all" }
            ChipButton("pass", filter == "pass") { filter = "pass" }
            ChipButton("fail", filter == "fail") { filter = "fail" }
        }

        if (shown.isEmpty()) {
            Card(title = "empty") {
                MonoLabel("No results yet. Start a run.", color = t.color.faint)
            }
        } else {
            Card(title = "log", icon = Icons.Default.TableChart) {
                shown.take(200).forEachIndexed { i, r ->
                    if (i > 0) Spacer(Modifier.height(t.space.xs))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                        StatusLabel(r.result, tone = if (r.pass) StatusTone.Accent else StatusTone.Danger)
                        MonoLabel("#${r.rowIndex}", color = t.color.faint)
                        Spacer(Modifier.weight(1f))
                        MonoLabel("${r.durationMs ?: "—"} ms", color = t.color.faint)
                    }
                    Text(r.url, style = t.font.mono, maxLines = 2)
                    if (r.finalURL != null && r.finalURL != r.url) {
                        Text("→ ${r.finalURL}", style = t.font.mono.copy(color = t.color.faint), maxLines = 1)
                    }
                }
                if (shown.size > 200) {
                    Spacer(Modifier.height(t.space.sm))
                    MonoLabel("… ${shown.size - 200} more (export to see all).", color = t.color.faint)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
            PrimaryButton("Share CSV", icon = Icons.Default.Share, enabled = all.isNotEmpty()) {
                val file = RunState.logger?.currentFile ?: return@PrimaryButton
                val uri = FileProvider.getUriForFile(ctx,
                    "${ctx.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(intent, "Share log"))
            }
            PrimaryButton("Upload to Nextcloud", icon = Icons.Default.CloudUpload,
                          enabled = all.isNotEmpty()) {
                scope.launch {
                    val creds = app.creds.read()
                    val file = RunState.logger?.currentFile
                    if (creds == null || file == null) {
                        status = "⚠ Set Nextcloud credentials first."; statusTone = StatusTone.Danger
                        return@launch
                    }
                    try {
                        val dir = app.settings.uploadDir.first()
                        val client = WebDavClient(creds)
                        client.put(dir, file.name, file.readBytes(),
                                   contentType = "text/csv; charset=utf-8")
                        status = "Uploaded to $dir/${file.name}"; statusTone = StatusTone.Accent
                    } catch (t: Throwable) {
                        status = "⚠ ${t.message}"; statusTone = StatusTone.Danger
                    }
                }
            }
        }

        status?.let { StatusLabel(it, tone = statusTone) }
    }
}

@androidx.compose.runtime.Composable
private fun stat(t: com.raban.etabli.probe.ui.theme.CoderTokens, label: String, value: String,
                 color: androidx.compose.ui.graphics.Color? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = t.font.display.copy(color = color ?: t.color.ink))
        MonoLabel(label, color = t.color.faint)
    }
}

