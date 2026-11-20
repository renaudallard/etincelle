// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import it.allard.etincelle.core.model.LiveThumbnails
import java.io.IOException
import java.util.TreeMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Finds the ad breaks of a live channel watched behind the live point, from the pictures the service
 * keeps of it ([LiveThumbnails]), by the logo that is in a corner through the programme and gone
 * during the adverts (see [BreakGuess]). It finds the whole interruption, with the trailers and the
 * sponsors' words that a stream's own markers leave out, and works where the stream marks nothing.
 * An advert with something of its own in the logo's corner can pass for the programme: the break then
 * ends early, in the adverts.
 *
 * The logo is learnt from the pictures of the forty minutes around what is being watched, a show
 * having graphics of its own, and learnt again as playback moves on. Then the pictures around the
 * playhead are read as it goes, one every few seconds of playback, and further on while a stretch
 * without the logo has not ended. One instance per channel being played; not for several threads.
 */
internal class LiveBreaks(private val thumbnails: LiveThumbnails) {
    private val http = DefaultHttpDataSource.Factory()
    private val step = thumbnails.intervalSec.toLong()

    private var logo: Logo? = null
    private var learntAt = Long.MIN_VALUE
    private var width = 0
    private var height = 0

    // What each picture read has of the logo, by its time (s since 1970, a multiple of the step).
    private val scores = TreeMap<Long, Double>()
    // The same of the show's own logo, when one was learnt beside the channel's.
    private val lowerScores = TreeMap<Long, Double>()

    // The pictures that could not be had, with how many times each was asked for.
    private val missed = HashMap<Long, Int>()

    /**
     * The breaks around the playhead, at [atMs] with the live point at [liveMs] (ms since 1970), as
     * far as they are known, in the same time. Reads the pictures it still needs: for an IO thread.
     */
    suspend fun around(atMs: Long, liveMs: Long): List<LongRange> {
        val at = atMs / 1000 / step * step
        // The last pictures are not there yet.
        val last = (liveMs / 1000 - NOT_YET_S) / step * step
        if (at > last) return emptyList()
        if (abs(at - learntAt) > LEARN_AGAIN_S) learn(at, last)
        val logo = logo ?: return emptyList()
        read(logo, at - BACK_S, minOf(last, at + AHEAD_S))
        // A stretch without the logo is followed to where the programme is back, or was before it.
        for (more in 0 until MORE_TIMES) {
            val (first, gone) = known(logo, scores, at)
            val runs = BreakGuess.runs(BreakGuess.solid(gone), BreakGuess.BRIDGE)
            val until = first + gone.size * step
            val open = runs.any { it.last + BreakGuess.AROUND >= gone.size && (gone.size - it.first) * step <= FAR_S }
            val begun = runs.any { it.first < BreakGuess.AROUND && (it.last + 1) * step <= FAR_S }
            if (open && until <= last) {
                read(logo, until, minOf(last, until + MORE_S))
            } else if (begun && first > at - FAR_S) {
                // No further back than a break the playhead is in can have begun: what is read
                // beyond is of no use, and is forgotten below only to be read again.
                read(logo, first - MORE_S, first - step)
            } else {
                break
            }
        }
        val (first, gone) = known(logo, scores, at)
        // Read at the same times as the first, so over the same pictures.
        val lowerGone = logo.lower?.let { known(it, lowerScores, at).second }
        scores.headMap(at - KEPT_S).clear()
        lowerScores.headMap(at - KEPT_S).clear()
        missed.keys.removeAll { it < at - KEPT_S }
        return BreakGuess.logoBreaks(gone, first * 1000, step * 1000, lowerGone)
    }

    // The pictures read, without a gap, around [at]: the time of the first, and whether each is
    // without the logo.
    private fun known(logo: Logo, scores: TreeMap<Long, Double>, at: Long): Pair<Long, BooleanArray> {
        var first = at
        while (scores.containsKey(first - step)) first -= step
        var end = at
        while (scores.containsKey(end)) end += step
        val score = DoubleArray(((end - first) / step).toInt()) { scores[first + it * step] ?: logo.usual }
        return first to BreakGuess.gone(score, logo.usual, BooleanArray(score.size) { true })
    }

