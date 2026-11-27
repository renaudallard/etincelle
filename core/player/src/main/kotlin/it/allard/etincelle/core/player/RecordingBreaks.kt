// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Representation
import it.allard.etincelle.core.model.PlaybackSource
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Finds the ad breaks of a recording, so that one being watched can be skipped: a recording is one
 * unbroken stream with no marker, but its captions and its thumbnails come in the clear, and
 * [BreakGuess] reads the breaks from them. It means fetching every caption segment (about a megabyte
 * for an hour) and two strips of thumbnails in three (some fifteen megabytes, a hundredth of what
 * playing the hour takes), once per recording for as long as the app lives. Some ten seconds on a
 * home network. The blacks between its adverts (see [SeparatorBreaks.recording]) are read once per
 * recording too, from the first bytes of every segment of two renditions: some eight megabytes an
 * hour, about a minute.
 */
object RecordingBreaks {
    private const val CAPTIONS_AT_ONCE = 6
    private const val STRIPS_AT_ONCE = 3

    // No recording has more segments: one that says so is not read.
    private const val MAX_SEGMENTS = 10_000L

    // Nor more strips of thumbnails: eleven hours of them, and what is kept of each is memory.
    private const val MAX_STRIPS = 400

    // A strip of thumbnails is decoded at half its size, which the logo survives.
    private const val SAMPLE = 2
    private const val KNOWN = 20

    // A recording that could not be read is not searched again before this.
    internal const val RETRY_MS = 5 * 60_000L

    private val http = DefaultHttpDataSource.Factory()

    // Not the screen's: a search cut short by leaving it would download it all again on return.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // By the captions and thumbnails, and by the blacks between the adverts.
    private val guesses = Searches<List<Guess>> { read(it) }
    private val blacks = Searches<List<SeparatedBreak>> { SeparatorBreaks().recording(it) }

    /**
     * The breaks of the recording [key], whose [manifest] the player holds, as positions (ms) in it.
     * Empty when none is found; null when it could not be read, and is not to be asked again before
     * [RETRY_MS].
     */
    suspend fun find(key: String, manifest: DashManifest): List<Guess>? = guesses.find(key, manifest)

    /** The breaks that the blacks between the adverts of the recording [key] give, as [find] does. */
    suspend fun separated(key: String, manifest: DashManifest): List<SeparatedBreak>? = blacks.find(key, manifest)

    // What a search found in each of the last KNOWN recordings, so that coming back to one, or to the
    // phone's player after a cast, does not download it again.
    private class Searches<T : Any>(private val look: suspend (DashManifest) -> T?) {
        // Nor cancelled with the screen, so recordings opened one after the other would be searched
        // all at once, each with its downloads, and its pictures in memory: one at a time.
        private val oneAtATime = Mutex()

        // All three under the lock of [known].
        private val known = LinkedHashMap<String, T>()
        private val running = HashMap<String, Deferred<T?>>()
        private val failedAt = LinkedHashMap<String, Long>()

        suspend fun find(key: String, manifest: DashManifest): T? {
            val search = synchronized(known) {
                known[key]?.let { return it }
                // What could not be read is not remembered as nothing found: asked again a while later.
                failedAt[key]?.let { if (SystemClock.elapsedRealtime() - it < RETRY_MS) return null }
                // The search cannot finish before it is listed: it takes the lock to say so.
                running.getOrPut(key) { scope.async { search(key, manifest) } }
            }
            return search.await()
        }

        private suspend fun search(key: String, manifest: DashManifest): T? {
            var found: T? = null
            try {
                found = try {
                    oneAtATime.withLock { look(manifest) }
                } catch (e: IOException) {
                    null
                }
            } finally {
                synchronized(known) {
                    running.remove(key)
                    failedAt.remove(key)
                    val result = found
                    if (result == null) {
                        failedAt[key] = SystemClock.elapsedRealtime()
                        if (failedAt.size > KNOWN) failedAt.remove(failedAt.keys.first())
                    } else {
                        known[key] = result
                        if (known.size > KNOWN) known.remove(known.keys.first())
                    }
                }
            }
            return found
        }
    }

