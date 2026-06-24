// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.net

import android.util.Base64
import com.raban.etabli.probe.engine.WebDavCreds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Minimal Nextcloud WebDAV client — pure-Android, HttpURLConnection + the
// stdlib XmlPullParser. Auth = HTTP Basic with the user's Nextcloud APP
// PASSWORD. We never disable TLS verification; if the cert is bad the
// error surfaces verbatim and the user fixes it server-side.

data class DavEntry(
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedAt: Long?,
)

sealed class WebDavError(message: String) : RuntimeException(message) {
    object NotConfigured : WebDavError("Set Nextcloud credentials in Settings.")
    class Http(val status: Int, val body: String?) : WebDavError("Server returned HTTP $status.")
    class Transport(msg: String) : WebDavError("Network error: $msg.")
    class Decoding(msg: String) : WebDavError("Couldn't parse response: $msg.")
}

class WebDavClient(private val creds: WebDavCreds) {

    private fun userRoot(path: String = ""): URL {
        val base = creds.baseURL.trimEnd('/', ' ')
        val cleaned = path.trim('/')
        val suffix = if (cleaned.isEmpty()) "" else "/$cleaned"
        return URL("$base/remote.php/dav/files/${creds.username}$suffix")
    }

    private fun basicAuth(): String {
        val raw = "${creds.username}:${creds.appPassword}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    suspend fun testConnection(): Int = list("").size

    suspend fun list(path: String): List<DavEntry> = withContext(Dispatchers.IO) {
        val url = userRoot(path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PROPFIND"
            doOutput = true
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", basicAuth())
            setRequestProperty("Depth", "1")
            setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                w.write(propfindBody)
            }
            val code = conn.responseCode
            if (code != 207 && code != 200) {
                throw WebDavError.Http(code, conn.errorStream?.bufferedReader()?.readText())
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            parseMultiStatus(bytes, selfPath = url.path)
        } catch (e: WebDavError) { throw e }
        catch (t: Throwable) { throw WebDavError.Transport(t.message ?: "?") }
        finally { conn.disconnect() }
    }

    suspend fun get(path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = userRoot(path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", basicAuth())
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw WebDavError.Http(code, conn.errorStream?.bufferedReader()?.readText())
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: WebDavError) { throw e }
        catch (t: Throwable) { throw WebDavError.Transport(t.message ?: "?") }
        finally { conn.disconnect() }
    }

    suspend fun put(targetDir: String, fileName: String, bytes: ByteArray,
                    contentType: String = "application/octet-stream") = withContext(Dispatchers.IO) {
        val cleaned = targetDir.trim('/')
        if (cleaned.isNotEmpty()) {
            val parts = cleaned.split('/')
            for (i in 1..parts.size) mkcol(parts.take(i).joinToString("/"))
        }
        val dest = if (cleaned.isEmpty()) fileName else "$cleaned/$fileName"
        val url = userRoot(dest)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Authorization", basicAuth())
            setRequestProperty("Content-Type", contentType)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw WebDavError.Http(code, conn.errorStream?.bufferedReader()?.readText())
            }
        } catch (e: WebDavError) { throw e }
        catch (t: Throwable) { throw WebDavError.Transport(t.message ?: "?") }
        finally { conn.disconnect() }
    }

    private suspend fun mkcol(path: String) = withContext(Dispatchers.IO) {
        val url = userRoot(path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "MKCOL"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", basicAuth())
        }
        try {
            val code = conn.responseCode
            if (code == 405 || code == 409) return@withContext      // already exists
            if (code !in 200..299) {
                throw WebDavError.Http(code, conn.errorStream?.bufferedReader()?.readText())
            }
        } catch (e: WebDavError) { throw e }
        catch (t: Throwable) { throw WebDavError.Transport(t.message ?: "?") }
        finally { conn.disconnect() }
    }

    private fun parseMultiStatus(xml: ByteArray, selfPath: String): List<DavEntry> {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val entries = mutableListOf<DavEntry>()
        var inResponse = false
        var href: String? = null
        var displayname: String? = null
        var size: Long? = null
        var lastMod: String? = null
        var isDir = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                    "response" -> { inResponse = true; href = null; displayname = null; size = null; lastMod = null; isDir = false }
                    "href" -> if (inResponse) href = parser.nextText()?.trim()
                    "displayname" -> if (inResponse) displayname = parser.nextText()?.trim()
                    "getcontentlength" -> if (inResponse) size = parser.nextText()?.toLongOrNull()
                    "getlastmodified" -> if (inResponse) lastMod = parser.nextText()?.trim()
                    "collection" -> if (inResponse) isDir = true
                }
                XmlPullParser.END_TAG -> if (parser.name?.lowercase() == "response" && inResponse) {
                    inResponse = false
                    val raw = href
                    if (raw != null) {
                        val pathOnly = try { URI(raw).path ?: raw } catch (_: Throwable) { raw }
                        val self0 = selfPath.trim('/')
                        val cand0 = pathOnly.trim('/')
                        if (cand0 != self0) {
                            val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Throwable) { raw }
                            val nameTrail = decoded.trimEnd('/').substringAfterLast('/')
                            val name = displayname?.takeIf { it.isNotBlank() } ?: nameTrail
                            val mod = lastMod?.let { runCatching { httpDate.parse(it)?.time }.getOrNull() }
                            entries += DavEntry(decoded, name, isDir, size, mod)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return entries.sortedWith(
            compareByDescending<DavEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    companion object {
        private val propfindBody = """<?xml version="1.0"?>
            |<d:propfind xmlns:d="DAV:">
            |  <d:prop>
            |    <d:displayname/>
            |    <d:getcontentlength/>
            |    <d:getlastmodified/>
            |    <d:resourcetype/>
            |  </d:prop>
            |</d:propfind>""".trimMargin()
    }
}
