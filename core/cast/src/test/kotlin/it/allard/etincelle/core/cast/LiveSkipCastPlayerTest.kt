// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSkipCastPlayerTest {
    // A cast player skipping 10 s back and 30 s forward. (Player.Commands needs the Android runtime, so the
    // offered commands are left to the device test.)
    private val calls = mutableListOf<String>()
    private val castPlayer = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        calls += method.name
        when (method.name) {
            "getSeekBackIncrement" -> 10_000L
            "getSeekForwardIncrement" -> 30_000L
            else -> null
        }
    } as Player

    private var live = true
    private val skips = mutableListOf<Long>()
    private val player = LiveSkipCastPlayer(castPlayer, live = { live }, skipLive = { skips += it })

    @Test
    fun aLiveCastSkipsByMovingTheReceiver() {
        player.seekBack()
        player.seekForward()
        assertEquals(listOf(-10_000L, 30_000L), skips)
        assertFalse("seekBack" in calls || "seekForward" in calls)
    }

    @Test
    fun onDemandIsLeftToTheCastPlayer() {
        live = false
        player.seekBack()
        player.seekForward()
        assertTrue(skips.isEmpty())
        assertTrue("seekBack" in calls && "seekForward" in calls)
    }
}
