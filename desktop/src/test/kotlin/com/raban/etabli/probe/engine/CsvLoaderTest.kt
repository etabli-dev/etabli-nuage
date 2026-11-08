// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CsvLoaderTest {
    // The first five cases mirror android/.../engine/InputTest.kt so both engines stay in step.

    @Test fun preservesHeaderlessFirstRecordAndAllowsExplicitHeaderSwitch() {
        val input = CsvLoader.parse("example.org,first\rsecond.example,second\r".toByteArray())
        assertFalse(input.hasHeader)
        assertEquals(listOf("col 1", "col 2"), input.headers)
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
        assertEquals("https://example.org/x", UrlNormalize.normalize("//example.org/x"))
        assertEquals("http://example.org", UrlNormalize.normalize("\"http://example.org\""))
        for (bad in listOf("https://u:p@example.org", "ftp://example.org", "javascript:alert(1)", "https://example.org/a b", "", ".example", "no-dot"))
            assertNull(bad, UrlNormalize.normalize(bad))
    }

    @Test fun detectsTabAndSemicolonDelimitersOutsideQuotes() {
        val tab = CsvLoader.parse("link\tlabel\nhttps://a.example\tA\n".toByteArray())
        assertEquals(listOf("link", "label"), tab.headers)
        // Commas inside quotes must not outvote the real semicolon delimiter.
        val semi = CsvLoader.parse("url;label\n\"https://a.example/?x=1,2,3,4\";A\n".toByteArray())
        assertEquals(listOf("https://a.example/?x=1,2,3,4"), semi.column(0))
    }

    @Test fun fallsBackToLatin1AndPadsRaggedRows() {
        val latin1 = "url,ville\nhttps://a.example,Orl\u00e9ans\nhttps://b.example\n".toByteArray(Charsets.ISO_8859_1)
        val parsed = CsvLoader.parse(latin1)
        assertEquals("Orl\u00e9ans", parsed.rows[0][1])
        assertEquals(listOf("https://b.example", ""), parsed.rows[1])
    }

    @Test fun infersHeadersOnlyFromKnownUrlColumnNames() {
        for (name in listOf("URL", " links ", "Website", "Webadresse", "address")) {
            assertTrue(name, CsvLoader.parse("$name,x\nhttps://a.example,1\n".toByteArray()).hasHeader)
        }
        assertFalse(CsvLoader.parse("name,site\nA,https://a.example\n".toByteArray()).hasHeader)
        assertTrue(CsvLoader.parse("name,site\nA,https://a.example\n".toByteArray(), hasHeader = true).hasHeader)
        val blank = CsvLoader.parse(",url\n1,https://a.example\n".toByteArray())
        assertEquals(listOf("(blank)", "url"), blank.headers)
    }

    @Test fun emptyInputHasNoColumns() {
        val empty = CsvLoader.parse(ByteArray(0))
        assertTrue(empty.headers.isEmpty())
        assertTrue(empty.rows.isEmpty())
    }
}
