// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifest
import java.io.IOException
import java.nio.ByteBuffer
import java.util.TreeMap
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The ad breaks of M6, Gulli and a few others (see PlaybackSource.separatedBreaks) as their streams
 * give them away: these channels put 0.48 s of black between two adverts. The pictures are
 * encrypted, but not the size of each frame, in the header of each segment, and a black is a dozen
 * frames of a few bytes, ended by a key frame of several kilobytes where the next advert starts. A break is a series of these, one every 10 to 55 s, or
 * twice that where one was missed.
 *
 * Seen on the service in November 2026, at the lowest rendition (270 lines, 25 frames a second), over
 * four hours of each: on M6, nearly every separator of a break and a few elsewhere; on Gulli the same
 * in its breaks, but cartoons hold still frames that look alike, which made seven in under two
 * minutes. A black's frames are the smallest a frame can be, 32 to 36 bytes, a held picture's
 * mostly more: so they must be [BLACK_MEAN] at most on average, which left at most three of a
 * cartoon's within a minute, and a break is at least [LEAST] of them over [SPAN_MS] or more, none
 * more than [GAP_MS] from the next. A black now and then is a few bytes over and missed (seen: 41 on
 * average), which leaves two adverts between two others, 95 s on M6: two runs of [PART] or more are
 * one across up to [WIDE_GAP_MS], but not a run of one or two, as a cartoon's last blacks just
 * before a break on Canal J. W9 and 6ter have none.
 * The first advert has no separator before it and the last none after it, so the separators of a
 * break are all in its adverts. A stray one, in the programme less than a minute from a break, would
 * be counted in it: a break runs from the second to the one before the last, which one stray at
 * either end leaves in the adverts, and it can be skipped from the one to the other.
 * All times in ms.
 */
internal object Separators {
    // A frame of black, or of a picture held, is a few bytes; the key frame after a black is several
    // kilobytes, as a picture held ends with no new one. At 25 frames a second, 0.48 s is 12 frames,
    // of which those around the segment's own key frames can be counted in or out.
    private const val TINY = 120
    private const val BLACK_MEAN = 40
    private const val KEY = 4000
    private val BLACK = 10..14

    // The separators of a break, at the least, how long they span at the least, and the most apart
    // two of them are: the longest advert seen between two was 55 s. Two runs of PART or more are one
    // across up to WIDE_GAP_MS, where a black was missed: two adverts, 95 s seen.
    const val LEAST = 6
    const val SPAN_MS = 90_000L
    const val GAP_MS = 60_000L
    const val WIDE_GAP_MS = 100_000L
    const val PART = 3

    // The most bytes a segment's header is read to, and the most frames it may list: a few
    // kilobytes for its hundred frames.
    private const val MOOF_MAX = 65_536
    private const val MOST_FRAMES = 10_000

    /**
     * Where the separators in a segment end, the next advert starting: [sizes] are its frames', the
     * first at [startMs], each [frameMs] long.
     */
    fun ends(sizes: IntArray, startMs: Long, frameMs: Double): List<Long> {
        val out = ArrayList<Long>()
        var tiny = 0
        var bytes = 0
        for ((i, size) in sizes.withIndex()) {
            if (size in 0 until TINY) {
                tiny++
                bytes += size
                continue
            }
            if (tiny in BLACK && bytes <= BLACK_MEAN * tiny && size > KEY) out += startMs + (i * frameMs).roundToLong()
            tiny = 0
            bytes = 0
        }
        return out
    }

    /** The breaks the separators ending at [ends] make, in order: each from its second to the one before its last. */
    fun breaks(ends: List<Long>): List<LongRange> {
        val runs = ArrayList<MutableList<Long>>()
        for (end in ends.sorted()) {
            val last = runs.lastOrNull()
            if (last != null && end - last.last() <= GAP_MS) last += end else runs += mutableListOf(end)
        }
        val joined = ArrayList<MutableList<Long>>()
        for (run in runs) {
            val last = joined.lastOrNull()
            if (last != null && run.first() - last.last() <= WIDE_GAP_MS && last.size >= PART && run.size >= PART) last += run else joined += run
        }
        return joined.filter { it.size >= LEAST && it.last() - it.first() >= SPAN_MS }.map { it[1]..it[it.size - 2] }
    }

    /**
     * Where the first moof of a segment whose first bytes are [d] ends, which may be past them; null
     * when they do not reach its header, or it is not where a segment has it.
     */
    fun moofEnd(d: ByteArray): Int? {
        val b = ByteBuffer.wrap(d)
        var a = 0
        while (d.size - a >= 8) {
            val size = b.getInt(a).toLong() and 0xFFFFFFFFL
            if (size < 8 || a + size > MOOF_MAX) return null
            if (type(d, a) == "moof") return a + size.toInt()
            a += size.toInt()
        }
        return null
    }

