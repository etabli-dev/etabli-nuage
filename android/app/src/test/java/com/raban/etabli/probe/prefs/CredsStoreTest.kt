// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.prefs

import android.app.Application
import android.content.Context
import com.raban.etabli.probe.engine.WebDavCreds
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CredsStoreTest {
    private fun store(): CredsStore {
        val context = RuntimeEnvironment.getApplication()
        return CredsStore { context.getSharedPreferences("credentials-test-" + UUID.randomUUID(), Context.MODE_PRIVATE) }
    }

    @Test fun disconnectRejectsAnOlderSuccessfulConnectionResponse() {
        val store = store()
        val existing = WebDavCreds("https://example.test", "user", "existing-secret")
        store.write(existing, store.beginConnectionAttempt())
        val installId = store.installId()
        val candidate = existing.copy(appPassword = "late-secret")
        val attempt = store.beginConnectionAttempt()
        val responseReleased = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit {
                check(responseReleased.await(5, TimeUnit.SECONDS)) { "Test did not release the response" }
                store.write(candidate, attempt)
            }
            store.clear()
            responseReleased.countDown()
            val failure = assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            assertNull(store.read())
            assertEquals(installId, store.installId())
        } finally {
            responseReleased.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun laterConnectionAttemptSupersedesAnEarlierReply() {
        val store = store()
        val first = store.beginConnectionAttempt()
        val latest = store.beginConnectionAttempt()
        val expected = WebDavCreds("https://example.test", "user", "current-secret")
        store.write(expected, latest)
        assertThrows(CancellationException::class.java) {
            store.write(expected.copy(appPassword = "old-secret"), first)
        }
        assertEquals(expected, store.read())
    }
}
