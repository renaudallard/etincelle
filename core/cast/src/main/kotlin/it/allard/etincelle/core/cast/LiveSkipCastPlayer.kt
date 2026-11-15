// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * The Play Services cast player as the UI and the media session see it. Media3 only lets a stream of
 * known duration seek, so a live cast had no way back or forward: while [live] says one plays, the
 * 10 s back and 30 s forward skips are offered and run through [skipLive], which moves the receiver
 * by that many milliseconds within its rewind window. Everything else is [player]'s own.
 */
class LiveSkipCastPlayer(
    player: Player,
    private val live: () -> Boolean,
    private val skipLive: (Long) -> Unit,
) : ForwardingPlayer(player) {

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        return if (live()) commands.buildUpon().addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build() else commands
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun seekBack() = if (live()) skipLive(-seekBackIncrement) else super.seekBack()

    override fun seekForward() = if (live()) skipLive(seekForwardIncrement) else super.seekForward()
}
