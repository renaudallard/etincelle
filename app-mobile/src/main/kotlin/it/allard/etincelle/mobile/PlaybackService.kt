// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID

/**
 * Keeps playback going in the background, with the media notification and the lock-screen controls.
 * The activity owns the player and its [session]; this service only hosts that session, which puts the
 * app in the foreground while something plays.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })
        session?.let { addSession(it) }
    }

    // The service may outlive the activity whose session it was created with: the one of the activity
    // that starts it now is taken on then.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        session?.let { if (!isSessionAdded(it)) addSession(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private val handler = Handler(Looper.getMainLooper())
    // The notification update put off while a cast changes item, run once the gap has lasted too long.
    private var gapEnd: Runnable? = null

    // A cast changing item (the next episode, a fresh stream, the receiver reloading by itself) has no
    // item for a moment, on which media3 removes the notification and leaves the foreground. From the
    // background Android refuses to let it back, and the app can then be frozen for the rest of the
    // cast: nothing starts the next episode or fetches a fresh stream. Keep the notification through
    // that gap; one lasting longer than ITEM_GAP_MS is not a change of item, so let media3 act on it.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val player = session.player
        if (player !is ExoPlayer && player.currentTimeline.isEmpty) {
            if (gapEnd == null) {
                gapEnd = Runnable {
                    gapEnd = null
                    super.onUpdateNotification(session, false)
                }.also { handler.postDelayed(it, ITEM_GAP_MS) }
            }
            return
        }
        gapEnd?.let(handler::removeCallbacks)
        gapEnd = null
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    override fun onDestroy() {
        gapEnd?.let(handler::removeCallbacks)
        gapEnd = null
        super.onDestroy()
    }

    // The player lives in the activity, which goes away with its task.
    override fun onTaskRemoved(rootIntent: Intent?) = stopSelf()

    companion object {
        private const val ITEM_GAP_MS = 60_000L

        /** The activity's session, set before it starts this service and cleared when it ends. */
        var session: MediaSession? = null

        // A stream is a single item: the controls skip within it, as the player's own buttons do
        // (10 s back, 30 s forward), instead of to a previous or next item. They are session commands
        // because only those reach the system controls as buttons.
        private val SEEK_BACK = SessionCommand("it.allard.etincelle.SEEK_BACK", Bundle.EMPTY)
        private val SEEK_FORWARD = SessionCommand("it.allard.etincelle.SEEK_FORWARD", Bundle.EMPTY)
        private val SKIPS = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setSessionCommand(SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK).setDisplayName("Reculer de 10 secondes").build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setSessionCommand(SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD).setDisplayName("Avancer de 30 secondes").build(),
        )
        private val SKIP_COMMANDS = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(SEEK_BACK).add(SEEK_FORWARD).build()
        private val NO_ITEM_SKIPS = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().removeAll(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        ).build()

        /** The session for [player]; tapping the notification brings the app back. */
        fun createSession(context: Context, player: Player): MediaSession =
            MediaSession.Builder(context, player)
                // One of its own for each activity: Android may create the next activity before it has
                // destroyed the one being closed, whose session still exists, and two sessions under
                // one id are refused with an exception that ends the app as it opens.
                .setId(UUID.randomUUID().toString())
                .setMediaButtonPreferences(SKIPS)
                .setCallback(object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailablePlayerCommands(NO_ITEM_SKIPS)
                            .setAvailableSessionCommands(SKIP_COMMANDS)
                            .build()

                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: SessionCommand,
                        args: Bundle,
                    ): ListenableFuture<SessionResult> {
                        when (customCommand.customAction) {
                            SEEK_BACK.customAction -> session.player.seekBack()
                            SEEK_FORWARD.customAction -> session.player.seekForward()
                            else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                })
                .apply {
                    // The activity itself, not the launch intent: that one names whichever launcher alias
                    // is enabled now, and switching the app icon disables it.
                    val open = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    setSessionActivity(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
                }
                .build()
    }
}
