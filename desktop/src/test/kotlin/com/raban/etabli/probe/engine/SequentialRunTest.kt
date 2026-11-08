// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

class SequentialRunTest {
    /** Loopback server that records request order and the highest number of parallel requests. */
    private class RecordingDispatcher : Dispatcher() {
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(30)
                paths += request.path.orEmpty()
                return MockResponse().setResponseCode(if (request.path == "/missing") 404 else 200)
            } finally { inFlight.decrementAndGet() }
        }
    }

    @Test fun checksOneUrlAtATimeInSourceOrderAndKeepsInvalidRows() = runBlocking {
        val recorder = RecordingDispatcher()
        MockWebServer().use { server ->
            server.dispatcher = recorder
            val csv = buildString {
                append("url,label\n")
                for (p in listOf("/a", "/missing", "/c", "/d")) append(server.url(p)).append(",x\n")
                append("not a url,y\n")
                append(server.url("/f")).append(",z\n")
            }
            val entries = CsvLoader.parse(csv.toByteArray()).entries(0)
            val checker = BackgroundChecker(5_000, "t")
            val results = mutableListOf<CheckResult>()
            runSequentialChecks(entries, delayMs = 0, probe = checker::probe) { i, r ->
                assertEquals(results.size, i)
                results += r
            }
            assertEquals(listOf("/a", "/missing", "/c", "/d", "/f"), recorder.paths)
            assertEquals(1, recorder.maxInFlight.get())
            assertEquals(listOf(2, 3, 4, 5, 6, 7), results.map { it.rowIndex })
            assertEquals(listOf("200", "404", "200", "200", "SKIPPED: invalid URL", "200"), results.map { it.result })
        }
    }

    @Test fun waitsTheDelayBetweenChecksButNotAfterTheLast() = runBlocking {
        val entries = (1..3).map { UrlEntry(it, "u$it", null) }
        val stamps = mutableListOf<Long>()
        val started = System.nanoTime()
        runSequentialChecks(entries, delayMs = 400, probe = { e ->
            stamps += (System.nanoTime() - started) / 1_000_000
            CheckResult(e.rowIndex, e.raw, "background", "SKIPPED: invalid URL", null, 0, Date(), Date(), "t")
        }) { _, _ -> }
        val total = (System.nanoTime() - started) / 1_000_000
        assertTrue("second check after the delay: $stamps", stamps[1] - stamps[0] >= 390)
        assertTrue("third check after the delay: $stamps", stamps[2] - stamps[1] >= 390)
        assertTrue("no trailing delay: $total ms", total - stamps[2] < 300)
    }

    @Test fun cancellationStopsTheRunAndKeepsDeliveredResults() = runBlocking {
        val entries = (1..5).map { UrlEntry(it, "u$it", null) }
        val delivered = mutableListOf<Int>()
        val secondDone = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            runSequentialChecks(entries, delayMs = 1_000, probe = { e ->
                CheckResult(e.rowIndex, e.raw, "background", "SKIPPED: invalid URL", null, 0, Date(), Date(), "t")
            }) { _, r ->
                synchronized(delivered) { delivered += r.rowIndex }
                if (r.rowIndex == 2) secondDone.complete(Unit)
            }
        }
        withContext(Dispatchers.Default) { secondDone.await() }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1, 2), synchronized(delivered) { delivered.toList() })
    }
}
