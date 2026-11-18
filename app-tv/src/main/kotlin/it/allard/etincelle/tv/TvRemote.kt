// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import it.allard.etincelle.core.remote.RemoteMessages
import it.allard.etincelle.core.remote.StatusPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// How often what the phone driving this app is to hear of playback is looked at (see StatusPacer).
private const val STATUS_MS = 1_000L

/**
 * The phone's remote control of this TV app, one for the process: the server a phone finds and connects
 * to (see [TvRemoteServer]) and the screen its commands drive. Once the app may display over other apps
 * it can come back to the front by itself, so the server is then kept at all times (see
 * RemoteKeepAliveService), in front or not, TV awake or asleep, as a Chromecast is: with no screen to
 * drive, a programme or a key sent by the phone opens the app. Main thread only.
 */
class TvRemote(private val app: EtincelleTvApp) {
    /** What the activity does for the phone. */
    interface Screen {
        fun onRemoteCommand(message: JSONObject)

        /** What plays and where (see RemoteMessages.STATUS). */
        fun remoteStatus(): JSONObject
    }

    private val server = TvRemoteServer(app, ::accountKey, ::onCommand)
    private val pacer = StatusPacer()
    private var scope: CoroutineScope? = null
    private var screen: Screen? = null

    // The programme a phone asked for with no screen to play it: the one its asking opens plays it.
    private var asked: JSONObject? = null

    /** Whether the server is kept once the app is left. */
    val always: Boolean get() = canComeBack(app)

    fun start() {
        server.start()
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also {
            // Also with no screen, or behind the screensaver: a phone left without news drops the connection.
            it.launch {
                var phones = server.phones
                while (true) {
                    if (server.connected) {
                        // A phone just let in knows nothing yet.
                        if (phones != server.phones) pacer.reset()
                        phones = server.phones
                        tell(screen?.remoteStatus() ?: idle())
                    }
                    delay(STATUS_MS)
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        server.stop()
        asked = null
    }

    /** An account signed in here: its phones may drive this TV from now on. */
    fun signedIn() = server.accountChanged()

    /** The account was signed out here: the phones it let in no longer drive this TV. */
    fun signedOut() {
        server.forgetAccount()
        asked = null
    }

    /** [screen] takes the phone's commands from now on. */
    fun attach(screen: Screen) {
        this.screen = screen
    }

    fun detach(screen: Screen) {
        if (this.screen === screen) this.screen = null
    }

    /** The programme whose asking opened the app, once, unless the phone has left since. */
    fun takeAsked(): JSONObject? = asked.also { asked = null }?.takeIf { server.connected }

    // With no screen opened since the app started, nothing has read the saved sign-in yet.
    private suspend fun accountKey(): ByteArray? {
        val repository = app.container.repository
        if (repository.currentSession() == null) repository.restoreSession()
        return repository.remoteKey()
    }

    // Sends [status] to the phone when it is worth it (see StatusPacer).
    private fun tell(status: JSONObject) {
        val live = status.optBoolean(RemoteMessages.LIVE)
        // The window of a live stream slides: its start and length change all the time, the wall-clock
        // time of what plays in it does not while paused.
        val what = JSONObject(status.toString()).apply {
            remove(RemoteMessages.POSITION)
            remove(RemoteMessages.WINDOW_START)
            if (live) remove(RemoteMessages.DURATION)
        }.toString()
        val clock = status.optLong(RemoteMessages.POSITION) + if (live) status.optLong(RemoteMessages.WINDOW_START) else 0L
        val playing = status.optString(RemoteMessages.STATE) == RemoteMessages.STATE_PLAYING
        // One that did not leave is to be sent again.
        if (pacer.due(what, clock, playing, SystemClock.elapsedRealtime()) && !server.send(status)) pacer.reset()
    }

    private fun onCommand(message: JSONObject) {
        val type = message.optString(RemoteMessages.TYPE)
        // The phone shows what it asked for at once, and is to hear what came of it even when nothing
        // did. Keys and volume leave it nothing to wait for.
        if (type != RemoteMessages.KEY && type != RemoteMessages.VOLUME) pacer.reset()
        // What is asked now replaces the programme still waiting for its screen.
        if (type == RemoteMessages.PLAY || type == RemoteMessages.STOP) asked = null
        screen?.let { return it.onRemoteCommand(message) }
        // No screen: what needs one opens the app, which wakes the TV. A key does no more, as the first
        // press on the TV's own remote only wakes it.
        when (type) {
            RemoteMessages.PLAY -> asked = message
            RemoteMessages.KEY -> Unit
            else -> return
        }
        runCatching { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun idle(): JSONObject =
        JSONObject().put(RemoteMessages.TYPE, RemoteMessages.STATUS).put(RemoteMessages.STATE, RemoteMessages.STATE_IDLE)
}

/**
 * The app may display over other apps, which lets it come back to the front by itself: from behind the
 * screensaver or another app, or with the TV asleep.
 */
internal fun canComeBack(context: Context): Boolean = Settings.canDrawOverlays(context)
