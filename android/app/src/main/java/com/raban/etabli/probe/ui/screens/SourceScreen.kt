// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.raban.etabli.probe.EtabliProbeApplication
import com.raban.etabli.probe.engine.CsvLoader
import com.raban.etabli.probe.engine.CsvPreview
import com.raban.etabli.probe.engine.UrlEntry
import com.raban.etabli.probe.engine.UrlNormalize
import com.raban.etabli.probe.engine.WebDavCreds
import com.raban.etabli.probe.net.WebDavClient
import com.raban.etabli.probe.ui.RunState
import com.raban.etabli.probe.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SourceScreen(app: EtabliProbeApplication, onPrepared: () -> Unit) {
    val t = Coder.tokens
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<CsvPreview?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(StatusTone.Info) }
    var busy by remember { mutableStateOf(false) }
    var creds by remember { mutableStateOf<WebDavCreds?>(null) }
    var showDav by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { creds = app.creds.read() }

    val openCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true; status = null
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Couldn't read file.")
                    preview = CsvLoader.parse(bytes)
                } catch (t: Throwable) {
                    status = "⚠ ${t.message}"; statusTone = StatusTone.Danger
                } finally { busy = false }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("probe", "source"))
        Card(title = "pick a local .csv", icon = Icons.Default.FolderOpen) {
            MonoLabel("Choose a CSV from device storage. One column should hold the URLs (with or without a header row).",
                      color = t.color.faint)
            PrimaryButton("Pick file", icon = Icons.Default.UploadFile, enabled = !busy) {
                openCsv.launch(arrayOf("text/csv", "text/comma-separated-values",
                                       "text/plain", "*/*"))
            }
        }

        Card(title = "fetch from Nextcloud", icon = Icons.Default.Cloud) {
            MonoLabel(
                if (creds == null) "Set Nextcloud credentials in Settings first."
                else "Auth as ${creds!!.username} via app password.",
                color = t.color.faint,
            )
            PrimaryButton("Fetch from WebDAV", icon = Icons.Default.Cloud,
                          enabled = !busy && creds != null) { showDav = true }
        }

        preview?.let { p ->
            ColumnMapCard(p, onChoose = { idx ->
                val raws = p.column(idx)
                val entries = raws.mapIndexed { i, raw ->
                    UrlEntry(rowIndex = i + 1, raw = raw, normalized = UrlNormalize.normalize(raw))
                }
                RunState.prepared = entries
                val valid = entries.count { it.valid }
                status = "$valid valid of ${entries.size} prepared. Switching to Run."
                statusTone = StatusTone.Accent
                onPrepared()
            })
        }

        status?.let { StatusLabel(it, tone = statusTone) }
    }

    if (showDav) {
        DavFetchSheet(
            creds = creds,
            onDismiss = { showDav = false },
            onFetch = { path ->
                showDav = false
                scope.launch {
                    busy = true; status = null
                    try {
                        val c = creds ?: throw IllegalStateException("no creds")
                        val bytes = WebDavClient(c).get(path)
                        preview = CsvLoader.parse(bytes)
                    } catch (t: Throwable) {
                        status = "⚠ ${t.message}"; statusTone = StatusTone.Danger
                    } finally { busy = false }
                }
            },
        )
    }
}

@Composable
private fun ColumnMapCard(p: CsvPreview, onChoose: (Int) -> Unit) {
    val t = Coder.tokens
    var selected by remember(p) {
        // Best-guess: first column with at least one URL-looking value.
        val idx = (0 until p.headers.size).firstOrNull { i ->
            p.column(i).any { UrlNormalize.normalize(it) != null }
        }
        mutableStateOf(idx)
    }
    Card(title = "pick the URL column", icon = Icons.Default.UploadFile) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(t.space.sm),
        ) {
            p.headers.forEachIndexed { i, h ->
                ChipButton(label = h, selected = selected == i) { selected = i }
            }
        }
        selected?.let { s ->
            val raws = p.column(s)
            val valid = raws.count { UrlNormalize.normalize(it) != null }
            Spacer(Modifier.height(t.space.sm))
            MonoLabel("$valid valid of ${raws.size} rows.",
                      color = if (valid == 0) t.color.danger else t.color.faint)
            Spacer(Modifier.height(t.space.xs))
            raws.take(6).forEach { Text(it.ifBlank { "(blank)" }, style = t.font.mono, maxLines = 1) }
            Spacer(Modifier.height(t.space.md))
            PrimaryButton("Continue", icon = Icons.Default.ArrowForward, enabled = valid > 0) {
                onChoose(s)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DavFetchSheet(creds: WebDavCreds?, onDismiss: () -> Unit, onFetch: (String) -> Unit) {
    val t = Coder.tokens
    val sheet = rememberModalBottomSheetState()
    var path by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = t.color.surface) {
        Column(modifier = Modifier.padding(t.space.lg),
               verticalArrangement = Arrangement.spacedBy(t.space.md)) {
            PromptHeader(listOf("probe", "fetch"))
            Card(title = "WebDAV path", icon = Icons.Default.FolderOpen) {
                MonoLabel("Path relative to your Nextcloud root.", color = t.color.faint)
                TextInput(value = path, placeholder = "links/probe.csv", onChange = { path = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                PrimaryButton("Cancel", onClick = onDismiss)
                PrimaryButton("Fetch", icon = Icons.Default.Cloud, enabled = path.isNotBlank()) {
                    onFetch(path)
                }
            }
        }
    }
}
