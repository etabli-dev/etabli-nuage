// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.raban.etabli.probe.desktop.BuildInfo
import com.raban.etabli.probe.desktop.DesktopPlatform
import com.raban.etabli.probe.desktop.NuageState
import com.raban.etabli.probe.desktop.PendingDav
import com.raban.etabli.probe.desktop.Screen
import com.raban.etabli.probe.desktop.ThemePref
import com.raban.etabli.probe.desktop.firstDroppedFile
import com.raban.etabli.probe.desktop.theme.Coder
import com.raban.etabli.probe.desktop.theme.CoderTheme

/** Opens a CSV through the platform dialog and switches to Source. */
fun NuageState.openWithDialog(platform: DesktopPlatform) {
    platform.chooseCsvFile()?.let { screen = Screen.Source; openFile(it) }
}

/** Exports the current results through the platform save dialog. */
fun NuageState.exportWithDialog(platform: DesktopPlatform) {
    if (results.isEmpty()) return
    platform.chooseExportFile(suggestedFileName())?.let { exportTo(it) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NuageApp(state: NuageState, platform: DesktopPlatform, settingsLocation: String, onQuit: () -> Unit) {
    val dark = when (state.settings.theme) {
        ThemePref.System -> null
        ThemePref.Light -> false
        ThemePref.Dark -> true
    }
    CoderTheme(darkOverride = dark) {
        val t = Coder.tokens
        var dragging by remember { mutableStateOf(false) }
        val dropTarget = remember(state) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) { dragging = true }
                override fun onExited(event: DragAndDropEvent) { dragging = false }
                override fun onEnded(event: DragAndDropEvent) { dragging = false }
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dragging = false
                    val files = event.dragData() as? DragData.FilesList ?: return false
                    val path = firstDroppedFile(files.readFiles()) ?: return false
                    state.screen = Screen.Source
                    state.openFile(path)
                    return true
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(t.color.paper)
                .dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = dropTarget),
        ) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(state)
                VerticalDivider(color = t.color.hairline)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (state.screen) {
                        Screen.Source -> SourcePane(state, onOpenFile = { state.openWithDialog(platform) })
                        Screen.Run -> RunPane(state)
                        Screen.Results -> ResultsPane(state, onExport = { state.exportWithDialog(platform) })
                        Screen.Settings -> SettingsPane(state, settingsLocation)
                    }
                }
            }
            if (dragging) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(t.color.paper.copy(alpha = 0.85f))
                        .padding(t.space.xl)
                        .border(2.dp, t.color.accent, RoundedCornerShape(t.radius.lg)),
                ) {
                    Text("Drop to open the CSV", style = t.font.title.copy(color = t.color.accentStrong))
                }
            }
        }
        Dialogs(state, onQuit)
    }
}

@Composable
private fun Sidebar(state: NuageState) {
    val t = Coder.tokens
    NavigationRail(
        containerColor = t.color.surface,
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = t.space.md)) {
                Text("nuage", style = t.font.headline.copy(color = t.color.accentStrong))
                Spacer(Modifier.height(t.space.md))
            }
        },
        modifier = Modifier.width(104.dp),
    ) {
        val colors = NavigationRailItemDefaults.colors(
            selectedIconColor = t.color.accentStrong,
            selectedTextColor = t.color.accentStrong,
            indicatorColor = t.color.accentMuted,
            unselectedIconColor = t.color.faint,
            unselectedTextColor = t.color.faint,
        )
        listOf(
            Triple(Screen.Source, "Source", Icons.Default.Home),
            Triple(Screen.Run, if (state.running) "Running" else "Run", Icons.Default.PlayArrow),
            Triple(Screen.Results, "Results", Icons.AutoMirrored.Filled.List),
            Triple(Screen.Settings, "Settings", Icons.Default.Settings),
        ).forEach { (screen, label, icon) ->
            NavigationRailItem(
                selected = state.screen == screen,
                onClick = { state.screen = screen },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label, style = t.font.caption) },
                colors = colors,
            )
        }
        Spacer(Modifier.weight(1f))
        Text("preview ${BuildInfo.VERSION}", style = t.font.caption, modifier = Modifier.padding(bottom = t.space.md))
    }
}

@Composable
private fun Dialogs(state: NuageState, onQuit: () -> Unit) {
    val t = Coder.tokens
    state.passwordPrompt?.let { pending -> PasswordDialog(state, pending) }
    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = { state.confirmDiscard = false },
            title = { Text("Start a new run?", style = t.font.headline) },
            text = { BodyText("${state.unsavedCount} results of the previous run are not exported or uploaded. A new run replaces them.") },
            confirmButton = { TextButton(onClick = { state.startRun() }) { Text("Discard and start") } },
            dismissButton = { TextButton(onClick = { state.confirmDiscard = false }) { Text("Keep results") } },
            containerColor = t.color.surface,
        )
    }
    if (state.confirmQuit) {
        AlertDialog(
            onDismissRequest = { state.confirmQuit = false },
            title = { Text("Quit Établi Nuage?", style = t.font.headline) },
            text = {
                BodyText(
                    if (state.running) "A run is in progress and will stop. Unsaved results are lost."
                    else "${state.unsavedCount} results are not exported or uploaded and will be lost.",
                )
            },
            confirmButton = { TextButton(onClick = { state.confirmQuit = false; state.cancelRun(); onQuit() }) { Text("Quit") } },
            dismissButton = { TextButton(onClick = { state.confirmQuit = false }) { Text("Stay") } },
            containerColor = t.color.surface,
        )
    }
}

@Composable
private fun PasswordDialog(state: NuageState, pending: PendingDav) {
    val t = Coder.tokens
    var password by remember(pending) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(pending) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { state.dismissPasswordPrompt() },
        title = { Text("Nextcloud app password", style = t.font.headline) },
        text = {
            Column {
                BodyText(
                    "For ${state.settings.username} on ${state.settings.serverUrl}. It stays in memory " +
                        "until you quit and is never saved.",
                    color = t.color.faint,
                )
                Spacer(Modifier.height(t.space.md))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("App password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = t.color.accent, cursorColor = t.color.accentStrong,
                        focusedLabelColor = t.color.accentStrong,
                    ),
                    modifier = Modifier
                        .focusRequester(focus)
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && password.isNotEmpty()) {
                                state.submitPassword(password); true
                            } else false
                        },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { state.submitPassword(password) }, enabled = password.isNotEmpty()) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = { state.dismissPasswordPrompt() }) { Text("Cancel") } },
        containerColor = t.color.surface,
    )
}
