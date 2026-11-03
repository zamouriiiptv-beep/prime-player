package com.castivio.playback.api

import com.castivio.core.common.StreamQuality
import kotlin.math.roundToLong

/**
 * One second of the indicator's working, written down so that its thresholds can be tuned against a real provider.
 *
 * **For a debug build and for nothing else.** The monitor builds a line only when it is given somewhere to send it, and the
 * player gives it somewhere only in a debuggable build: a release build builds none, sends none, and the monitor does what
 * it did without it. What is in a line is what the monitor already holds -- the evidence the policy was handed, the verdict
 * it gave, the rating on the screen -- and nothing of the stream's address, its credentials or its content. Nothing is asked
 * of the network or the engine for it.
 *
 * Plain numbers and a [format] that turns them into one short line, so what is written is checked without a device.
 */
data class QualityTraceLine(
    /** How long the stream has been playing, or null before it has played. */
    val sinceStartMs: Long?,
    /** What the stream is doing: playing, stalled (buffering), opening again after it dropped, failed. */
    val phase: String,
    /** Bits per second that arrived over the last second, or null where the engine does not count bytes or nothing is comparable yet. */
    val rateBps: Double?,
    /** Bits per second over the last few seconds -- the figure the rate-drop rule compares -- or null before there is one. */
    val recentRateBps: Double?,
    /** Bits per second this stream usually arrives at (the median the drop is measured against), or null before there is one. */
    val usualRateBps: Double?,
    /** How long no byte has arrived, or null where bytes are not counted. */
    val silentMs: Long?,
    /** What the engine says it holds ahead of the picture, or null where it cannot say. */
    val bufferedMs: Long?,
    /** The buffer as a share of what it usually holds, 0 to 1, or null where there is no usual. */
    val bufferRatio: Double?,
    /** How long the stream has been buffering right now, 0 while it runs. */
    val stalledNowMs: Long,
    /** Stalls counted in the last minute. */
    val stallsInWindow: Int,
    /** Lost or corrupt data that arrived since the last line. */
    val newFaults: Long,
    /** Lost or corrupt data in the last half minute. */
    val faultsInWindow: Int,
    /** How much of a seek's grace is left, 0 when there is none. */
    val graceMs: Long,
    /** What the policy says of this second alone. */
    val verdict: StreamQuality,
    /** Whether that verdict stands on something the viewer can see, and is shown at once. */
    val firm: Boolean,
    /** What the indicator shows, or null while it shows nothing. */
    val shown: StreamQuality?,
) {

    /** The line as it is written: `key=value` pairs, `-` for what is not known. */
    fun format(): String = buildString {
        append("q t=").append(sinceStartMs?.let { tenths(it / MS_PER_SECOND) } ?: "-").append("s")
        append(' ').append(phase)
        append(" rate=").append(kilobits(rateBps))
        append(" r5=").append(kilobits(recentRateBps))
        append(" usual=").append(kilobits(usualRateBps))
        append(" silent=").append(silentMs?.let { "${it}ms" } ?: "-")
        append(" buf=").append(bufferedMs?.let { "${it}ms" } ?: "-")
        append(" ratio=").append(bufferRatio?.let { hundredths(it) } ?: "-")
        append(" stall=").append(stalledNowMs).append("ms")
        append(" stalls60=").append(stallsInWindow)
        append(" faults=+").append(newFaults).append('/').append(faultsInWindow)
        append(" grace=").append(graceMs).append("ms")
        append(" verdict=").append(verdict.name).append(if (firm) "/firm" else "/soft")
        append(" shown=").append(shown?.name ?: "-")
    }

    private fun kilobits(bitsPerSecond: Double?): String =
        bitsPerSecond?.let { "${tenths(it / BITS_PER_KILOBIT)}kbps" } ?: "-"

    /** One decimal place, without a locale: a log line reads the same on every device. */
    private fun tenths(value: Double): String {
        val scaled = (value.coerceAtLeast(0.0) * 10.0).roundToLong()
        return "${scaled / 10}.${scaled % 10}"
    }

    /** Two decimal places, for a share between 0 and 1. */
    private fun hundredths(value: Double): String {
        val scaled = (value.coerceAtLeast(0.0) * 100.0).roundToLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    private companion object {
        const val MS_PER_SECOND = 1_000.0
        const val BITS_PER_KILOBIT = 1_000.0
    }
}
