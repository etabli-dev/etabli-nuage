// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.engine

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.ZoneId
import java.util.UUID

/**
 * Results CSV in the mobile log format: the same header, column order and quoting as the
 * Android CheckLogger, UTF-8 without BOM, LF line endings.
 */
object ResultsCsv {
    fun encode(results: List<CheckResult>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append(CheckResult.csvHeader.joinToString(",")).append('\n')
        for (r in results) append(r.toCsvRow(zone).joinToString(",", transform = ::escape)).append('\n')
    }

    fun escape(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
            "\"${s.replace("\"", "\"\"")}\""
        } else s

    /**
     * Writes the CSV next to [target] first, flushes it to disk, then moves it into place, so
     * an interrupted export never leaves a truncated file at the chosen path.
     */
    fun write(results: List<CheckResult>, target: Path, zone: ZoneId = ZoneId.systemDefault()) {
        val bytes = encode(results, zone).toByteArray(Charsets.UTF_8)
        val absolute = target.toAbsolutePath()
        val dir = absolute.parent ?: throw IllegalArgumentException("Choose a file inside a folder")
        val temp = dir.resolve(".${absolute.fileName}.${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
