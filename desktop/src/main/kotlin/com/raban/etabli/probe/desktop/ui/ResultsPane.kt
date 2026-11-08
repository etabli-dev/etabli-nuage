// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raban.etabli.probe.desktop.NuageState
import com.raban.etabli.probe.desktop.ResultFilter
import com.raban.etabli.probe.desktop.theme.Coder
import com.raban.etabli.probe.engine.CheckResult

@Composable
fun ResultsPane(state: NuageState, onExport: () -> Unit) {
    val t = Coder.tokens
    val results = state.results
    Column(
        modifier = Modifier.fillMaxSize().padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.md),
    ) {
        PromptHeader(listOf("nuage", "results", results.size.toString()))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md), modifier = Modifier.height(IntrinsicSize.Max)) {
            Card("this run", modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(horizontalArrangement = Arrangement.SpaceAround, modifier = Modifier.fillMaxWidth()) {
                    Stat("total", results.size.toString())
                    Stat("pass", state.passCount.toString(), color = t.color.accentStrong)
                    Stat("fail", state.failCount.toString(), color = if (state.failCount > 0) t.color.danger else null)
                }
            }
            Card("save", modifier = Modifier.weight(1.5f).fillMaxHeight()) {
                val unsaved = state.unsavedCount
                BodyText(
                    when {
                        results.isEmpty() -> "No results yet. Start a run first."
                        unsaved > 0 -> "$unsaved rows are not exported or uploaded yet."
                        else -> "All rows of this run are saved."
                    },
                    color = t.color.faint,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                    PrimaryButton("Export CSV…", icon = Icons.Default.Done, enabled = results.isNotEmpty(), onClick = onExport)
                    SecondaryButton(
                        "Upload to Nextcloud", icon = Icons.AutoMirrored.Filled.Send,
                        enabled = results.isNotEmpty() && !state.davBusy,
                    ) { state.uploadResults(state.suggestedFileName()) }
                }
                if (state.hasAccount && results.isNotEmpty()) {
                    MonoLabel(
                        "uploads to ${state.settings.uploadDir.trim('/')}/${state.suggestedFileName()}",
                        color = t.color.faint, maxLines = 1,
                    )
                }
            }
        }
        state.journalPath?.let { path ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text("Local run log: $path", style = t.font.mono)
            }
        }
        val earlierLogs = state.previousRunLogs.filter { it != state.journalPath }
        if (earlierLogs.isNotEmpty()) {
            Card("previous local runs (${earlierLogs.size})") {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(earlierLogs.take(3).joinToString("\n"), style = t.font.mono)
                }
                BodyText("CSV files remain in this folder after restart.", color = t.color.faint)
            }
        }
        state.resultsNotice?.let { NoticeLabel(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm), verticalAlignment = Alignment.CenterVertically) {
            ResultFilter.entries.forEach { f ->
                ChipButton(f.name.lowercase(), state.filter == f) { state.filter = f }
            }
        }
        ResultsTable(state, Modifier.weight(1f))
    }
}

@Composable
private fun ResultsTable(state: NuageState, modifier: Modifier) {
    val t = Coder.tokens
    val shown by remember(state) {
        derivedStateOf {
            when (state.filter) {
                ResultFilter.All -> state.results
                ResultFilter.Pass -> state.results.filter { it.pass }
                ResultFilter.Fail -> state.results.filter { !it.pass }
            }
        }
    }
    val listState = rememberLazyListState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm)),
    ) {
        Row(Modifier.fillMaxWidth().background(t.color.surfaceAlt).padding(vertical = t.space.xs)) {
            HeaderCell("row", 64.dp)
            HeaderCell("result", 190.dp)
            HeaderCell("url", null)
            HeaderCell("final url", null)
            HeaderCell("ms", 80.dp)
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(t.space.lg), contentAlignment = Alignment.Center) {
                MonoLabel(if (state.results.isEmpty()) "No results yet." else "No rows match this filter.", color = t.color.faint)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
                    itemsIndexed(shown, key = { i, r -> "${r.rowIndex}:$i" }) { i, r -> ResultRow(r, stripe = i % 2 == 1) }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ResultRow(r: CheckResult, stripe: Boolean) {
    val t = Coder.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (stripe) t.color.surface else Color.Transparent)
            .padding(vertical = t.space.xs),
    ) {
        BodyCell(r.rowIndex.toString(), 64.dp, color = t.color.faint)
        BodyCell(r.result, 190.dp, color = if (r.pass) t.color.accentStrong else t.color.danger, bold = true)
        BodyCell(r.url, null)
        BodyCell(if (r.finalURL != null && r.finalURL != r.url) r.finalURL else "", null, color = t.color.faint)
        BodyCell(r.durationMs?.toString() ?: "—", 80.dp, color = t.color.faint)
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, width: Dp?) {
    val t = Coder.tokens
    Text(
        text, style = t.font.mono.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
        modifier = (if (width == null) Modifier.weight(1f) else Modifier.width(width)).padding(horizontal = t.space.sm),
    )
}

@Composable
private fun RowScope.BodyCell(text: String, width: Dp?, color: Color? = null, bold: Boolean = false) {
    val t = Coder.tokens
    Text(
        text,
        style = t.font.mono.copy(
            color = color ?: t.color.ink,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = (if (width == null) Modifier.weight(1f) else Modifier.width(width)).padding(horizontal = t.space.sm),
    )
}
