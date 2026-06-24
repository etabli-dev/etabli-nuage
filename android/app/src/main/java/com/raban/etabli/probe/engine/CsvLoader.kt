// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

// CSV loader. Accepts comma OR semicolon (the two real-world "Save as CSV"
// dialects depending on locale). Handles double-quoted fields with escaped
// quotes. Returns the headers + rows so the UI can show a column picker.

data class CsvPreview(val headers: List<String>, val rows: List<List<String>>) {
    fun column(idx: Int): List<String> = rows.map { if (idx < it.size) it[idx] else "" }
}

object CsvLoader {
    fun parse(bytes: ByteArray): CsvPreview {
        val text = decodeText(bytes)
        val delim = detectDelimiter(text)
        val rows = tokenize(text, delim)
        if (rows.isEmpty()) return CsvPreview(emptyList(), emptyList())
        val cols = rows.maxOf { it.size }
        val padded = rows.map { r -> r + List(cols - r.size) { "" } }
        return if (looksLikeHeader(padded.first())) {
            val headers = padded.first().map { if (it.isBlank()) "(blank)" else it.trim() }
            CsvPreview(headers, padded.drop(1))
        } else {
            CsvPreview(List(cols) { "col ${it + 1}" }, padded)
        }
    }

    private fun decodeText(bytes: ByteArray): String =
        try { String(bytes, Charsets.UTF_8) } catch (_: Throwable) { String(bytes, Charsets.ISO_8859_1) }

    private fun detectDelimiter(text: String): Char {
        val head = text.take(8192)
        val commas = head.count { it == ',' }
        val semis  = head.count { it == ';' }
        val tabs   = head.count { it == '\t' }
        return listOf(',' to commas, ';' to semis, '\t' to tabs).maxByOrNull { it.second }?.first ?: ','
    }

    private fun looksLikeHeader(row: List<String>): Boolean {
        val nonEmpty = row.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return false
        val headerish = nonEmpty.count { v ->
            val lower = v.lowercase()
            val url = lower.startsWith("http://") || lower.startsWith("https://") || v.contains("://")
            val num = v.toDoubleOrNull() != null
            !url && !num && v.length <= 64
        }
        return headerish.toDouble() / nonEmpty.size >= 0.6
    }

    private fun tokenize(text: String, delim: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i += 2; continue
                    }
                    inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                delim -> { current += field.toString(); field.clear() }
                '\r' -> { /* swallow */ }
                '\n' -> { current += field.toString(); field.clear(); rows += current; current = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) { current += field.toString(); rows += current }
        return rows
    }
}

object UrlNormalize {
    private val schemeRe = Regex("^https?://", RegexOption.IGNORE_CASE)
    fun normalize(raw: String): String? {
        val t = raw.trim().trim('"')
        if (t.isEmpty()) return null
        val candidate = when {
            schemeRe.containsMatchIn(t) -> t
            t.startsWith("//") -> "https:$t"
            t.contains(' ') || t.contains('\n') -> return null
            t.contains('.') && !t.startsWith('.') -> "https://$t"
            else -> return null
        }
        return try {
            val uri = java.net.URI(candidate)
            if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()) null
            else uri.toString()
        } catch (_: Throwable) { null }
    }
}
