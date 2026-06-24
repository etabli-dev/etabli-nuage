// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raban.etabli.probe.engine.LogRotation
import com.raban.etabli.probe.engine.MaskMode
import com.raban.etabli.probe.engine.RunMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Non-secret settings live in DataStore (run mode, delay, timeout, theme,
// upload directory). Credentials live in EncryptedSharedPreferences via
// CredsStore — never here.

private val Context.probeStore by preferencesDataStore(name = "probe_settings")

enum class ThemePref { System, Light, Dark;
    val isDark: Boolean? get() = when (this) {
        System -> null
        Light -> false
        Dark -> true
    }
}

class SettingsStore(private val context: Context) {

    private val KMode      = stringPreferencesKey("mode")
    private val KDelayMs   = longPreferencesKey("delayMs")
    private val KTimeoutMs = longPreferencesKey("timeoutMs")
    private val KTheme     = stringPreferencesKey("theme")
    private val KRotation  = stringPreferencesKey("rotation")
    private val KUploadDir = stringPreferencesKey("uploadDir")
    private val KMaskMode  = stringPreferencesKey("maskMode")

    val mode: Flow<RunMode> = context.probeStore.data.map {
        when (it[KMode]) {
            "background" -> RunMode.Background
            "scheduled"  -> RunMode.Scheduled
            else -> RunMode.Foreground
        }
    }
    suspend fun setMode(m: RunMode) = context.probeStore.edit {
        it[KMode] = when (m) {
            RunMode.Foreground -> "foreground"
            RunMode.Background -> "background"
            RunMode.Scheduled  -> "scheduled"
        }
    }

    val mask: Flow<MaskMode> = context.probeStore.data.map { MaskMode.fromRaw(it[KMaskMode]) }
    suspend fun setMask(m: MaskMode) = context.probeStore.edit { it[KMaskMode] = m.toRaw() }

    val delayMs: Flow<Long> = context.probeStore.data.map { it[KDelayMs] ?: 1_000L }
    suspend fun setDelayMs(v: Long) = context.probeStore.edit { it[KDelayMs] = v }

    val timeoutMs: Flow<Long> = context.probeStore.data.map { it[KTimeoutMs] ?: 15_000L }
    suspend fun setTimeoutMs(v: Long) = context.probeStore.edit { it[KTimeoutMs] = v }

    val theme: Flow<ThemePref> = context.probeStore.data.map {
        when (it[KTheme]) { "light" -> ThemePref.Light; "dark" -> ThemePref.Dark; else -> ThemePref.System }
    }
    suspend fun setTheme(p: ThemePref) = context.probeStore.edit {
        it[KTheme] = when (p) { ThemePref.Light -> "light"; ThemePref.Dark -> "dark"; ThemePref.System -> "system" }
    }

    val rotation: Flow<LogRotation> = context.probeStore.data.map {
        if (it[KRotation] == "perRun") LogRotation.PerRun else LogRotation.Append
    }
    suspend fun setRotation(r: LogRotation) = context.probeStore.edit {
        it[KRotation] = if (r == LogRotation.PerRun) "perRun" else "append"
    }

    val uploadDir: Flow<String> = context.probeStore.data.map { it[KUploadDir] ?: "EtabliProbe" }
    suspend fun setUploadDir(v: String) = context.probeStore.edit { it[KUploadDir] = v.trim() }
}
