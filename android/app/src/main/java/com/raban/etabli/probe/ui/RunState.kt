// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.ui

import com.raban.etabli.probe.engine.CheckLogger
import com.raban.etabli.probe.engine.UrlEntry

// Process-wide run state shared between the Source / Run / Results screens.
// Plain holder — Compose reads via SnapshotStateOf or recompose drivers
// hosted by RunScreen.
object RunState {
    var prepared: List<UrlEntry> = emptyList()
    var logger: CheckLogger? = null
}
