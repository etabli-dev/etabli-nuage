package com.raban.etabli.probe.engine

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CheckerTest {
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
    @Test fun reportsTimeoutAndDoesNotConvertCancellationIntoACompletedCheck() = runBlocking {
        MockWebServer().use { server ->
            val url = server.url("/stall").toString()
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS))
            val result = BackgroundChecker(100, "test").probe(UrlEntry(1, url, url))
            assertTrue(result.result.contains("timeout"))
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

}
