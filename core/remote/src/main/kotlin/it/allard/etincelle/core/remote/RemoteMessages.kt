// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

/**
 * The messages a [RemoteLink] carries, as JSON objects whose "t" field names the type.
 *
 * Phone to TV: [PLAY] (kind live/vod/recording, id, where a recording played from the page of its
 * episode is asked for as that page, a vod, which is where its next episode is; pos ms, where 0 leaves
 * the TV its own resume point unless start is true; rewind ms behind live, req: a number the
 * TV echoes in its status, so the phone knows the error there is about this request), [PAUSE], [RESUME],
 * [SEEK] (pos ms in the stream, or -1 for the live edge), [STOP], [KEY] (k: up, down, left, right, ok,
 * back, or skip for the ad break the TV offers to skip), [VOLUME] (dir: up, down, or mute).
 *
 * TV to phone: [STATUS], when what it says changes and every ten seconds otherwise (see [StatusPacer]):
 * state (idle, buffering, playing, paused, ended), title,
 * subtitle, pos and dur in ms (on a live stream, within its rewind window, whose wall-clock start is
 * ws, and where in it the TV plays at the live point, lp), live, the kind and id of what plays, the req of the last play handled and the error it ended
 * on, if any, whether something else plays or not, and skip when playback is in an ad break the TV
 * offers to skip.
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
    const val FROM_START = "start"
    const val KEY_NAME = "k"
    const val DIRECTION = "dir"
    const val STATE = "state"
    const val TITLE = "title"
    const val SUBTITLE = "subtitle"
    const val DURATION = "dur"
    const val LIVE = "live"
    const val WINDOW_START = "ws"
    const val LIVE_POSITION = "lp"
    const val REQUEST = "req"
    const val ERROR = "error"
    const val SKIP = "skip"

    const val KEY_SKIP = "skip"

    const val KIND_LIVE = "live"
    const val KIND_VOD = "vod"
    const val KIND_RECORDING = "recording"

    const val STATE_IDLE = "idle"
    const val STATE_BUFFERING = "buffering"
    const val STATE_PLAYING = "playing"
    const val STATE_PAUSED = "paused"
    const val STATE_ENDED = "ended"
}
