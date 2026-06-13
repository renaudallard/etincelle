// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import android.os.Build
import java.util.UUID

/** Device identity reported to Fubo. The id is per-process for now (persisted later via DataStore). */
class DeviceInfo(
    val deviceId: String = UUID.randomUUID().toString(),
    val brand: String = Build.BRAND ?: "android",
    val model: String = Build.MODEL ?: "android",
    val osVersion: String = Build.VERSION.RELEASE ?: "",
)