    /**
     * The sizes of the frames of a segment whose first bytes are [d], from its first moof; null when
     * [d] does not hold all of it, or it cannot be read.
     */
    fun sizes(d: ByteArray): IntArray? {
        val end = moofEnd(d) ?: return null
        if (end > d.size) return null
        val b = ByteBuffer.wrap(d)
        val moof = boxes(b, 0, end).lastOrNull()?.takeIf { it.type == "moof" } ?: return null
        val traf = boxes(b, moof.from, moof.to).firstOrNull { it.type == "traf" } ?: return null
        val out = ArrayList<Int>()
        var fallback = -1
        for (box in boxes(b, traf.from, traf.to)) {
            val a = box.from
            if (box.type != "tfhd" && box.type != "trun") continue
            if (box.to - a < 8) return null
            val flags = b.getInt(a) and 0xFFFFFF
            when (box.type) {
                "tfhd" -> {
                    // After the track: a base offset, a description index, a frame length, then the size.
                    var p = a + 8
                    if (flags and 0x01 != 0) p += 8
                    if (flags and 0x02 != 0) p += 4
                    if (flags and 0x08 != 0) p += 4
                    if (flags and 0x10 != 0) {
                        if (p + 4 > box.to) return null
                        fallback = b.getInt(p)
                    }
                }
                "trun" -> {
                    val count = b.getInt(a + 4)
                    // Each frame has a length, a size, flags and an offset, each when the flags say so.
                    val each = Integer.bitCount(flags and 0xF00) * 4
                    var p = a + 8 + (if (flags and 0x01 != 0) 4 else 0) + (if (flags and 0x04 != 0) 4 else 0)
                    if (count !in 0..MOST_FRAMES || p + count * each > box.to) return null
                    if (flags and 0x200 == 0 && fallback < 0) return null
                    repeat(count) {
                        val at = p + (if (flags and 0x100 != 0) 4 else 0)
                        out += if (flags and 0x200 != 0) b.getInt(at) else fallback
                        p += each
                    }
                }
            }
        }
        return out.toIntArray()
    }

    private class Box(val type: String, val from: Int, val to: Int)

    // The whole boxes from [from] to [to], in order: their type and where their content is.
    private fun boxes(b: ByteBuffer, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var a = from
        while (to - a >= 8) {
            val size = b.getInt(a).toLong() and 0xFFFFFFFFL
            if (size < 8 || size > to - a) break
            out += Box(type(b.array(), a), a + 8, a + size.toInt())
            a += size.toInt()
        }
        return out
    }

    private fun type(d: ByteArray, at: Int) = String(d, at + 4, 4, Charsets.ISO_8859_1)
}

/**
 * A segment of a stream's video: when it starts (ms since 1970), how long it is, and where it is,
 * from [offset] in what [uri] gives.
 */
internal class Segment(val startMs: Long, val durationMs: Long, val uri: Uri, val offset: Long)

/**
 * Reads the separators of the live stream around the playhead (see [Separators]), from the first
 * bytes of each segment of its lowest video rendition, and gives the breaks they make. One instance
 * per stream watched; calls to [around] are taken one at a time.
 */
internal class SeparatorBreaks {
    private val http = DefaultHttpDataSource.Factory()
    private val lock = Mutex()

    // The separators of each segment read, by its start (ms since 1970).
    private val read = TreeMap<Long, LongArray>()

    // The segments that could not be read, with how many times each was asked for.
    private val missed = HashMap<Long, Int>()

    /**
     * The breaks around the playhead of the stream [dash] describes, at [atMs] with the live point
     * at [liveMs] (ms since 1970), as far as they are known, in the same time. Reads the segments it
     * still needs: for an IO thread.
     */
    suspend fun around(dash: DashManifest, atMs: Long, liveMs: Long): List<LongRange> = lock.withLock {
        val wanted = segments(dash, atMs - FAR_MS, minOf(liveMs - NOT_YET_MS, atMs + FAR_MS))
            .filter { it.startMs !in read || (missed[it.startMs] ?: TRIES) < TRIES }
        val got = coroutineScope {
            val gate = Semaphore(AT_ONCE)
            wanted.map { segment -> async { gate.withPermit { ensureActive(); ends(segment) } } }.awaitAll()
        }
        // One that cannot be read has no separator, which ends a break rather than making one, and is
        // asked for again the next times, a few of them.
        wanted.forEachIndexed { i, segment ->
            val ends = got[i]
            if (ends == null) missed[segment.startMs] = (missed[segment.startMs] ?: 0) + 1 else missed.remove(segment.startMs)
            read[segment.startMs] = ends ?: LongArray(0)
        }
        read.headMap(atMs - KEPT_MS).clear()
        missed.keys.removeAll { it < atMs - KEPT_MS }
        Separators.breaks(read.values.flatMap { it.asList() })
    }

