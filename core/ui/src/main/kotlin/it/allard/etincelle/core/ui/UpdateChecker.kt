// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release found on GitHub: the direct link to its APK asset, with the size and sha256 GitHub published. */
data class UpdateInfo(
    val version: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    // Installed by itself once the app is left (see AutoUpdate), so not proposed to the user.
    val quiet: Boolean = false,
)

/** Outcome of an explicit "check for updates", so the UI can tell up-to-date apart from a failure. */
sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    object UpToDate : UpdateCheck
    object Failed : UpdateCheck
}

/**
 * Checks the project's GitHub releases for a version newer than the running app. [variant] names
 * the app's APK in a release ("mobile" or "tv", as in etincelle-<version>-<variant>.apk). Any failure
 * (offline, rate limit, malformed payload) resolves to null so a launch check can never disrupt
 * startup or nag the user.
 */
class UpdateChecker(
    private val currentVersion: String,
    private val variant: String,
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
                val info = parseUpdate(conn.inputStream.bufferedReader().use { it.readText() }, currentVersion, variant)
                if (info != null) UpdateCheck.Available(info) else UpdateCheck.UpToDate
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(UpdateCheck.Failed)
    }
}

/**
 * Turns a GitHub "latest release" payload into an [UpdateInfo] when it is newer and ships the APK
 * of this [variant].
 */
internal fun parseUpdate(body: String, currentVersion: String, variant: String): UpdateInfo? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
    if (!isNewer(tag, currentVersion)) return null
    val json = root.optJSONArray("assets") ?: return null
    val assets = (0 until json.length()).mapNotNull { json.optJSONObject(it) }
        .map { ReleaseAsset(it.optString("name"), it.optString("browser_download_url"), it.optLong("size"), sha256Of(it.optString("digest"))) }
    val apk = apkFor(assets, variant) ?: return null
    return UpdateInfo(tag.removePrefix("v").removePrefix("V"), apk.url, apk.size, apk.sha256 ?: return null)
}

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** The hex sha256 from a GitHub asset digest ("sha256:<64 hex>"), or null for anything else. */
internal fun sha256Of(digest: String): String? =
    digest.removePrefix("sha256:").lowercase().takeIf { digest.startsWith("sha256:") && it.length == 64 && it.all { c -> c in "0123456789abcdef" } }

/**
 * The APK of this [variant] among a release's assets. A release ships one APK per app, so taking any
 * APK would hand the TV the phone's. Release assets are served from github.com, and the download is
 * checked against the digest GitHub publishes; an asset without both is ignored.
 */
internal fun apkFor(assets: List<ReleaseAsset>, variant: String): ReleaseAsset? =
    assets.firstOrNull {
        it.name.endsWith("-$variant.apk", ignoreCase = true) && it.url.startsWith("https://github.com/") &&
            it.size > 0 && it.sha256 != null
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
