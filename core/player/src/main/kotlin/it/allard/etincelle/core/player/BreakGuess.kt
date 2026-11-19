// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import kotlin.math.abs

/**
 * The rules that tell the ad breaks of a recording from what it carries in the clear, its captions
 * and its thumbnails, the pictures and sound being encrypted and the stream marking nothing. Each is
 * a guess, as measured on the service in November 2026:
 * - Captions: a programme that has them speaks for minutes on end, an advert for seconds. A break is
 *   a stretch between two long runs of captions that has few of its own. Useless on a programme with
 *   no captions, where only the adverts have some.
 * - Logo: the programme shows a logo in a corner, its own or the channel's, and the adverts do not.
 *   What stays put in a corner through most of the recording is taken for it, without knowing any
 *   logo beforehand, and a break is a stretch without it. An advert with something of its own in
 *   that corner can pass for the programme.
 *
 * They fail in different places, so a break is kept where both see one, and on the word of one alone
 * only when the other has nothing to go by. Its edges are the logo's, the sharper of the two: the
 * captions of an advert that follows or precedes the programme closely pass for the programme's.
 * All times in ms.
 */
internal object BreakGuess {
    // A break lasts between these: under, it is a pause or a scene without a word; over, the end of
    // one programme and the start of another.
    const val MIN_MS = 150_000L
    const val MAX_MS = 800_000L

    // A run of captions this long is programme, no advert being; a run holds silences of up to
    // CAPTION_GAP segments.
    private const val PROGRAMME_MS = 60_000L
    private const val CAPTION_GAP = 3

    // What two guesses of a break must share to be the same one, and how far apart their edges may
    // be for the logo's to be taken: as far as captioned adverts next to the programme account for.
    private const val SHARED_MS = 60_000L
    private const val EDGE_MS = 90_000L

    private val PARAGRAPH = Regex("<p\\b[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")

    /** Whether a caption segment, a TTML document inside its container, has anything to read. */
    fun speaks(segment: ByteArray): Boolean =
        PARAGRAPH.findAll(String(segment, Charsets.ISO_8859_1)).any { TAG.replace(it.groupValues[1], "").isNotBlank() }

