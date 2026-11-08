// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.URI

// Ported unchanged from android/app/src/main/java/com/raban/etabli/probe/engine/CsvLoader.kt.
// Keep both copies identical until the planned shared/ module replaces them.

const val MAX_CSV_BYTES = 10 * 1024 * 1024

data class CsvPreview(
    val headers: List<String>, val rows: List<List<String>>,
    val sourceRows: List<List<String>> = rows, val hasHeader: Boolean = false,
) {
    fun column(idx: Int): List<String> {
        require(idx in headers.indices) { "Choose an existing CSV column" }
        return rows.map { it.getOrElse(idx) { "" } }
    }
    fun withHeader(enabled: Boolean): CsvPreview {
        if (sourceRows.isEmpty()) return CsvPreview(emptyList(), emptyList())
        val cols = sourceRows.first().size
        return CsvPreview(
            if (enabled) sourceRows.first().map { it.trim().ifBlank { "(blank)" } } else List(cols) { "col ${it + 1}" },
            if (enabled) sourceRows.drop(1) else sourceRows, sourceRows, enabled,
        )
    }
}

object CsvLoader {
    fun parse(bytes: ByteArray, hasHeader: Boolean? = null): CsvPreview {
        require(bytes.size <= MAX_CSV_BYTES) { "CSV exceeds the 10 MiB import limit" }
        val text = decodeText(bytes).removePrefix("\uFEFF")
        val rows = tokenize(text, detectDelimiter(text))
        if (rows.isEmpty()) return CsvPreview(emptyList(), emptyList())
        val cols = rows.maxOf { it.size }
        require(cols <= 1024) { "CSV has more than 1024 columns" }
        require(cols.toLong() * rows.size <= 1_000_000) { "CSV exceeds one million cells after padding" }
        val padded = rows.map { it + List(cols - it.size) { "" } }
        val knownHeaders = setOf("url", "urls", "link", "links", "website", "webseite", "webadresse", "address")
        val inferred = rows.first().any { it.trim().lowercase() in knownHeaders }
        return CsvPreview(emptyList(), padded, padded).withHeader(hasHeader ?: inferred)
    }

    private fun decodeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { String(bytes, Charsets.ISO_8859_1) }

    private fun detectDelimiter(text: String): Char {
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        var quoted = false
        var records = 0
        var i = 0
        while (i < text.length && records < 10) {
            val c = text[i]
            if (c == '"') {
                if (quoted && i + 1 < text.length && text[i + 1] == '"') i++ else quoted = !quoted
            } else if (!quoted) {
                if (c in counts) counts[c] = counts.getValue(c) + 1
                if (c == '\n' || c == '\r') records++
            }
            i++
        }
        return counts.maxBy { it.value }.key
    }

    private fun tokenize(text: String, delim: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var cells = 0
        fun finishField() {
            require(current.size < 1024 && ++cells <= 1_000_000) { "CSV exceeds the column or cell limit" }
            current += field.toString(); field.clear(); closed = false
        }
        fun finishRow() {
            finishField(); require(current.size <= 1024) { "CSV has more than 1024 columns" }
            rows += current; current = mutableListOf()
            require(rows.size <= 100_000) { "CSV has more than 100000 records" }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else field.append(c)
            } else when (c) {
                '"' -> { require(field.isEmpty() && !closed) { "Unexpected quote in CSV field" }; quoted = true }
                delim -> finishField()
                '\r', '\n' -> { finishRow(); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++ }
                else -> { require(!closed || c.isWhitespace()) { "Unexpected text after quoted CSV field" }; if (!closed) field.append(c) }
            }
            i++
        }
        require(!quoted) { "Unclosed quoted CSV field" }
        if (field.isNotEmpty() || current.isNotEmpty() || closed) finishRow()
        return rows
    }
}

object UrlNormalize {
    fun normalize(raw: String): String? {
        val text = raw.trim().trim('"')
        if (text.isEmpty() || text.any { it.isWhitespace() || it.code < 32 }) return null
        val candidate = when {
            text.startsWith("http://", true) || text.startsWith("https://", true) -> text
            text.startsWith("//") -> "https:$text"
            "://" !in text && '.' in text && !text.startsWith('.') -> "https://$text"
            else -> return null
        }
        return try {
            val uri = URI(candidate)
            if (uri.scheme.lowercase() !in listOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) null
            else uri.scheme.lowercase() + uri.toASCIIString().substring(uri.scheme.length)
        } catch (_: Exception) { null }
    }
}
