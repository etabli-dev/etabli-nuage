// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.desktop

import com.raban.etabli.probe.engine.CheckResult
import com.raban.etabli.probe.engine.CsvLoader
import com.raban.etabli.probe.net.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The desktop workflow without the window: open, choose column, run, export, Nextcloud. */
class NuageStateTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun CoroutineScope.newState(newWebDav: ((com.raban.etabli.probe.engine.WebDavCreds) -> WebDavClient)? = null) =
        if (newWebDav == null) NuageState(SettingsStore(tmp.root.toPath().resolve("cfg/settings.properties")), this)
        else NuageState(SettingsStore(tmp.root.toPath().resolve("cfg/settings.properties")), this, newWebDav = newWebDav)

    @Test fun opensChoosesRunsAndExports() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/moved"))
            server.enqueue(MockResponse().setResponseCode(404))
            val input = tmp.newFile("links.csv").toPath()
            Files.writeString(input, "name;website\nA;${server.url("/a")}\nB;${server.url("/b")}\nC;ftp://nope.example\n")
            val before = Files.readAllBytes(input)

            val state = newState()
            state.setDelayMs(100)
            state.openFile(input).join()
            val preview = state.preview!!
            assertTrue(preview.hasHeader)
            assertEquals(1, state.selectedColumn)
            assertTrue(state.prepare())
            assertEquals(Screen.Run, state.screen)
            assertEquals(listOf(2, 3, 4), state.prepared.map { it.rowIndex })

            state.startRun()!!.join()
            assertFalse(state.running)
            assertEquals(listOf("200", "404", "SKIPPED: invalid URL"), state.results.map { it.result })
            assertEquals(server.url("/moved").toString(), state.results[1].finalURL)
            assertEquals(1, state.passCount)
            assertEquals(2, state.failCount)
            assertEquals(Tone.Accent, state.runNotice?.tone)
            assertEquals(3, state.unsavedCount)
            assertFalse(state.requestQuit())
            assertTrue(state.confirmQuit)

            val out = tmp.root.toPath().resolve("export/results.csv")
            Files.createDirectories(out.parent)
            state.exportTo(out).join()
            assertEquals(0, state.unsavedCount)
            assertTrue(state.requestQuit())
            val exported = CsvLoader.parse(Files.readAllBytes(out), hasHeader = true)
            assertEquals(CheckResult.csvHeader, exported.headers)
            assertEquals(listOf("2", "3", "4"), exported.column(0))
            assertEquals(listOf("200", "404", "SKIPPED: invalid URL"), exported.column(3))
            assertTrue(exported.column(8).all { it == state.settings.installId })
            assertTrue("the source CSV is never changed", before.contentEquals(Files.readAllBytes(input)))
        }
    }

    @Test fun cancellingKeepsCompletedRowsAndAsksBeforeReplacingThem() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(3, TimeUnit.SECONDS))
            val input = tmp.newFile("two.csv").toPath()
            Files.writeString(input, "url\n${server.url("/1")}\n${server.url("/2")}\n${server.url("/3")}\n")
            val state = newState()
            state.setDelayMs(100)
            state.openFile(input).join()
            assertTrue(state.prepare())
            val job = state.startRun()!!
            withContext(Dispatchers.IO) {
                server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) // second check is waiting for headers
            }
            assertTrue(state.running)
            state.cancelRun()
            job.join()
            assertFalse(state.running)
            assertEquals(listOf("200"), state.results.map { it.result })
            assertTrue(state.runNotice!!.text.startsWith("Cancelled after 1 of 3"))

            state.requestStart()
            assertTrue(state.confirmDiscard)
            assertEquals(1, state.results.size)
        }
    }

    @Test fun refusesColumnsWithoutUrls() = runBlocking {
        val input = tmp.newFile("none.csv").toPath()
        Files.writeString(input, "a,b\n1,2\n")
        val state = newState()
        state.openFile(input).join()
        assertNull(state.selectedColumn)
        state.selectColumn(0)
        assertFalse(state.prepare())
        assertEquals(Tone.Danger, state.sourceNotice?.tone)
        assertTrue(state.prepared.isEmpty())
    }

    @Test fun unreadableFilesReportAnErrorInsteadOfFailing() = runBlocking {
        val state = newState()
        state.openFile(tmp.root.toPath().resolve("missing.csv")).join()
        assertNull(state.preview)
        assertEquals("Could not open missing.csv: file not found", state.sourceNotice?.text)
    }

    @Test fun nextcloudPasswordIsAskedPerSessionAndNeverSaved() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val transport = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            val state = newState { WebDavClient(it, transport) }
            assertNull(state.fetchFromNextcloud("links/a.csv"))
            assertEquals(Tone.Danger, state.sourceNotice?.tone)

            state.saveAccount(server.url("/nc").toString(), "me", "Results/Links")
            assertTrue(state.hasAccount)
            assertNull(state.fetchFromNextcloud("links/a.csv"))
            assertEquals(PendingDav.Fetch("links/a.csv"), state.passwordPrompt)

            server.enqueue(MockResponse().setResponseCode(200).setBody("url\nhttps://a.example\n"))
            state.submitPassword("app-pw-secret")!!.join()
            val get = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/nc/remote.php/dav/files/me/links/a.csv", get.path)
            assertEquals(okhttp3.Credentials.basic("me", "app-pw-secret"), get.getHeader("Authorization"))
            assertEquals("a.csv", state.sourceName)
            assertEquals(listOf("https://a.example"), state.preview!!.column(0))

            // Within the session the password is reused; a 401 discards it.
            server.enqueue(MockResponse().setResponseCode(401))
            state.testConnection()!!.join()
            assertEquals("", state.sessionPassword)
            assertEquals(Tone.Danger, state.accountNotice?.tone)

            assertNull(state.testConnection())
            assertEquals(PendingDav.Test, state.passwordPrompt)
            server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
            state.submitPassword("second")!!.join()
            assertEquals(Tone.Accent, state.accountNotice?.tone)
            assertEquals("second", state.sessionPassword)

            // Another server or user never receives the password typed for the previous one.
            state.saveAccount("https://other.example", "me", "Results")
            assertEquals("", state.sessionPassword)

            val saved = Files.readString(tmp.root.toPath().resolve("cfg/settings.properties"))
            assertFalse(saved.contains("app-pw-secret"))
            assertFalse(saved.contains("second"))
            assertTrue(saved.contains("username=me"))
        }
    }

    @Test fun uploadsResultsAsCsvIntoTheConfiguredFolder() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val transport = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        MockWebServer().use { checks ->
            MockWebServer().use { dav ->
                dav.useHttps(serverTls.sslSocketFactory(), false)
                checks.enqueue(MockResponse().setResponseCode(200))
                val input = tmp.newFile("one.csv").toPath()
                Files.writeString(input, "url\n${checks.url("/ok")}\n")
                val state = newState { WebDavClient(it, transport) }
                state.openFile(input).join()
                assertTrue(state.prepare())
                state.startRun()!!.join()

                state.saveAccount(dav.url("/").toString(), "me", "Out")
                assertNull(state.uploadResults("r.csv"))
                dav.enqueue(MockResponse().setResponseCode(405)) // MKCOL: folder exists
                dav.enqueue(MockResponse().setResponseCode(201)) // PUT
                state.submitPassword("pw")!!.join()
                assertEquals("MKCOL", dav.takeRequest().method)
                val put = dav.takeRequest()
                assertEquals("/remote.php/dav/files/me/Out/r.csv", put.path)
                val uploaded = CsvLoader.parse(put.body.readByteArray(), hasHeader = true)
                assertEquals(listOf("200"), uploaded.column(3))
                assertEquals(0, state.unsavedCount)
                assertEquals(Tone.Accent, state.resultsNotice?.tone)
            }
        }
    }
}
