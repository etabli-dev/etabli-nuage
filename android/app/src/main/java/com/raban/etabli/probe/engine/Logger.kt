// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Append-only CSV logger. One row per check is flushed to disk before we
// move on, so a kill mid-run doesn't lose progress.

class CheckLogger(private val dir: File, private val rotation: LogRotation) {
    constructor(context: Context, rotation: LogRotation) : this(File(context.filesDir, "probe_logs"), rotation)
    init { require(dir.isDirectory || dir.mkdirs()) { "Could not create the log directory" } }
    private val runStamp: String =
        SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US).format(Date()) + "-" + java.util.UUID.randomUUID().toString().take(8)
    val currentFile: File by lazy {
        val name = if (rotation == LogRotation.PerRun) "probe_$runStamp.csv" else "probe.csv"
        File(dir, name)
    }
    private val memory = mutableListOf<CheckResult>()
    private var primed = false

    @Synchronized
    fun all(): List<CheckResult> = memory.toList()

    @Synchronized
    fun append(r: CheckResult) {
        ensureHeader()
        val line = (r.toCsvRow().map(::escape).joinToString(",")) + "\n"
        FileOutputStream(currentFile, true).use { it.write(line.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        memory += r
    }

    private fun ensureHeader() {
        if (primed) return
        if (currentFile.exists() && currentFile.length() > 0) { primed = true; return }
        val header = CheckResult.csvHeader.joinToString(",", postfix = "\n")
        FileOutputStream(currentFile, false).use { it.write(header.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        primed = true
    }

    private fun escape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"${s.replace("\"", "\"\"")}\""
        } else s
}
