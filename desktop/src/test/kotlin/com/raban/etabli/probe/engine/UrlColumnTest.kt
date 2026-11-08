// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlColumnTest {
    @Test fun suggestsTheFirstColumnHoldingAUrl() {
        val p = CsvLoader.parse("id,label,url\n1,first,https://a.example\n2,second,b.example\n".toByteArray(), hasHeader = true)
        assertEquals(2, p.suggestedUrlColumn())
        // One URL-looking value is enough, as on mobile.
        val sparse = CsvLoader.parse("name;homepage\nA;\nB;b.example\n".toByteArray(), hasHeader = true)
        assertEquals(1, sparse.suggestedUrlColumn())
    }

    @Test fun suggestsNothingWhenNoColumnHoldsAUrl() {
        assertNull(CsvLoader.parse("a,b\n1,2\n".toByteArray()).suggestedUrlColumn())
        assertNull(CsvLoader.parse(ByteArray(0)).suggestedUrlColumn())
    }

    @Test fun headerRecordIsNotTreatedAsData() {
        // "website" is a known header name, so the first record becomes the header, not a check.
        val p = CsvLoader.parse("website\nhttps://a.example\n".toByteArray())
        assertTrue(p.hasHeader)
        assertEquals(0, p.suggestedUrlColumn())
        assertEquals(listOf("https://a.example"), p.entries(0).map { it.raw })
    }

    @Test fun entriesCarryOneBasedSourceRecordNumbersAndValidity() {
        val csv = "url,label\nhttps://a.example,A\nnot a url,B\nb.example,C\n".toByteArray()
        val withHeader = CsvLoader.parse(csv)
        val entries = withHeader.entries(0)
        assertEquals(listOf(2, 3, 4), entries.map { it.rowIndex })
        assertEquals(listOf(true, false, true), entries.map { it.valid })
        assertEquals("https://b.example", entries[2].normalized)
        assertNull(entries[1].normalized)

        val headerless = withHeader.withHeader(false).entries(0)
        assertEquals(listOf(1, 2, 3, 4), headerless.map { it.rowIndex })
        assertFalse(headerless.first().valid) // "url" is not a URL
    }

    @Test fun multilineRecordsCountAsOneRecord() {
        val p = CsvLoader.parse("url,note\nhttps://a.example,\"two\nlines\"\nhttps://b.example,x\n".toByteArray())
        assertEquals(listOf(2, 3), p.entries(0).map { it.rowIndex })
    }
}
