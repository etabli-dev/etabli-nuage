// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raban.etabli.probe.desktop.Notice
import com.raban.etabli.probe.desktop.Tone
import com.raban.etabli.probe.desktop.theme.Coder

// Desktop counterparts of the mobile Coder components (PromptHeader, Card, MonoLabel,
// PrimaryButton, StatusLabel, ChipButton, TextInput), built on Material 3 widgets so
// keyboard focus, hover and screen-reader semantics work out of the box.

@Composable
fun PromptHeader(segments: List<String>, trailing: (@Composable () -> Unit)? = null) {
    val t = Coder.tokens
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("~/", style = t.font.prompt.copy(color = t.color.accentStrong))
        segments.forEachIndexed { idx, seg ->
            if (idx > 0) Text("/", style = t.font.prompt.copy(color = t.color.faint))
            Text(seg, style = t.font.prompt)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun MonoLabel(text: String, color: Color? = null, maxLines: Int = Int.MAX_VALUE) {
    val t = Coder.tokens
    Text(
        text, style = t.font.mono.copy(color = color ?: t.color.ink),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun BodyText(text: String, color: Color? = null) {
    val t = Coder.tokens
    Text(text, style = t.font.body.copy(color = color ?: t.color.ink))
}

@Composable
fun Card(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = Coder.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(t.color.surface, RoundedCornerShape(t.radius.md))
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.md))
            .padding(t.space.md),
        verticalArrangement = Arrangement.spacedBy(t.space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = t.color.accentStrong, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(t.space.sm))
            }
            Text(title, style = t.font.headline)
        }
        content()
    }
}

@Composable
fun PrimaryButton(title: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val t = Coder.tokens
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(t.radius.sm),
        colors = ButtonDefaults.buttonColors(
            containerColor = t.color.accentStrong, contentColor = t.color.onAccentStrong,
            disabledContainerColor = t.color.surfaceAlt, disabledContentColor = t.color.faint,
        ),
        contentPadding = PaddingValues(horizontal = t.space.md, vertical = t.space.sm),
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(t.space.xs))
        }
        Text(title)
    }
}

@Composable
fun SecondaryButton(title: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val t = Coder.tokens
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(t.radius.sm),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = t.color.ink, disabledContentColor = t.color.faint),
        // Keep the outline visible when disabled so the control still reads as a button.
        border = BorderStroke(1.dp, t.color.hairline),
        contentPadding = PaddingValues(horizontal = t.space.md, vertical = t.space.sm),
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(t.space.xs))
        }
        Text(title)
    }
}

@Composable
fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = Coder.tokens
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = t.font.mono, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = RoundedCornerShape(t.radius.sm),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = t.color.surface,
            labelColor = t.color.faint,
            selectedContainerColor = t.color.accentMuted,
            selectedLabelColor = t.color.ink,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = t.color.hairline, selectedBorderColor = t.color.accent,
            selectedBorderWidth = 1.dp,
        ),
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    secret: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val t = Coder.tokens
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder, color = t.color.faint) }),
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = t.font.body,
        shape = RoundedCornerShape(t.radius.sm),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = t.color.accent,
            unfocusedBorderColor = t.color.hairline,
            focusedLabelColor = t.color.accentStrong,
            cursorColor = t.color.accentStrong,
            focusedContainerColor = t.color.paper,
            unfocusedContainerColor = t.color.paper,
        ),
        modifier = modifier,
    )
}

/** Inline status line, announced politely by screen readers when it changes. */
@Composable
fun NoticeLabel(notice: Notice) {
    val t = Coder.tokens
    val (icon, tint) = when (notice.tone) {
        Tone.Info -> Icons.Default.Info to t.color.faint
        Tone.Accent -> Icons.Default.CheckCircle to t.color.accentStrong
        Tone.Danger -> Icons.Default.Warning to t.color.danger
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(t.color.surface, RoundedCornerShape(t.radius.sm))
            .border(1.dp, if (notice.tone == Tone.Info) t.color.hairline else tint, RoundedCornerShape(t.radius.sm))
            .padding(horizontal = t.space.md, vertical = t.space.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(t.space.sm))
        Text(notice.text, style = t.font.body)
    }
}

@Composable
fun Stat(label: String, value: String, color: Color? = null) {
    val t = Coder.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = t.font.stat.copy(color = color ?: t.color.ink))
        Spacer(Modifier.height(t.space.xs))
        Text(label, style = t.font.caption)
    }
}
