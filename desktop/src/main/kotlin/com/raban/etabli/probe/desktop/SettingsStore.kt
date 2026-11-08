// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

enum class ThemePref { System, Light, Dark }

/**
 * Non-secret desktop settings. The Nextcloud app password is deliberately absent: it is
 * asked for each session and kept in memory only (see NuageState.sessionPassword).
 */
data class AppSettings(
    val delayMs: Long = 1_000,
    val timeoutMs: Long = 15_000,
    val theme: ThemePref = ThemePref.System,
    val serverUrl: String = "",
    val username: String = "",
    val uploadDir: String = "EtabliProbe",
    val installId: String = "",
) {
    companion object {
        // Same ranges as the mobile Settings sliders.
        val DELAY_RANGE = 100L..10_000L
        val TIMEOUT_RANGE = 3_000L..120_000L
    }
}

/** Reads and writes [AppSettings] as a small properties file in the per-user config folder. */
class SettingsStore(val file: Path) {
    fun load(): AppSettings {
        val props = Properties()
        if (Files.isRegularFile(file)) Files.newBufferedReader(file, Charsets.UTF_8).use { props.load(it) }
        fun long(key: String, default: Long, range: LongRange) =
            props.getProperty(key)?.toLongOrNull()?.coerceIn(range) ?: default
        val defaults = AppSettings()
        val loaded = AppSettings(
            delayMs = long("delayMs", defaults.delayMs, AppSettings.DELAY_RANGE),
            timeoutMs = long("timeoutMs", defaults.timeoutMs, AppSettings.TIMEOUT_RANGE),
            theme = ThemePref.entries.firstOrNull { it.name.equals(props.getProperty("theme"), ignoreCase = true) }
                ?: ThemePref.System,
            serverUrl = props.getProperty("serverUrl").orEmpty(),
            username = props.getProperty("username").orEmpty(),
            uploadDir = props.getProperty("uploadDir") ?: defaults.uploadDir,
            installId = props.getProperty("installId").orEmpty(),
        )
        if (loaded.installId.isNotEmpty()) return loaded
        // Per-install UUID, as on mobile; not a hardware or user identifier.
        return loaded.copy(installId = UUID.randomUUID().toString()).also(::save)
    }

    fun save(settings: AppSettings) {
        val props = Properties().apply {
            setProperty("delayMs", settings.delayMs.toString())
            setProperty("timeoutMs", settings.timeoutMs.toString())
            setProperty("theme", settings.theme.name.lowercase())
            setProperty("serverUrl", settings.serverUrl)
            setProperty("username", settings.username)
            setProperty("uploadDir", settings.uploadDir)
            setProperty("installId", settings.installId)
        }
        val dir = file.toAbsolutePath().parent ?: throw IOException("Settings file has no folder")
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".settings", ".tmp")
        try {
            Files.newBufferedWriter(temp, Charsets.UTF_8).use {
                props.store(it, "Etabli Nuage desktop settings. The Nextcloud app password is never stored here.")
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        /** Per-user config folder: XDG on Linux, Application Support on macOS, %APPDATA% on Windows. */
        fun defaultFile(
            osName: String = System.getProperty("os.name").orEmpty(),
            env: (String) -> String? = System::getenv,
            home: String = System.getProperty("user.home"),
        ): Path {
            val os = osName.lowercase()
            val dir = when {
                os.startsWith("windows") ->
                    Paths.get(env("APPDATA")?.takeIf { it.isNotBlank() } ?: Paths.get(home, "AppData", "Roaming").toString(), "Etabli Nuage")
                os.startsWith("mac") -> Paths.get(home, "Library", "Application Support", "Etabli Nuage")
                else -> {
                    val xdg = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() && Paths.get(it).isAbsolute }
                    Paths.get(xdg ?: Paths.get(home, ".config").toString(), "etabli-nuage")
                }
            }
            return dir.resolve("settings.properties")
        }
    }
}
