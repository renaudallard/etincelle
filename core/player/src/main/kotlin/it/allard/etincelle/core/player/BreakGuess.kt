// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import kotlin.math.abs

/**
 * A break guessed from what a stream carries, from [range.first] to [range.last] (ms). [sure] when
 * nothing in how it was found leaves its edges in doubt (see [BreakGuess.logoBreaks]): it may be
 * skipped unasked when the settings allow it.
 */
data class Guess(val range: LongRange, val sure: Boolean = false)

/**
 * The rules that tell the ad breaks of a recording from what it carries in the clear, its captions
 * and its thumbnails, the pictures and sound being encrypted and the stream marking nothing. Each is
 * a guess, as measured on the service in November 2026:
 * - Captions: a programme that has them speaks for minutes on end, an advert for seconds. A break is
 *   a stretch between two long runs of captions that has few of its own. Useless on a programme with
 *   no captions, where only the adverts have some.
 * - Logo: the programme shows a logo in a corner, its own or the channel's, and the adverts do not.
 *   What stays put in a corner through most of the recording is taken for it, without knowing any
 *   logo beforehand, and a break is a stretch without it. The channel's, in an upper corner, comes
 *   first: a show's own, lower, is missing from its titles and from the short programmes around
 *   it, which would pass for adverts. Where both are found, a picture with the show's is the
 *   programme's all the same: the channel's, often white, does not show over a bright picture. An
 *   advert with something of its own in a logo's corner can pass for the programme.
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

    // The share of its usual score under which a picture is without the logo, and the pictures
    // either side it is smoothed with.
    private const val GONE = 0.5
    private const val SMOOTH = 2

    // Fewer pictures than this without the logo are a stray, a bright scene or a badge put over the
    // logo for a moment, and count for nothing.
    private const val SOLID = 5

    // The pictures with the logo a break may hold (an advert with something in that corner). No
    // more: the programme often comes back with a badge over its logo within a minute, and bridged
    // to that, the break would run on into it.
    const val BRIDGE = 7

    // The pictures that must be known on each side of a break, and how many of them at least must
    // have the logo: it is between two parts of the programme, not at the edge of what was read.
    const val AROUND = 15
    private const val MOSTLY = 8

    // The pictures a piece at either end of a break must last to stay part of it when only the
    // bridge holds it there.
    private const val END_PIECE = 10

    // Of the AROUND pictures on each side, how many at least must have the logo for a break to be
    // sure: the programme is plainly there, not a scene of it that hides the logo now and then.
    private const val CLEAR = 12

    /**
     * Of each picture, whether it is without the logo, from what each has of it ([score], [usual]
     * with it). One odd picture is no break, nor the end of one: each is taken with its neighbours.
     * A picture not [given] has the logo, and is no neighbour.
     */
    fun gone(score: DoubleArray, usual: Double, given: BooleanArray): BooleanArray = BooleanArray(score.size) { i ->
        given[i] && (maxOf(0, i - SMOOTH)..minOf(score.size - 1, i + SMOOTH)).filter { given[it] }.map { score[it] }.sorted()
            .let { it[it.size / 2] } < GONE * usual
    }

    /** [gone] without its strays. */
    fun solid(gone: BooleanArray): BooleanArray {
        val out = BooleanArray(gone.size)
        for (run in runs(gone, 0)) if (run.last - run.first + 1 >= SOLID) for (i in run) out[i] = true
        return out
    }

    /**
     * The stretches of [gone] that have the programme on both sides, as breaks; the first picture
     * being at [firstMs] and each [pictureMs] after the one before. With a second logo, [lowerGone]
     * tells of each picture whether it is without that one: a break is then what is left of one
     * once the pictures that show the second logo are taken for the programme's.
     *
     * A break is sure when it is one stretch without the logo, nothing bridged in it nor given up
     * at its ends, the second logo changed nothing in it, and the logo is there through most of
     * the pictures on each side. On the pictures of every channel that marks nothing, nearly 18
     * hours of them in November 2026, each of the 133 such breaks ended, 32 s early, in adverts,
     * trailers or the channel's own jingles, and began at the programme's end or in its end
     * credits, but for one that began 8 s before the end of a weather forecast.
     */
    fun logoBreaks(gone: BooleanArray, firstMs: Long, pictureMs: Long, lowerGone: BooleanArray? = null): List<Guess> {
        var without = gone
        var found = breakRuns(without, pictureMs)
        val unnarrowed = found.toSet()
        if (lowerGone != null) {
            without = BooleanArray(gone.size)
            for (run in found) for (i in run) without[i] = gone[i] && lowerGone[i]
            found = breakRuns(without, pictureMs)
        }
        val solid = solid(without)
        fun clear(from: Int, to: Int) = (from until to).count { !solid[it] } >= CLEAR
        return found.map { whole ->
            // A short piece held to either end by the bridge is given up: the logo hidden for a
            // moment just after a break, or before one, is a bright scene of the programme more
            // often than a last advert, and the break would run into it.
            val pieces = runs(BooleanArray(whole.last - whole.first + 1) { solid[whole.first + it] }, 0)
            var from = 0
            var to = pieces.lastIndex
            while (to > from && pieces[to].last - pieces[to].first + 1 < END_PIECE) to--
            while (from < to && pieces[from].last - pieces[from].first + 1 < END_PIECE) from++
            val sure = pieces.size == 1 && whole in unnarrowed &&
                clear(whole.first - AROUND, whole.first) && clear(whole.last + 1, whole.last + 1 + AROUND)
            Guess(firstMs + (whole.first + pieces[from].first) * pictureMs..firstMs + (whole.first + pieces[to].last + 1) * pictureMs, sure)
        }
    }

    private fun breakRuns(gone: BooleanArray, pictureMs: Long): List<IntRange> {
        val solid = solid(gone)
        fun programme(from: Int, to: Int) = from >= 0 && to <= solid.size && (from until to).count { !solid[it] } >= MOSTLY
        return runs(solid, BRIDGE).filter { run ->
            val between = programme(run.first - AROUND, run.first) && programme(run.last + 1, run.last + 1 + AROUND)
            between && (run.last + 1 - run.first) * pictureMs in MIN_MS..MAX_MS
        }
    }

    /**
     * What [captions] and [logo] agree on; either is null when it had nothing to go by. A break the
     * captions alone give is never sure, and one of the logo's stays so only where the captions do
     * not say otherwise of its edges.
     */
    fun combine(captions: List<LongRange>?, logo: List<Guess>?): List<Guess> {
        if (captions == null) return logo.orEmpty()
        if (logo == null) return captions.map { Guess(it) }
        return captions.flatMap { spoken ->
            logo.mapNotNull { guess ->
                val shown = guess.range
                val late = maxOf(spoken.first, shown.first)
                val early = minOf(spoken.last, shown.last)
                if (early - late < SHARED_MS) return@mapNotNull null
                // Further apart than that, one of them is wrong: the shorter break loses no programme.
                val startAgrees = abs(spoken.first - shown.first) <= EDGE_MS
                val endAgrees = abs(spoken.last - shown.last) <= EDGE_MS
                val start = if (startAgrees) shown.first else late
                val end = if (endAgrees) shown.last else early
                Guess(start..end, guess.sure && startAgrees && endAgrees)
            }
        }
    }
}

