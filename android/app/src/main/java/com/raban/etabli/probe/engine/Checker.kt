// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.TimeZone

// User-agent matches the iOS twin — server logs see one identity.
const val USER_AGENT = "EtabliProbe/1.0 (+QA link-checker; sequential)"

class BackgroundChecker(
    private val timeoutMs: Long,
    private val installId: String,
) {
    suspend fun probe(entry: UrlEntry): CheckResult = withContext(Dispatchers.IO) {
        val now = Date()
        val nowUtc = Date(now.time)
        TimeZone.getTimeZone("UTC")
        if (!entry.valid) {
            return@withContext CheckResult(
                rowIndex = entry.rowIndex, url = entry.raw, mode = "background",
                result = "SKIPPED: invalid URL",
                finalURL = null, durationMs = 0L,
                timestampLocal = now, timestampUtc = nowUtc, installId = installId,
            )
        }
        val started = System.currentTimeMillis()
        try {
            withTimeout(timeoutMs + 1_000) {
                val (code, finalUrl) = probeOnce(entry.normalized!!)
                val elapsed = System.currentTimeMillis() - started
                CheckResult(
                    rowIndex = entry.rowIndex, url = entry.normalized,
                    mode = "background", result = code.toString(),
                    finalURL = finalUrl, durationMs = elapsed,
                    timestampLocal = Date(), timestampUtc = Date(),
                    installId = installId,
                )
            }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            val elapsed = System.currentTimeMillis() - started
            CheckResult(
                rowIndex = entry.rowIndex, url = entry.normalized ?: entry.raw,
                mode = "background", result = "FAILED: timeout (${timeoutMs / 1000}s)",
                finalURL = null, durationMs = elapsed,
                timestampLocal = Date(), timestampUtc = Date(),
                installId = installId,
            )
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - started
            CheckResult(
                rowIndex = entry.rowIndex, url = entry.normalized ?: entry.raw,
                mode = "background", result = "FAILED: ${t.message ?: t::class.simpleName}",
                finalURL = null, durationMs = elapsed,
                timestampLocal = Date(), timestampUtc = Date(),
                installId = installId,
            )
        }
    }

    /// Hand-rolled redirect follower so we capture the *final* URL the same
    /// way URLSession does on iOS. The platform HttpURLConnection only
    /// auto-follows http→http or https→https, so we drive it ourselves.
    private fun probeOnce(start: String): Pair<Int, String?> {
        var current = start
        repeat(10) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location") ?: return code to current
                    current = if (next.startsWith("http")) next else URL(URL(current), next).toString()
                } else {
                    // drain body to free the connection
                    try { conn.inputStream.use { it.readBytes() } } catch (_: Throwable) {
                        conn.errorStream?.use { it.readBytes() }
                    }
                    return code to current
                }
            } finally { conn.disconnect() }
        }
        return 0 to current
    }
}
