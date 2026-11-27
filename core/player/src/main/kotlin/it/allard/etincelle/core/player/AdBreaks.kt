// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.dash.manifest.DashManifest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * An ad break of a live stream, as positions (ms) in the player's window: from [startMs] to [endMs].
 * The window slides and takes those along, at each reading of the manifest; [id], the time of day
 * the break began (epoch ms), stays. [skippable] when there is somewhere to skip to: it has ended
 * before the live point, and is not on its last second. [guessed] when the stream does not say it
 * is one, as in a recording (see [RecordingBreaks]) or by a live channel's pictures (see
 * [LiveBreaks]): for a button to offer, and to skip unasked only when [sure] and the settings
 * allow it (see [SkipAds]). [separated] when found by the blacks between its adverts (see
 * [Separators]): both its ends are then in the adverts, and it is sure from its start. A skip asked
 * for goes to [askedEndMs], which is [endMs] but where such a break lies in one by the logo that is
 * taken to end later (see [AdBreaks.askedEnd]).
 */
data class AdBreak(
    val startMs: Long,
    val endMs: Long,
    val id: Long,
    val skippable: Boolean,
    val guessed: Boolean = false,
    val sure: Boolean = false,
    val separated: Boolean = false,
    val askedEndMs: Long = endMs,
)

/**
 * Finds the ad breaks of the live stream a player plays, from its DASH manifest, so that one being
 * watched behind the live point can be skipped to its end.
 *
 * What a manifest says of its breaks depends on who inserts the ads, as read on the service in
 * November 2026:
 * - The TF1 and France Télévisions channels mark each break with an SCTE-35 event (a "placement
 *   opportunity" or an ad "start", with its length), mostly as a period of its own, on LCI as events
 *   timed inside one long period. These are exact.
 * - The RMC channels and BFMTV mark nothing, but cut the stream into periods at each break, which
 *   then shows as a few minutes, some five seconds of jingle, and a few minutes again. That shape is
 *   taken for a break when the manifest carries no marker at all. A break that airs while the
 *   stream is being watched shows instead as the adverts the service's ad server puts in it, each a
 *   period of its own served by that server: those periods are a break too, exact.
 * - The M6 channels, and every recording, are one unbroken period with no marker, in the manifest or
 *   in the segments. M6 and Gulli give their breaks away all the same, by the size of the frames of
 *   the blacks between two adverts (see [Separators]).
 *
 * A recording is told its breaks from outside, with [recorded]: its stream marks none, and they are
 * guessed from what it carries (see [RecordingBreaks]). So is a live stream, with [guessed], on the
 * channels that mark nothing or too little: the M6 channels' breaks, and those the RMC periods miss;
 * and on M6 and Gulli, with [separated], by the blacks between their adverts.
 * A channel that marks its breaks is told nothing more: see PlaybackSource.breakPictures.
 *
 * One instance per player screen: the breaks are worked out again only when the manifest changes.
 */
class AdBreaks {
    private var manifest: Any? = null
    private var found: List<LongRange> = emptyList()

    // What each event of the last reading says (see [opens]), by its content: each reading of a live
    // manifest, a few seconds apart, has mostly the same events, which are not decoded again.
    private var said = HashMap<String, Boolean?>()

    /** The breaks of the recording being played, as positions (ms) in it, once they are known. */
    var recorded: List<Guess> = emptyList()

    /**
     * The breaks of the recording being played that the blacks between its adverts give away (see
     * [SeparatorBreaks.recording]), by their place in it. Only one within a break of [recorded] counts
     * (see [confirmed]).
     */
    var recordedSeparated: List<SeparatedBreak> = emptyList()

    /**
     * The breaks of the live channel being played that its pictures give away (see [LiveBreaks]), as
     * times of day (ms since 1970): those the stream does not mark.
     */
    var guessed: List<Guess> = emptyList()

