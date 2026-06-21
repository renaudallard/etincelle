// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import android.content.Context
import android.os.Build
import java.util.UUID

/** Device identity reported to Fubo. The id must stay stable across launches (see [persistent]). */
class DeviceInfo(
    val deviceId: String = UUID.randomUUID().toString(),
    val brand: String = Build.BRAND ?: "android",
    val model: String = Build.MODEL ?: "android",
    val osVersion: String = Build.VERSION.RELEASE ?: "",
) {
    companion object {
        /**
         * Device identity with an install-stable id: generated once and persisted, so Fubo sees the
         * same device every launch (a fresh id per launch can trip device limits or force re-pairing).
         */
        fun persistent(context: Context): DeviceInfo {
            val prefs = context.getSharedPreferences("etincelle_device", Context.MODE_PRIVATE)
            val id = prefs.getString("device_id", null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
            return DeviceInfo(deviceId = id)
        }
    }
}
