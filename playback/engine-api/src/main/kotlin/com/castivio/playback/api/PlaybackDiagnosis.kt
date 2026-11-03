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

    /**
     * The video decoder that was running, or being built, when it failed -- the platform's own name for it.
     *
     * Null when none had been named by then, which is a fact about the failure and not a gap in the report.
     */
    val decoderName: String? = null,

    /**
     * What the platform said about the failing format, as the engine read it from the renderer and the decoder list:
     * `renderer: EXCEEDS_CAPABILITIES · decoder: not supported`. Null when it was not asked or could not be --
     * which is not the same as supported, and is never printed as if it were.
     */
    val formatSupport: String? = null,

    /** More evidence out of the failing exception, one line each: the `MediaCodec.CodecException` fields and the like. */
    val notes: List<String> = emptyList(),
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
        decoderName?.let { appendLine("decoderName: $it") }
        formatSupport?.let { appendLine("formatSupport: $it") }
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

        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("details")
            notes.forEach { appendLine("  $it") }
        }

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
    /** Bits per luma sample, when the stream declared it. Never worked out from the profile: that would be a guess. */
    val bitDepth: Int? = null,
) {
    /**
     * The profile and the level, **read out of [codecs]** -- not asked of the device and not declared separately, so a
     * report prints them as inferred. Null when the string is not an H.264 or H.265 one this knows how to read.
     */
    val profile: String? get() = videoProfileAndLevel(codecs)?.first
    val level: String? get() = videoProfileAndLevel(codecs)?.second

    fun render(): String = buildString {
        sampleMimeType?.let { appendLine("  mime: $it") }
        codecs?.let { appendLine("  codecs: $it") }
        profile?.let { appendLine("  profile: $it (inferred)") }
        level?.let { appendLine("  level: $it (inferred)") }
        if (width != null && height != null) appendLine("  size: ${width}x$height")
        frameRate?.let { appendLine("  frameRate: $it") }
        bitDepth?.let { appendLine("  bitDepth: $it") }
        channelCount?.let { appendLine("  channels: $it") }
        sampleRateHz?.let { appendLine("  sampleRate: $it Hz") }
        bitrate?.let { appendLine("  bitrate: $it bps") }
    }
}

/**
 * The profile and level an RFC 6381 H.264 / H.265 codec string names, as words: `avc1.640028` is High, 4.0.
 *
 * Reads only what the string says. A profile it has no name for is printed as its number, and a string of another
 * codec is null, so a report never shows a profile that was made up to fill the line.
 */
private fun videoProfileAndLevel(codecs: String?): Pair<String, String?>? {
    val codec = codecs?.split(',')?.map { it.trim() }?.firstOrNull {
        it.startsWith("avc") || it.startsWith("hvc") || it.startsWith("hev")
    } ?: return null
    val parts = codec.split('.')
    if (codec.startsWith("avc")) {
        // avc1.PPCCLL: profile_idc, constraint flags, level_idc -- one hexadecimal byte each.
        val hex = parts.getOrNull(1)?.takeIf { it.length == AVC_HEX_LENGTH } ?: return null
        val profileIdc = hex.substring(0, 2).toIntOrNull(HEX) ?: return null
        val levelIdc = hex.substring(4, 6).toIntOrNull(HEX) ?: return null
        val profile = when (profileIdc) {
            66 -> "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4 Predictive"
            else -> "profile_idc $profileIdc"
        }
        return profile to "${levelIdc / 10}.${levelIdc % 10}"
    }
    // hvc1.[A|B|C]<profile_idc>.<compatibility>.<L|H><level_idc>[.constraints]: the level is thirty times its number.
    val profileIdc = parts.getOrNull(1)?.trimStart('A', 'B', 'C')?.toIntOrNull() ?: return null
    val profile = when (profileIdc) {
        1 -> "Main"
        2 -> "Main 10"
        3 -> "Main Still Picture"
        4 -> "Range Extensions"
        5 -> "High Throughput"
        9 -> "Screen Content Extensions"
        else -> "profile_idc $profileIdc"
    }
    val tierAndLevel = parts.getOrNull(3)
    val levelIdc = tierAndLevel?.drop(1)?.toIntOrNull()
    val level = when {
        levelIdc == null -> null
        levelIdc % HEVC_LEVEL_STEP != 0 -> "level_idc $levelIdc"
        else -> "${levelIdc / HEVC_LEVEL_STEP / 10}.${levelIdc / HEVC_LEVEL_STEP % 10}" +
            if (tierAndLevel?.startsWith('H') == true) " (High tier)" else ""
    }
    return profile to level
}

private const val HEX = 16
private const val AVC_HEX_LENGTH = 6
private const val HEVC_LEVEL_STEP = 3
