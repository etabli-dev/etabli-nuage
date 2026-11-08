// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Checks [entries] one at a time in source order, waiting [delayMs] between checks (not after
 * the last one), like the mobile run loop. Invalid entries are passed to [probe] too, which
 * records them as skipped. Cancellation stops the loop; results already delivered stay valid.
 */
suspend fun runSequentialChecks(
    entries: List<UrlEntry>,
    delayMs: Long,
    probe: suspend (UrlEntry) -> CheckResult,
    onStart: (index: Int, entry: UrlEntry) -> Unit = { _, _ -> },
    onResult: suspend (index: Int, result: CheckResult) -> Unit,
) {
    require(delayMs >= 0) { "Delay must not be negative" }
    for ((i, entry) in entries.withIndex()) {
        currentCoroutineContext().ensureActive()
        onStart(i, entry)
        val result = probe(entry)
        currentCoroutineContext().ensureActive()
        onResult(i, result)
        if (i + 1 < entries.size) delay(delayMs)
    }
}
