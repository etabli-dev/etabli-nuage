// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.net

import com.raban.etabli.probe.engine.WebDavCreds
import com.raban.etabli.probe.engine.MAX_CSV_BYTES
import com.raban.etabli.probe.engine.readBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DavEntry(val href: String, val name: String, val isDirectory: Boolean, val size: Long?, val modifiedAt: Long?)

sealed class WebDavError(message: String) : RuntimeException(message) {
    object NotConfigured : WebDavError("Set Nextcloud credentials in Settings.")
    class Http(val status: Int, val body: String?) : WebDavError("Server returned HTTP $status.")
    class Transport(msg: String) : WebDavError("Network error: $msg.")
    class Decoding(msg: String) : WebDavError("Couldn't parse response: $msg.")
}

/** HTTPS only for credentials; no redirect can forward authentication to another origin. */
class WebDavClient(private val creds: WebDavCreds, transport: OkHttpClient = sharedTransport) {
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base = creds.baseURL.trim().trimEnd('/').toHttpUrl().also {
        require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null) {
            "Use an HTTPS Nextcloud server URL without embedded credentials, query or fragment"
        }
        require(creds.username.isNotBlank() && creds.username !in listOf(".", "..") && !creds.username.contains('/') && !creds.username.contains(':')) {
            "Invalid Nextcloud username"
        }
        require(creds.appPassword.isNotBlank()) { "Enter a Nextcloud app password" }
    }

    internal fun userRoot(path: String = ""): HttpUrl {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.size <= 128 && parts.none { it == "." || it == ".." || it.contains('\\') || it.any { c -> c.code < 32 } }) {
            "Use a path inside your Nextcloud files, without dot segments or control characters"
        }
        return base.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(creds.username)
            .apply { parts.forEach { addPathSegment(it) } }.build()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun request(method: String, path: String, body: RequestBody? = null, depth: String? = null): Response {
        val builder = Request.Builder().url(userRoot(path)).method(method, body)
            .header("Authorization", Credentials.basic(creds.username, creds.appPassword, Charsets.UTF_8))
        if (depth != null) builder.header("Depth", depth)
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(WebDavError.Transport(e.message ?: "Connection failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { response.close() }
                }
            })
        }
    }

    private fun requireSuccess(response: Response, accepted: Boolean = response.isSuccessful) {
        if (!accepted) throw WebDavError.Http(response.code, null)
    }

    suspend fun testConnection(): Int = list("").size

    suspend fun list(path: String): List<DavEntry> = withContext(Dispatchers.IO) {
        request("PROPFIND", path, propfindBody.toRequestBody("application/xml; charset=utf-8".toMediaType()), "1").use { response ->
            requireSuccess(response, response.code == 207 || response.code == 200)
            val bytes = response.body?.byteStream()?.use { it.readBounded(2 * 1024 * 1024) }
                ?: throw WebDavError.Decoding("Empty response")
            parseMultiStatus(bytes, userRoot(path))
        }
    }

    suspend fun get(path: String): ByteArray = withContext(Dispatchers.IO) {
        request("GET", path).use { response ->
            requireSuccess(response, response.code == 200)
            response.body?.byteStream()?.use { it.readBounded(MAX_CSV_BYTES) }
                ?: throw WebDavError.Decoding("Empty response")
        }
    }

    suspend fun put(targetDir: String, fileName: String, bytes: ByteArray, contentType: String = "application/octet-stream") =
        upload(targetDir, fileName, bytes.toRequestBody(contentType.toMediaType()))

    suspend fun putFile(targetDir: String, file: File, contentType: String = "text/csv; charset=utf-8") =
        upload(targetDir, file.name, file.asRequestBody(contentType.toMediaType()))

    private suspend fun upload(targetDir: String, fileName: String, body: RequestBody) = withContext(Dispatchers.IO) {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) { "Use a file name without a path" }
        val parts = targetDir.trim('/').split('/').filter { it.isNotEmpty() }
        val path = (parts + fileName).joinToString("/")
        userRoot(path) // Validate everything before creating any directory.
        for (i in 1..parts.size) {
            request("MKCOL", parts.take(i).joinToString("/"), ByteArray(0).toRequestBody()).use { response ->
                // 405 means it exists. 409 is a missing parent/conflict, not success.
                requireSuccess(response, response.isSuccessful || response.code == 405)
            }
        }
        request("PUT", path, body).use { requireSuccess(it) }
    }

    private fun parseMultiStatus(xml: ByteArray, self: HttpUrl): List<DavEntry> {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        require(!xml.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) { "DTD declarations are not accepted" }
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
                        val resolved = self.resolve(raw)
                        val selfPath = self.toUri().path.trimEnd('/')
                        val path = resolved?.toUri()?.path
                        // A depth-one listing must not invent entries outside the requested collection.
                        if (resolved != null && resolved.scheme == self.scheme && resolved.host == self.host &&
                            resolved.port == self.port && path != null && path.trimEnd('/') != selfPath &&
                            path.startsWith("$selfPath/") &&
                            !path.removePrefix("$selfPath/").trimEnd('/').contains('/')) {
                            val nameTrail = path.trimEnd('/').substringAfterLast('/')
                            val name = displayname?.takeIf { it.isNotBlank() } ?: nameTrail
                            val mod = lastMod?.let { runCatching { httpDate.parse(it)?.time }.getOrNull() }
                            entries += DavEntry(path, name, isDir, size?.takeIf { it >= 0 }, mod)
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
        private val sharedTransport = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
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
