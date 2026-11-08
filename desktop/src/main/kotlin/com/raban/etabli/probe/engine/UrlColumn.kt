// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.engine

// The column choice and row numbering used by the mobile Source screens, extracted
// into plain functions so the desktop UI and its tests share one implementation.

/** Best guess for the URL column: the first column with at least one URL-looking value. */
fun CsvPreview.suggestedUrlColumn(): Int? =
    headers.indices.firstOrNull { i -> column(i).any { UrlNormalize.normalize(it) != null } }

/**
 * Turns one column into check entries. `rowIndex` is the 1-based source record number,
 * so a header record shifts data records to start at 2.
 */
fun CsvPreview.entries(columnIndex: Int): List<UrlEntry> =
    column(columnIndex).mapIndexed { i, raw ->
        UrlEntry(rowIndex = i + if (hasHeader) 2 else 1, raw = raw, normalized = UrlNormalize.normalize(raw))
    }
