// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.raban.etabli.probe.EtabliProbeApplication
import com.raban.etabli.probe.engine.LogRotation
import com.raban.etabli.probe.engine.MaskMode
import com.raban.etabli.probe.engine.RunMode
import com.raban.etabli.probe.engine.UsageProfile
import com.raban.etabli.probe.engine.UsageProfileParser
import com.raban.etabli.probe.engine.UsageProfileStore
import com.raban.etabli.probe.engine.WebDavCreds
import com.raban.etabli.probe.net.WebDavClient
import com.raban.etabli.probe.prefs.ThemePref
import com.raban.etabli.probe.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    app: EtabliProbeApplication,
    themePref: ThemePref,
    onThemeChange: (ThemePref) -> Unit,
) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()
    val mode by app.settings.mode.collectAsState(initial = RunMode.Foreground)
    val delayMs by app.settings.delayMs.collectAsState(initial = 1000L)
    val timeoutMs by app.settings.timeoutMs.collectAsState(initial = 15_000L)
    val rotation by app.settings.rotation.collectAsState(initial = LogRotation.Append)
    val uploadDir by app.settings.uploadDir.collectAsState(initial = "EtabliProbe")

    var url by remember { mutableStateOf("https://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var dirField by remember(uploadDir) { mutableStateOf(uploadDir) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(StatusTone.Info) }

    LaunchedEffect(Unit) {
        app.creds.read()?.let {
            url = it.baseURL; user = it.username
            pass = "•••••••• (stored — re-enter to overwrite)"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("probe", "settings"))

        Card(title = "check mode", icon = Icons.Default.SwapHoriz) {
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ChipButton("foreground", mode == RunMode.Foreground) {
                    scope.launch { app.settings.setMode(RunMode.Foreground) }
                }
                ChipButton("background", mode == RunMode.Background) {
                    scope.launch { app.settings.setMode(RunMode.Background) }
                }
                ChipButton("scheduled", mode == RunMode.Scheduled) {
                    scope.launch { app.settings.setMode(RunMode.Scheduled) }
                }
            }
            MonoLabel(
                when (mode) {
                    RunMode.Foreground -> "each URL renders in a real WebView so you can watch it load."
                    RunMode.Background -> "each URL is probed with HttpURLConnection (follows redirects, captures status)."
                    RunMode.Scheduled  -> "URLs are opened on a probability schedule (see card below)."
                },
                color = t.color.faint,
            )
        }

        UsageProfileCard(app, scope)
        AccessMaskCard(app, scope)

        Card(title = "politeness", icon = Icons.Default.Timer) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                MonoLabel("request delay")
                MonoLabel("${delayMs}ms")
            }
            Slider(
                value = delayMs.toFloat(),
                onValueChange = { scope.launch { app.settings.setDelayMs(it.toLong()) } },
                valueRange = 100f..10_000f, steps = 99,
                colors = SliderDefaults.colors(thumbColor = t.color.accent,
                                               activeTrackColor = t.color.accent),
            )
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                MonoLabel("timeout")
                MonoLabel("${timeoutMs / 1000}s")
            }
            Slider(
                value = timeoutMs.toFloat(),
                onValueChange = { scope.launch { app.settings.setTimeoutMs(it.toLong()) } },
                valueRange = 3_000f..120_000f, steps = 117,
                colors = SliderDefaults.colors(thumbColor = t.color.accent,
                                               activeTrackColor = t.color.accent),
            )
        }

        Card(title = "Nextcloud", icon = Icons.Default.Cloud) {
            MonoLabel("Use an APP PASSWORD (Settings → Security → Devices & sessions in Nextcloud). NOT your login.",
                      color = t.color.faint)
            Spacer(Modifier.height(t.space.sm))
            MonoLabel("base URL", color = t.color.faint)
            TextInput(value = url, placeholder = "https://cloud.example.com", onChange = { url = it })
            Spacer(Modifier.height(t.space.sm))
            MonoLabel("username", color = t.color.faint)
            TextInput(value = user, placeholder = "username", onChange = { user = it })
            Spacer(Modifier.height(t.space.sm))
            MonoLabel("app password", color = t.color.faint)
            TextInput(value = pass, placeholder = "app password (NOT your login)",
                      onChange = { pass = it }, isSecret = true)
            Spacer(Modifier.height(t.space.sm))
            MonoLabel("upload folder for the log", color = t.color.faint)
            TextInput(value = dirField, placeholder = "EtabliProbe", onChange = { dirField = it })
            Spacer(Modifier.height(t.space.md))
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
                PrimaryButton(if (busy) "Testing…" else "Save & test",
                              icon = Icons.Default.CheckCircle, enabled = !busy) {
                    scope.launch {
                        busy = true; status = null
                        try {
                            val pw = if (pass.startsWith("••••••••")) (app.creds.read()?.appPassword ?: "") else pass
                            if (url.isBlank() || user.isBlank() || pw.isBlank()) {
                                status = "⚠ Fill in all three fields."; statusTone = StatusTone.Danger
                                return@launch
                            }
                            val creds = WebDavCreds(url.trim(), user.trim(), pw)
                            val count = WebDavClient(creds).testConnection()
                            app.creds.write(creds)
                            app.settings.setUploadDir(dirField)
                            pass = "•••••••• (stored — re-enter to overwrite)"
                            status = "OK — $count items at root. Stored in EncryptedSharedPreferences."
                            statusTone = StatusTone.Accent
                        } catch (t: Throwable) {
                            status = "⚠ ${t.message}"; statusTone = StatusTone.Danger
                        } finally { busy = false }
                    }
                }
                PrimaryButton("Disconnect", icon = Icons.AutoMirrored.Filled.Logout) {
                    app.creds.clear()
                    pass = ""
                    status = "Disconnected — creds removed."; statusTone = StatusTone.Info
                }
            }
            status?.let {
                Spacer(Modifier.height(t.space.sm))
                StatusLabel(it, tone = statusTone)
            }
        }

        Card(title = "log") {
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ChipButton("append", rotation == LogRotation.Append) {
                    scope.launch { app.settings.setRotation(LogRotation.Append) }
                }
                ChipButton("per run", rotation == LogRotation.PerRun) {
                    scope.launch { app.settings.setRotation(LogRotation.PerRun) }
                }
            }
        }

        Card(title = "appearance", icon = Icons.Default.Palette) {
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ChipButton("auto",  themePref == ThemePref.System) { onThemeChange(ThemePref.System) }
                ChipButton("light", themePref == ThemePref.Light)  { onThemeChange(ThemePref.Light) }
                ChipButton("dark",  themePref == ThemePref.Dark)   { onThemeChange(ThemePref.Dark) }
            }
        }

        Card(title = "about", icon = Icons.Default.Info) {
            Text("Établi Probe — Coder-suite link-checker.", style = t.font.body)
            MonoLabel("install id (NOT a hardware/device id):", color = t.color.faint)
            MonoLabel(app.installId, color = t.color.faint)
        }
    }
}

