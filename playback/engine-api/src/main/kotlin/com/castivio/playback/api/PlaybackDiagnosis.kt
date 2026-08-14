package com.castivio.playback.api

/**
 * Everything known about a failure, in a form a person can read and paste.
 *
 * ## Why this exists
 *
 * A file failed on a device and the application could say nothing useful about it. The
 * information existed — ExoPlayer had an error code, a renderer name, a decoder name, a
 * `diagnosticInfo` string from `MediaCodec` and the input `Format` — and all of it was
 * collapsed into a single enum value on the way to the screen, then relabelled by a
 * mapping that turned anything unclassified into "format not supported".
 *
 * So the enum stays the thing the *product* reasons about, and this is the thing a *person*
 * reads. Nothing here drives behaviour; it is evidence.
 *
 * ## Why every field is a String or a primitive
 *
 * Because this module compiles for every platform and may not name a `MediaCodecInfo`, and
 * because the destination is a text box with a Copy button. Flattening at the point of
 * capture also means the report survives the thing that produced it being released, which
 * matters: the engine is torn down the moment the fallback switches.
 *
 * ## Why it is not gathered unless something fails
 *
 * The statistics rule applies here too. Nothing below is computed while playback is
 * healthy — the engine keeps the two input formats it was already given by callbacks, and
 * everything else is read out of the exception at the moment there is one.
 */
data class PlaybackDiagnosis(
    val engine: EngineId,
    val reason: PlaybackError,

    /** ExoPlayer's own code and its name, so a report says the name and not the number. */
    val errorCode: Int? = null,
    val errorCodeName: String? = null,

    /** Which renderer raised it, when the failure came from one. */
    val rendererName: String? = null,

    /**
     * The whole chain, outermost first, one entry per link.
     *
     * The chain is where the answer usually is: `ExoPlaybackException` on top says almost
     * nothing, and four links down is a `MediaCodec.CodecException` with a vendor string
     * that names the real problem.
     */
    val causes: List<String> = emptyList(),

    val decoder: DecoderReport? = null,
    val video: FormatReport? = null,
    val audio: FormatReport? = null,

    /** The source being opened, with any query stripped — a token must not reach a report. */
    val source: String? = null,

    /** Set only when the failure was the opening budget expiring with no error at all. */
    val timedOutAfterMs: Long? = null,
) {

    /**
     * The report, as text.
     *
     * Rendered here rather than in the screen so that the copied text and the displayed
     * text are the same string. A report that is formatted twice is a report where the
     * copy is missing the line that mattered.
     *
     * Absent fields are omitted rather than printed as "null": a report with eight blank
     * rows buries the three that are filled in.
     */
    fun render(): String = buildString {
        appendLine("engine: $engine")
        appendLine("reason: $reason")
        errorCode?.let { appendLine("errorCode: $it${errorCodeName?.let { n -> "  ($n)" } ?: ""}") }
        rendererName?.let { appendLine("renderer: $it") }
        timedOutAfterMs?.let { appendLine("timedOut: after ${it}ms with no frame and no error") }
        source?.let { appendLine("source: $it") }

        decoder?.let { d ->
            appendLine()
            appendLine("decoder init failed")
            d.codecName?.let { appendLine("  codec: $it") }
            d.mimeType?.let { appendLine("  mime: $it") }
            d.secureDecoderRequired?.let { appendLine("  secureRequired: $it") }
            d.diagnosticInfo?.let { appendLine("  diagnostic: $it") }
            // The single most informative line in the whole report for the fallback
            // question: it says whether the platform had a second decoder and whether that
            // one failed too.
            appendLine("  triedAnotherDecoder: ${d.triedAnotherDecoder}")
        }

        video?.let { appendLine(); appendLine("video"); append(it.render()) }
        audio?.let { appendLine(); appendLine("audio"); append(it.render()) }

        if (causes.isNotEmpty()) {
            appendLine()
            appendLine("causes")
            causes.forEachIndexed { index, line -> appendLine("  ${index + 1}. $line") }
        }
    }
}

/**
 * What `MediaCodecRenderer.DecoderInitializationException` knew.
 *
 * [triedAnotherDecoder] is the field that answers whether the backup engine's one real
 * difference — walking the device's decoder list — was exercised and still failed. When it
 * is true and the reason is still a decoder failure, another decoder from the same list
 * will not help, and saying so is more useful than offering the button again.
 */
data class DecoderReport(
    val codecName: String? = null,
    val mimeType: String? = null,
    val diagnosticInfo: String? = null,
    val secureDecoderRequired: Boolean? = null,
    val triedAnotherDecoder: Boolean = false,
)

/** An input format, as the renderer received it. */
data class FormatReport(
    val sampleMimeType: String? = null,
    /** The RFC 6381 codec string — `avc1.42C01E`, `mp4a.40.2`. The profile is in here. */
    val codecs: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val channelCount: Int? = null,
    val sampleRateHz: Int? = null,
    val bitrate: Int? = null,
) {
    fun render(): String = buildString {
        sampleMimeType?.let { appendLine("  mime: $it") }
        codecs?.let { appendLine("  codecs: $it") }
        if (width != null && height != null) appendLine("  size: ${width}x$height")
        frameRate?.let { appendLine("  frameRate: $it") }
        channelCount?.let { appendLine("  channels: $it") }
        sampleRateHz?.let { appendLine("  sampleRate: $it Hz") }
        bitrate?.let { appendLine("  bitrate: $it bps") }
    }
}
