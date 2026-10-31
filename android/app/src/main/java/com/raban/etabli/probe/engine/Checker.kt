// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.probe.engine

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val USER_AGENT = "EtabliNuage/1.0 (+QA link-checker; sequential)"

class BackgroundChecker(private val timeoutMs: Long, private val installId: String) {
    init { require(timeoutMs in 1..120_000) { "Timeout must be between 1 and 120000 ms" } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun headers(url: HttpUrl, remaining: Long): Response {
        val call = transport.newBuilder().callTimeout(remaining, TimeUnit.MILLISECONDS)
            .connectTimeout(remaining, TimeUnit.MILLISECONDS).readTimeout(remaining, TimeUnit.MILLISECONDS)
            .build().newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build())
        return suspendCancellableCoroutine { pending ->
            pending.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { if (!pending.isCancelled) pending.resumeWithException(error) }
                override fun onResponse(call: Call, response: Response) { pending.resume(response) { response.close() } }
            })
        }
    }

    suspend fun probe(entry: UrlEntry): CheckResult {
        val started = System.nanoTime()
        fun result(label: String, finalUrl: String?) = CheckResult(
            entry.rowIndex, entry.normalized ?: entry.raw, "background", label, finalUrl,
            (System.nanoTime() - started) / 1_000_000, Date(), Date(), installId,
        )
        currentCoroutineContext().ensureActive()
        if (!entry.valid) return result("SKIPPED: invalid URL", null)
        try {
            var current = entry.normalized!!.toHttpUrl()
            val deadline = started + timeoutMs * 1_000_000
            repeat(10) {
                currentCoroutineContext().ensureActive()
                require(current.username.isEmpty() && current.password.isEmpty()) { "Embedded credentials are not supported" }
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) throw SocketTimeoutException()
                headers(current, remaining).use { response ->
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() > deadline) throw SocketTimeoutException()
                    val location = if (response.code in listOf(301, 302, 303, 307, 308)) response.header("Location") else null
                    if (location == null) return result(response.code.toString(), current.toString())
                    current = current.resolve(location) ?: throw IOException("Only HTTP(S) redirects are supported")
                }
            }
            throw IOException("Too many redirects (maximum 10)")
        } catch (e: CancellationException) { throw e }
        catch (_: java.io.InterruptedIOException) { return result("FAILED: timeout (${timeoutMs}ms)", null) }
        catch (e: Exception) { return result("FAILED: ${e.message ?: e.javaClass.simpleName}", null) }
    }

    companion object {
        private val transport = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    }
}
