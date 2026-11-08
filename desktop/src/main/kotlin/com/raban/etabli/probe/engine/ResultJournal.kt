// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.engine

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZoneId
import java.util.UUID

/** One durable CSV per run. Prior runs are never truncated or reused. */
class ResultJournal(private val directory: Path) {
    fun savedRuns(): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("nuage-") && it.fileName.toString().endsWith(".csv") }
                .sorted(compareByDescending { Files.getLastModifiedTime(it).toMillis() }).toList()
        }
    }

    fun create(): Path {
        Files.createDirectories(directory)
        val path = directory.resolve("nuage-${UUID.randomUUID()}.csv")
        write(path, CheckResult.csvHeader.joinToString(",") + "\n", create = true)
        return path
    }

    fun append(path: Path, result: CheckResult, zone: ZoneId = ZoneId.systemDefault()) {
        write(path, result.toCsvRow(zone).joinToString(",", transform = ResultsCsv::escape) + "\n")
    }

    private fun write(path: Path, text: String, create: Boolean = false) {
        val options = if (create) arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            else arrayOf(StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        FileChannel.open(path, *options).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }
}
