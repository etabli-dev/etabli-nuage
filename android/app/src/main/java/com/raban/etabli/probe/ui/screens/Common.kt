// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.raban.etabli.probe.ui.theme.Coder

@Composable
fun TextInput(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    isSecret: Boolean = false,
) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radius.sm))
            .background(t.color.paper)
            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm))
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = t.font.body.copy(color = t.color.faint))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = t.font.body.copy(color = t.color.ink),
            cursorBrush = SolidColor(t.color.accent),
            singleLine = true,
            visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ChipButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = Coder.tokens
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .background(if (selected) t.color.accentMuted else t.color.surface)
            .border(1.dp, if (selected) t.color.accent else t.color.hairline,
                    RoundedCornerShape(t.radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = t.space.md, vertical = t.space.sm),
    ) {
        Text(label, style = t.font.caption.copy(
            color = if (selected) t.color.accent else t.color.faint
        ))
    }
}
