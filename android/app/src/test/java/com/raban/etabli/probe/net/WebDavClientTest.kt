package com.raban.etabli.probe.net

import com.raban.etabli.probe.engine.WebDavCreds
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

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
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.getHeader("Depth"))
        assertEquals("Basic YStiOmFwcC1zZWNyZXQ=", request.getHeader("Authorization"))
        assertEquals("/nextcloud/remote.php/dav/files/a+b", request.path)
    }
    @Test fun pathTraversalIsRejectedAndReservedCharactersAreEncoded() = fixture { _, client ->
        assertThrows(IllegalArgumentException::class.java) { client.userRoot("../outside") }
        assertTrue(client.userRoot("folder/100% #1?.csv").encodedPath.endsWith("/folder/100%25%20%231%3F.csv"))
        assertThrows(IllegalArgumentException::class.java) { WebDavClient(WebDavCreds("http://example.org", "a", "b")) }
    }
    @Test fun authenticatedTransfersDoNotFollowRedirects() = fixture { server, client ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.invalid/steal"))
        try { client.get("file.csv"); fail("Redirect accepted") }
        catch (e: WebDavError.Http) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
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
    @Test fun refusesOversizedListingAndXmlDoctype() = fixture { server, client ->
        server.enqueue(MockResponse().setBody(" ".repeat(2 * 1024 * 1024 + 1)).setResponseCode(207))
        try { client.list(""); fail("Oversized listing accepted") } catch (_: IllegalArgumentException) { }
        server.enqueue(MockResponse().setBody("<!DOCTYPE d SYSTEM \"file:///etc/passwd\"><d/>").setResponseCode(207))
        try { client.list(""); fail("DTD accepted") } catch (_: IllegalArgumentException) { }
    }
}