    /**
     * The breaks of the live channel being played that the blacks between its adverts give away
     * (see [Separators]), as times of day (ms since 1970), both ends in the adverts.
     */
    var separated: List<SeparatedBreak> = emptyList()

    /**
     * Where playback is, in the time of a break's [AdBreak.id]: the time of day on a live stream, the
     * position in a recording; null when not known. [window] as for [current].
     */
    fun timeOf(player: Player, window: Timeline.Window): Long? {
        if (!player.isCurrentMediaItemLive) return player.currentPosition
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        timeline.getWindow(player.currentMediaItemIndex, window)
        val start = window.windowStartTimeMs
        return if (start == C.TIME_UNSET) null else start + player.currentPosition
    }

    /**
     * The break playback is in, whether or not it can be skipped yet, or null. [window] is a
     * caller-owned scratch instance to fill.
     */
    fun current(player: Player, window: Timeline.Window): AdBreak? {
        val at = player.currentPosition
        if (!player.isCurrentMediaItemLive) {
            // The blacks between the adverts first, where the pictures or captions also have the
            // programme away: both ends are in the adverts, and as on a live stream the break may have
            // begun up to SEPARATED_LEAD_MS before its first black.
            val sure = confirmed(recordedSeparated, recorded)
            sure.firstOrNull { at >= it.range.first && at < it.range.last }?.let { s ->
                val asked = askedEnd(s, recorded) { it.range.last - GUESS_EARLY_MS } ?: s.range.last
                return AdBreak(s.range.first, s.range.last, s.firstMs - SEPARATED_LEAD_MS, skippable = at < s.range.last - LAST_MOMENT_MS, guessed = true, sure = true, separated = true, askedEndMs = asked)
            }
            return recorded.firstOrNull { at >= offeredFrom(it, sure) && at < it.range.last - GUESS_EARLY_MS }?.let {
                val end = it.range.last - GUESS_EARLY_MS
                AdBreak(offeredFrom(it, sure), end, it.range.first, skippable = at < end - LAST_MOMENT_MS, guessed = true, sure = it.sure)
            }
        }
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        timeline.getWindow(player.currentMediaItemIndex, window)
        val start = window.windowStartTimeMs
        if (start == C.TIME_UNSET) return null
        val live = window.defaultPositionMs
        fun breakAt(from: Long, to: Long, id: Long, guessed: Boolean, sure: Boolean = false, separated: Boolean = false, askedTo: Long? = null) =
            AdBreak(from, to, id, skippable = at < to - LAST_MOMENT_MS && to <= live - LIVE_MARGIN_MS, guessed = guessed, sure = sure, separated = separated, askedEndMs = askedTo ?: to)
        // What the stream marks is exact, and comes first.
        val dash = player.currentManifest as? DashManifest
        val zero = dash?.availabilityStartTimeMs ?: C.TIME_UNSET
        if (dash != null && zero != C.TIME_UNSET) {
            if (dash !== manifest) {
                manifest = dash
                val now = HashMap<String, Boolean?>()
                found = breaks(
                    stretches(dash) { data ->
                        val key = String(data, Charsets.ISO_8859_1)
                        val opens = if (key in said) said[key] else opens(data)
                        now[key] = opens
                        opens
                    },
                )
                said = now
            }
            // From the manifest's own time to a position in the window.
            val shift = zero - start
            found.firstOrNull { at >= it.first + shift && at < it.last + shift }
                ?.let { return breakAt(it.first + shift, it.last + shift, zero + it.first, guessed = false) }
        }
        // Then the blacks between the adverts, whose ends are in them, and as the break began up to
        // SEPARATED_LEAD_MS before its first black, one gone back into there was gone back into.
        val ranges = separated.map { it.range }
        separated.firstOrNull { at >= it.range.first - start && at < it.range.last - start }?.let { s ->
            val asked = askedEnd(s, guessed) { liveEnd(it, ranges) }?.minus(start)?.takeIf { it <= live - LIVE_MARGIN_MS }
            return breakAt(s.range.first - start, s.range.last - start, s.firstMs - SEPARATED_LEAD_MS, guessed = true, sure = true, separated = true, askedTo = asked)
        }
        return guessed.firstOrNull { at >= offeredFrom(it, separated) - start && at < liveEnd(it) - start }
            ?.let { breakAt(offeredFrom(it, separated) - start, liveEnd(it, ranges) - start, it.range.first, guessed = true, sure = it.sure) }
    }

