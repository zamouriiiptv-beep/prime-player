package com.castivio.feature.player

import com.castivio.playback.api.EngineId
import com.castivio.playback.api.OpenAttempts
import com.castivio.playback.api.OpeningMarks
import com.castivio.playback.api.PlaybackDiagnosis
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
        // The tries at the address, from the engine's own clock like the marks above. Only a measuring build has any.
        attemptsText(marks.attempts)
        handover?.let { left ->
            append(" | primary left ").append(left.reason)
            append(" after ").append(left.afterMs).append("ms")
            append(" server ").append(ms(left.serverAnsweredMs))
            append(" bytes ").append(ms(left.firstBytesMs))
            // And the primary's, which are from the press as everything about the primary here is.
            attemptsText(left.attempts)
        }
    }

    /**
     * The tries at the address as one more column of the log line: each try's start, answer, first bytes and end in
     * milliseconds, how it ended and by what name, and the host's address families by count. Nothing when there are
     * none. No address, no host and no exception message: the names of exceptions only.
     */
    private fun StringBuilder.attemptsText(attempts: OpenAttempts) {
        if (attempts.isEmpty) return
        append(" | attempts ").append(attempts.count)
        attempts.tries.forEach { attempt ->
            append(" #").append(attempt.number)
            append("[start ").append(ms(attempt.startedMs))
            append(" answer ").append(ms(attempt.answeredMs))
            append(" bytes ").append(ms(attempt.firstBytesMs))
            append(" end ").append(ms(attempt.endedMs))
            append(' ').append(attempt.outcome)
            attempt.failure?.let { append(' ').append(it) }
            append(']')
        }
        attempts.lookup?.let { found ->
            append(" dns ")
            if (found.failure != null) {
                append("failed ").append(found.failure)
            } else {
                append(found.ipv4).append(" v4 ").append(found.ipv6).append(" v6")
            }
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
    /**
     * What the primary reported about the failure, kept for the panel: the engine that held it is released as the
     * backup begins, and the backup playing says nothing of why it was needed. Null when the primary gave up
     * without a report -- a budget that expired, or an engine with nothing to say.
     */
    val report: PlaybackDiagnosis? = null,
    /**
     * **Development builds only.** The tries the primary made at the address before it was let go, with every instant
     * from the press: the account the two figures above are the last of. Empty where the primary kept none.
     */
    val attempts: OpenAttempts = OpenAttempts.NONE,
) {

    /** Whether any of the stream had reached the primary: the difference between a network that said nothing and a stream that was refused. */
    val bytesArrived: Boolean get() = firstBytesMs != null
}
