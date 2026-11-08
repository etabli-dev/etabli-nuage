// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raban.etabli.probe.desktop.NuageState
import com.raban.etabli.probe.desktop.theme.Coder
import com.raban.etabli.probe.engine.CsvPreview
import com.raban.etabli.probe.engine.UrlNormalize

private const val PREVIEW_ROWS = 8
private const val PREVIEW_COLUMNS = 40

@Composable
fun SourcePane(state: NuageState, onOpenFile: () -> Unit) {
    val t = Coder.tokens
    val scroll = rememberScrollState()
    var davPath by remember { mutableStateOf("") }
    ScrollPane(scroll) {
        PromptHeader(listOf("nuage", "source"))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md), modifier = Modifier.height(IntrinsicSize.Max)) {
            Card("open a local CSV", modifier = Modifier.weight(1f).fillMaxHeight()) {
                BodyText(
                    "Choose a file, or drop it anywhere on this window. Comma, semicolon and tab " +
                        "delimiters, quoted and multiline fields, UTF-8 (with or without BOM) and " +
                        "Latin-1 are read. The file itself is never changed.",
                    color = t.color.faint,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Open CSV…", enabled = !state.loading, onClick = onOpenFile)
                    Spacer(Modifier.width(t.space.md))
                    DropHint(Modifier.weight(1f))
                }
            }
            Card("fetch from Nextcloud", modifier = Modifier.weight(1f).fillMaxHeight()) {
                BodyText(
                    if (state.hasAccount) {
                        "${state.settings.username} on ${state.settings.serverUrl}. The path is relative " +
                            "to your Nextcloud files; the app password is asked once per session."
                    } else {
                        "Optional. Add your HTTPS server and username in Settings first."
                    },
                    color = t.color.faint,
                )
                LabeledField(
                    "WebDAV path", davPath, { davPath = it },
                    placeholder = "links/probe.csv", enabled = state.hasAccount,
                )
                SecondaryButton(
                    "Fetch", enabled = state.hasAccount && davPath.isNotBlank() && !state.loading,
                ) { state.fetchFromNextcloud(davPath) }
            }
        }
        if (state.loading) {
            LinearProgressIndicator(color = t.color.accent, trackColor = t.color.surfaceAlt, modifier = Modifier.fillMaxWidth())
        }
        state.sourceNotice?.let { NoticeLabel(it) }
        state.preview?.takeIf { it.headers.isNotEmpty() }?.let { ColumnChooser(state, it) }
    }
}

@Composable
private fun DropHint(modifier: Modifier) {
    val t = Coder.tokens
    val outline = t.color.hairline
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(t.radius.sm.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                )
            },
    ) {
        Text("or drop a .csv here", style = t.font.caption)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ColumnChooser(state: NuageState, preview: CsvPreview) {
    val t = Coder.tokens
    val selected = state.selectedColumn
    val counts = remember(preview, selected) {
        selected?.let { column ->
            val raws = preview.column(column)
            raws.count { UrlNormalize.normalize(it) != null } to raws.size
        }
    }
    Card("choose the URL column") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.toggleable(
                value = preview.hasHeader, role = Role.Switch, onValueChange = state::setHeader,
            ),
        ) {
            Switch(
                checked = preview.hasHeader, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = t.color.accentStrong),
            )
            Spacer(Modifier.width(t.space.sm))
            BodyText("First record contains column names")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.space.sm),
            verticalArrangement = Arrangement.spacedBy(t.space.xs),
        ) {
            preview.headers.forEachIndexed { i, header -> ChipButton(header, selected == i) { state.selectColumn(i) } }
        }
        when {
            counts == null -> MonoLabel("No column holds a URL-looking value. Choose a column to check anyway.", color = t.color.danger)
            else -> MonoLabel(
                "${counts.first} valid of ${counts.second} rows" +
                    if (counts.first < counts.second) " · the others are recorded as skipped" else "",
                color = if (counts.first == 0) t.color.danger else t.color.faint,
            )
        }
        PreviewTable(preview, selected)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                "Continue to Run", icon = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = (counts?.first ?: 0) > 0 && !state.running,
            ) { state.prepare() }
            if (state.running) {
                Spacer(Modifier.width(t.space.md))
                MonoLabel("A run is in progress.", color = t.color.faint)
            }
        }
    }
}

@Composable
private fun PreviewTable(preview: CsvPreview, selected: Int?) {
    val t = Coder.tokens
    val columns = preview.headers.take(PREVIEW_COLUMNS)
    val rows = preview.rows.take(PREVIEW_ROWS)
    val firstRecord = if (preview.hasHeader) 2 else 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(Modifier.background(t.color.surfaceAlt)) {
            Cell("#", 56.dp, header = true)
            columns.forEachIndexed { i, h -> Cell(h, 200.dp, header = true, highlighted = i == selected) }
        }
        rows.forEachIndexed { r, row ->
            Row {
                Cell("${r + firstRecord}", 56.dp, faint = true)
                columns.indices.forEach { i -> Cell(row.getOrElse(i) { "" }, 200.dp, highlighted = i == selected) }
            }
        }
    }
    val more = buildList {
        if (preview.rows.size > PREVIEW_ROWS) add("${preview.rows.size - PREVIEW_ROWS} more records")
        if (preview.headers.size > PREVIEW_COLUMNS) add("${preview.headers.size - PREVIEW_COLUMNS} more columns")
    }
    if (more.isNotEmpty()) MonoLabel("Preview only · " + more.joinToString(" · "), color = t.color.faint)
}

@Composable
private fun Cell(text: String, width: Dp, header: Boolean = false, highlighted: Boolean = false, faint: Boolean = false) {
    val t = Coder.tokens
    val style = t.font.mono.copy(
        color = if (faint) t.color.faint else t.color.ink,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
    )
    Box(
        modifier = Modifier
            .width(width)
            .background(if (highlighted) t.color.accentMuted else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = t.space.sm, vertical = t.space.xs),
    ) {
        Text(text.lineSequence().firstOrNull().orEmpty(), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Scrollable page column with a desktop scrollbar. */
@Composable
fun ScrollPane(
    scroll: androidx.compose.foundation.ScrollState,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val t = Coder.tokens
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(t.space.lg),
            verticalArrangement = Arrangement.spacedBy(t.space.md),
            content = content,
        )
        androidx.compose.foundation.VerticalScrollbar(
            adapter = androidx.compose.foundation.rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = t.space.xs),
        )
    }
}
