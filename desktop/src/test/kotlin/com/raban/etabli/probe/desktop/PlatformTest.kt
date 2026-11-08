// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlatformTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun droppedFileUrisBecomeLocalPaths() {
        val spaced = tmp.newFile("links list.csv").toPath()
        val second = tmp.newFile("b.csv").toPath()
        // Desktop drag and drop delivers percent-encoded file URIs.
        assertEquals(spaced, firstDroppedFile(listOf(spaced.toUri().toString())))
        assertEquals(spaced, firstDroppedFile(listOf(spaced.toUri().toString(), second.toUri().toString())))
        assertEquals(second, firstDroppedFile(listOf(second.toAbsolutePath().toString())))
    }

    @Test fun nonFileItemsAreIgnored() {
        val csv = tmp.newFile("c.csv").toPath()
        assertNull(firstDroppedFile(emptyList()))
        assertNull(firstDroppedFile(listOf("https://example.org/a.csv", "relative/a.csv")))
        assertEquals(csv, firstDroppedFile(listOf("https://example.org/a.csv", csv.toUri().toString())))
    }
}
