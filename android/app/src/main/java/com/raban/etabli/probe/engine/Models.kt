// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Data models — pure value types.

data class UrlEntry(val rowIndex: Int, val raw: String, val normalized: String?) {
    val valid: Boolean get() = normalized != null
}

enum class RunMode { Foreground, Background, Scheduled }
enum class LogRotation { Append, PerRun }

data class WebDavCreds(val baseURL: String, val username: String, val appPassword: String)

data class CheckResult(
    val rowIndex: Int,
    val url: String,
    val mode: String,          // "foreground" | "background"
    val result: String,        // "200" | "FAILED: …" | "LOADED" | …
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

    fun toCsvRow(): List<String> = listOf(
        rowIndex.toString(),
        url,
        mode,
        result,
        finalURL.orEmpty(),
        durationMs?.toString().orEmpty(),
        iso.format(timestampLocal),
        utcIso.format(timestampUtc),
        installId,
    )

    companion object {
        val csvHeader = listOf(
            "row_index","url","mode","result","final_url",
            "duration_ms","timestamp_local","timestamp_utc","install_id",
        )
        private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        private val utcIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
