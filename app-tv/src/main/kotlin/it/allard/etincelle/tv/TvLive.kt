// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.Program
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.LiveChannel
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.player.AdaptiveLoadControl
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Molotov's channels in the system's TV guide (the Google TV live tab): the channels and their next
 * hours of programmes, written for etincelle's TV input, which plays them itself ([TvLiveInputService]).
 */
object TvLive {
    // How far ahead the guide goes: twelve hours keep it useful without overloading the TV's provider.
    private const val GUIDE_HOURS = 12
    // The guide is a large download and many writes, so it is rewritten at most this often (the app
    // starting or stopping asks for a sync each time).
    private const val REFRESH_MS = 3L * 60 * 60 * 1000
    private const val PREFS = "etincelle"
    private const val SYNCED_AT = "live_guide_synced_at"
    // The account the guide was written for: another account's channels are rewritten at once.
    private const val SYNCED_FOR = "live_guide_synced_for"
    // The last attempt, successful or not, and how long a failed one is left alone.
    private const val TRIED_AT = "live_guide_tried_at"
    private const val TRIED_FOR = "live_guide_tried_for"
    private const val RETRY_MS = 30L * 60 * 1000
    private val lock = Mutex()

    fun inputId(context: Context): String =
        TvContract.buildInputId(ComponentName(context, TvLiveInputService::class.java))

    /**
     * Writes the channels and their programmes for the input; signed out, removes them. True when the
     * input has its channels afterwards. [asked] for by someone, as from the input's setup, it is not
     * put off for having failed a moment ago.
     */
    suspend fun sync(context: Context, repo: MolotovRepository, asked: Boolean = false): Boolean = lock.withLock {
        val signedIn = repo.signedIn() ?: return@withLock false
        if (!signedIn) {
            context.contentResolver.delete(TvContract.buildChannelsUriForInput(inputId(context)), null, null)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(SYNCED_AT).remove(SYNCED_FOR).remove(TRIED_AT).remove(TRIED_FOR).apply()
            return@withLock false
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val account = repo.currentSession()?.userId
        val fresh = now - prefs.getLong(SYNCED_AT, 0) < REFRESH_MS && prefs.getString(SYNCED_FOR, null) == account
        if (fresh && hasChannels(context)) return@withLock true
        // Tried not long ago for this account without getting there, as where the system refuses the
        // rows or has no guide to write to: not at every opening and leaving of the app, each a download
        // of the whole feed.
        val tried = now - prefs.getLong(TRIED_AT, 0)
        if (!asked && tried in 0 until RETRY_MS && prefs.getString(TRIED_FOR, null) == account) return@withLock false
        prefs.edit().putLong(TRIED_AT, now).putString(TRIED_FOR, account).apply()
        val channels = repo.liveChannels(GUIDE_HOURS)
        if (channels.isNotEmpty()) {
            write(context, channels)
            prefs.edit().putLong(SYNCED_AT, now).putString(SYNCED_FOR, account).apply()
        }
        hasChannels(context)
    }

    private fun hasChannels(context: Context): Boolean =
        context.contentResolver.query(TvContract.buildChannelsUriForInput(inputId(context)), arrayOf(TvContract.Channels._ID), null, null, null)
            ?.use { it.count > 0 } == true

    private fun write(context: Context, channels: List<LiveChannel>) {
        val resolver = context.contentResolver
        val inputId = inputId(context)
        val existing = HashMap<String, Long>()
        resolver.query(
            TvContract.buildChannelsUriForInput(inputId),
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) c.getBlob(1)?.let { existing[String(it)] = c.getLong(0) }
        }
        channels.forEachIndexed { index, channel ->
            val values = Channel.Builder()
                .setInputId(inputId)
                .setType(TvContract.Channels.TYPE_OTHER)
                .setDisplayNumber("${index + 1}")
                .setDisplayName(channel.name)
                .setInternalProviderData(channel.id.toByteArray())
                .setAppLinkText("Ouvrir dans etincelle")
                .setAppLinkIntentUri(Uri.parse("etincelle://channel/${Uri.encode(channel.id)}"))
                .build().toContentValues()
            val rowId = existing.remove(channel.id)?.also {
                resolver.update(TvContract.buildChannelUri(it), values, null, null)
            } ?: resolver.insert(TvContract.Channels.CONTENT_URI, values)?.let { ContentUris.parseId(it) }?.also { id ->
                // A new channel gets its logo once (the provider downloads and keeps it).
                channel.logoUrl?.let { runCatching { ChannelLogoUtils.storeChannelLogo(context, id, Uri.parse(it)) } }
            } ?: return@forEachIndexed
            writePrograms(context, rowId, channel)
        }
        // Channels Molotov no longer offers.
        existing.values.forEach { resolver.delete(TvContract.buildChannelUri(it), null, null) }
    }

