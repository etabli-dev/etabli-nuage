// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.raban.etabli.probe.desktop.ui.NuageApp
import com.raban.etabli.probe.desktop.ui.exportWithDialog
import com.raban.etabli.probe.desktop.ui.openWithDialog
import org.jetbrains.skia.Image
import java.awt.Dimension
import java.nio.file.Paths

private val isMac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

private fun appIcon(): Painter? = runCatching {
    val bytes = NuageState::class.java.getResourceAsStream("/nuage-icon.png")!!.use { it.readBytes() }
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

/** Optional first argument: a CSV file to open at start. */
fun main(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(SettingsStore.defaultFile()) }
    val state = remember { NuageState(store, scope) }
    val icon = remember { appIcon() }
    val dialogs = remember { AwtFileDialogs() }
    val windowState = rememberWindowState(size = DpSize(1200.dp, 820.dp), position = WindowPosition(Alignment.Center))
    Window(
        onCloseRequest = { if (state.requestQuit()) exitApplication() },
        state = windowState,
        title = "Établi Nuage",
        icon = icon,
        onPreviewKeyEvent = { event ->
            // Ctrl+O / Ctrl+S (Cmd on macOS) open a CSV and export the results.
            val shortcut = event.type == KeyEventType.KeyDown && (if (isMac) event.isMetaPressed else event.isCtrlPressed)
            when {
                shortcut && event.key == Key.O -> { state.openWithDialog(dialogs); true }
                shortcut && event.key == Key.S -> { state.exportWithDialog(dialogs); true }
                else -> false
            }
        },
    ) {
        SideEffect { dialogs.owner = window }
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(960, 640)
            args.firstOrNull()?.let { state.openFile(Paths.get(it)) }
        }
        NuageApp(state, dialogs, store.file.toString(), onQuit = ::exitApplication)
    }
}
