// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date
import java.util.concurrent.TimeUnit

// All network tests talk to a loopback MockWebServer; no external site is contacted.
class CheckerTest {
    private fun entry(url: String) = UrlEntry(1, url, UrlNormalize.normalize(url))

    @Test fun usesGetWithTheSuiteUserAgentAndClassifiesStatusCodes() = runBlocking {
        MockWebServer().use { server ->
            val checker = BackgroundChecker(2_000, "install-1")
            for (code in listOf(200, 204, 304, 399, 404, 410, 500, 503)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val result = checker.probe(entry(server.url("/s$code").toString()))
                assertEquals(code.toString(), result.result)
                assertEquals("background", result.mode)
                assertEquals("install-1", result.installId)
                assertEquals(code in 200..399, result.pass)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals(USER_AGENT, request.getHeader("User-Agent"))
            }
        }
    }

    @Test fun redirectWithoutLocationIsReportedAsItsOwnStatus() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302))
            val result = BackgroundChecker(2_000, "t").probe(entry(server.url("/r").toString()))
            assertEquals("302", result.result)
            assertTrue(result.pass)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidEntriesAreSkippedWithoutNetworkAccess() = runBlocking {
        val result = BackgroundChecker(1_000, "t").probe(UrlEntry(7, "not a url", null))
        assertEquals("SKIPPED: invalid URL", result.result)
        assertEquals("not a url", result.url)
        assertEquals(7, result.rowIndex)
        assertNull(result.finalURL)
        assertFalse(result.pass)
    }

    @Test fun passRuleMatchesTheMobileLogs() {
        fun r(mode: String, result: String) = CheckResult(1, "u", mode, result, null, 1, Date(0), Date(0), "i")
        assertTrue(r("background", "200").pass)
        assertTrue(r("background", "399").pass)
        assertFalse(r("background", "199").pass)
        assertFalse(r("background", "400").pass)
        assertFalse(r("background", "FAILED: timeout (100ms)").pass)
        assertFalse(r("background", "SKIPPED: invalid URL").pass)
        assertTrue(r("foreground", "LOADED").pass)
        assertFalse(r("foreground", "200").pass)
    }

    @Test fun refusesAnInvalidTimeout() {
        for (bad in listOf(0L, -1L, 120_001L)) {
            try { BackgroundChecker(bad, "t"); fail("Timeout $bad accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    // Ported from android/.../engine/CheckerTest.kt.

    @Test fun getsHeadersWithoutReadingAnUnboundedOrStalledBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("not needed").setBodyDelay(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            val url = server.url("/slow-body").toString()
            val result = BackgroundChecker(500, "test").probe(UrlEntry(1, url, url))
            assertEquals("200", result.result)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
        }
    }

    @Test fun followsRelativeRedirectsButRefusesUnsupportedSchemesAndLoops() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
            server.enqueue(MockResponse().setResponseCode(404))
            val url = server.url("/start").toString()
            val checker = BackgroundChecker(1000, "test")
            val result = checker.probe(UrlEntry(1, url, url))
            assertEquals("404", result.result)
            assertEquals(server.url("/final").toString(), result.finalURL)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "file:///etc/passwd"))
            assertTrue(checker.probe(UrlEntry(1, url, url)).result.startsWith("FAILED:"))
            repeat(10) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
            assertTrue(checker.probe(UrlEntry(1, url, url)).result.contains("Too many redirects"))
        }
    }

    @Test fun nineRedirectsAreFollowedWithinTheTenAttemptLimit() = runBlocking {
        MockWebServer().use { server ->
            for (code in listOf(301, 302, 303, 307, 308, 301, 302, 303, 307)) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "/hop"))
            }
            server.enqueue(MockResponse().setResponseCode(200))
            val result = BackgroundChecker(5_000, "t").probe(entry(server.url("/start").toString()))
            assertEquals("200", result.result)
            assertEquals(10, server.requestCount)
        }
    }

    @Test fun reportsTimeoutAndDoesNotConvertCancellationIntoACompletedCheck() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/stall").toString()
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS))
            val result = BackgroundChecker(100, "test").probe(UrlEntry(1, url, url))
            assertEquals("FAILED: timeout (100ms)", result.result)
            assertFalse(result.pass)
            val job = async(start = CoroutineStart.LAZY) { BackgroundChecker(100, "test").probe(UrlEntry(1, url, url)) }
            job.cancel()
            try { job.await(); fail("Cancelled work returned a result") } catch (_: CancellationException) { }
        }
    }

    @Test fun cancellingAnActiveProbeClosesTheCallImmediately() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS))
            val url = server.url("/cancel").toString()
            val task = async { BackgroundChecker(10_000, "test").probe(UrlEntry(1, url, url)) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            val started = System.nanoTime()
            task.cancelAndJoin()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
        }
    }

    @Test fun connectionFailuresAreRecordedAsFailed() = runBlocking {
        val server = MockWebServer()
        server.start()
        val url = server.url("/gone").toString()
        server.shutdown() // nothing listens on this loopback port any more
        val result = BackgroundChecker(2_000, "t").probe(entry(url))
        assertTrue(result.result, result.result.startsWith("FAILED: "))
        assertNull(result.finalURL)
    }
}