    private fun writePrograms(context: Context, rowId: Long, channel: LiveChannel) {
        val resolver = context.contentResolver
        resolver.delete(TvContract.buildProgramsUriForChannel(rowId), null, null)
        val values = channel.programs.map { p ->
            Program.Builder()
                .setChannelId(rowId)
                .setTitle(p.title)
                .setEpisodeTitle(p.episodeTitle)
                .setDescription(p.description)
                .setPosterArtUri(p.imageUrl?.let(Uri::parse))
                .setStartTimeUtcMillis(p.startMs)
                .setEndTimeUtcMillis(p.endMs)
                .build().toContentValues()
        }
        if (values.isNotEmpty()) resolver.bulkInsert(TvContract.Programs.CONTENT_URI, values.toTypedArray())
    }

    /** The Molotov channel id of a guide channel row, as written by [sync]. */
    fun channelId(context: Context, channelUri: Uri): String? =
        context.contentResolver.query(channelUri, arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getBlob(0)?.let { String(it) } else null }
}

/**
 * etincelle's TV input: the system tunes a guide channel through it, and it plays that channel live
 * on the surface the system gives, as the app's own player does.
 */
class TvLiveInputService : TvInputService() {
    // The sessions open in this service, told of memory pressure as the app's own player is. Main
    // thread only.
    private val live = mutableSetOf<LiveSession>()

    override fun onCreateSession(inputId: String): Session = LiveSession(this)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        live.forEach { it.loadControl.onTrimMemory(level) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        live.forEach { it.loadControl.onLowMemory() }
    }

    companion object {
        // The sessions the system holds, each a channel of its guide played in this process: an
        // update installed meanwhile would cut it. Main thread only.
        var sessions = 0
            private set
    }

    private inner class LiveSession(context: Context) : Session(context) {
        // Sized to the heap and trimmed under pressure, as the app's own player's.
        val loadControl = AdaptiveLoadControl.create(context)
        private val player = ExoPlayer.Builder(context).setLoadControl(loadControl).build()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var tuneJob: Job? = null
        private var recovery: PlaybackRecovery? = null

        init {
            sessions++
            live += this
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> notifyVideoAvailable()
                        Player.STATE_BUFFERING -> notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
                        else -> Unit
                    }
                }

                // Being brought back (see onTune), which says so itself if it cannot be.
                override fun onPlayerError(error: PlaybackException) =
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
            })
        }

        // The stream of channel [id], or null when it cannot be had.
        private suspend fun resolve(id: String): PlaybackSource? = try {
            withContext(Dispatchers.IO) {
                val repo = (application as EtincelleTvApp).container.repository
                if (repo.currentSession() == null) repo.restoreSession()
                // The system shows its own guide: the programme on air is of no use here.
                repo.resolveLiveChannel(id, programme = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        private fun dropRecovery() {
            recovery?.let {
                player.removeListener(it)
                it.stop()
            }
            recovery = null
        }

        override fun onTune(channelUri: Uri): Boolean {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            // A pressure that lowered the buffer is not told to be over: a new channel starts whole.
            loadControl.restoreFullBuffer()
            tuneJob?.cancel()
            dropRecovery()
            player.stop()
            // A newer tune cancels this one, and reports its own state.
            tuneJob = scope.launch {
                val id = withContext(Dispatchers.IO) { runCatching { TvLive.channelId(this@TvLiveInputService, channelUri) }.getOrNull() }
                val source = id?.let { resolve(it) }
                if (id == null || source == null) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    return@launch
                }
                player.setMediaItem(MediaItemFactory.create(source))
                player.prepare()
                player.playWhenReady = true
                // An error would otherwise leave the channel black until it is tuned again.
                recovery = PlaybackRecovery(player, scope, source, { resolve(id) }) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                }.also(player::addListener)
            }
            return true
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            player.setVideoSurface(surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            player.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) = Unit

        override fun onRelease() {
            sessions--
            live -= this
            dropRecovery()
            scope.cancel()
            player.release()
        }
    }
}

/** The input's setup, run from the system's channel settings: writes the channels, then hands back. */
class TvLiveSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var message by mutableStateOf("Ajout des chaînes Molotov…")
        setContent {
            EtincelleTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(message, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        lifecycleScope.launch {
            val repo = (application as EtincelleTvApp).container.repository
            val synced = withContext(Dispatchers.IO) { runCatching { TvLive.sync(applicationContext, repo, asked = true) }.getOrDefault(false) }
            val signedIn = repo.currentSession() != null
            val ok = synced && signedIn
            if (!ok) {
                message = if (signedIn) "Ajout des chaînes impossible, réessayez plus tard." else "Connectez-vous dans etincelle, puis réessayez."
                delay(3000)
            }
            setResult(if (ok) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }
}
