// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * A [DefaultLoadControl] that buffers further ahead for on-demand playback (replays, recordings,
 * VOD), so a brief network drop drains the buffer instead of stalling the show, while staying safe
 * on low-memory devices.
 *
 * ExoPlayer holds its buffer as byte[] on the Java heap, so the forward buffer is sized as a slice
 * of the per-app heap budget (ActivityManager.getMemoryClass), not of total device RAM. Live is
 * unaffected: it cannot buffer past the live edge, so the larger ceiling is simply never reached.
 *
 * Under memory pressure (onTrimMemory) the forward buffer is lowered so it stops growing, and the
 * allocator's reusable free list is trimmed back right away; the data already buffered is then given
 * up as it plays out, because the lowered cap keeps it from refilling. It is restored when the app
 * returns to the foreground.
 *
 * It subclasses [DefaultLoadControl] and overrides only [shouldContinueLoading] to add the dynamic
 * cap, inheriting every other behaviour unchanged.
 */
@UnstableApi
class AdaptiveLoadControl private constructor(
    private val allocator: DefaultAllocator,
    private val fullBufferBytes: Int,
    maxBufferMs: Int,
) : DefaultLoadControl(
    allocator,
    DEFAULT_MIN_BUFFER_MS,
    maxBufferMs,
    DEFAULT_BUFFER_FOR_PLAYBACK_MS,
    DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    fullBufferBytes,
    false,
    DEFAULT_BACK_BUFFER_DURATION_MS,
    DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
) {
    @Volatile
    private var capBytes: Int = fullBufferBytes

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // On top of DefaultLoadControl's own time and size limits, stop once the buffer reaches the
        // current (possibly trimmed) cap and let playback drain it back down, keeping the held heap
        // bounded.
        if (allocator.totalBytesAllocated >= capBytes) return false
        return super.shouldContinueLoading(parameters)
    }

    /**
     * Lower the forward-buffer cap under memory pressure (more severe pressure frees more) and trim
     * the allocator's reusable free list back to it now. The buffered data above the new cap is
     * released as playback consumes it, since the cap stops it refilling.
     */
    fun onTrimMemory(level: Int) {
        val cap = trimmedCapBytes(level, fullBufferBytes)
        capBytes = cap
        allocator.setTargetBufferSize(cap)
        allocator.trim()
    }

    /** Restore the full buffer once memory pressure has passed (app back in the foreground). */
    fun restoreFullBuffer() {
        capBytes = fullBufferBytes
        allocator.setTargetBufferSize(fullBufferBytes)
    }

    companion object {
        private const val MB = 1024 * 1024

        // Matches the segment size DefaultLoadControl.Builder uses for its default allocator.
        private const val BUFFER_SEGMENT_SIZE = 64 * 1024

        // The steady-state floor is at most this, and the buffer never grows past the ceiling.
        private const val FLOOR_CAP_BYTES = 40 * MB
        private const val MAX_BUFFER_BYTES = 256 * MB

        // Under critical pressure the cap may dip below the steady-state floor to here, still enough
        // to keep playing without constant rebuffering.
        private const val TRIM_FLOOR_BYTES = 16 * MB

        // Time ceiling for the forward buffer; the heap-scaled byte cap is the real limiter for HD.
        // Kept short on low-RAM devices so a low-bitrate stream cannot slowly fill the byte cap.
        private const val MAX_BUFFER_MS = 240_000
        private const val LOW_RAM_MAX_BUFFER_MS = 60_000

        /**
         * Forward-buffer byte target for a device whose app-heap budget is [heapBytes]. Scales with
         * the heap (a smaller share on low-RAM devices) and the floor scales too, so a small-heap
         * device is never pinned to a buffer larger than the stock behaviour.
         */
        internal fun targetBufferBytes(heapBytes: Long, lowRam: Boolean): Int {
            val percent = if (lowRam) 15 else 25
            val floor = (heapBytes * 10 / 100).coerceAtMost(FLOOR_CAP_BYTES.toLong())
            return (heapBytes * percent / 100).coerceIn(floor, MAX_BUFFER_BYTES.toLong()).toInt()
        }

        /**
         * The forward-buffer cap for a given onTrimMemory [level]; more severe pressure frees more.
         * The floor is clamped to [fullBytes] so a tiny steady-state buffer is never raised above
         * itself (which would make the valve a no-op on the weakest devices).
         */
        internal fun trimmedCapBytes(level: Int, fullBytes: Int): Int =
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> fullBytes / 4
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> fullBytes / 2
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> fullBytes / 4 * 3
                else -> fullBytes
            }.coerceAtLeast(minOf(TRIM_FLOOR_BYTES, fullBytes))

        /** Build a control sized to this device's app-heap budget. */
        fun create(context: Context): AdaptiveLoadControl {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // ExoPlayer buffers on the Java heap and largeHeap is not set, so scale to getMemoryClass(),
            // not device RAM. Stay clear of the heap Coil also wants for its bitmap cache.
            val heapBytes = am.memoryClass.toLong() * MB
            val target = targetBufferBytes(heapBytes, am.isLowRamDevice)
            val maxBufferMs = if (am.isLowRamDevice) LOW_RAM_MAX_BUFFER_MS else MAX_BUFFER_MS
            return AdaptiveLoadControl(DefaultAllocator(true, BUFFER_SEGMENT_SIZE), target, maxBufferMs)
        }
    }
}