    /**
     * One period of a manifest, as far as its ads go; times in ms from the manifest's time zero.
     * [inserted] when the ad server serves it (see [INSERTED]).
     */
    internal data class Stretch(val startMs: Long, val endMs: Long?, val marks: List<Mark>, val signalled: Boolean, val inserted: Boolean = false)

    /** An SCTE-35 event: where it stands, the length it gives if any, and whether it opens a break. */
    internal data class Mark(val atMs: Long, val durationMs: Long?, val opens: Boolean)

    internal companion object {
        // A break on its last second is left to end by itself, and one that reaches the live point,
        // or nearly, is still being broadcast: there is nowhere to skip to.
        private const val LAST_MOMENT_MS = 1_000L
        private const val LIVE_MARGIN_MS = 2_000L

        // A guessed break is taken to end this long before the logo is back: channels put their
        // logo up again as much as half a minute after the programme, and skipping to it would cut
        // that much of the show. The last adverts are left in instead.
        private const val GUESS_EARLY_MS = 32_000L

        // A sure one, live, ends less early: on the pictures of every channel that marks nothing,
        // in November 2026, the programme itself was back at most 20 s before the logo after the
        // 210 sure breaks, its titles aside, so 24 s with the pictures 4 s apart.
        private const val SURE_EARLY_MS = 24_000L

        // How long before the first black between its adverts a break begins, at the most: the card
        // that opens it and the first advert, 8 to 45 s on M6 and Gulli in November 2026.
        private const val SEPARATED_LEAD_MS = 45_000L

        /**
         * The breaks of [separated] that lie within one of [guesses], a recording's breaks by its
         * pictures and captions: where the programme is away by those too, so that blacks in a
         * programme, on a channel that has none between its adverts, never make one.
         */
        fun confirmed(separated: List<SeparatedBreak>, guesses: List<Guess>): List<SeparatedBreak> =
            separated.filter { s -> guesses.any { it.range.first <= s.range.first && s.range.last <= it.range.last } }

        /**
         * Where the break [guess] by the logo is offered from: the first black of a break of
         * [separated] within it, once known, rather than where the logo goes, as cards and trailers
         * come between the programme and the adverts (M6, November 2026).
         */
        fun offeredFrom(guess: Guess, separated: List<SeparatedBreak>): Long =
            separated.filter { it.range.first < guess.range.last && it.range.last > guess.range.first }
                .minOfOrNull { it.firstMs }?.let { maxOf(guess.range.first, it) } ?: guess.range.first

        /**
         * Where a skip asked for in the break [s] by the blacks between its adverts goes beyond its end:
         * where one of [guesses] by the logo that it lies in is taken to end, [end], when later, as the
         * cards and trailers after the last advert go too; null when none goes further. Pressed earlier
         * in the logo's break, a skip goes there already. One unasked stays at the blacks' end, which is
         * sure.
         */
        fun askedEnd(s: SeparatedBreak, guesses: List<Guess>, end: (Guess) -> Long): Long? =
            guesses.filter { it.range.first < s.range.last }.map(end).filter { it > s.range.last }.maxOrNull()

        /** Where the live break [guess] is taken to end. */
        fun liveEnd(guess: Guess) = guess.range.last - if (guess.sure) SURE_EARLY_MS else GUESS_EARLY_MS

        /**
         * Where the live break [guess] is taken to end when the blacks between its adverts are
         * known: a break of [separated] begun before [liveEnd] is the same one, and its end is in the
         * adverts, so it is skipped to when further.
         */
        fun liveEnd(guess: Guess, separated: List<LongRange>): Long {
            val end = liveEnd(guess)
            return separated.firstOrNull { it.first < end && it.last > end }?.last ?: end
        }

        // Two breaks this close are one: the TF1 channels put three seconds of jingle between two halves.
        private const val MERGE_GAP_MS = 10_000L

        // The shape of a break on a stream that marks nothing: a jingle of a few seconds between two
        // stretches of one to seven minutes (just over six in BFMTV's long breaks at night, November
        // 2026).
        private const val JINGLE_MAX_MS = 10_000L
        private val HALF_BREAK_MS = 60_000L..420_000L

        private const val SCTE35 = "urn:scte:scte35"

        // Where the service's ad server (Broadpeak) serves the adverts, and the filler of unsold time,
        // that it puts in a break of the RMC channels and BFMTV that airs while the stream is being
        // watched, as seen in November 2026.
        private const val INSERTED = "/bpkio-jitt/"
        private val BASE64 = Regex("[A-Za-z0-9+/]{16,}={0,2}")

        fun stretches(dash: DashManifest, opensOf: (ByteArray) -> Boolean? = ::opens): List<Stretch> = (0 until dash.periodCount).map { i ->
            val period = dash.getPeriod(i)
            val length = dash.getPeriodDurationMs(i).takeIf { it != C.TIME_UNSET }
            val streams = period.eventStreams.filter { it.schemeIdUri.startsWith(SCTE35) }
            val marks = streams.flatMap { stream ->
                stream.events.mapIndexedNotNull { n, event ->
                    val opens = opensOf(event.messageData) ?: return@mapIndexedNotNull null
                    val at = period.startMs + stream.presentationTimesUs.getOrElse(n) { 0L } / 1000
                    Mark(at, event.durationMs.takeIf { it > 0 }, opens)
                }
            }
            val inserted = period.adaptationSets.any { set -> set.representations.any { rep -> rep.baseUrls.any { INSERTED in it.url } } }
            Stretch(period.startMs, length?.let { period.startMs + it }, marks, streams.isNotEmpty(), inserted)
        }

        /** The ad breaks among [stretches], in the manifest's time, in order and without overlap. */
        fun breaks(stretches: List<Stretch>): List<LongRange> {
            val raw = if (stretches.any { it.signalled }) {
                stretches.flatMap { stretch ->
                    stretch.marks.filter { it.opens }.mapNotNull { mark ->
                        // As long as it says; a mark with no length goes on to the next mark of its
                        // period, which closes it or opens another, or to the period's end.
                        val end = mark.durationMs?.let { mark.atMs + it }
                            ?: stretch.marks.filter { it.atMs > mark.atMs }.minOfOrNull { it.atMs }
                            ?: stretch.endMs ?: return@mapNotNull null
                        (mark.atMs..end).takeIf { end > mark.atMs }
                    }
                }
            } else {
                val shaped = (1 until stretches.size - 1).mapNotNull { i ->
                    val before = stretches[i - 1]
                    val jingle = stretches[i]
                    val after = stretches[i + 1]
                    val beforeEnd = before.endMs ?: return@mapNotNull null
                    val jingleEnd = jingle.endMs ?: return@mapNotNull null
                    val afterEnd = after.endMs ?: return@mapNotNull null
                    // The jingle is the channel's own, never filler that the ad server serves.
                    val shaped = !jingle.inserted && jingleEnd - jingle.startMs < JINGLE_MAX_MS &&
                        beforeEnd - before.startMs in HALF_BREAK_MS && afterEnd - after.startMs in HALF_BREAK_MS
                    (before.startMs..afterEnd).takeIf { shaped }
                }
                // A break in one piece, as RMC Story's in the morning: one to seven minutes between two
                // longer stretches, the one after it maybe still on air. Only on a stream that shows it
                // cuts itself at its breaks, by one of the shape above: one cut at its programmes would
                // lose a short one.
                val alone = if (shaped.isEmpty()) emptyList() else (1 until stretches.size - 1).mapNotNull { i ->
                    val before = stretches[i - 1]
                    val block = stretches[i]
                    val after = stretches[i + 1]
                    val beforeEnd = before.endMs ?: return@mapNotNull null
                    val blockEnd = block.endMs ?: return@mapNotNull null
                    val single = !block.inserted && blockEnd - block.startMs in HALF_BREAK_MS &&
                        beforeEnd - before.startMs > HALF_BREAK_MS.last && (after.endMs?.let { it - after.startMs > HALF_BREAK_MS.last } ?: true)
                    (block.startMs..blockEnd).takeIf { single }
                }
                // The adverts of a break that aired since the stream was asked for, each a period of
                // its own; the few seconds of jingle between its halves, left as aired, are merged in.
                shaped + alone + stretches.filter { it.inserted }.mapNotNull { stretch -> stretch.endMs?.let { stretch.startMs..it } }
            }
            val merged = mutableListOf<LongRange>()
            for (range in raw.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && range.first - last.last <= MERGE_GAP_MS) {
                    merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
                } else {
                    merged += range
                }
            }
            return merged
        }