    // Where the separators of [segment] end; null when it cannot be read.
    private fun ends(segment: Segment): LongArray? {
        var bytes = fetch(segment, HEAD_BYTES) ?: return null
        val end = Separators.moofEnd(bytes) ?: return null
        if (end > bytes.size) bytes = fetch(segment, end) ?: return null
        val sizes = Separators.sizes(bytes)?.takeIf { it.isNotEmpty() } ?: return null
        return Separators.ends(sizes, segment.startMs, segment.durationMs.toDouble() / sizes.size).toLongArray()
    }

    private fun fetch(segment: Segment, length: Int): ByteArray? = try {
        val spec = DataSpec.Builder().setUri(segment.uri).setPosition(segment.offset).setLength(length.toLong()).build()
        DataSourceInputStream(http.createDataSource(), spec).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    companion object {
        private const val AT_ONCE = 6
        private const val TRIES = 3

        // A segment's header is in its first few kilobytes; more is read when it is not.
        private const val HEAD_BYTES = 8192

        // Read this far on each side of the playhead: further than a break lasts, so that one the
        // playhead is in is known from its first separator to its last.
        private const val FAR_MS = 480_000L

        // What was read is kept this far behind the playhead: a break longer than FAR_MS keeps its
        // start, and with it the time it began (see AdBreaks.current).
        private const val KEPT_MS = 2 * FAR_MS

        // How long after the live point a segment can be asked for.
        private const val NOT_YET_MS = 8_000L

        // The most segments listed at once, for a manifest that would give them too short.
        private const val MOST = 1000

        /**
         * The segments of the lowest video rendition of [dash] that play between [fromMs] and [toMs]
         * (ms since 1970), in order.
         */
        fun segments(dash: DashManifest, fromMs: Long, toMs: Long): List<Segment> {
            val out = ArrayList<Segment>()
            if (dash.availabilityStartTimeMs == C.TIME_UNSET || toMs <= fromMs) return out
            for (i in 0 until dash.periodCount) {
                val period = dash.getPeriod(i)
                val periodMs = dash.availabilityStartTimeMs + period.startMs
                val lengthUs = dash.getPeriodDurationUs(i)
                val rep = period.adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { it.representations }.filter { it.format.bitrate > 0 }.minByOrNull { it.format.bitrate } ?: continue
                val index = rep.index ?: continue
                val base = rep.baseUrls.firstOrNull()?.url ?: continue
                val count = index.getSegmentCount(lengthUs)
                val last = if (count == DashSegmentIndex.INDEX_UNBOUNDED.toLong()) Long.MAX_VALUE else index.firstSegmentNum + count - 1
                var n = maxOf(index.firstSegmentNum, index.getSegmentNum(Util.msToUs(maxOf(0L, fromMs - periodMs)), lengthUs))
                while (n <= last && out.size < MOST) {
                    val start = periodMs + Util.usToMs(index.getTimeUs(n))
                    if (start >= toMs) break
                    val duration = Util.usToMs(index.getDurationUs(n, lengthUs))
                    if (duration <= 0) break
                    if (start + duration > fromMs) {
                        val where = index.getSegmentUrl(n)
                        out += Segment(start, duration, where.resolveUri(base), where.start)
                    }
                    n++
                }
            }
            return out
        }
    }
}

// Nothing is looked for this close to the live point: a break goes on for minutes, and there would
// be nowhere to skip to.
private const val BEHIND_MS = 30_000L
private const val LOOK_EVERY_MS = 4_000L

/**
 * Has this skipper follow the separators of the live stream [player] plays (see [SeparatorBreaks]):
 * while it is watched behind the live point, the breaks around the playhead are kept up to date, for
 * [AdSkipper.tick] to skip or offer. Only for a channel that has them: see
 * PlaybackSource.separatedBreaks. Never returns; to be cancelled with the screen.
 */
suspend fun AdSkipper.followSeparators(player: Player, window: Timeline.Window) {
    val breaks = SeparatorBreaks()
    while (true) {
        val dash = player.currentManifest as? DashManifest
        val at = LivePlayback.playheadEpochMs(player, window)
        val behind = LivePlayback.behindLiveEdgeMs(player, window)
        if (dash != null && at != null && behind > BEHIND_MS) separated(withContext(Dispatchers.IO) { breaks.around(dash, at, at + behind) })
        delay(LOOK_EVERY_MS)
    }
}
