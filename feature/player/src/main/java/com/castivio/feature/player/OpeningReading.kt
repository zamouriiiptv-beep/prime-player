package com.castivio.feature.player

import com.castivio.playback.api.AttemptOutcome
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.HostLookup
import com.castivio.playback.api.OpenAttempt
import com.castivio.playback.api.OpenAttempts
import com.castivio.playback.api.PlaybackDiagnosis
import com.castivio.playback.api.SourceRedaction
import java.util.Locale

/**
 * **Temporary, for development.** One opening of a channel -- from the press to the first picture, or to the failure
 * -- as the panel over the big player reads it.
 *
 * A reading is a snapshot: the view model builds a new one every tenth of a second while the opening runs, and a last
 * one when it ends. Everything in it is a figure that [OpeningTiming] and the engines already keep; this adds the
 * attempt's identity, what it was (a first play, an engine kept, one built again, one after a failure, the backup),
 * and how long the main thread stood still during it. Pure: nothing here reads a clock or an engine, so a line can be
 * checked without a device.
 *
 * ## What is a measurement and what is not
 *
 * The application's stages -- the press reaching the player, the old engine let go, the new one built -- follow one
 * another, are measured by the view model on one clock, and add up to the moment the engine was asked. The engine's
 * events -- the server answering, bytes, tracks, the decoder, readiness, the picture -- are **instants from the
 * press**, each stamped where its engine said it, and they overlap: they are never added up. An event an engine
 * cannot report is printed `غير متاح`; one that has not come yet is `…`; one that never came is `-`. A figure an
 * engine only approximates -- LibVLC's video output appearing, which is the nearest it says to a picture -- carries
 * `≈` and is never presented as the picture itself.
 */
