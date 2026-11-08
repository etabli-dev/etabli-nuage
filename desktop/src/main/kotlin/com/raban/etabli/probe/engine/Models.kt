// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

// Data models — pure value types, ported unchanged from the Android engine so the
// desktop results CSV has the same columns and pass/fail rule as the mobile logs.

data class UrlEntry(val rowIndex: Int, val raw: String, val normalized: String?) {
    val valid: Boolean get() = normalized != null
}

data class WebDavCreds(val baseURL: String, val username: String, val appPassword: String) {
    // Keep the app password out of logs, crash reports and debugger summaries.
    override fun toString(): String = "WebDavCreds(baseURL=$baseURL, username=$username, appPassword=***)"
}

data class CheckResult(
    val rowIndex: Int,
    val url: String,
    val mode: String,          // "foreground" | "background"; the desktop runs background (HTTP status) checks
    val result: String,        // "200" | "FAILED: …" | "SKIPPED: invalid URL" | "LOADED" | …
    val finalURL: String?,
    val durationMs: Long?,
    val timestampLocal: Date,
    val timestampUtc: Date,
    val installId: String,
) {
    val pass: Boolean get() = when (mode) {
        "foreground" -> result == "LOADED"
        else -> result.toIntOrNull()?.let { it in 200..399 } == true
    }

    fun toCsvRow(zone: ZoneId = ZoneId.systemDefault()): List<String> = listOf(
        rowIndex.toString(),
        url,
        mode,
        result,
        finalURL.orEmpty(),
        durationMs?.toString().orEmpty(),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestampLocal.toInstant().atZone(zone)),
        DateTimeFormatter.ISO_INSTANT.format(timestampUtc.toInstant()),
        installId,
    )

    companion object {
        val csvHeader = listOf(
            "row_index", "url", "mode", "result", "final_url",
            "duration_ms", "timestamp_local", "timestamp_utc", "install_id",
        )
    }
}