    private suspend fun read(manifest: DashManifest): List<Guess> = coroutineScope {
        // One still being made, of no length yet, is read once it is whole: what is found is kept.
        if (manifest.dynamic) throw IOException("not whole yet")
        if (manifest.periodCount != 1) return@coroutineScope emptyList()
        val lengthMs = manifest.getPeriodDurationMs(0)
        if (lengthMs == C.TIME_UNSET) throw IOException("of no length yet")
        val sets = manifest.getPeriod(0).adaptationSets
        val text = sets.filter { it.type == C.TRACK_TYPE_TEXT }.mapNotNull { it.representations.firstOrNull() }
        val image = sets.firstOrNull { it.type == C.TRACK_TYPE_IMAGE }?.representations?.firstOrNull()
        val captions = async { text.firstNotNullOfOrNull { captionBreaks(it, lengthMs) } }
        val logo = async { image?.let { logoBreaks(it, lengthMs) } }
        BreakGuess.combine(captions.await(), logo.await()).filter { it.range.first > 0 && it.range.last < lengthMs }
    }

    private class Segment(val timeMs: Long, val durationMs: Long, val uri: Uri)

    private fun segments(representation: Representation, lengthMs: Long): List<Segment> {
        val index = representation.index ?: return emptyList()
        val base = representation.baseUrls.firstOrNull()?.url ?: return emptyList()
        val lengthUs = Util.msToUs(lengthMs)
        val count = index.getSegmentCount(lengthUs)
        if (count !in 1..MAX_SEGMENTS) return emptyList()
        val first = index.firstSegmentNum
        // Segments that are numbered rather than timed, as older recordings have them, are timed by
        // the player library from the start of the recording, though the first may begin before it:
        // a recording's strips of thumbnails do, by a minute and a half. Where the first begins is in
        // its number and the track's offset, as the manifest's own eptDelta has it. Never a whole
        // segment before, nor after: that would be numbers that do not count from the same start.
        val length = index.getDurationUs(first, lengthUs)
        val early = if (index.isExplicit) 0L else ((first - 1) * length - representation.presentationTimeOffsetUs).takeIf { it < 0 && -it < length } ?: 0L
        return (first until first + count).map { n ->
            Segment(Util.usToMs(index.getTimeUs(n) + early), Util.usToMs(index.getDurationUs(n, lengthUs)), index.getSegmentUrl(n).resolveUri(base))
        }
    }

    // Asked twice: one that fails for a moment would otherwise have the whole search made again, and
    // all of it downloaded again.
    private fun fetch(uri: Uri): ByteArray? = fetchOnce(uri) ?: fetchOnce(uri)

