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
 * allow it (see [SkipAds]).
 */
data class AdBreak(
    val startMs: Long,
    val endMs: Long,
    val id: Long,
    val skippable: Boolean,
    val guessed: Boolean = false,
    val sure: Boolean = false,
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
 * - The RMC channels mark nothing, but cut the stream into periods at each break, which then shows as
 *   a few minutes, some five seconds of jingle, and a few minutes again. That shape is taken for a
 *   break when the manifest carries no marker at all.
 * - The M6 channels, and every recording, are one unbroken period with no marker, in the manifest or
 *   in the segments: nothing is found there.
 *
 * A recording is told its breaks from outside, with [recorded]: its stream marks none, and they are
 * guessed from what it carries (see [RecordingBreaks]). So is a live stream, with [guessed], on the
 * channels that mark nothing or too little: the M6 channels' breaks, and those the RMC shape misses.
 * A channel that marks its breaks is told nothing more: see PlaybackSource.breakPictures.
 *
 * One instance per player screen: the breaks are worked out again only when the manifest changes.
 */
class AdBreaks {
    private var manifest: Any? = null
    private var found: List<LongRange> = emptyList()

    /** The breaks of the recording being played, as positions (ms) in it, once they are known. */
    var recorded: List<Guess> = emptyList()

    /**
     * The breaks of the live channel being played that its pictures give away (see [LiveBreaks]), as
     * times of day (ms since 1970): those the stream does not mark.
     */
    var guessed: List<Guess> = emptyList()

    /**
     * The break playback is in, whether or not it can be skipped yet, or null. [window] is a
     * caller-owned scratch instance to fill.
     */
    fun current(player: Player, window: Timeline.Window): AdBreak? {
        val at = player.currentPosition
        if (!player.isCurrentMediaItemLive) {
            return recorded.firstOrNull { at >= it.range.first && at < it.range.last - GUESS_EARLY_MS }?.let {
                val end = it.range.last - GUESS_EARLY_MS
                AdBreak(it.range.first, end, it.range.first, skippable = at < end - LAST_MOMENT_MS, guessed = true, sure = it.sure)
            }
        }
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        timeline.getWindow(player.currentMediaItemIndex, window)
        val start = window.windowStartTimeMs
        if (start == C.TIME_UNSET) return null
        val live = window.defaultPositionMs
        fun breakAt(from: Long, to: Long, id: Long, guessed: Boolean, sure: Boolean = false) =
            AdBreak(from, to, id, skippable = at < to - LAST_MOMENT_MS && to <= live - LIVE_MARGIN_MS, guessed = guessed, sure = sure)
        // What the stream marks is exact, and comes first.
        val dash = player.currentManifest as? DashManifest
        val zero = dash?.availabilityStartTimeMs ?: C.TIME_UNSET
        if (dash != null && zero != C.TIME_UNSET) {
            if (dash !== manifest) {
                manifest = dash
                found = breaks(stretches(dash))
            }
            // From the manifest's own time to a position in the window.
            val shift = zero - start
            found.firstOrNull { at >= it.first + shift && at < it.last + shift }
                ?.let { return breakAt(it.first + shift, it.last + shift, zero + it.first, guessed = false) }
        }
        return guessed.firstOrNull { at >= it.range.first - start && at < it.range.last - GUESS_EARLY_MS - start }
            ?.let { breakAt(it.range.first - start, it.range.last - GUESS_EARLY_MS - start, it.range.first, guessed = true, sure = it.sure) }
    }

    /** One period of a manifest, as far as its ads go; times in ms from the manifest's time zero. */
    internal data class Stretch(val startMs: Long, val endMs: Long?, val marks: List<Mark>, val signalled: Boolean)

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

        // Two breaks this close are one: the TF1 channels put three seconds of jingle between two halves.
        private const val MERGE_GAP_MS = 10_000L

        // The shape of a break on a stream that marks nothing: a jingle of a few seconds between two
        // stretches of one to five and a half minutes.
        private const val JINGLE_MAX_MS = 10_000L
        private val HALF_BREAK_MS = 60_000L..330_000L

        private const val SCTE35 = "urn:scte:scte35"
        private val BASE64 = Regex("[A-Za-z0-9+/]{16,}={0,2}")

        fun stretches(dash: DashManifest): List<Stretch> = (0 until dash.periodCount).map { i ->
            val period = dash.getPeriod(i)
            val length = dash.getPeriodDurationMs(i).takeIf { it != C.TIME_UNSET }
            val streams = period.eventStreams.filter { it.schemeIdUri.startsWith(SCTE35) }
            val marks = streams.flatMap { stream ->
                stream.events.mapIndexedNotNull { n, event ->
                    val opens = opens(event.messageData) ?: return@mapIndexedNotNull null
                    val at = period.startMs + stream.presentationTimesUs.getOrElse(n) { 0L } / 1000
                    Mark(at, event.durationMs.takeIf { it > 0 }, opens)
                }
            }
            Stretch(period.startMs, length?.let { period.startMs + it }, marks, streams.isNotEmpty())
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
                (1 until stretches.size - 1).mapNotNull { i ->
                    val before = stretches[i - 1]
                    val jingle = stretches[i]
                    val after = stretches[i + 1]
                    val beforeEnd = before.endMs ?: return@mapNotNull null
                    val jingleEnd = jingle.endMs ?: return@mapNotNull null
                    val afterEnd = after.endMs ?: return@mapNotNull null
                    val shaped = jingleEnd - jingle.startMs < JINGLE_MAX_MS &&
                        beforeEnd - before.startMs in HALF_BREAK_MS && afterEnd - after.startMs in HALF_BREAK_MS
                    (before.startMs..afterEnd).takeIf { shaped }
                }
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
