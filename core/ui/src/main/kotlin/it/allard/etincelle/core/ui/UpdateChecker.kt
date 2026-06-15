// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release found on GitHub, with the direct link to its APK asset. */
data class UpdateInfo(val version: String, val apkUrl: String)

/** Outcome of an explicit "check for updates", so the UI can tell up-to-date apart from a failure. */
sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    object UpToDate : UpdateCheck
    object Failed : UpdateCheck
}

/**
 * Checks the project's GitHub releases for a version newer than the running app. Any failure
 * (offline, rate limit, malformed payload) resolves to null so a launch check can never disrupt
 * startup or nag the user.
 */
class UpdateChecker(
    private val currentVersion: String,
    private val releaseUrl: String = "https://api.github.com/repos/renaudallard/etincelle/releases/latest",
) {
    /** Startup path: just the newer release, or null on up-to-date or any failure (never disruptive). */
    suspend fun latestUpdate(): UpdateInfo? = (check() as? UpdateCheck.Available)?.info

    /** Explicit-check path: distinguishes a newer release, up-to-date, and a failed lookup. */
    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(releaseUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "etincelle-app")
                connectTimeout = 8000
                readTimeout = 8000
            }
            try {
                if (conn.responseCode != 200) return@runCatching UpdateCheck.Failed
                val info = parseUpdate(conn.inputStream.bufferedReader().use { it.readText() }, currentVersion)
                if (info != null) UpdateCheck.Available(info) else UpdateCheck.UpToDate
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(UpdateCheck.Failed)
    }
}

/** Turns a GitHub "latest release" payload into an [UpdateInfo] when it is newer and ships an APK. */
internal fun parseUpdate(body: String, currentVersion: String): UpdateInfo? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
    if (!isNewer(tag, currentVersion)) return null
    val assets = root.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val url = asset.optString("browser_download_url")
        // Release assets are served from github.com; never hand the browser anything else.
        if (asset.optString("name").endsWith(".apk", true) && url.startsWith("https://github.com/")) {
            return UpdateInfo(tag.removePrefix("v").removePrefix("V"), url)
        }
    }
    return null
}

/** True when [latest] is a strictly higher dotted version than [current]; a leading "v" is ignored. */
internal fun isNewer(latest: String, current: String): Boolean {
    if (current.isBlank()) return false
    val l = latest.versionParts()
    val c = current.versionParts()
    for (i in 0 until maxOf(l.size, c.size)) {
        val a = l.getOrElse(i) { 0 }
        val b = c.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun String.versionParts(): List<Int> =
    trim().removePrefix("v").removePrefix("V").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
