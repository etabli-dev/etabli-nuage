// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.raban.etabli.probe.EtabliProbeApplication
import com.raban.etabli.probe.engine.BackgroundChecker
import com.raban.etabli.probe.engine.CheckLogger
import com.raban.etabli.probe.engine.CheckResult
import com.raban.etabli.probe.engine.MaskMode
import com.raban.etabli.probe.engine.RunMode
import com.raban.etabli.probe.engine.UsageProfile
import com.raban.etabli.probe.engine.UsageProfileStore
import com.raban.etabli.probe.engine.USER_AGENT
import com.raban.etabli.probe.engine.UrlEntry
import com.raban.etabli.probe.ui.RunState
import com.raban.etabli.probe.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

@Composable
fun RunScreen(app: EtabliProbeApplication, onSeeResults: () -> Unit) {
    val t = Coder.tokens
    val scope = rememberCoroutineScope()

    val mode by app.settings.mode.collectAsState(initial = RunMode.Foreground)
    val delayMs by app.settings.delayMs.collectAsState(initial = 1000L)
    val timeoutMs by app.settings.timeoutMs.collectAsState(initial = 15_000L)
    val rotation by app.settings.rotation.collectAsState(initial = com.raban.etabli.probe.engine.LogRotation.Append)
    val mask by app.settings.mask.collectAsState(initial = MaskMode.None)
    val profileStore = remember { UsageProfileStore(app) }
    var profile by remember { mutableStateOf<UsageProfile?>(null) }
    LaunchedEffect(Unit) { profile = profileStore.load() }

    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var pass by remember { mutableIntStateOf(0) }
    var fail by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<String?>(null) }
    var runJob by remember { mutableStateOf<Job?>(null) }
    var lastProb by remember { mutableStateOf<Double?>(null) }
    var lastTickMinute by remember { mutableIntStateOf(0) }

    val entries = RunState.prepared

    // Foreground WebView holder
    val pageCompleter = remember { mutableStateOf<CompletableDeferred<Pair<String, String?>>?>(null) }
    var lastFinalUrl by remember { mutableStateOf<String?>(null) }
    val webView = remember {
        WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    lastFinalUrl = url
                    pageCompleter.value?.complete("LOADED" to url)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, err: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        pageCompleter.value?.complete("LOAD_ERROR: ${err?.description}" to url)
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        runJob?.cancel()
    }

    fun startForeground() {
        if (entries.isEmpty()) return
        val logger = CheckLogger(app, rotation)
        RunState.logger = logger
        total = entries.size; done = 0; pass = 0; fail = 0; current = null
        running = true; paused = false
        runJob = scope.launch {
            try {
                for ((i, entry) in entries.withIndex()) {
                    while (paused && running) delay(200)
                    if (!running) break
                    current = "opening ${entry.normalized ?: entry.raw}"
                    val res = if (!entry.valid) {
                        CheckResult(
                            entry.rowIndex, entry.raw, "foreground",
                            "SKIPPED: invalid URL", null, 0L,
                            Date(), Date(), app.installId,
                        )
                    } else {
                        val started = System.currentTimeMillis()
                        val def = CompletableDeferred<Pair<String, String?>>()
                        pageCompleter.value = def
                        lastFinalUrl = null
                        webView.loadUrl(entry.normalized!!)
                        val outcome = withTimeoutOrNull(timeoutMs) { def.await() } ?: ("TIMEOUT" to null)
                        pageCompleter.value = null
                        val elapsed = System.currentTimeMillis() - started
                        CheckResult(
                            entry.rowIndex, entry.normalized, "foreground",
                            outcome.first, outcome.second ?: lastFinalUrl, elapsed,
                            Date(), Date(), app.installId,
                        )
                    }
                    logger.append(res)
                    if (res.pass) pass++ else fail++
                    done = i + 1
                    if (i + 1 < entries.size) delay(delayMs)
                }
            } catch (_: CancellationException) { /* user stop */ }
            running = false; paused = false; current = null
        }
    }

    fun startScheduled() {
        val valid = entries.filter { it.valid }
        if (valid.isEmpty()) return
        val snapshot = profile
        if (snapshot == null || snapshot.isEmpty) {
            current = "Import a usage-profile TXT in Settings first."; return
        }
        val logger = CheckLogger(app, rotation)
        RunState.logger = logger
        total = valid.size; done = 0; pass = 0; fail = 0; current = null
        lastProb = null; lastTickMinute = 0
        running = true; paused = false
        val activeMask = mask
        runJob = scope.launch {
            try {
                val queue = valid.toMutableList()
                while (queue.isNotEmpty() && running) {
                    while (paused && running) delay(200)
                    if (!running) break

                    val cal = java.util.Calendar.getInstance()
                    val minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                                      cal.get(java.util.Calendar.MINUTE)
                    val weekday = cal.get(java.util.Calendar.DAY_OF_WEEK)
                    val p = snapshot.probability(minuteOfDay, weekday, activeMask)
                    val open = kotlin.random.Random.nextDouble() < p

                    lastTickMinute = minuteOfDay
                    lastProb = p
                    current = "minute %s  p=%.3f  %s".format(
                        UsageProfile.hhmm(minuteOfDay), p,
                        if (open) "→ opening" else "skipped this minute"
                    )

                    if (open) {
                        val entry = queue.removeAt(0)
                        val started = System.currentTimeMillis()
                        val def = CompletableDeferred<Pair<String, String?>>()
                        pageCompleter.value = def
                        lastFinalUrl = null
                        webView.loadUrl(entry.normalized!!)
                        val outcome = withTimeoutOrNull(timeoutMs) { def.await() } ?: ("TIMEOUT" to null)
                        pageCompleter.value = null
                        val elapsed = System.currentTimeMillis() - started
                        val res = CheckResult(
                            entry.rowIndex, entry.normalized, "foreground",
                            outcome.first, outcome.second ?: lastFinalUrl, elapsed,
                            Date(), Date(), app.installId,
                        )
                        logger.append(res)
                        if (res.pass) pass++ else fail++
                        done = valid.size - queue.size
                    }
                    // Sleep until just after the next wall-clock minute boundary.
                    val nowMs = System.currentTimeMillis()
                    val next = (nowMs / 60_000 + 1) * 60_000 + 500
                    delay((next - nowMs).coerceAtLeast(1_000))
                }
            } catch (_: CancellationException) { /* user stop */ }
            running = false; paused = false; current = null
        }
    }

    fun startBackground() {
        if (entries.isEmpty()) return
        val logger = CheckLogger(app, rotation)
        RunState.logger = logger
        total = entries.size; done = 0; pass = 0; fail = 0
        running = true; paused = false
        runJob = scope.launch {
            val checker = BackgroundChecker(timeoutMs = timeoutMs, installId = app.installId)
            try {
                for ((i, entry) in entries.withIndex()) {
                    while (paused && running) delay(200)
                    if (!running) break
                    val res = checker.probe(entry)
                    logger.append(res)
                    if (res.pass) pass++ else fail++
                    done = i + 1
                    if (i + 1 < entries.size) delay(delayMs)
                }
            } catch (_: CancellationException) { /* user stop */ }
            running = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("probe", "run", when (mode) {
            RunMode.Foreground -> "foreground"
            RunMode.Background -> "background"
            RunMode.Scheduled  -> "scheduled"
        }))

        if (entries.isEmpty()) {
            Card(title = "no source loaded") {
                MonoLabel("Pick a CSV in Source first.", color = t.color.faint)
            }
        } else {
            Card(title = "ready", icon = Icons.Default.PlayArrow) {
                val valid = entries.count { it.valid }
                MonoLabel("${entries.size} rows · $valid valid", color = t.color.faint)
                MonoLabel("delay ${delayMs}ms · timeout ${timeoutMs / 1000}s", color = t.color.faint)
            }
        }

        if (running || done > 0) {
            Card(title = "progress", icon = Icons.Default.PlayArrow) {
                MonoLabel("$done of $total")
                Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                    StatusLabel("pass $pass", tone = StatusTone.Accent)
                    StatusLabel("fail $fail", tone = if (fail > 0) StatusTone.Danger else StatusTone.Info)
                }
                current?.let { MonoLabel(it, color = t.color.faint) }
            }
        }

        when (mode) {
            RunMode.Foreground, RunMode.Scheduled -> {
                if (mode == RunMode.Scheduled) {
                    Card(title = "usage-profile schedule", icon = Icons.Default.Info) {
                        MonoLabel(
                            "Every wall-clock minute we look up p = profile[minute-of-day], apply " +
                            "the active mask, and roll Bernoulli(p). On heads, the next URL is " +
                            "opened via the WebView probe. Loop ends when the URL list is empty.",
                            color = t.color.faint,
                        )
                        val snap = profile
                        if (snap == null || snap.isEmpty) {
                            StatusLabel("⚠ no profile loaded — import a TXT in Settings.",
                                        tone = StatusTone.Warn)
                        } else {
                            MonoLabel("source · ${snap.sourceName ?: "—"}", color = t.color.ink)
                            MonoLabel("days · ${snap.totalDays} · records · ${snap.totalRecords}",
                                      color = t.color.faint)
                            val pp = snap.perMinute[snap.peakMinute]
                            MonoLabel("peak · ${UsageProfile.hhmm(snap.peakMinute)} (p=${"%.3f".format(pp)})",
                                      color = t.color.faint)
                        }
                        MonoLabel("mask · ${mask.label}", color = t.color.faint)
                        lastProb?.let { p ->
                            StatusLabel(
                                "last p(minute ${UsageProfile.hhmm(lastTickMinute)}) = ${"%.3f".format(p)}",
                                tone = if (p > 0) StatusTone.Accent else StatusTone.Info
                            )
                        }
                    }
                }
                Card(title = "webview", icon = Icons.Default.PlayArrow) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(t.radius.sm))
                            .border(1.dp, t.color.hairline, RoundedCornerShape(t.radius.sm)),
                    ) {
                        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            RunMode.Background -> {
                Card(title = "OS budget — read this", icon = Icons.Default.Info) {
                    MonoLabel(
                        "Background mode runs sequential URLSession-style HTTP GETs " +
                        "while the app is in the foreground. For true unattended " +
                        "background sweeps a WorkManager queue would have to be " +
                        "scheduled — out of scope for this PoC (see SUBMISSION.md).",
                        color = t.color.faint,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(t.space.md)) {
            if (!running) {
                PrimaryButton("Start", icon = Icons.Default.PlayArrow,
                              enabled = entries.any { it.valid }) {
                    when (mode) {
                        RunMode.Foreground -> startForeground()
                        RunMode.Background -> startBackground()
                        RunMode.Scheduled  -> startScheduled()
                    }
                }
            } else {
                PrimaryButton(if (paused) "Resume" else "Pause",
                              icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause) {
                    paused = !paused
                }
                PrimaryButton("Stop", icon = Icons.Default.Stop) { stop() }
            }
            PrimaryButton("See log", icon = Icons.Default.TableChart, onClick = onSeeResults)
        }
    }
}
