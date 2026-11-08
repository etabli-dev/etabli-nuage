// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.raban.etabli.probe.desktop.DesktopPlatform
import com.raban.etabli.probe.desktop.NuageState
import com.raban.etabli.probe.desktop.Screen
import com.raban.etabli.probe.desktop.SettingsStore
import com.raban.etabli.probe.engine.CsvLoader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** Drives the real window content (without a native window) through the main workflow. */
class NuageAppUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun openChooseRunAndExportThroughTheUi() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(500))
            val input = tmp.newFile("links.csv").toPath()
            Files.writeString(input, "id,link\n1,${server.url("/ok")}\n2,${server.url("/broken")}\n")
            val export = tmp.root.toPath().resolve("exported.csv")
            val platform = object : DesktopPlatform {
                override fun chooseCsvFile(): Path = input
                override fun chooseExportFile(suggestedName: String): Path = export
            }
            lateinit var state: NuageState
            compose.setContent {
                val scope = rememberCoroutineScope()
                state = remember {
                    NuageState(SettingsStore(tmp.root.toPath().resolve("settings.properties")), scope).apply { setDelayMs(100) }
                }
                NuageApp(state, platform, "settings.properties", onQuit = {})
            }

            compose.onNodeWithText("Continue to Run").assertDoesNotExist()
            compose.onNodeWithText("Open CSV…").performClick()
            compose.waitUntil(5_000) { state.preview != null }
            compose.onNodeWithText("1 valid of 2 rows", substring = true).assertDoesNotExist()
            compose.onNodeWithText("2 valid of 2 rows").assertExists()
            compose.onNodeWithText("Continue to Run").assertIsEnabled().performClick()
            compose.waitForIdle()
            assertEquals(Screen.Run, state.screen)

            compose.onNodeWithText("Start").performClick()
            compose.waitUntil(10_000) { !state.running && state.done == 2 }
            compose.onNodeWithText("Finished: 2 checked, 1 passed, 1 failed.").assertExists()

            compose.onNodeWithText("Results").performClick()
            compose.onNodeWithText("204").assertExists()
            compose.onNodeWithText("500").assertExists()
            compose.onNodeWithText("Export CSV…").performClick()
            compose.waitUntil(5_000) { Files.exists(export) }
            val exported = CsvLoader.parse(Files.readAllBytes(export), hasHeader = true)
            assertEquals(listOf("204", "500"), exported.column(3))

            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Nextcloud (optional)").assertExists()
            compose.onNodeWithText("Test connection").assertIsNotEnabled()
        }
    }
}
