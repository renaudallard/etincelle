// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

/**
 * The messages a [RemoteLink] carries, as JSON objects whose "t" field names the type.
 *
 * Phone to TV: [PLAY] (kind live/vod/recording, id, pos ms, rewind ms behind live), [PAUSE], [RESUME],
 * [SEEK] (pos ms in the stream, or -1 for the live edge), [STOP], [KEY] (k: up, down, left, right, ok,
 * back), [VOLUME] (dir: up, down, or mute).
 *
 * TV to phone: [STATUS], about once a second: state (idle, buffering, playing, paused, ended), title,
 * subtitle, pos and dur in ms (on a live stream, within its rewind window, whose wall-clock start is
 * ws), live, and the kind and id of what plays.
 */
object RemoteMessages {
    const val TYPE = "t"

    const val PLAY = "play"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val SEEK = "seek"
    const val STOP = "stop"
    const val KEY = "key"
    const val VOLUME = "volume"
    const val STATUS = "status"

    const val KIND = "kind"
    const val ID = "id"
    const val POSITION = "pos"
    const val REWIND = "rewind"
    const val KEY_NAME = "k"
    const val DIRECTION = "dir"
    const val STATE = "state"
    const val TITLE = "title"
    const val SUBTITLE = "subtitle"
    const val DURATION = "dur"
    const val LIVE = "live"
    const val WINDOW_START = "ws"

    const val KIND_LIVE = "live"
    const val KIND_VOD = "vod"
    const val KIND_RECORDING = "recording"

    const val STATE_IDLE = "idle"
    const val STATE_BUFFERING = "buffering"
    const val STATE_PLAYING = "playing"
    const val STATE_PAUSED = "paused"
    const val STATE_ENDED = "ended"
}