    /** The runs of true in [flags], each from its first to its last, holding up to [bridge] false in a row. */
    fun runs(flags: BooleanArray, bridge: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < flags.size) {
            if (!flags[i]) {
                i++
                continue
            }
            var last = i
            var j = i + 1
            while (j < flags.size && j - last <= bridge + 1) {
                if (flags[j]) last = j
                j++
            }
            out += i..last
            i = last + 1
        }
        return out
    }

    /**
     * The breaks the captions give away, [spoken] saying of each segment, [segmentMs] long and the
     * first at [firstMs], whether it has text. Null when the programme has too few captions to tell.
     */
    fun captionBreaks(spoken: BooleanArray, firstMs: Long, segmentMs: Long): List<LongRange>? {
        if (segmentMs <= 0 || spoken.count { it } * 2 < spoken.size) return null
        val programme = runs(spoken, CAPTION_GAP).filter { (it.last - it.first + 1) * segmentMs >= PROGRAMME_MS }
        return programme.zipWithNext().mapNotNull { (before, after) ->
            val start = firstMs + (before.last + 1) * segmentMs
            val end = firstMs + after.first * segmentMs
            val inside = (before.last + 1 until after.first).count { spoken[it] }
            (start..end).takeIf { end - start in MIN_MS..MAX_MS && inside * 2 < after.first - before.last - 1 }
        }
    }

    // The share of its usual score under which a picture is without the logo, the pictures either
    // side it is smoothed with, the pictures with the logo a break may hold (an advert with something
    // in that corner), and the pictures of programme wanted on each side of one.
    private const val GONE = 0.5
    private const val SMOOTH = 2
    const val BRIDGE = 7
    const val AROUND = 15

    /**
     * Of each picture, whether it is without the logo, from what each has of it ([score], [usual]
     * with it). One odd picture is no break, nor the end of one: each is taken with its neighbours.
     * A picture not [given] has the logo, and is no neighbour.
     */
    fun gone(score: DoubleArray, usual: Double, given: BooleanArray): BooleanArray = BooleanArray(score.size) { i ->
        given[i] && (maxOf(0, i - SMOOTH)..minOf(score.size - 1, i + SMOOTH)).filter { given[it] }.map { score[it] }.sorted()
            .let { it[it.size / 2] } < GONE * usual
    }

    /**
     * The stretches of [gone] that have the programme on both sides, as breaks; the first picture
     * being at [firstMs] and each [pictureMs] after the one before.
     */
    fun logoBreaks(gone: BooleanArray, firstMs: Long, pictureMs: Long): List<LongRange> {
        fun programme(from: Int, to: Int) = from >= 0 && to <= gone.size && (from until to).none { gone[it] }
        return runs(gone, BRIDGE).mapNotNull { run ->
            val start = firstMs + run.first * pictureMs
            val end = firstMs + (run.last + 1) * pictureMs
            val between = programme(run.first - AROUND, run.first) && programme(run.last + 1, run.last + 1 + AROUND)
            (start..end).takeIf { between && end - start in MIN_MS..MAX_MS }
        }
    }

    /** What [captions] and [logo] agree on; either is null when it had nothing to go by. */
    fun combine(captions: List<LongRange>?, logo: List<LongRange>?): List<LongRange> {
        if (captions == null) return logo.orEmpty()
        if (logo == null) return captions
        return captions.flatMap { spoken ->
            logo.mapNotNull { shown ->
                val late = maxOf(spoken.first, shown.first)
                val early = minOf(spoken.last, shown.last)
                if (early - late < SHARED_MS) return@mapNotNull null
                // Further apart than that, one of them is wrong: the shorter break loses no programme.
                val start = if (abs(spoken.first - shown.first) <= EDGE_MS) shown.first else late
                val end = if (abs(spoken.last - shown.last) <= EDGE_MS) shown.last else early
                start..end
            }
        }
    }
}

/**
 * A logo learnt from pictures (see [LogoTrack]): the points of a picture, [width] wide, where its
 * edges are, with how strong each mostly is, and what a picture that shows it usually scores.
 */
internal class Logo(private val width: Int, private val points: IntArray, private val pattern: IntArray, val usual: Double) {
    private val whole = pattern.sum().toDouble()

    /** How much of the logo the picture of [luma], row by row, has: about [usual] with it, little without. */
    fun score(luma: ByteArray): Double {
        var sum = 0
        for (k in points.indices) {
            val p = points[k]
            val here = luma[p].toInt() and 0xFF
            val edge = abs((luma[p + 1].toInt() and 0xFF) - here) + abs((luma[p + width].toInt() and 0xFF) - here)
            sum += minOf(edge, pattern[k])
        }
        return sum / whole
    }
}

/**
 * The corners of a run of thumbnails, [pictures] of them [width] by [height], kept as the strength
 * of their edges: what is needed to find the logo and where it is gone (see [BreakGuess]). Not every
 * picture need be given: one left out counts as showing the logo, so those around a stretch without
 * it must be. Pictures may be added from several threads, each its own.
 */
internal class LogoTrack(private val pictures: Int, private val width: Int, private val height: Int) {
    private val cornerWidth = (width * CORNER).toInt()
    private val cornerHeight = (height * CORNER).toInt()
    private val edgesWide = cornerWidth - 1
    private val edgesHigh = cornerHeight - 1
    private val size = edgesWide * edgesHigh

    // The left and top of each corner.
    private val origins = arrayOf(0 to 0, width - cornerWidth to 0, 0 to height - cornerHeight, width - cornerWidth to height - cornerHeight)
    private val edges = Array(origins.size) { ByteArray(if (size > 0) pictures * size else 0) }
    private val given = BooleanArray(pictures)