        /**
         * Whether the SCTE-35 message in [event], an event's content as the manifest gives it (XML
         * around a base64 section), opens a break (true) or closes one (false); null when it does
         * neither, or cannot be read.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun opens(event: ByteArray): Boolean? {
            val text = String(event, Charsets.ISO_8859_1)
            // The section is the run of base64 that starts as one does (table id 0xFC): the XML around
            // it has other runs that look like base64, such as the path of its namespace.
            val section = BASE64.findAll(text)
                // Padded base64 comes in fours: any other run would only fail to decode.
                .filter { it.value.length % 4 == 0 }
                .mapNotNull { run -> runCatching { Base64.decode(run.value) }.getOrNull() }
                .firstOrNull { it.size >= 20 && it[0] == 0xFC.toByte() } ?: return null
            return try {
                opens(section, section[13].toInt() and 0xFF)
            } catch (e: IndexOutOfBoundsException) {
                null
            }
        }

        // [command] 5 is a splice_insert, which says by itself whether the network is left; otherwise
        // the answer is in a segmentation descriptor (tag 2, marked "CUEI"), by its type.
        private fun opens(section: ByteArray, command: Int): Boolean? {
            if (command == 5) {
                val cancelled = section[18].toInt() and 0x80 != 0
                if (!cancelled) return section[19].toInt() and 0x80 != 0
            }
            var i = 16
            while (i + 4 <= section.size) {
                val cuei = section[i] == 'C'.code.toByte() && section[i + 1] == 'U'.code.toByte() &&
                    section[i + 2] == 'E'.code.toByte() && section[i + 3] == 'I'.code.toByte()
                if (cuei && section[i - 2].toInt() == 2) segmentation(section, i + 8)?.let { return it }
                i++
            }
            return null
        }

        // Reads a segmentation descriptor from its cancel indicator at [from] on to its type.
        private fun segmentation(section: ByteArray, from: Int): Boolean? {
            var q = from
            if (section[q].toInt() and 0x80 != 0) return null
            q++
            val flags = section[q].toInt()
            q++
            // Components, when the segmentation is not of the whole programme.
            if (flags and 0x80 == 0) q += 1 + 6 * (section[q].toInt() and 0xFF)
            // A duration.
            if (flags and 0x40 != 0) q += 5
            // The UPID: its type, its length, and that many bytes.
            q += 2 + (section[q + 1].toInt() and 0xFF)
            return when (section[q].toInt() and 0xFF) {
                0x22, 0x30, 0x32, 0x34, 0x36 -> true
                0x23, 0x31, 0x33, 0x35, 0x37 -> false
                else -> null
            }
        }
    }
}