    private fun fetchOnce(uri: Uri): ByteArray? = try {
        DataSourceInputStream(http.createDataSource(), DataSpec(uri)).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    // Runs [work] on each of [items], [atOnce] at a time, on the caller's (IO) threads.
    private suspend fun <T, R> each(items: List<T>, atOnce: Int, work: (Int, T) -> R): List<R> = coroutineScope {
        val gate = Semaphore(atOnce)
        items.mapIndexed { i, item ->
            async {
                gate.withPermit {
                    ensureActive()
                    work(i, item)
                }
            }
        }.awaitAll()
    }

    private suspend fun captionBreaks(representation: Representation, lengthMs: Long): List<LongRange>? {
        val segments = segments(representation, lengthMs)
        if (segments.isEmpty()) return null
        val spoken = each(segments, CAPTIONS_AT_ONCE) { _, segment -> fetch(segment.uri)?.let(BreakGuess::speaks) }
        // Too many that could not be read say nothing of the recording.
        if (spoken.count { it == null } * 20 > spoken.size) throw IOException("captions unread")
        // One that could not be read counts as spoken: it can hide a break, not make one.
        return BreakGuess.captionBreaks(BooleanArray(spoken.size) { spoken[it] != false }, segments[0].timeMs, segments[0].durationMs)
    }

    private suspend fun logoBreaks(representation: Representation, lengthMs: Long): List<Guess>? {
        val across = representation.format.tileCountHorizontal
        val down = representation.format.tileCountVertical
        if (across == Format.NO_VALUE || down == Format.NO_VALUE || across <= 0 || down <= 0) return null
        val strips = segments(representation, lengthMs)
        if (strips.isEmpty() || strips.size > MAX_STRIPS) return null
        val perStrip = across * down
        var track: LogoTrack? = null
        var width = 0
        var height = 0
        var tooBig = false
        val lock = Any()
        // Gives the track the pictures of the strips [wanted]; false when one could not be had.
        suspend fun give(wanted: List<Int>): Boolean = each(wanted, STRIPS_AT_ONCE) { _, i ->
            val bitmap = fetch(strips[i].uri)?.let { decode(it) } ?: return@each false
            try {
                val w = bitmap.width / across
                val h = bitmap.height / down
                val logo = synchronized(lock) {
                    if (track == null && !tooBig) {
                        width = w
                        height = h
                        // Some megabytes for an hour: a device short of them does without the logo.
                        track = try {
                            LogoTrack(strips.size * perStrip, w, h)
                        } catch (e: OutOfMemoryError) {
                            tooBig = true
                            null
                        }
                    }
                    track.takeIf { w == width && h == height && w > 0 && h > 0 }
                } ?: return@each false
                val pixels = IntArray(w * h)
                val luma = ByteArray(w * h)
                for (k in 0 until perStrip) {
                    bitmap.getPixels(pixels, 0, w, (k % across) * w, (k / across) * h, w, h)
                    for (p in pixels.indices) {
                        val c = pixels[p]
                        luma[p] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
                    }
                    logo.add(i * perStrip + k, luma)
                }
                true
            } finally {
                bitmap.recycle()
            }
        }.all { it }
        // Every other strip first: a break is longer than the one between two of them, so it shows
        // in at least one. Then those next to a strip with the logo gone, for the break's edges.
        // A strip missing where it was wanted would pass for a break, or hide one: nothing then.
        fun unread(): Nothing? = if (tooBig) null else throw IOException("thumbnails unread")
        if (!give(strips.indices.filter { it % 2 == 0 })) return unread()
        val logo = track ?: return null
        val gone = logo.gone() ?: return null
        fun suspect(strip: Int) = strip in strips.indices && (strip * perStrip until (strip + 1) * perStrip).any { gone[it] }
        if (!give(strips.indices.filter { it % 2 == 1 && (suspect(it - 1) || suspect(it + 1)) })) return unread()
        return logo.breaks(strips[0].timeMs, strips[0].durationMs / perStrip)
    }

    private fun decode(strip: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = SAMPLE
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(strip, 0, strip.size, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

/**
 * Has this skipper follow the recording [key] that [player] plays: its breaks are looked for once the
 * player has its manifest (see [RecordingBreaks]), and again later, less and less often, as long as
 * they could not be read, then [onBreak] is told every second which one playback is in, to be offered
 * with a button, when [AdSkipper.tick] has not skipped it. Nothing is done while the player plays
 * something else, as when a screen comes back to a recording that the next episode followed in the
 * background. Never returns; to be cancelled with the screen.
 */
suspend fun AdSkipper.followRecording(
    player: Player,
    key: String,
    window: Timeline.Window,
    onBreak: (AdBreak?) -> Unit,
): Unit = coroutineScope {
    val manifest = manifestOf(player, key)
    fun <T : Any> follow(find: suspend (DashManifest) -> T?, found: (T) -> Unit) = launch {
        var dash = manifest
        var wait = RecordingBreaks.RETRY_MS
        while (true) {
            find(dash)?.let {
                found(it)
                return@launch
            }
            delay(wait)
            // Less and less often: one that can never be read would be downloaded again and again.
            wait *= 2
            // The player's own by then: a recording still being made has become whole.
            dash = manifestOf(player, key)
        }
    }
    // The blacks between its adverts, where its channel has them, read from its segments.
    follow({ RecordingBreaks.separated(key, it) }) { recordedSeparated(it) }
    follow({ RecordingBreaks.find(key, it) }) { recorded(it) }
    while (true) {
        onBreak(if (recordingOf(player) == key) tick(player, window) else null)
        delay(1000)
    }
}

// The recording [player] plays, from the source its item was made of.
private fun recordingOf(player: Player): String? =
    (player.currentMediaItem?.localConfiguration?.tag as? PlaybackSource)?.originRecordingAssetId

// The manifest of the recording [key], once [player] has it. A cast player has none, and never will:
// there is nothing to look in.
private suspend fun manifestOf(player: Player, key: String): DashManifest {
    while (true) {
        if (recordingOf(player) == key) (player.currentManifest as? DashManifest)?.let { return it }
        delay(1000)
    }
}
