// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun firstLoadUsesMobileDefaultsAndPersistsAnInstallId() {
        val file = tmp.root.toPath().resolve("cfg/settings.properties")
        val store = SettingsStore(file)
        val first = store.load()
        assertEquals(1_000L, first.delayMs)
        assertEquals(15_000L, first.timeoutMs)
        assertEquals(ThemePref.System, first.theme)
        assertEquals("EtabliProbe", first.uploadDir)
        assertTrue(first.installId.matches(Regex("[0-9a-f-]{36}")))
        assertEquals(first, store.load())
    }

    @Test fun roundTripsAndClampsValues() {
        val store = SettingsStore(tmp.root.toPath().resolve("settings.properties"))
        val saved = store.load().copy(delayMs = 250, timeoutMs = 30_000, theme = ThemePref.Dark, serverUrl = "https://cloud.example", username = "me", uploadDir = "Out/Links")
        store.save(saved)
        assertEquals(saved, store.load())
        Files.writeString(store.file, "delayMs=5\ntimeoutMs=999999\ntheme=purple\ninstallId=x\n")
        val clamped = store.load()
        assertEquals(100L, clamped.delayMs)
        assertEquals(120_000L, clamped.timeoutMs)
        assertEquals(ThemePref.System, clamped.theme)
    }

    @Test fun neverWritesAPasswordAndLeavesNoTemporaryFiles() {
        val store = SettingsStore(tmp.root.toPath().resolve("settings.properties"))
        store.save(store.load().copy(serverUrl = "https://cloud.example", username = "me"))
        val text = Files.readString(store.file)
        assertFalse(text.contains("password=", ignoreCase = true))
        assertEquals(setOf("delayMs", "timeoutMs", "theme", "serverUrl", "username", "uploadDir", "installId"),
            java.util.Properties().apply { load(Files.newBufferedReader(store.file)) }.stringPropertyNames())
        assertEquals(listOf("settings.properties"), tmp.root.list()!!.toList())
    }

    @Test fun choosesThePerUserConfigFolderOfEachSystem() {
        // Absolute paths of the machine running the test, so the check is portable.
        val home = tmp.newFolder("home").absolutePath
        val xdg = tmp.newFolder("xdg").absolutePath
        assertEquals(
            Paths.get(home, ".config", "etabli-nuage", "settings.properties"),
            SettingsStore.defaultFile("Linux", { null }, home),
        )
        assertEquals(
            Paths.get(xdg, "etabli-nuage", "settings.properties"),
            SettingsStore.defaultFile("Linux", { if (it == "XDG_CONFIG_HOME") xdg else null }, home),
        )
        // A relative XDG_CONFIG_HOME is invalid per the XDG spec and ignored.
        assertEquals(
            Paths.get(home, ".config", "etabli-nuage", "settings.properties"),
            SettingsStore.defaultFile("Linux", { if (it == "XDG_CONFIG_HOME") "rel" else null }, home),
        )
        assertEquals(
            Paths.get(home, "Library", "Application Support", "Etabli Nuage", "settings.properties"),
            SettingsStore.defaultFile("Mac OS X", { null }, home),
        )
        assertEquals(
            Paths.get(xdg, "Etabli Nuage", "settings.properties"),
            SettingsStore.defaultFile("Windows 11", { if (it == "APPDATA") xdg else null }, home),
        )
        assertEquals(
            Paths.get(home, "AppData", "Roaming", "Etabli Nuage", "settings.properties"),
            SettingsStore.defaultFile("Windows 11", { null }, home),
        )
    }
}
