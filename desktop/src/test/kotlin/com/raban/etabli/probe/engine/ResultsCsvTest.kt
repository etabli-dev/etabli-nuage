// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

class ResultsCsvTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = listOf(
        CheckResult(2, "https://a.example/", "background", "200", "https://a.example/", 41, Date(0), Date(0), "install"),
        CheckResult(3, "https://b.example/x,y", "background", "FAILED: timeout (15000ms)", null, 15000, Date(1_500), Date(1_500), "install"),
        CheckResult(4, "say \"hi\"\nthere", "background", "SKIPPED: invalid URL", null, 0, Date(0), Date(0), "install"),
    )

    @Test fun usesTheMobileLogHeaderAndColumnOrder() {
        val text = ResultsCsv.encode(emptyList())
        assertEquals("row_index,url,mode,result,final_url,duration_ms,timestamp_local,timestamp_utc,install_id\n", text)
    }

    @Test fun encodesRowsWithQuotingAndIsoTimestamps() {
        val lines = ResultsCsv.encode(sample, ZoneId.of("Europe/Berlin")).split('\n')
        assertEquals(
            "2,https://a.example/,background,200,https://a.example/,41,1970-01-01T01:00:00+01:00,1970-01-01T00:00:00Z,install",
            lines[1],
        )
        assertEquals(
            "3,\"https://b.example/x,y\",background,FAILED: timeout (15000ms),,15000,1970-01-01T01:00:01.5+01:00,1970-01-01T00:00:01.500Z,install",
            lines[2],
        )
        assertTrue(ResultsCsv.encode(sample).contains("4,\"say \"\"hi\"\"\nthere\",background,SKIPPED: invalid URL,,0,"))
    }

    @Test fun exportRoundTripsThroughTheCsvReader() {
        val target = tmp.root.toPath().resolve("results.csv")
        ResultsCsv.write(sample, target, ZoneOffset.UTC)
        val parsed = CsvLoader.parse(Files.readAllBytes(target), hasHeader = true)
        assertEquals(CheckResult.csvHeader, parsed.headers)
        assertEquals(sample.map { it.toCsvRow(ZoneOffset.UTC) }, parsed.rows)
        assertEquals(listOf("results.csv"), tmp.root.list()!!.toList())
    }

    @Test fun replacesAnExistingFileAndLeavesNoTemporaryFiles() {
        val target = tmp.root.toPath().resolve("out.csv")
        Files.writeString(target, "old content that is longer than the new export would be ".repeat(20))
        ResultsCsv.write(sample.take(1), target)
        assertEquals(2, Files.readAllLines(target).size)
        assertEquals(listOf("out.csv"), tmp.root.list()!!.toList())
    }

    @Test fun failedExportKeepsTheTargetUntouchedAndCleansUp() {
        // A non-empty directory at the target path cannot be replaced by a file.
        val target = tmp.root.toPath().resolve("taken")
        Files.createDirectories(target.resolve("child"))
        try { ResultsCsv.write(sample, target); fail("Export over a directory succeeded") } catch (_: java.io.IOException) { }
        assertTrue(Files.isDirectory(target.resolve("child")))
        assertEquals(listOf("taken"), tmp.root.list()!!.toList())
    }
}
