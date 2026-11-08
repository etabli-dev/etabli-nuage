// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.net

import com.raban.etabli.probe.engine.WebDavCreds
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

// Ported from android/.../net/WebDavClientTest.kt. A loopback HTTPS server with a test-only
// certificate stands in for Nextcloud; the client keeps normal certificate validation.
class WebDavClientTest {
    private fun fixture(run: suspend (MockWebServer, WebDavClient) -> Unit) = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            val transport = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            val client = WebDavClient(WebDavCreds(server.url("/nextcloud").toString(), "a+b", "app-secret"), transport)
            run(server, client)
        }
    }

    @Test fun propfindUsesCustomMethodAuthenticationAndPreservesPlusNames() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/nextcloud/remote.php/dav/files/a+b/</d:href></d:response><d:response><d:href>/nextcloud/remote.php/dav/files/a+b/a%2Bb.csv</d:href><d:propstat><d:prop><d:getcontentlength>12</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""))
        val entries = client.list("")
        assertEquals("a+b.csv", entries.single().name)
        assertEquals(12L, entries.single().size)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("Basic YStiOmFwcC1zZWNyZXQ=", request.getHeader("Authorization"))
        assertEquals("/nextcloud/remote.php/dav/files/a+b", request.path)
    }

    @Test fun listingKeepsDirectChildrenOnlyAndReadsProperties() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/nextcloud/remote.php/dav/files/a+b/links/</d:href></d:response>
              <d:response><d:href>/nextcloud/remote.php/dav/files/a+b/links/sub/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/nextcloud/remote.php/dav/files/a+b/links/b.csv</d:href>
                <d:propstat><d:prop><d:displayname>B list</d:displayname>
                <d:getlastmodified>Sun, 08 Nov 2026 10:00:00 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
              <d:response><d:href>/nextcloud/remote.php/dav/files/a+b/links/sub/deep.csv</d:href></d:response>
              <d:response><d:href>https://elsewhere.invalid/nextcloud/remote.php/dav/files/a+b/links/x.csv</d:href></d:response>
            </d:multistatus>"""))
        val entries = client.list("links")
        assertEquals(listOf("sub", "B list"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertFalse(entries[1].isDirectory)
        assertEquals(1_794_132_000_000L, entries[1].modifiedAt)
    }

    @Test fun pathTraversalIsRejectedAndReservedCharactersAreEncoded() = fixture { _, client ->
        assertThrows(IllegalArgumentException::class.java) { client.userRoot("../outside") }
        assertTrue(client.userRoot("folder/100% #1?.csv").encodedPath.endsWith("/folder/100%25%20%231%3F.csv"))
        assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavCreds("http://example.org", "a", "b")) }
        assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavCreds("https://u:p@example.org", "a", "b")) }
        assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavCreds("https://example.org", "a/b", "b")) }
        assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavCreds("https://example.org", "a", " ")) }
    }

    @Test fun authenticatedTransfersDoNotFollowRedirects() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.invalid/steal"))
        try { client.get("file.csv"); fail("Redirect accepted") }
        catch (e: WebDavError.Http) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }

    @Test fun downloadsAreBoundedToTheCsvLimit() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("url\nhttps://a.example\n"))
        assertEquals("url\nhttps://a.example\n", client.get("links/a.csv").toString(Charsets.UTF_8))
        assertEquals("/nextcloud/remote.php/dav/files/a+b/links/a.csv", server.takeRequest().path)
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(10 * 1024 * 1024 + 1)))
        try { client.get("big.csv"); fail("Oversized download accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun uploadsWithMkcolAndRefusesConflicts() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        client.put("logs", "result.csv", "rows".toByteArray())
        assertEquals("MKCOL", server.takeRequest().method)
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("rows", upload.body.readUtf8())
        server.enqueue(MockResponse().setResponseCode(409))
        try { client.put("missing", "file.csv", byteArrayOf()); fail("Conflict treated as success") }
        catch (e: WebDavError.Http) { assertEquals(409, e.status) }
        assertEquals(3, server.requestCount)
    }

    @Test fun existingFoldersAreAcceptedWhenUploading() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(405)) // MKCOL: already exists
        server.enqueue(MockResponse().setResponseCode(201)) // MKCOL: created
        server.enqueue(MockResponse().setResponseCode(204)) // PUT: replaced
        client.put("/EtabliProbe/2026/", "r.csv", "x".toByteArray(), "text/csv; charset=utf-8")
        assertEquals("/nextcloud/remote.php/dav/files/a+b/EtabliProbe", server.takeRequest().path)
        assertEquals("/nextcloud/remote.php/dav/files/a+b/EtabliProbe/2026", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("/nextcloud/remote.php/dav/files/a+b/EtabliProbe/2026/r.csv", put.path)
        assertEquals("text/csv; charset=utf-8", put.getHeader("Content-Type"))
    }

    @Test fun refusesOversizedListingAndXmlDoctype() = fixture { server, client ->
        server.enqueue(MockResponse().setBody(" ".repeat(2 * 1024 * 1024 + 1)).setResponseCode(207))
        try { client.list(""); fail("Oversized listing accepted") } catch (_: IllegalArgumentException) { }
        server.enqueue(MockResponse().setBody("<!DOCTYPE d SYSTEM \"file:///etc/passwd\"><d/>").setResponseCode(207))
        try { client.list(""); fail("DTD accepted") } catch (_: IllegalArgumentException) { }
        server.enqueue(MockResponse().setBody("<d:multistatus xmlns:d=\"DAV:\"><d:response>").setResponseCode(207))
        try { client.list(""); fail("Truncated XML accepted") } catch (_: WebDavError.Decoding) { }
    }

    @Test fun appPasswordIsNotPrintedByToString() {
        assertFalse(WebDavCreds("https://cloud.example", "me", "s3cret-app-pw").toString().contains("s3cret"))
    }
}