@Composable
private fun UsageProfileCard(app: EtabliProbeApplication, scope: kotlinx.coroutines.CoroutineScope) {
    val t = Coder.tokens
    val ctx = LocalContext.current
    val store = remember { UsageProfileStore(app) }
    var profile by remember { mutableStateOf<UsageProfile?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(StatusTone.Info) }
    LaunchedEffect(Unit) { profile = store.load() }

    val pickTxt = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?.toString(Charsets.UTF_8)
                    } ?: error("Couldn't read file.")
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.txt"
                    val parsed = withContext(Dispatchers.Default) {
                        UsageProfileParser.parse(text, sourceName = name)
                    }
                    store.save(parsed)
                    profile = parsed
                    if (parsed.totalDays == 0) {
                        status = "⚠ no valid events recognised (${parsed.skippedLines} lines skipped)."
                        statusTone = StatusTone.Danger
                    } else {
                        status = "Loaded ${parsed.totalRecords} events across ${parsed.totalDays} days."
                        statusTone = StatusTone.Accent
                    }
                } catch (e: Throwable) {
                    status = "⚠ ${e.message}"; statusTone = StatusTone.Danger
                }
            }
        }
    }

    Card(title = "usage profile", icon = Icons.Default.Insights) {
        MonoLabel(
            "Drive the 'scheduled' run mode from real smartphone use. Import a TXT log of app open/close events with timestamps; the parser builds a 1440-minute-of-day probability profile by averaging over every distinct day in the file.",
            color = t.color.faint,
        )
        val p = profile
        if (p != null && !p.isEmpty) {
            MonoLabel("source · ${p.sourceName ?: "—"}", color = t.color.ink)
            MonoLabel("records · ${p.totalRecords} parsed (${p.skippedLines} skipped)", color = t.color.faint)
            MonoLabel("days · ${p.totalDays} distinct", color = t.color.faint)
            MonoLabel("peak · ${UsageProfile.hhmm(p.peakMinute)} at p=${"%.3f".format(p.perMinute[p.peakMinute])}",
                      color = t.color.faint)
            MonoLabel("mean · ${"%.3f".format(p.mean)}", color = t.color.faint)
        } else {
            MonoLabel("no profile loaded.", color = t.color.faint)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
            PrimaryButton("Import .txt", icon = Icons.Default.FileUpload) {
                pickTxt.launch(arrayOf("text/plain", "*/*"))
            }
            PrimaryButton("Clear", icon = Icons.Default.CheckCircle) {
                store.clear(); profile = null
                status = "Profile cleared."; statusTone = StatusTone.Info
            }
        }
        status?.let { StatusLabel(it, tone = statusTone) }
    }
}

@Composable
private fun AccessMaskCard(app: EtabliProbeApplication, scope: kotlinx.coroutines.CoroutineScope) {
    val t = Coder.tokens
    val mask by app.settings.mask.collectAsState(initial = MaskMode.None)
    Card(title = "access mask", icon = Icons.Default.CalendarToday) {
        MonoLabel(
            "Multiplies the profile probability by 0 outside the chosen window. " +
            "Worktime = Mon-Fri 08:00..18:00. Off-hours = everything else.",
            color = t.color.faint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
            MaskMode.values().forEach { m ->
                ChipButton(m.label, mask == m) {
                    scope.launch { app.settings.setMask(m) }
                }
            }
        }
    }
}