    // Reads the pictures from [from] to [to] that are not known yet. One that cannot be had counts
    // as showing the logo, which ends a break rather than making one, and is asked for again the
    // next times, a few of them.
    private suspend fun read(logo: Logo, from: Long, to: Long) {
        val wanted = (from..to step step).filter { it !in scores || (missed[it] ?: TRIES) < TRIES }
        if (wanted.isEmpty()) return
        val lower = logo.lower
        val got = each(wanted) { time -> luma(time)?.let { logo.score(it) to lower?.score(it) } }
        wanted.forEachIndexed { i, time ->
            val score = got[i]
            if (score == null) missed[time] = (missed[time] ?: 0) + 1 else missed.remove(time)
            scores[time] = score?.first ?: logo.usual
            if (lower != null) lowerScores[time] = score?.second ?: lower.usual
        }
    }

    private suspend fun learn(at: Long, last: Long) {
        learntAt = at
        logo = null
        scores.clear()
        lowerScores.clear()
        missed.clear()
        val times = (at - LEARN_SPAN_S..minOf(last, at + LEARN_SPAN_S) step LEARN_EVERY_S).toList()
        val pictures = each(times) { luma(it) }.filterNotNull()
        if (pictures.isEmpty()) return
        val track = LogoTrack(pictures.size, width, height)
        pictures.forEachIndexed { i, picture -> track.add(i, picture) }
        logo = track.logo()
        // Nothing learnt, as when the pictures could not be had: tried again a little further on.
        if (logo == null) learntAt = at - LEARN_AGAIN_S + LEARN_RETRY_S
    }

    private suspend fun <R> each(times: List<Long>, work: (Long) -> R): List<R> = coroutineScope {
        val gate = Semaphore(AT_ONCE)
        times.map { time -> async { gate.withPermit { work(time) } } }.awaitAll()
    }

    // The picture of [time], at half its size, as its luma row by row. Null when it cannot be had,
    // or is not of the size of the others.
    private fun luma(time: Long): ByteArray? {
        val bytes = try {
            DataSourceInputStream(http.createDataSource(), DataSpec(Uri.parse(thumbnails.baseUrl.trimEnd('/') + "/" + time))).use { it.readBytes() }
        } catch (e: IOException) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = SAMPLE
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        try {
            val w = bitmap.width
            val h = bitmap.height
            synchronized(this) {
                if (width == 0) {
                    width = w
                    height = h
                }
                if (w != width || h != height || w < MIN_SIDE || h < MIN_SIDE) return null
            }
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            return ByteArray(w * h) { p ->
                val c = pixels[p]
                ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val AT_ONCE = 6
        const val TRIES = 3
        const val SAMPLE = 2
        const val MIN_SIDE = 32

        // How long after it was shown a picture can be asked for.
        const val NOT_YET_S = 12L

        // The logo is learnt from a picture every LEARN_EVERY_S, this far on each side of the
        // playhead, and again once the playhead is LEARN_AGAIN_S from there.
        const val LEARN_SPAN_S = 1200L
        const val LEARN_EVERY_S = 28L
        const val LEARN_AGAIN_S = 900L
        const val LEARN_RETRY_S = 120L

        // Read around the playhead at all times, and more at a time, up to MORE_TIMES in a go,
        // while a stretch without the logo goes on, for as far as a break may (FAR_S).
        const val BACK_S = 64L
        const val AHEAD_S = 88L
        const val MORE_S = 120L
        const val MORE_TIMES = 4
        const val FAR_S = 960L

        // Pictures this far behind the playhead are forgotten.
        const val KEPT_S = 1200L
    }
}

// How far behind the live point a break is looked for: at it there is nowhere to skip to.
private const val BEHIND_MS = 60_000L
private const val LOOK_EVERY_MS = 4_000L
private const val PICTURE_EVERY_S = 4

/**
 * Has this skipper follow the live channel [player] plays, whose pictures are at [thumbnails]: while
 * it is watched behind the live point, the breaks around the playhead are looked for (see
 * [LiveBreaks]) and kept up to date, for [AdSkipper.tick] to offer. Never returns; to be cancelled
 * with the screen.
 */
suspend fun AdSkipper.followLive(player: Player, thumbnails: LiveThumbnails, window: Timeline.Window) {
    // The rule counts in pictures four seconds apart, as the service keeps them.
    if (thumbnails.intervalSec != PICTURE_EVERY_S) return
    val breaks = LiveBreaks(thumbnails)
    while (true) {
        val at = LivePlayback.playheadEpochMs(player, window)
        val behind = LivePlayback.behindLiveEdgeMs(player, window)
        if (at != null && behind > BEHIND_MS) guessed(withContext(Dispatchers.IO) { breaks.around(at, at + behind) })
        delay(LOOK_EVERY_MS)
    }
}
