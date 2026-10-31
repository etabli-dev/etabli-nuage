package com.raban.etabli.probe.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class InputTest {
    @Test fun preservesHeaderlessFirstRecordAndAllowsExplicitHeaderSwitch() {
        val input = CsvLoader.parse("example.org,first\rsecond.example,second\r".toByteArray())
        assertFalse(input.hasHeader)
        assertEquals(listOf("example.org", "second.example"), input.column(0))
        assertEquals(listOf("second.example"), input.withHeader(true).column(0))
        assertEquals(input.rows, input.withHeader(true).withHeader(false).rows)
    }
    @Test fun readsBomQuotedSeparatorsMultilineAndEscapedQuotes() {
        val input = CsvLoader.parse("\uFEFFurl;note\r\nhttps://example.org;\"many,commas,inside,quotes\nwith \"\"quoted\"\" text\"\r\n".toByteArray())
        assertTrue(input.hasHeader)
        assertEquals(listOf("url", "note"), input.headers)
        assertEquals("many,commas,inside,quotes\nwith \"quoted\" text", input.rows.single()[1])
        assertThrows(IllegalArgumentException::class.java) { CsvLoader.parse("\"unclosed".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { input.column(-1) }
    }
    @Test fun boundsBytesAndRaggedExpansionWithoutTruncating() {
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readBounded(2))
        assertThrows(IllegalArgumentException::class.java) { ByteArrayInputStream(ByteArray(3)).readBounded(2) }
        assertThrows(IllegalArgumentException::class.java) { CsvLoader.parse(ByteArray(MAX_CSV_BYTES + 1)) }
        val ragged = (List(1024) { "a" }.joinToString(",") + "\n" + "https://example.org\n".repeat(1000)).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { CsvLoader.parse(ragged) }
    }
    @Test fun normalizesHttpWithoutChangingEscapedPathsAndRejectsEmbeddedSecrets() {
        assertEquals("https://example.org/a%20b?q=x%2By", UrlNormalize.normalize(" HTTPS://example.org/a%20b?q=x%2By "))
        assertEquals("https://example.org", UrlNormalize.normalize("example.org"))
        for (bad in listOf("https://u:p@example.org", "ftp://example.org", "javascript:alert(1)", "https://example.org/a b")) assertNull(UrlNormalize.normalize(bad))
    }
    @Test fun logsBecomeVisibleOnlyAfterDurableWriteAndRunsHaveUniqueFiles() {
        val dir = Files.createTempDirectory("nuage-log-test").toFile()
        try {
            val first = CheckLogger(dir, LogRotation.PerRun)
            val second = CheckLogger(dir, LogRotation.PerRun)
            assertNotEquals(first.currentFile, second.currentFile)
            val r = CheckResult(1, "https://example.org/a,b", "background", "200", null, 1, Date(0), Date(0), "test-install")
            first.append(r)
            assertEquals(listOf(r), first.all())
            val csv = CsvLoader.parse(first.currentFile.readBytes(), hasHeader = true)
            assertEquals(r.url, csv.rows.single()[1])
            second.currentFile.mkdir() // Force a real filesystem write failure.
            assertThrows(java.io.FileNotFoundException::class.java) { second.append(r) }
            assertTrue(second.all().isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
