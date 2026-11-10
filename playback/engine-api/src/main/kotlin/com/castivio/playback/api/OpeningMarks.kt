package com.castivio.playback.api

/**
 * Where an open has spent its time so far, as the engine saw it.
 *
 * Every figure is milliseconds after the engine was asked to open the source, on its own
 * monotonic clock, and null for a step that has not happened yet or that this engine cannot
 * see. Together with the player's own figures -- the press reaching it, the previous engine
 * let go, this one built -- they separate what the application spends from what the server,
 * the network and the decoder spend. That is the question a slow channel asks, and a single
 * total cannot answer it: a change made to the wrong part never shows up in the sum.
 */
data class OpeningMarks(
    /** The server answered: the connection is open, any redirect followed, the response begun. */
    val serverAnsweredMs: Long? = null,
    /** The first payload bytes delivered by Media3's transfer listener. */
    val firstBytesMs: Long? = null,
    /** DNS lookup duration(s) observed on the actual HTTP call, summed over every hop; null when no lookup occurred. */
    val dnsMs: Long? = null,
    /** TCP connection establishment duration(s) observed on the actual HTTP call: the sum over every hop of a redirect chain. */
    val tcpMs: Long? = null,
    /** TLS handshake duration(s) observed on the actual HTTPS call: the sum over every hop of a redirect chain. */
    val tlsMs: Long? = null,
    /** Request-headers-end to first response-headers-start on the actual HTTP call: the last hop's alone. See [hops]. */
    val ttfbMs: Long? = null,
    /** When Media3 entered onTracksChanged, before application-side track enumeration. */
    val tracksCallbackMs: Long? = null,
    /** Time spent enumerating and mapping the callback's track groups, in milliseconds. */
    val tracksProcessingMs: Long? = null,
    /** The stream had been read far enough to say what it carries: its tracks were known. */
    val tracksKnownMs: Long? = null,
    /** Media3 DecoderReuseEvaluation result: 0=no, 1=yes with flush, 2=yes with reconfiguration. */
    val decoderReuseResult: Int? = null,
    /** Media3 DecoderReuseEvaluation discard-reason bitmask, when a reuse evaluation was reported. */
    val decoderReuseDiscardReasons: Int? = null,
    /** The video decoder was ready for the picture. */
    val decoderReadyMs: Long? = null,
    /** How long building that decoder took, when one was built and the engine says. */
    val decoderInitMs: Long? = null,
    /** The decoder the previous source used was kept for this one rather than built again. */
    val decoderKept: Boolean = false,
    /** The first frame was on the screen. */
    val firstFrameMs: Long? = null,
    /**
     * The engine said it was ready: Media3's `STATE_READY`, LibVLC's `Playing`. **The input is running, which is not
     * a picture** -- a decoder can be ready while the surface is still black -- and it is kept apart from
     * [firstFrameMs] so the time between the two can be read. Null where the engine has not said it yet.
     */
    val readyMs: Long? = null,
    /**
     * For an engine that cannot see the frame itself: the playback clock moved after [firstFrameMs]. LibVLC's only
     * evidence, after the video output appearing, that pictures are being shown. Null for an engine that sees frames.
     */
    val clockMovingMs: Long? = null,
    /**
     * Whether [firstFrameMs] is the engine's own word that a frame was drawn (Media3's `onRenderedFirstFrame`), or
     * the nearest thing it says (LibVLC's video output appearing, which can come before the first picture is on the
     * glass). A figure that is not exact is printed as an approximation and is never presented as a measurement.
     */
    val firstFrameExact: Boolean = true,
    /**
     * **Development builds only.** The tries the engine made at the address, in order, with the lookup of the host's
     * address families: what [serverAnsweredMs] and [firstBytesMs] are the last of. Empty for an engine that does not
     * see its tries, and in any build that does not measure.
     */
    val attempts: OpenAttempts = OpenAttempts.NONE,
    /**
     * **Development builds only.** The responses the latest try followed to reach the stream -- each redirect, then the
     * answer -- with each one's own lookup, connection and wait: the parts [serverAnsweredMs] is the end of, which the
     * summed and last-hop transport figures above cannot separate. Empty for an engine that does not see its HTTP
     * client, and in any build that does not measure.
     */
    val hops: HttpHops = HttpHops.NONE,
    /**
     * **Development builds only.** Whether the stream's bytes arrived whole -- see [StreamIntegrity]. Read while the stream plays,
     * and not only while it opens: a picture breaks up after the first frame as well. Empty for an engine that cannot say.
     */
    val integrity: StreamIntegrity = StreamIntegrity.NONE,
    /** **Development builds only.** What the video decoder did with the frames -- see [DecoderHealth]. */
    val decoder: DecoderHealth = DecoderHealth.NONE,
)
