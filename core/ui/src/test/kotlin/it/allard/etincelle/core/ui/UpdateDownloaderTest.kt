// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class UpdateDownloaderTest {

    private val server = MockWebServer()
    private val apk = ByteArray(200_000) { (it * 31).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private lateinit var dir: File
    private lateinit var target: File

    @Before
    fun setUp() {
        server.start()
        dir = Files.createTempDirectory("update").toFile()
        target = File(dir, "update.apk")
    }

    @After
    fun tearDown() {
        server.shutdown()
        // Only the two files the downloader may create; no tree walk.
        File(target.path + ".part").delete()
        target.delete()
        dir.delete()
    }

    private fun info(size: Long = apk.size.toLong(), sha256: String = sha) =
        UpdateInfo("9.9.9", server.url("/etincelle-9.9.9-tv.apk").toString(), size, sha256)

    private fun serve(body: ByteArray = apk, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(Buffer().write(body)))

    @Test
    fun keepsTheApkWhenSizeAndDigestMatch() = runBlocking {
        serve()
        val progress = mutableListOf<Int>()
        assertTrue(downloadUpdate(info(), target) { progress += it })
        assertArrayEquals(apk, target.readBytes())
        assertEquals(100, progress.last())
        assertEquals(progress.sorted().distinct(), progress)
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun dropsTheApkWhenTheDigestDiffers() = runBlocking {
        serve()
        assertFalse(downloadUpdate(info(sha256 = "0".repeat(64)), target) {})
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun dropsTheApkWhenLongerThanPublished() = runBlocking {
        serve()
        assertFalse(downloadUpdate(info(size = apk.size - 1L), target) {})
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun dropsATruncatedApk() = runBlocking {
        serve(apk.copyOf(apk.size - 1))
        assertFalse(downloadUpdate(info(), target) {})
        assertFalse(target.exists())
    }

    @Test
    fun failsOnHttpError() = runBlocking {
        serve(ByteArray(0), 404)
        assertFalse(downloadUpdate(info(), target) {})
        assertFalse(target.exists())
    }
}
