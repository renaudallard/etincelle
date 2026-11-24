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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Finds the ad breaks of a live channel watched behind the live point, from the pictures the service
 * keeps of it ([LiveThumbnails]), by the logo that is in a corner through the programme and gone
 * during the adverts (see [BreakGuess]). It finds the whole interruption, trailers and sponsors'
 * words included, and is for the channels whose stream marks nothing, or too little: it is not
 * used on one that marks its breaks, where what it adds around them can run into the programme.
 * An advert with something of its own in a logo's corner can pass for the programme: the break then
 * ends early, in the adverts, or is not found.
 *
 * The logo is learnt from the pictures of the forty minutes around what is being watched, a show
 * having graphics of its own, and learnt again as playback moves on. Then the pictures around the
 * playhead are read as it goes, one every few seconds of playback, and further on while a stretch
 * without the logo has not ended. One instance per channel being played, kept while the screen is
 * stopped and started again (see [of]); calls to [around] are taken one at a time.
 */
internal class LiveBreaks(private val thumbnails: LiveThumbnails) {
    private val http = DefaultHttpDataSource.Factory()
    private val step = thumbnails.intervalSec.toLong()
    private val lock = Mutex()

    private var logo: Logo? = null
    // Whether the earlier pictures were searched for the channel's logo, which is done once (see [kept]).
    private var searchedBack = false
    private var learntAt = Long.MIN_VALUE
    // The learns in a row that found no logo.
    private var failures = 0
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
    suspend fun around(atMs: Long, liveMs: Long): List<Guess> = lock.withLock {
        val at = atMs / 1000 / step * step
        // The last pictures are not there yet.
        val last = (liveMs / 1000 - NOT_YET_S) / step * step
        if (at > last) return@withLock emptyList()
        if (abs(at - learntAt) > LEARN_AGAIN_S) learn(at, last)
        val logo = logo ?: return@withLock emptyList()
        read(logo, at - BACK_S, minOf(last, at + AHEAD_S))
        // A stretch without the logo is followed to where the programme is back, or was before it.
        for (more in 0 until MORE_TIMES) {
            val (first, gone) = known(logo, scores, at)
            // The show's logo is followed too: adverts can keep the channel's seen through half a
            // break (see BreakGuess.logoBreaks), which the show's alone then gives.
            val lowerGone = logo.lower?.let { known(it, lowerScores, at).second }
            val runs = BreakGuess.runs(BreakGuess.solid(gone), BreakGuess.BRIDGE) +
                lowerGone?.let { BreakGuess.runs(BreakGuess.solid(it), BreakGuess.BRIDGE) }.orEmpty()
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
        BreakGuess.logoBreaks(gone, first * 1000, step * 1000, lowerGone)
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
        val times = (at - LEARN_SPAN_S..minOf(last, at + LEARN_SPAN_S) step LEARN_EVERY_S).toList()
        // Nothing changes until the pictures are in, and the earlier ones looked at where needed: a
        // learn cut short with the screen is done again.
        val pictures = each(times) { luma(it) }.filterNotNull()
        val own = if (pictures.isEmpty()) null else LogoTrack(pictures.size, width, height).run {
            pictures.forEachIndexed { i, picture -> add(i, picture) }
            logo()
        }
        own?.takeIf { it.channel }?.let { remember(it.alone()) }
        val found = if (pictures.isEmpty()) null else own ?: kept(at, pictures)
        learntAt = at
        logo = found
        scores.clear()
        lowerScores.clear()
        missed.clear()
        if (pictures.isEmpty()) return
        // Nothing learnt: tried again a little further on, then less and less often, as each try
        // fetches all its pictures again.
        if (logo == null) {
            learntAt = at - LEARN_AGAIN_S + minOf(LEARN_AGAIN_S, LEARN_RETRY_S shl failures)
            if (LEARN_RETRY_S shl failures < LEARN_AGAIN_S) failures++
        } else {
            failures = 0
        }
    }

    // [logo], the channel's, kept for when its pictures are read again.
    private fun remember(logo: Logo) {
        remembered[thumbnails.baseUrl] = logo
    }

    // Where no logo stands out in [pictures], as when most of them are of adverts and trailers, the
    // channel's from before, when seen on enough of them: the last found since the app started, or
    // else one looked for in the earlier pictures, once a viewing. One learnt over another programme
    // can fit these pictures too poorly: the top left corner of M6's teleshopping holds its own logo.
    private suspend fun kept(at: Long, pictures: List<ByteArray>): Logo? {
        remembered[thumbnails.baseUrl]?.takeIf { it.seenIn(pictures, KEPT_SEEN) }?.let { return it }
        if (searchedBack) return null
        val back = earlier(at)
        // Only once searched to the end: a search cut short with the screen is made again.
        searchedBack = true
        if (back == null) return null
        remember(back)
        return back.takeIf { it.seenIn(pictures, KEPT_SEEN) }
    }

    // The channel's logo as the pictures before those around [at] show it, forty minutes of them at a
    // time, the nearest first, up to EARLIER_TIMES of them: for a channel opened in the middle of a
    // long block of adverts and trailers, where its logo is faint and stands out only where the
    // programme fills most of the pictures. Null when it stands out in none.
    private suspend fun earlier(at: Long): Logo? {
        for (back in 0 until EARLIER_TIMES) {
            val end = at - LEARN_SPAN_S - back * 2 * LEARN_SPAN_S
            val pictures = each((end - 2 * LEARN_SPAN_S until end step LEARN_EVERY_S).toList()) { luma(it) }.filterNotNull()
            // Older than the service keeps them: the further back are too.
            if (pictures.isEmpty()) return null
            val track = LogoTrack(pictures.size, width, height)
            pictures.forEachIndexed { i, picture -> track.add(i, picture) }
            track.logo()?.takeIf { it.channel }?.let { return it.alone() }
        }
        return null
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

    companion object {
        // The last channel followed, with what was learnt and read of it: back on the screen, it goes
        // on from there instead of fetching the pictures of forty minutes again. Main thread only.
        private var followed: LiveBreaks? = null

        // The channel's logo last found, by the address of its pictures, for a channel played again
        // while the app runs: it stays from one programme to the next, and the hour before the
        // evening's programme often shows it too little to be found then. Any thread.
        private val remembered = ConcurrentHashMap<String, Logo>()

        /** The instance of the channel whose pictures are at [thumbnails]. On the main thread. */
        fun of(thumbnails: LiveThumbnails): LiveBreaks =
            followed?.takeIf { it.thumbnails == thumbnails } ?: LiveBreaks(thumbnails).also { followed = it }

        private const val AT_ONCE = 6
        private const val TRIES = 3
        private const val SAMPLE = 2
        private const val MIN_SIDE = 32

        // How long after it was shown a picture can be asked for.
        private const val NOT_YET_S = 12L

        // The logo is learnt from a picture every LEARN_EVERY_S, this far on each side of the
        // playhead, and again once the playhead is LEARN_AGAIN_S from there.
        private const val LEARN_SPAN_S = 1200L
        private const val LEARN_EVERY_S = 28L
        private const val LEARN_AGAIN_S = 900L
        private const val LEARN_RETRY_S = 120L
        // The share of the pictures of a learn the channel's last logo must be seen on to be taken
        // where none stands out, and how many times forty minutes before it is looked for.
        private const val KEPT_SEEN = 0.4
        private const val EARLIER_TIMES = 3

        // Read around the playhead at all times, and more at a time, up to MORE_TIMES in a go,
        // while a stretch without the logo goes on, for as far as a break may (FAR_S).
        private const val BACK_S = 64L
        private const val AHEAD_S = 88L
        private const val MORE_S = 120L
        private const val MORE_TIMES = 4
        private const val FAR_S = BreakGuess.LOGO_MAX_MS / 1000 + 160

        // Pictures this far behind the playhead are forgotten: no nearer than a read back may reach,
        // as far as a break may and a read more, or they would be read again at each look.
        private const val KEPT_S = FAR_S + MORE_S
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
    val breaks = LiveBreaks.of(thumbnails)
    while (true) {
        val at = LivePlayback.playheadEpochMs(player, window)
        val behind = LivePlayback.behindLiveEdgeMs(player, window)
        if (at != null && behind > BEHIND_MS) guessed(withContext(Dispatchers.IO) { breaks.around(at, at + behind) })
        delay(LOOK_EVERY_MS)
    }
}
