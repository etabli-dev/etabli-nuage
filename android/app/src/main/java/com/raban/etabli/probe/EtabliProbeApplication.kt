// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe

import android.app.Application
import com.raban.etabli.probe.prefs.CredsStore
import com.raban.etabli.probe.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class EtabliProbeApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsStore
        private set
    lateinit var creds: CredsStore
        private set
    lateinit var installId: String
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        creds = CredsStore(this)
        installId = creds.installId()
    }
}