/**
 * A logo learnt from pictures (see [LogoTrack]): the points of a picture, [width] wide, where its
 * edges are, with how strong each mostly is, and what a picture that shows it usually scores.
 */
internal class Logo(
    private val width: Int,
    private val points: IntArray,
    private val pattern: IntArray,
    val usual: Double,
    /** A show's own logo, lower down, found beside this one, the channel's. */
    val lower: Logo? = null,
) {
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

    // The left and top of each corner, the upper ones first.
    private val origins = arrayOf(0 to 0, width - cornerWidth to 0, 0 to height - cornerHeight, width - cornerWidth to height - cornerHeight)
    private val edges = Array(origins.size) { ByteArray(if (size > 0) pictures * size else 0) }
    private val given = BooleanArray(pictures)

    // The corner the logo was found in, kept for what is learnt again once more pictures are given:
    // those wanted next were told from that corner.
    private var found = -1

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

    // What stays put in a corner: the points of it that are a logo, what each mostly is, and how much
    // they stand out.
    private class Corner(val index: Int, val mask: IntArray, val median: IntArray, val strength: Double)

    // A logo, with what each picture given has of it, and the show's own found beside the channel's.
    private class Learnt(val corner: Corner, val score: DoubleArray, val usual: Double, val lower: Learnt? = null)

    private fun corner(c: Int, read: IntArray, counts: IntArray): Corner? {
        val edge = edges[c]
        // What each point of the corner mostly is: the logo where there is one, nothing where the
        // picture behind keeps changing.
        val median = IntArray(size) { p ->
            counts.fill(0)
            for (i in read) counts[edge[i * size + p].toInt() and 0xFF]++
            var below = 0
            var value = 0
            while (below + counts[value] <= read.size / 2) below += counts[value++]
            value
        }
        val cut = maxOf(1, median.sorted()[(size * MASK).toInt()])
        val mask = (0 until size).filter { median[it] >= cut }.toIntArray()
        if (mask.isEmpty()) return null
        return Corner(c, mask, median, mask.sumOf { median[it] }.toDouble() / mask.size)
    }

    private fun scored(corner: Corner, read: IntArray, lower: Learnt?): Learnt {
        val edge = edges[corner.index]
        val whole = corner.mask.sumOf { corner.median[it] }.toDouble()
        val score = DoubleArray(pictures)
        for (i in read) {
            val o = i * size
            var sum = 0
            for (p in corner.mask) sum += minOf(edge[o + p].toInt() and 0xFF, corner.median[p])
            score[i] = sum / whole
        }
        return Learnt(corner, score, read.map { score[it] }.sorted()[read.size / 2], lower)
    }

    private fun learn(): Learnt? {
        val read = (0 until pictures).filter { given[it] }.toIntArray()
        if (size <= 0 || read.size < 2 * BreakGuess.AROUND) return null
        val counts = IntArray(256)
        fun strongest(corners: IntRange) = corners.mapNotNull { corner(it, read, counts) }.maxByOrNull { it.strength }
        val lower = strongest(UPPER until edges.size)
        // The channel's logo, in an upper corner, when there is one, with the show's own beside it;
        // a lower corner alone when no upper one holds a logo.
        val upper = if (found < 0) strongest(0 until UPPER)?.takeIf { it.strength >= STRONG } else if (found < UPPER) corner(found, read, counts) else null
        val main = upper
            ?: (if (found < 0) lower?.takeIf { it.strength >= STRONG } else corner(found, read, counts))
            ?: return null
        found = main.index
        val beside = lower?.takeIf { upper != null && it.strength >= STRONG }
        return scored(main, read, beside?.let { scored(it, read, null) })
    }

    private fun logo(learnt: Learnt): Logo {
        val (left, top) = origins[learnt.corner.index]
        val mask = learnt.corner.mask
        val points = IntArray(mask.size) { k -> (top + mask[k] / edgesWide) * width + left + mask[k] % edgesWide }
        return Logo(width, points, IntArray(mask.size) { learnt.corner.median[mask[it]] }, learnt.usual, learnt.lower?.let(::logo))
    }

    /** The logo of the pictures given, to look for in others. Null when no corner holds anything that stays. */
    fun logo(): Logo? = learn()?.let(::logo)

    /** Of each picture, whether it was given and is without the logo. Null as for [logo]. */
    fun gone(): BooleanArray? {
        val learnt = learn() ?: return null
        return BreakGuess.gone(learnt.score, learnt.usual, given)
    }

    /**
     * The stretches without the logo that have the programme, with it, on both sides; the first
     * picture being at [firstMs] and each [pictureMs] after the one before. Null as for [logo].
     */
    fun breaks(firstMs: Long, pictureMs: Long): List<Guess>? {
        val learnt = learn() ?: return null
        val lowerGone = learnt.lower?.let { BreakGuess.gone(it.score, it.usual, given) }
        return BreakGuess.logoBreaks(BreakGuess.gone(learnt.score, learnt.usual, given), firstMs, pictureMs, lowerGone)
    }

    private companion object {
        // The share of a picture's width and height a corner takes, and of a corner's points, the
        // strongest, that are the logo.
        const val CORNER = 0.22
        const val MASK = 0.92

        // The least a logo's edges must stand out.
        const val STRONG = 25.0

        // How many of the corners, the first ones, are the upper ones.
        const val UPPER = 2
    }
}