internal data class OpeningReading(
    /** Counts the openings of the process, so two of them are never taken for one. */
    val id: Int,
    /** The player's own number for the attempt: see [PlayerState.attempt]. */
    val attempt: Int,
    /** What the viewer pressed, by name. Never the address, which carries the account. */
    val channel: String,
    val case: OpeningCase,
    val status: OpeningStatus,
    /** From the press: so far while it waits, and fixed when it ends. */
    val totalMs: Long,
    val timing: OpeningTiming,
    val stalls: MainThreadStalls,
    /** Why it failed, when it did: the player's reason. */
    val failure: String? = null,
) {

    val running: Boolean get() = status == OpeningStatus.WAITING

    /** The panel's lines for this opening: what it is, the stages, the events, the gaps, the main thread, the fault. */
    fun lines(): List<HudLine> {
        val tone = when (status) {
            OpeningStatus.WAITING -> HudTone.RUNNING
            OpeningStatus.SHOWN -> HudTone.DONE
            OpeningStatus.FAILED, OpeningStatus.REPLACED -> HudTone.FAILED
        }
        val engine = engineName(timing.engine)
        val approx = if (timing.marks.firstFrameExact) "" else "≈"
        val ending = when (status) {
            OpeningStatus.WAITING -> "now ${waitingFor()}"
            OpeningStatus.SHOWN -> "first frame$approx"
            OpeningStatus.FAILED -> "FAILED ${failure ?: DASH}"
            OpeningStatus.REPLACED -> "replaced before it ended"
        }
        val lines = ArrayList<HudLine>(8)
        lines += HudLine("#$attempt $channel · $engine · ${case.label} · ${seconds(totalMs)}$approx · $ending", tone)

        // Why the primary let the source go, and whether anything had reached it: the second half of a fallback's
        // story, which the backup's own figures below cannot tell. Amber, because it is the line to read.
        timing.handover?.let { left ->
            lines += HudLine(handoverLine(left), HudTone.RUNNING)
            // How the primary's server came to answer or not, try by try: the half of the story the line above has
            // only the last try of.
            lines += attemptLines("primary tries", left.attempts, ended = true)
        }

        // **The primary's failure report takes the place of the timing lines**, so the panel keeps the room it has:
        // what failed is the question while a report is shown, and the figures it would otherwise draw are still in the
        // log line and the table. The report in full is the Copy button's.
        val report = failureReport
        if (report != null) {
            failureLines(report).forEach { lines += HudLine(it, HudTone.RUNNING) }
        } else {
            lines += timingLines(approx)
        }

        if (status == OpeningStatus.FAILED) {
            lines += HudLine("FAILED ${failure ?: DASH} while ${waitingFor()}", HudTone.FAILED)
        }
        return lines
    }

    /** The application's stages, the engine's events, the gaps and the main thread: the lines a report stands in for. */
    private fun timingLines(approx: String): List<HudLine> {
        val lines = ArrayList<HudLine>(4)

        // The application's stages, one after another: they add up to when the engine was asked.
        val other = (timing.prepareMs - timing.pressToOpenMs - timing.releaseMs - timing.setupMs).coerceAtLeast(0)
        lines += HudLine(
            "app (ms): press→open ${timing.pressToOpenMs} · release ${timing.releaseMs} · setup ${timing.setupMs}" +
                " · other $other = ${timing.prepareMs} before the engine was asked",
            HudTone.MUTED,
        )

        // The engine's events, as instants from the press. They overlap and are not to be added.
        lines += HudLine("@ " + events().joinToString(" · "), HudTone.MUTED)

        // And the tries at the address behind the first two of them, which are the last try's: a retry after a silent
        // wait and a slow answer are the same figure above and two different lines here. Development builds only.
        lines += attemptLines("tries", timing.marks.attempts.shifted(timing.prepareMs), ended = !running)

        // The two figures a reader asks for: how far the engine's readiness is from the picture, and the engine's
        // own share of the wait.
        val marks = timing.marks
        val ready = marks.readyMs
        val frame = marks.firstFrameMs
        val gaps = buildList {
            if (ready != null && frame != null) add("ready→frame${approx} ${(frame - ready).coerceAtLeast(0)} ms")
            timing.prepareToFrameMs?.let { add("engine asked→frame$approx $it ms") }
        }
        if (gaps.isNotEmpty()) lines += HudLine(gaps.joinToString(" · "), HudTone.MUTED)

        lines += HudLine(
            if (stalls.count == 0) {
                "main thread (from open): no stall over $STALL_MS ms"
            } else {
                "main thread (from open): ${stalls.count} stall(s) over $STALL_MS ms · longest ${stalls.maxMs} ms" +
                    " · total ${stalls.totalMs} ms"
            },
            if (stalls.count == 0) HudTone.MUTED else HudTone.RUNNING,
        )
        return lines
    }

    /**
     * The engine's tries at the address, one line when there was one and a line each when there were more, every
     * instant in seconds from the press -- `(+x)` is that try's own time to the instant before it, which is not the
     * opening's: a retry's starts where the one before it ended.
     *
     * A try still going says how long it has waited so far, from this reading's own total; one that never answered says
     * so in words. The host's address families close the first line, said by count: no address and no name.
     * Nothing at all for an engine that kept none, which is every engine in a build that does not measure.
     */
    private fun attemptLines(label: String, attempts: OpenAttempts, ended: Boolean): List<HudLine> {
        if (attempts.isEmpty) return emptyList()
        val dns = lookupText(attempts.lookup, ended)
        val retries = attempts.count - 1
        if (retries == 0) {
            return listOf(HudLine("$label (s): 1 · ${attemptText(attempts.tries.first(), ended)} · $dns", HudTone.MUTED))
        }
        val lines = ArrayList<HudLine>(attempts.tries.size + 2)
        lines += HudLine("$label (s): ${attempts.count} · $retries ${if (retries == 1) "retry" else "retries"} · $dns", HudTone.RUNNING)
        attempts.tries.forEach {
            lines += HudLine("  ${attemptText(it, ended)}", if (it.outcome == AttemptOutcome.FAILED) HudTone.RUNNING else HudTone.MUTED)
        }
        if (attempts.more > 0) lines += HudLine("  +${attempts.more} more, not kept", HudTone.MUTED)
        return lines
    }

    /** One try: `#2 start 8.01 · answered 9.50 (+1.49) · bytes 9.51`, or how it failed, or that it is still waiting. */
    private fun attemptText(attempt: OpenAttempt, ended: Boolean): String = buildString {
        append('#').append(attempt.number).append(" start ").append(sec(attempt.startedMs))
        val answered = attempt.answeredMs
        if (answered != null) {
            append(" · answered ").append(sec(answered)).append(" (+").append(sec(attempt.answerTookMs ?: 0)).append(')')
        }
        val bytes = attempt.firstBytesMs
        when {
            bytes != null -> append(" · bytes ").append(sec(bytes))
            answered != null && attempt.outcome == AttemptOutcome.ANSWERED -> append(if (ended) " · no bytes" else " · bytes …")
        }
        when (attempt.outcome) {
            AttemptOutcome.WAITING ->
                if (ended) append(" · never answered") else append(" · waiting ").append(sec((totalMs - attempt.startedMs).coerceAtLeast(0))).append(" so far")
            AttemptOutcome.FAILED -> {
                append(" · FAILED ").append(sec(attempt.endedMs ?: attempt.startedMs))
                append(" (+").append(sec(attempt.lastedMs ?: 0)).append(") ").append(attempt.failure ?: DASH)
            }
            AttemptOutcome.CLOSED -> append(" · closed ").append(sec(attempt.endedMs ?: attempt.startedMs))
            AttemptOutcome.ANSWERED -> Unit
        }
    }

    /** What the host's lookup found, by count -- and that it is what the resolver offers, not what the connection used. */
    private fun lookupText(lookup: HostLookup?, ended: Boolean): String = when {
        lookup == null -> if (ended) "dns -" else "dns …"
        lookup.failure != null -> "dns lookup FAILED ${lookup.failure}"
        else -> "dns ${lookup.ipv4} v4 · ${lookup.ipv6} v6 available"
    }

    private fun sec(ms: Long): String = String.format(Locale.ROOT, "%.2f", ms / MS_PER_SECOND)

    /** What the primary reported when it gave this source up, when this opening is the backup's after a failure. */
    val failureReport: PlaybackDiagnosis? get() = timing.handover?.report

    /**
     * The summary of [report], one line each and none longer than [LINE_MAX]: what the panel has room for.
     *
     * Every value is the primary's own, or says what it is. A figure read out of the codec string carries
     * `(inferred)`, a decoder named from the last one built carries its own note, and one that was not available is
     * `n/a`: no line is filled in to look complete. Addresses are redacted in every line, the exceptions' messages
     * included, because those are text this code did not write.
     */
    private fun failureLines(report: PlaybackDiagnosis): List<String> {
        val video = report.video
        val derived = listOfNotNull(video?.profile, video?.level?.let { "L$it" }).joinToString(" ")
        val width = video?.width
        val height = video?.height
        val root = report.causes.lastOrNull()
        val lines = listOf(
            "decoder: ${report.decoderName ?: report.decoder?.codecName ?: NOT_AVAILABLE}",
            "video: ${video?.sampleMimeType ?: NOT_AVAILABLE} · ${video?.codecs ?: NOT_AVAILABLE}",
            listOf(
                if (derived.isEmpty()) "profile/level $NOT_AVAILABLE" else "$derived (inferred)",
                if (width != null && height != null) "${width}x$height" else "size $NOT_AVAILABLE",
                video?.frameRate?.let { "${rate(it)} fps" } ?: "fps $NOT_AVAILABLE",
                video?.bitDepth?.let { "$it-bit" } ?: "depth $NOT_AVAILABLE",
            ).joinToString(" · "),
            "Media3 error: " + errorCodeText(report),
            "support: ${report.formatSupport ?: NOT_AVAILABLE}",
            report.notes.firstOrNull() ?: "codec exception: $NOT_AVAILABLE",
            "cause: " + (root?.let { shortCause(it) } ?: NOT_AVAILABLE),
        )
        return lines.map { fit(SourceRedaction.scrub(it)) }
    }

    /**
     * This opening as a block of text to copy: the channel and the line that says why the primary left, then the
     * report in full -- the lines the panel had no room for included. Redacted as a whole, once, here.
     */
    fun copyText(): String? {
        val left = timing.handover ?: return null
        val report = left.report ?: return null
        val text = "channel: $channel\n${handoverLine(left)}\n\n${report.render()}"
        return SourceRedaction.scrub(text)
    }

    private fun errorCodeText(report: PlaybackDiagnosis): String {
        val name = report.errorCodeName?.removePrefix("ERROR_CODE_")
        val code = report.errorCode
        return when {
            name != null && code != null -> "$name ($code)"
            name != null -> name
            code != null -> "code $code"
            else -> "$NOT_AVAILABLE (${report.reason}, no exception)"
        }
    }

    /** `android.media.MediaCodec$CodecException: Error 0x…` as `MediaCodec$CodecException: Error 0x…`. */
    private fun shortCause(cause: String): String {
        val type = cause.substringBefore(": ")
        val message = cause.substringAfter(": ", missingDelimiterValue = "")
        return type.substringAfterLast('.') + if (message.isEmpty()) "" else ": $message"
    }

    private fun rate(frameRate: Float): String =
        if (frameRate % 1f == 0f) frameRate.toInt().toString() else String.format(Locale.ROOT, "%.2f", frameRate)

    private fun fit(line: String): String = if (line.length <= LINE_MAX) line else line.take(LINE_MAX - 1) + "…"

    /** This opening as one row of the table of the last few. */
    fun row(): String {
        val end = when (status) {
            OpeningStatus.WAITING -> "… ${seconds(totalMs)}"
            OpeningStatus.SHOWN -> seconds(totalMs) + if (timing.marks.firstFrameExact) "" else "≈"
            OpeningStatus.FAILED -> "FAILED ${failure ?: DASH} ${seconds(totalMs)}"
            OpeningStatus.REPLACED -> "replaced ${seconds(totalMs)}"
        }
        // A fallback says in the table why it was one, so a screenshot of the last eight is the whole account.
        val label = timing.handover?.let { "${case.label} (${it.reason}, ${if (it.bytesArrived) "bytes" else "no bytes"})" }
            ?: case.label
        return "#$attempt ${engineName(timing.engine)} · $label · $end · ${channel.take(CHANNEL_ROW)}"
    }

    /**
     * `primary left: TIMEOUT at 3.00 s · server answered 1.34 s · bytes arrived 2.40 s`, or what did not come.
     *
     * The instants are from the press, as every other in the panel is. A server that never answered and bytes that
     * never arrived are said in words and not as a dash: this line is read to tell a network that was silent from a
     * stream that was refused, and a dash would have to be decoded first.
     */
    private fun handoverLine(left: Handover): String {
        val server = left.serverAnsweredMs?.let { "server answered ${seconds(it)}" } ?: "server did not answer"
        val bytes = left.firstBytesMs?.let { "bytes arrived ${seconds(it)}" } ?: "no bytes arrived"
        return "primary left: ${left.reason} at ${seconds(left.afterMs)} · $server · $bytes"
    }

    /** The row's tone: a failure stands out, an opening that is still going is amber, the rest are quiet. */
    fun rowTone(): HudTone = when (status) {
        OpeningStatus.FAILED -> HudTone.FAILED
        OpeningStatus.WAITING -> HudTone.RUNNING
        OpeningStatus.SHOWN, OpeningStatus.REPLACED -> HudTone.MUTED
    }

    /**
     * What the opening is waiting for, from the furthest event its engine has reported: the latest of the ones it
     * says, which is not always the next in a fixed order -- a decoder can be ready before the tracks are known.
     */
    private fun waitingFor(): String {
        val marks = timing.marks
        val libvlc = timing.engine == EngineId.BACKUP
        return when {
            marks.firstFrameMs != null -> "the first frame is shown"
            marks.readyMs != null -> "the first frame (the engine is ready)"
            marks.decoderReadyMs != null -> "the engine to be ready (decoder is)"
            marks.tracksKnownMs != null -> if (libvlc) "the picture (tracks are known)" else "the decoder (tracks are known)"
            marks.firstBytesMs != null -> if (libvlc) "the tracks (a buffer arrived)" else "the tracks (bytes arrived)"
            marks.serverAnsweredMs != null -> "the first bytes (the server answered)"
            else -> if (libvlc) "the source (LibVLC)" else "the server"
        }
    }

    /** The engine's events as `name 0.42` from the press, in the order they usually come, each said as it stands. */
    private fun events(): List<String> {
        val marks = timing.marks
        val libvlc = timing.engine == EngineId.BACKUP
        val base = timing.prepareMs
        val ended = !running

        fun at(label: String, ms: Long?, available: Boolean = true): String = when {
            !available -> "$label $UNAVAILABLE"
            ms != null -> "$label ${seconds(base + ms)}"
            ended -> "$label $DASH"
            else -> "$label …"
        }

        val decoder = at("decoder", marks.decoderReadyMs, available = !libvlc) +
            when {
                marks.decoderKept -> " (kept)"
                marks.decoderInitMs != null -> " (built in ${marks.decoderInitMs} ms)"
                else -> ""
            }
        val frame = at(if (marks.firstFrameExact) "frame" else "frame≈ (video output)", marks.firstFrameMs)
        return buildList {
            add(at("server", marks.serverAnsweredMs, available = !libvlc))
            add(at(if (libvlc) "first buffer≈" else "bytes", marks.firstBytesMs))
            add(at("tracks", marks.tracksKnownMs))
            add(decoder)
            add(at("ready (not a picture)", marks.readyMs))
            add(frame)
            if (libvlc) add(at("clock moving", marks.clockMovingMs))
        }
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f s", ms / MS_PER_SECOND)

    private companion object {
        const val MS_PER_SECOND = 1_000.0
        const val CHANNEL_ROW = 24
        const val DASH = "-"
        const val UNAVAILABLE = "غير متاح"

        /** A fact the report does not have, said in the panel's other language so it is never read as a value. */
        const val NOT_AVAILABLE = "n/a"

        /** One line of a report on the panel: as wide as the panel's text is, so a line never wraps into two. */
        const val LINE_MAX = 62
    }
}

/** Why a channel was opened, as far as the comparison needs: the engine kept and the engine built are the question. */
internal enum class OpeningCase(val label: String) {
    /** The player had no engine: the first play after it opened, or after the application came back. */
    FIRST("first play"),

    /** The engine that played the last channel was kept for this one. */
    REUSED("engine reused"),

    /** The last channel's engine was let go and another built. */
    REBUILT("engine rebuilt"),

    /** The attempt before it had failed, or this is a retry of one. */
    AFTER_FAILURE("after a failure"),

    /** The backup engine has this one, because the primary gave it up or the viewer asked. */
    FALLBACK("fallback"),

    /** The viewer asked, from the panel, for the primary to try a channel the backup had: see `retryOnPrimary`. */
    RETRY("retry on Media3"),
}

/** Where an opening is. */
internal enum class OpeningStatus {
    WAITING,

    /** The first frame was shown. */
    SHOWN,

    FAILED,

    /** Another press began before this ended: it neither succeeded nor failed. */
    REPLACED,
}

/** How the main thread behaved while an opening ran: the stretches over [STALL_MS] it did not draw a frame in. */
internal data class MainThreadStalls(val count: Int = 0, val maxMs: Long = 0, val totalMs: Long = 0)

/** One line of the panel, and what it is about, which the screen turns into a colour. */
internal data class HudLine(val text: String, val tone: HudTone)

internal enum class HudTone { RUNNING, DONE, FAILED, MUTED }

/** The engine as the panel names it. */
internal fun engineName(engine: EngineId): String = when (engine) {
    EngineId.PRIMARY -> "MEDIA3"
    EngineId.BACKUP -> "LIBVLC"
}

/** The stretch the main thread may stand without a frame before it is a stall, in milliseconds. */
internal const val STALL_MS = 100L
