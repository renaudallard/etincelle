// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import kotlin.math.abs
import kotlin.math.sqrt

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
    // one programme and the start of another, a programme without captions being as silent as adverts.
    const val MIN_MS = 150_000L
    const val MAX_MS = 800_000L

    // Without the logo, as programmes show it, a break can last up to this: adverts and trailers fill
    // eighteen and a half minutes between M6's evening news and its sitcom.
    const val LOGO_MAX_MS = 1_200_000L

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
    const val GONE = 0.5
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

    // The share of a stretch without the show's logo that must be without the channel's too for it
    // to be a break the channel's logo alone did not give.
    private const val SHOW_LED = 0.5

    // The most pictures in a row the second logo may show in a break, the channel's being gone with
    // it, for them to be a trailer or a sponsor that carries it (Paris Première's pictogram) rather
    // than the programme, which shows it for minutes on end.
    private const val SHORT_SHOW = 10

    // The most pictures in a row the first logo may be seen in a break, the second being gone with it,
    // for them to be an advert with its brand in the first one's corner (M6's faint logo) rather than
    // the programme, which shows both.
    private const val BRAND = 12

    // A break not sure by its logo is sure all the same when both its edges are anchored (see
    // [anchored]): a picture this near its start (before, after) or its end (either side) comes back
    // in the break, at least ANCHOR_GAP_MS away, near an edge of a break; and the logo, the channel's,
    // is seen at most INNER pictures in a row in it, adverts with something in its corner, where a
    // weather forecast between two blocks of adverts shows it for a minute or more.
    private const val START_BEFORE_MS = 90_000L
    private const val START_AFTER_MS = 40_000L
    private const val END_SIDE_MS = 60_000L
    private const val ANCHOR_GAP_MS = 60_000L
    private const val EDGE_NEAR_MS = 90_000L
    private const val INNER = 9

    // A picture is told again by its glance: GLANCE_WIDE by GLANCE_HIGH blocks, the mean luma of
    // each. Two with blocks varying by at least FLAT are alike when they match to SAME; flatter ones,
    // as a card of one colour, when their means are within FLAT_MEAN and no block differs by more
    // than FLAT_BLOCK, but never black or white ones, which any fade gives.
    const val GLANCE_WIDE = 30
    const val GLANCE_HIGH = 15
    private const val FLAT = 8.0
    private const val SAME = 0.95
    private const val FLAT_MEAN = 4.0
    private const val FLAT_BLOCK = 12
    private const val DARKEST = 30.0
    private const val BRIGHTEST = 225.0

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
     * The second logo can be the channel's own pictogram, which its trailers carry too: a trailer in a
     * break would then pass for the programme and cut the break in two, each part too short to
     * count. So the second logo shown at most [SHORT_SHOW] pictures in a row, the first gone the while,
     * does not part two pieces of a break; nor does the first seen at most [BRAND] pictures in a row,
     * the second gone the while, which is an advert with its brand in the first one's corner, and
     * that from the first look on, where a part beyond it can be too short to count by itself, so
     * long as another is a break by itself and it is no shorter than an end piece: a new programme
     * whose show's logo is not the one learnt looks like such a brand, and a scene of it without the
     * first logo would otherwise be taken in.
     * Neither counts in the pieces' length, never into a break longer than one may be, and such a
     * break is never sure. Parts so joined that are no break together are taken each by itself.
     *
     * Adverts with something of their own in the logo's corner (Gulli's toy adverts, their makers'
     * logos) can keep it seen through half a break, which then falls into pieces too short to count.
     * Where a second logo is found, a stretch without it, a break by itself, that the first gave
     * nothing in is one all the same when the first is gone through at least half of it and plainly
     * there on each side, from where the first is first gone to where it is last gone, a short piece
     * at either end given up as below: never past its return. Never sure.
     *
     * A break is sure when it is one stretch without the logo, nothing bridged in it nor given up
     * at its ends, the second logo changed nothing in it, and the logo is there through most of
     * the pictures on each side; never when the first logo is not the [channel]'s: a show's own
     * leaves with the show, and a stretch without it can run from the end of one programme into
     * the opening of the next, as on M6 one night in November 2026, which would have cut three
     * minutes of programme. On the pictures of every channel that marks nothing, nearly 18
     * hours of them in November 2026, each of the 133 such breaks ended, 32 s early, in adverts,
     * trailers or the channel's own jingles, and began at the programme's end or in its end
     * credits, but for one that began 8 s before the end of a weather forecast.
     *
     * With the [glances] of the pictures, the first logo being the channel's, one not sure so is
     * sure all the same when both its edges are anchored (see [anchored]).
     */
    fun logoBreaks(
        gone: BooleanArray,
        firstMs: Long,
        pictureMs: Long,
        lowerGone: BooleanArray? = null,
        glances: Array<ByteArray?>? = null,
        channel: Boolean = true,
    ): List<Guess> {
        var without = gone
        val brand = lowerGone?.let { lower -> BooleanArray(gone.size) { !gone[it] && lower[it] } }
        val trailers = lowerGone?.let { lower -> BooleanArray(gone.size) { gone[it] && !lower[it] } }
        var found = breakRuns(without, pictureMs, trailers, brand, lengthen = true)
        val unnarrowed = found.toSet()
        if (lowerGone != null) {
            without = BooleanArray(gone.size)
            val trailer = BooleanArray(gone.size)
            for (run in found) for (i in run) {
                without[i] = gone[i] && lowerGone[i]
                trailer[i] = gone[i] && !lowerGone[i]
            }
            found = breakRuns(without, pictureMs, trailer, brand)
        }
        val solid = solid(without)
        // The sides of a break are told by the first logo, as with no second one: [solid] holds only
        // what lies in the breaks the first gives.
        val beside = if (lowerGone == null) solid else solid(gone)
        fun clear(from: Int, to: Int) = (from until to).count { !beside[it] } >= CLEAR
        val led = if (lowerGone != null) showLed(gone, lowerGone, found, firstMs, pictureMs) else emptyList()
        val guesses = found.map { whole ->
            // A short piece held to either end by the bridge is given up: the logo hidden for a
            // moment just after a break, or before one, is a bright scene of the programme more
            // often than a last advert, and the break would run into it. With a second logo, pieces
            // with nothing between them that looks like the programme are one: what parts them is an
            // advert's brand in the channel's corner, or the show's logo matched for a picture now and
            // then by the notices along the bottom of adverts.
            val pieces = runs(BooleanArray(whole.last - whole.first + 1) { solid[whole.first + it] }, 0)
            val span = trimmed(if (lowerGone == null) pieces else merged(pieces, whole.first, gone, lowerGone))
            val sure = channel && pieces.size == 1 && whole in unnarrowed &&
                clear(whole.first - AROUND, whole.first) && clear(whole.last + 1, whole.last + 1 + AROUND)
            Guess(firstMs + (whole.first + span.first) * pictureMs..firstMs + (whole.first + span.last + 1) * pictureMs, sure)
        }
        if (!channel || glances == null || glances.size != gone.size) return guesses + led
        val firm = solid(gone)
        val all = guesses + led
        return guesses.map { if (it.sure) it else anchored(it, all, firm, glances, firstMs, pictureMs) ?: it } + led
    }

    /**
     * [guess] made sure, with its edges drawn in to the anchors, when both are anchored; null when
     * not. An edge is anchored by a picture near it that comes back between the start and the end
     * sides of the break, at least ANCHOR_GAP_MS away, the one or the other being in a break of
     * [all] and the second near an edge of one: the channel's cards, bumpers and a show's splash
     * that open and close its breaks, or an advert shown at both ends of one. With the channel's
     * logo [firm] seen at most INNER pictures in a row in the break, and drawn in to no less than
     * MIN_MS, as a break sure by its logo. Pictures without a glance are not known and anchor nothing.
     *
     * On the pictures of every channel that marks nothing, in November 2026, each of the 33 breaks
     * so made sure began at the programme's end or later and landed, 24 s before its end as sure
     * breaks do live, before the programme was back.
     */
    private fun anchored(
        guess: Guess,
        all: List<Guess>,
        firm: BooleanArray,
        glances: Array<ByteArray?>,
        firstMs: Long,
        pictureMs: Long,
    ): Guess? {
        val start = guess.range.first
        val end = guess.range.last
        val from = ((start - firstMs) / pictureMs).toInt()
        val to = ((end - firstMs) / pictureMs).toInt()
        if (from < 0 || to > firm.size || longest(BooleanArray(firm.size) { !firm[it] }, from until to) > INNER) return null
        fun at(i: Int) = firstMs + i * pictureMs
        // The pictures from [low] to [high], as far as there are any.
        fun between(low: Long, high: Long) =
            maxOf(0L, (low - firstMs + pictureMs - 1).floorDiv(pictureMs)).toInt()..minOf(glances.size - 1L, (high - firstMs).floorDiv(pictureMs)).toInt()
        fun inside(time: Long) = all.any { time in it.range }
        fun nearEdge(time: Long) = all.any { abs(it.range.first - time) <= EDGE_NEAR_MS || abs(it.range.last - time) <= EDGE_NEAR_MS }
        val own = between(start - START_BEFORE_MS, end + END_SIDE_MS)
        // Each glance's numbers, worked out once.
        val seen = arrayOfNulls<Seen>(glances.size)
        fun seenAt(i: Int) = seen[i] ?: glances[i]?.let { Seen(it) }?.also { seen[i] = it }
        // The times of the pictures from [low] to [high] that anchor the break.
        fun anchors(low: Long, high: Long): List<Long> = between(low, high).filter { i ->
            val time = at(i)
            val one = seenAt(i)
            one != null && own.any { k ->
                val twin = at(k)
                abs(twin - time) >= ANCHOR_GAP_MS && nearEdge(twin) && (inside(time) || inside(twin)) && seenAt(k)?.let { alike(one, it) } == true
            }
        }.map(::at)
        val opening = anchors(start - START_BEFORE_MS, start + START_AFTER_MS)
        if (opening.isEmpty()) return null
        val closing = anchors(end - END_SIDE_MS, end + END_SIDE_MS)
        if (closing.isEmpty()) return null
        // Drawn in only: the start to the last anchor after it, the end to just after the first
        // anchor before it.
        val first = (opening.filter { it in start..start + START_AFTER_MS } + start).max()
        val last = (closing.map { it + pictureMs }.filter { it <= end } + end).min()
        if (last - first < MIN_MS) return null
        return Guess(first..last, sure = true)
    }

    /** What tells the picture of [luma], [width] by [height], from others (see [alike]). */
    fun glance(luma: ByteArray, width: Int, height: Int): ByteArray = ByteArray(GLANCE_WIDE * GLANCE_HIGH) { b ->
        val x0 = b % GLANCE_WIDE * width / GLANCE_WIDE
        val x1 = (b % GLANCE_WIDE + 1) * width / GLANCE_WIDE
        val y0 = b / GLANCE_WIDE * height / GLANCE_HIGH
        val y1 = (b / GLANCE_WIDE + 1) * height / GLANCE_HIGH
        var sum = 0L
        for (y in y0 until y1) for (x in x0 until x1) sum += luma[y * width + x].toInt() and 0xFF
        val count = (x1 - x0) * (y1 - y0)
        (if (count > 0) (sum + count / 2) / count else 0L).toInt().toByte()
    }

    // A glance's blocks, their mean, the sum of their squared distances to it, and their spread.
    private class Seen(glance: ByteArray) {
        val blocks = IntArray(glance.size) { glance[it].toInt() and 0xFF }
        val mean = blocks.average()
        val squares = blocks.sumOf { (it - mean) * (it - mean) }
        val spread = if (blocks.isEmpty()) 0.0 else sqrt(squares / blocks.size)
    }

    /** Whether the pictures of glances [a] and [b] are the same one (see [glance]). */
    fun alike(a: ByteArray, b: ByteArray): Boolean = a.isNotEmpty() && alike(Seen(a), Seen(b))

    private fun alike(a: Seen, b: Seen): Boolean {
        val x = a.blocks
        val y = b.blocks
        if (x.size != y.size) return false
        if (a.spread >= FLAT) {
            if (b.spread < FLAT) return false
            var xy = 0.0
            for (i in x.indices) xy += (x[i] - a.mean) * (y[i] - b.mean)
            return xy / sqrt(a.squares * b.squares) >= SAME
        }
        if (b.spread >= FLAT || a.mean !in DARKEST..BRIGHTEST || abs(a.mean - b.mean) > FLAT_MEAN) return false
        return x.indices.all { abs(x[it] - y[it]) <= FLAT_BLOCK }
    }

    // From the first picture of [held], the pieces of a break, to the last, once a short piece at
    // either end is given up (see [logoBreaks]); of short pieces only, the first is kept.
    private fun trimmed(held: List<IntRange>): IntRange {
        var from = 0
        var to = held.lastIndex
        while (to > from && held[to].last - held[to].first + 1 < END_PIECE) to--
        while (from < to && held[from].last - held[from].first + 1 < END_PIECE) from++
        return held[from].first..held[to].last
    }

    // [pieces] of a break whose first picture is [offset], made one where nothing between them looks
    // like the programme (see [programmeIn]).
    private fun merged(pieces: List<IntRange>, offset: Int, gone: BooleanArray, lowerGone: BooleanArray): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (piece in pieces) {
            val last = out.lastOrNull()
            if (last != null && !programmeIn(offset + last.last + 1 until offset + piece.first, gone, lowerGone)) {
                out[out.size - 1] = last.first..piece.last
            } else {
                out += piece
            }
        }
        return out
    }

    // Whether [range] looks like the programme: a picture with both logos seen, or the show's seen SOLID
    // pictures in a row.
    private fun programmeIn(range: IntRange, gone: BooleanArray, lowerGone: BooleanArray): Boolean {
        var row = 0
        for (i in range) {
            if (!gone[i] && !lowerGone[i]) return true
            row = if (lowerGone[i]) 0 else row + 1
            if (row >= SOLID) return true
        }
        return false
    }

    // The breaks a stretch without the second logo gives where [found] has none (see [logoBreaks]).
    private fun showLed(gone: BooleanArray, lowerGone: BooleanArray, found: List<IntRange>, firstMs: Long, pictureMs: Long): List<Guess> {
        val solid = solid(gone)
        fun clear(from: Int, to: Int) = from >= 0 && to <= solid.size && (from until to).count { !solid[it] } >= CLEAR
        return breakRuns(lowerGone, pictureMs).mapNotNull { run ->
            if (found.any { it.first <= run.last && run.first <= it.last }) return@mapNotNull null
            if (run.count { gone[it] } < SHOW_LED * (run.last - run.first + 1)) return@mapNotNull null
            val pieces = runs(BooleanArray(run.last - run.first + 1) { solid[run.first + it] }, 0)
            if (pieces.isEmpty()) return@mapNotNull null
            val from = run.first + pieces.first().first
            val to = run.first + pieces.last().last + 1
            if ((to - from) * pictureMs < MIN_MS || !clear(from - AROUND, from) || !clear(to, to + AROUND)) return@mapNotNull null
            val span = trimmed(merged(pieces, run.first, gone, lowerGone))
            Guess(firstMs + (run.first + span.first) * pictureMs..firstMs + (run.first + span.last + 1) * pictureMs)
        }
    }

    // The stretches of [gone] that are breaks: solid, held together by the bridge, or by a trailer or a
    // brand (see [logoBreaks]) where they are given, between two stretches with the programme, and of
    // a break's length. Parts joined that are no break together are taken each by itself, as are those
    // none of which is a break by itself where a join is only to [lengthen] one.
    private fun breakRuns(
        gone: BooleanArray,
        pictureMs: Long,
        trailer: BooleanArray? = null,
        brand: BooleanArray? = null,
        lengthen: Boolean = false,
    ): List<IntRange> {
        val solid = solid(gone)
        fun programme(from: Int, to: Int) = from >= 0 && to <= solid.size && (from until to).count { !solid[it] } >= MOSTLY
        fun isBreak(run: IntRange) = programme(run.first - AROUND, run.first) && programme(run.last + 1, run.last + 1 + AROUND) &&
            (run.last + 1 - run.first) * pictureMs in MIN_MS..LOGO_MAX_MS
        val held = runs(solid, BRIDGE)
        if (trailer == null || brand == null) return held.filter { isBreak(it) }
        // No short part to lengthen one with (see [logoBreaks]): it is never a break by itself either.
        val joinable = if (lengthen) held.filter { it.last - it.first + 1 >= END_PIECE } else held
        return joined(joinable, trailer, brand, pictureMs).flatMap { run ->
            val parts = joinable.filter { it.first >= run.first && it.last <= run.last }
            if (isBreak(run) && (!lengthen || parts.size == 1 || parts.any { isBreak(it) })) listOf(run) else parts.filter { isBreak(it) }
        }
    }

    // [runs] with those parted only by a trailer or a brand, with up to BRIDGE other pictures, made one,
    // unless that one would be longer than a break may be.
    private fun joined(runs: List<IntRange>, trailer: BooleanArray, brand: BooleanArray, pictureMs: Long): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (run in runs) {
            val last = out.lastOrNull()
            if (last != null && (run.last + 1 - last.first) * pictureMs <= LOGO_MAX_MS && partedBy(last.last + 1 until run.first, trailer, brand)) {
                out[out.size - 1] = last.first..run.last
            } else {
                out += run
            }
        }
        return out
    }

    // Whether [gap] holds a trailer or a brand, with up to BRIDGE other pictures, and no more of either.
    private fun partedBy(gap: IntRange, trailer: BooleanArray, brand: BooleanArray): Boolean =
        gap.count() <= SHORT_SHOW + BRAND + BRIDGE && gap.count { trailer[it] || brand[it] } >= gap.count() - BRIDGE &&
            longest(trailer, gap) <= SHORT_SHOW && longest(brand, gap) <= BRAND

    // The most [flags] in a row in [range].
    private fun longest(flags: BooleanArray, range: IntRange): Int {
        var row = 0
        var most = 0
        for (i in range) {
            row = if (flags[i]) row + 1 else 0
            most = maxOf(most, row)
        }
        return most
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
    /** In an upper corner: the channel's, which stays from one programme to the next. */
    val channel: Boolean = false,
) {
    /** This one alone, without the show's beside it, which changes with the programme. */
    fun alone() = if (lower == null) this else Logo(width, points, pattern, usual, null, channel)

    /** Whether [pictures], as luma row by row, show it often enough for it to be theirs still. */
    fun seenIn(pictures: List<ByteArray>, share: Double) = pictures.count { score(it) >= BreakGuess.GONE * usual } >= share * pictures.size

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
        val beside = lower?.takeIf { upper != null && it.strength >= BESIDE }
        return scored(main, read, beside?.let { scored(it, read, null) })
    }

    private fun logo(learnt: Learnt): Logo {
        val (left, top) = origins[learnt.corner.index]
        val mask = learnt.corner.mask
        val points = IntArray(mask.size) { k -> (top + mask[k] / edgesWide) * width + left + mask[k] % edgesWide }
        return Logo(
            width, points, IntArray(mask.size) { learnt.corner.median[mask[it]] }, learnt.usual, learnt.lower?.let(::logo),
            channel = learnt.corner.index < UPPER,
        )
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
        return BreakGuess.logoBreaks(
            BreakGuess.gone(learnt.score, learnt.usual, given), firstMs, pictureMs, lowerGone, channel = learnt.corner.index < UPPER,
        )
    }

    private companion object {
        // The share of a picture's width and height a corner takes, and of a corner's points, the
        // strongest, that are the logo.
        const val CORNER = 0.22
        const val MASK = 0.92

        // The least a logo's edges must stand out, and those of a show's own beside the channel's: a
        // fainter one, as a band of text along the bottom, is matched by the notices along the bottom
        // of adverts, which then pass for the programme and part the break.
        const val STRONG = 25.0
        const val BESIDE = 30.0

        // How many of the corners, the first ones, are the upper ones.
        const val UPPER = 2
    }
}