    /** Picture [index], as its [luma] row by row. */
    fun add(index: Int, luma: ByteArray) {
        if (size <= 0 || index !in 0 until pictures || luma.size < width * height) return
        for ((corner, origin) in origins.withIndex()) {
            val (left, top) = origin
            val out = edges[corner]
            var o = index * size
            for (y in 0 until edgesHigh) {
                var p = (top + y) * width + left
                for (x in 0 until edgesWide) {
                    val here = luma[p].toInt() and 0xFF
                    val right = luma[p + 1].toInt() and 0xFF
                    val below = luma[p + width].toInt() and 0xFF
                    out[o++] = minOf(255, abs(right - here) + abs(below - here)).toByte()
                    p++
                }
            }
        }
        given[index] = true
    }

    // The corner that holds the logo, the points of it that are the logo with what each mostly is,
    // and what each picture given has of it.
    private class Learnt(val corner: Int, val mask: IntArray, val median: IntArray, val score: DoubleArray, val usual: Double)

    private fun learn(): Learnt? {
        val read = (0 until pictures).filter { given[it] }.toIntArray()
        if (size <= 0 || read.size < 2 * BreakGuess.AROUND) return null
        val counts = IntArray(256)
        var kept = -1
        var keptMedian = IntArray(0)
        var keptMask = IntArray(0)
        var strongest = 0.0
        for ((c, corner) in edges.withIndex()) {
            // What each point of the corner mostly is: the logo where there is one, nothing where
            // the picture behind keeps changing.
            val median = IntArray(size) { p ->
                counts.fill(0)
                for (i in read) counts[corner[i * size + p].toInt() and 0xFF]++
                var below = 0
                var value = 0
                while (below + counts[value] <= read.size / 2) below += counts[value++]
                value
            }
            val cut = maxOf(1, median.sorted()[(size * MASK).toInt()])
            val mask = (0 until size).filter { median[it] >= cut }.toIntArray()
            if (mask.isEmpty()) continue
            val strength = mask.sumOf { median[it] }.toDouble() / mask.size
            if (strength > strongest) {
                strongest = strength
                kept = c
                keptMedian = median
                keptMask = mask
            }
        }
        if (kept < 0 || strongest < STRONG) return null
        val corner = edges[kept]
        val whole = keptMask.sumOf { keptMedian[it] }.toDouble()
        val score = DoubleArray(pictures)
        for (i in read) {
            val o = i * size
            var sum = 0
            for (p in keptMask) sum += minOf(corner[o + p].toInt() and 0xFF, keptMedian[p])
            score[i] = sum / whole
        }
        return Learnt(kept, keptMask, keptMedian, score, read.map { score[it] }.sorted()[read.size / 2])
    }

    /** The logo of the pictures given, to look for in others. Null when no corner holds anything that stays. */
    fun logo(): Logo? {
        val learnt = learn() ?: return null
        val (left, top) = origins[learnt.corner]
        val points = IntArray(learnt.mask.size) { k -> (top + learnt.mask[k] / edgesWide) * width + left + learnt.mask[k] % edgesWide }
        return Logo(width, points, IntArray(learnt.mask.size) { learnt.median[learnt.mask[it]] }, learnt.usual)
    }

    /** Of each picture, whether it was given and is without the logo. Null as for [logo]. */
    fun gone(): BooleanArray? {
        val learnt = learn() ?: return null
        return BreakGuess.gone(learnt.score, learnt.usual, given)
    }

    /**
     * The stretches without the logo that have the programme, with it, on both sides; the first
     * picture being at [firstMs] and each [pictureMs] after the one before. Null as for [logo].
     */
    fun breaks(firstMs: Long, pictureMs: Long): List<LongRange>? = gone()?.let { BreakGuess.logoBreaks(it, firstMs, pictureMs) }

    private companion object {
        // The share of a picture's width and height a corner takes, and of a corner's points, the
        // strongest, that are the logo.
        const val CORNER = 0.22
        const val MASK = 0.92

        // The least a logo's edges must stand out.
        const val STRONG = 25.0
    }
}
