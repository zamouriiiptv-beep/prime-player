package com.castivio.feature.player

import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.PlaybackError
import com.castivio.playback.api.PlaybackDiagnosis
import com.castivio.playback.api.PlaybackSample
import com.castivio.playback.api.Track

/**
 * What the player screen renders, as one value.
 *
 * ## Why the picture's state and the chrome's state are separate fields
 *
 * They change independently and at wildly different rates. The picture goes
 * opening → playing once; the chrome appears and hides every few seconds, opens a sheet,
 * shows a panel. Modelled as a single sealed hierarchy, every chrome change would have to
 * restate which picture state it was in, and "buffering with the settings sheet open"
 * would be a case somebody forgot to write.
 *
 * So [picture] is a sealed type — one of a fixed set, and the compiler will not let a
 * screen forget one — and everything else is a field beside it.
 */
data class PlayerState(
    val request: PlayerRequest,
    val picture: Picture = Picture.Opening,
    /** Which engine is actually playing. Shown only as a small badge, and only for the backup. */
    val engine: EngineId = EngineId.PRIMARY,
    /**
     * The switch, while it is happening.
     *
     * A sentence that appears and goes away, not a decision to present: the user did not
     * ask for the fallback and cannot act on it.
     */
    val switching: Boolean = false,
    val controls: Boolean = true,
    val locked: Boolean = false,
    /**
     * How the picture is fitted into the screen. [AspectMode.FIT] is the only honest
     * default: it shows the whole frame the director shot, and every other mode throws
     * some of it away or bends it in exchange for filling the glass.
     */
    val aspect: AspectMode = AspectMode.FIT,
    /**
     * The picture's own shape, or null for a sound file and for a source that has not
     * decoded yet.
     *
     * Needed because [AspectMode.FIT] and [AspectMode.ZOOM] are relative to the source and
     * the others are not: 16:9 is 16:9 whatever arrives, but "fit" means nothing until the
     * frame has a shape. Null therefore means "do not letterbox yet" rather than "square".
     */
    val videoAspectRatio: Float? = null,
    val sheet: Sheet? = null,
    /**
     * Only ever true because the user asked. Nothing samples the engine until it is.
     */
    val statistics: Boolean = false,
    val sample: PlaybackSample? = null,
    /**
     * Why the last failure happened, in full.
     *
     * Carried on the state rather than fetched by the card, because the engine that knows
     * is released the moment the fallback switches — a card that asked at draw time would
     * be asking something that no longer exists.
     */
    val diagnosis: PlaybackDiagnosis? = null,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long? = null,
    /**
     * Whether the source will accept a jump at all, as the engine reports it.
     *
     * On the state because the statistics panel is the only place a person can be told. A
     * live stream with no buffer behind it is not seekable and never will be, and a player
     * whose jump controls quietly do nothing — with no way to find out which of the several
     * possible reasons it was — is a player that cannot be diagnosed from a photograph.
     */
    val seekable: Boolean = false,
    /**
     * How many jumps have been asked for, and where the last one was aimed.
     *
     * Counted at the moment the press arrives, before any check: that is what makes the
     * pair diagnostic rather than decorative. Zero after pressing the control means the
     * press never reached the state holder at all, which is a different fault in a
     * different file from a jump the engine received and refused.
     */
    val seekRequests: Int = 0,
    val lastSeekMs: Long? = null,
    val speed: Float = 1f,
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<Track> = emptyList(),
    /**
     * The video renditions the container declared.
     *
     * An HLS ladder has several and a plain transport stream has one, so the quality sheet
     * lists what is actually there rather than an invented 1080p/720p/480p. Offering three
     * buttons where two do nothing is worse than offering the one that is true.
     */
    val videoTracks: List<Track> = emptyList(),
    /**
     * The programme strip's contents, or null while the guide has not answered.
     *
     * Null is *not* "no strip". The strip is drawn at its full height either way — that
     * is the whole point of `live-cold` in the drawing — and null selects the skeleton
     * rather than removing the band. A screen that let this field decide whether to
     * compose the strip would reflow the moment the guide arrived, which is the failure
     * the reserved height exists to prevent.
     */
    val programme: Programme? = null,
    /** How far behind the live edge, in milliseconds. Zero means at the edge. */
    val behindLiveMs: Long = 0,
) {
    val isTimeshifted: Boolean get() = behindLiveMs > TIMESHIFT_THRESHOLD_MS

    companion object {
        /**
         * Below this, "behind live" is jitter rather than a state.
         *
         * A live stream sits a few seconds behind the edge by design, and a player that
         * announced "12 seconds behind live" on every channel would be reporting its own
         * buffer as if it were the user's doing.
         */
        const val TIMESHIFT_THRESHOLD_MS = 30_000L
    }
}

/**
 * What the picture is doing. One of these, always, and the screen renders all of them.
 *
 * [Opening] and [Buffering] are separated because they are different screens: opening has
 * no frame behind it and buffering does, so one is a title on black and the other is a
 * spinner over a dimmed picture. Merging them would either dim nothing at the start or
 * blank the picture on every rebuffer.
 */
sealed interface Picture {

    /**
     * Before the first frame. The only state where the critical-path rule applies, and
     * the only state that carries nothing but what was passed in.
     */
    data object Opening : Picture

    data object Playing : Picture
    data object Paused : Picture

    /** A frame exists; the next one is late. */
    data object Buffering : Picture

    /** The stream dropped and is being reopened. Distinct from [Buffering] to the user. */
    data class Reconnecting(val attempt: Int, val of: Int) : Picture

    /**
     * It stopped, and here is what can be done about it.
     *
     * [canTryBackup] is computed once, by `FallbackPolicy`, and carried rather than
     * recomputed by the screen. The rule that a DRM card must not offer the backup engine
     * is then held by the fact that the screen has no way to decide it for itself.
     */
    data class Failed(val reason: PlaybackError, val canTryBackup: Boolean) : Picture

    data object Ended : Picture
}

/** The panels that slide in from the end edge. The statistics panel is not one of them. */
enum class Sheet { Subtitles, Audio, Settings, Quality }

/**
 * What is on now, and what is next.
 *
 * Arrives after the first frame or not at all. [progress] is a fraction rather than two
 * timestamps because that is what the strip draws, and computing it in the state holder
 * keeps arithmetic out of composition.
 */
data class Programme(
    val now: String,
    val window: String,
    val next: String?,
    val progress: Float,
)
