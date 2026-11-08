// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/** Native file choosers, behind an interface so the UI can be exercised without dialogs. */
interface DesktopPlatform {
    fun chooseCsvFile(): Path?
    fun chooseExportFile(suggestedName: String): Path?
}

/** Uses the platform file dialog (GTK on Linux, Explorer on Windows, Finder on macOS). */
class AwtFileDialogs(var owner: Frame? = null) : DesktopPlatform {
    override fun chooseCsvFile(): Path? {
        val dialog = FileDialog(owner, "Open CSV", FileDialog.LOAD).apply {
            isMultipleMode = false
            isVisible = true
        }
        val name = dialog.file ?: return null
        return Paths.get(dialog.directory ?: "", name)
    }

    override fun chooseExportFile(suggestedName: String): Path? {
        val dialog = FileDialog(owner, "Export results as CSV", FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val name = dialog.file ?: return null
        return Paths.get(dialog.directory ?: "", name)
    }
}

/**
 * Picks the first local file from dropped items. Desktop drag and drop delivers file URIs
 * (`file:/home/me/links.csv`); plain absolute paths are accepted too, anything else is ignored.
 */
fun firstDroppedFile(items: List<String>): Path? = items.firstNotNullOfOrNull { item ->
    val uri = runCatching { URI(item) }.getOrNull()
    if (uri != null && uri.scheme.equals("file", ignoreCase = true)) runCatching { Paths.get(uri) }.getOrNull()
    else runCatching { Paths.get(item).takeIf { it.isAbsolute } }.getOrNull()
}
