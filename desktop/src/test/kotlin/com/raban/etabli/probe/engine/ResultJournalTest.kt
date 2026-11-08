// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.engine

import java.nio.file.Files
import java.util.Date
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResultJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun completedRowsRemainReadableAcrossJournalInstancesAndRuns() {
        val dir = tmp.root.toPath().resolve("logs")
        val first = ResultJournal(dir)
        val path = first.create()
        val row = CheckResult(2, "https://example.test/a,b", "background", "200", null, 10,
            Date(0), Date(0), "test-install")
        first.append(path, row)
        // Opening another process/run must never truncate the previous journal.
        val second = ResultJournal(dir)
        val other = second.create()
        second.append(other, row.copy(rowIndex = 3))
        val saved = CsvLoader.parse(Files.readAllBytes(path), hasHeader = true)
        assertEquals(CheckResult.csvHeader, saved.headers)
        assertEquals(listOf("2"), saved.column(0))
        assertEquals(listOf(row.url), saved.column(1))
        assertNotEquals(path, other)
        assertEquals(setOf(path, other), ResultJournal(dir).savedRuns().toSet())
        assertEquals(listOf("3"), CsvLoader.parse(Files.readAllBytes(other), hasHeader = true).column(0))
    }

    @Test fun unwritableLogLocationFailsInsteadOfClaimingPersistence() {
        val file = tmp.newFile("not-a-directory").toPath()
        assertThrows(Exception::class.java) { ResultJournal(file).create() }
    }
}
