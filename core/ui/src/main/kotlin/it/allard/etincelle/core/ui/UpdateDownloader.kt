// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the APK of [info] to [target], reporting progress in percent. The file is kept only when
 * its size and sha256 match what GitHub published; false on any failure, with nothing left behind.
 */
suspend fun downloadUpdate(info: UpdateInfo, target: File, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
    val part = File(target.path + ".part")
    var kept = false
    try {
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var shown = -1
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            // Never write more than the published size.
                            if (done > info.size) break
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                            val percent = (done * 100 / info.size).toInt()
                            if (percent != shown) {
                                shown = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                kept = done == info.size && sha256 == info.sha256 && part.renameTo(target)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        kept = false
    } finally {
        // Also reached on cancellation, so an abandoned download never lingers.
        if (!kept) part.delete()
    }
    kept
}
