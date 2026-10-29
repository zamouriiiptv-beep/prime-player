package com.castivio.feature.player

import com.castivio.playback.api.EngineId
import com.castivio.playback.api.OpeningMarks
import com.castivio.playback.api.PlaybackError

/**
 * One opening, stage by stage, from the press to the first frame.
 *
 * ## Why the total is never reported alone
 *
 * "The channel took a second to open" is a sum, and its parts have different owners. The
 * press reaching the player, the previous engine being let go and the next one being built
 * are this application's. The server answering and the first bytes arriving are the
 * provider's and the network's. The tracks, the decoder and the first picture are the
 * stream's and the device's -- a picture can only begin at a keyframe, and the stream decides
 * how far away the next one is. A change aimed at the wrong part cannot show up in the total,
 * so every part is a figure of its own.
 *
 * The application's figures are milliseconds after the press. The engine's, in [marks], are
 * milliseconds after the engine was asked to open, which is [prepareMs] after the press.
 */
data class OpeningTiming(
    /** The engine this attempt ran on. */
    val engine: EngineId,
    /** Whether this attempt is the backup's, after the primary gave the source up. */
    val fallback: Boolean,
    /** Whether the engine that played the last channel was kept for this one, rather than one built. */
    val reused: Boolean,
    /** The press, to the player being asked to open: composition and dispatch. */
    val pressToOpenMs: Long,
    /** Letting the previous engine go. Zero when there was none, or it was kept. */
    val releaseMs: Long,
    /** Building this attempt's engine and giving it the surface. Zero for a kept one. */
    val setupMs: Long,
    /** The press, to the engine being asked to open the source. */
    val prepareMs: Long,
    /** The engine's own account, from that moment on. */
    val marks: OpeningMarks,
    /** The press, to the first frame. Null for an attempt that has none, or ended without one. */
    val firstFrameMs: Long?,
    /** Why the primary gave this source up and how far it had got, for an attempt that is the backup's. */
    val handover: Handover? = null,
) {

    /** The engine being asked to open, to the first frame: what the source and the device took. */
    val prepareToFrameMs: Long? get() = firstFrameMs?.let { it - prepareMs }

    /**
     * One line for the log.
     *
     * Figures and the engine's name and nothing else: no address, no account, no token. A step
     * the engine did not see is a dash rather than left out, so every line has the same columns
     * and two openings can be read against each other.
     */
    fun render(): String = buildString {
        append("engine=").append(engine)
        append(" fallback=").append(if (fallback) "yes" else "no")
        append(" reused=").append(if (reused) "yes" else "no")
        append(" | press→open ").append(pressToOpenMs).append("ms")
        append(" release ").append(releaseMs).append("ms")
        append(" setup ").append(setupMs).append("ms")
        append(" press→prepare ").append(prepareMs).append("ms")
        append(" | server ").append(ms(marks.serverAnsweredMs))
        append(" bytes ").append(ms(marks.firstBytesMs))
        append(" tracks ").append(ms(marks.tracksKnownMs))
        append(" decoder ").append(ms(marks.decoderReadyMs))
        when {
            marks.decoderKept -> append(" (kept)")
            marks.decoderInitMs != null -> append(" (built in ").append(marks.decoderInitMs).append("ms)")
        }
        append(" frame ").append(ms(marks.firstFrameMs))
        append(" | prepare→frame ").append(ms(prepareToFrameMs))
        append(" press→frame ").append(ms(firstFrameMs))
        handover?.let { left ->
            append(" | primary left ").append(left.reason)
            append(" after ").append(left.afterMs).append("ms")
            append(" server ").append(ms(left.serverAnsweredMs))
            append(" bytes ").append(ms(left.firstBytesMs))
        }
    }

    private fun ms(value: Long?): String = if (value == null) "–" else "${value}ms"
}

/**
 * The primary engine giving a source up, as the backup's attempt inherits it: why, when, and whether the
 * source had said anything to it.
 *
 * The backup's own figures describe the backup. Without this, the three seconds the primary was given are a
 * block of "other" time before the engine was asked, and a channel the network never answered looks the same
 * as one the decoder refused. The two are told apart by what the primary had received when it was let go, which
 * is read before its engine is released -- see `PlayerViewModel.fallOver`.
 *
 * Figures are milliseconds **from the press**, as the panel's own are, and null for an event that had not come.
 */
data class Handover(
    /** What the primary reported, or [PlaybackError.TIMEOUT] when the budget ran out first. */
    val reason: PlaybackError,
    /** The press, to the primary being given up. */
    val afterMs: Long,
    /** The press, to the server answering the primary. */
    val serverAnsweredMs: Long?,
    /** The press, to the first bytes of the stream reaching the primary. */
    val firstBytesMs: Long?,
) {

    /** Whether any of the stream had reached the primary: the difference between a network that said nothing and a stream that was refused. */
    val bytesArrived: Boolean get() = firstBytesMs != null
}
