// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.raban.etabli.probe.engine.WebDavCreds
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

// Nextcloud credentials + per-install UUID live in EncryptedSharedPreferences
// (AES-256 GCM payload + key inside the Android Keystore). Never logged,
// never serialised to the log file.

class CredsStore internal constructor(createPreferences: () -> SharedPreferences) {
    constructor(context: Context) : this({
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "probe_creds",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    })

    private val prefs: SharedPreferences by lazy(createPreferences)
    private val credentialLock = Any()
    private val generation = AtomicLong()

    fun beginConnectionAttempt(): Long = generation.incrementAndGet()

    fun read(): WebDavCreds? = synchronized(credentialLock) {
        val u = prefs.getString("baseURL", null).orEmpty()
        val n = prefs.getString("username", null).orEmpty()
        val p = prefs.getString("password", null).orEmpty()
        if (u.isNotEmpty() && n.isNotEmpty() && p.isNotEmpty())
            WebDavCreds(u, n, p) else null
    }

    fun write(c: WebDavCreds, attempt: Long): Unit = synchronized(credentialLock) {
        if (generation.get() != attempt) throw CancellationException("Account changed")
        prefs.edit().putString("baseURL", c.baseURL)
            .putString("username", c.username)
            .putString("password", c.appPassword)
            .commit().also { check(it) { "Could not persist credentials" } }
    }

    fun clear() {
        generation.incrementAndGet()
        synchronized(credentialLock) {
            prefs.edit().remove("baseURL").remove("username").remove("password").commit().also { check(it) { "Could not persist credentials" } }
        }
    }

    /// Per-install UUID. NOT a hardware/device ID — those don't exist on
    /// modern Android in a stable, store-compliant form.
    fun installId(): String {
        val existing = prefs.getString("installId", null)
        if (!existing.isNullOrEmpty()) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString("installId", fresh).commit().also { check(it) { "Could not persist credentials" } }
        return fresh
    }
}
