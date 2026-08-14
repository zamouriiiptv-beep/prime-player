package com.castivio.playback.api

/**
 * Which of the two engines is playing, and how the second one is reached.
 *
 * ## The rule this file exists to enforce
 *
 * **The fallback is a budget, not a retry loop.** Engine 1 gets exactly one attempt to
 * open a source, bounded by a deadline; if it has not produced a frame by then, or has
 * failed with something a different engine could plausibly fix, engine 2 takes over —
 * once. There is no third attempt and no exponential backoff on the opening path.
 *
 * That is a performance decision before it is a correctness one. A retry loop turns a
 * dead source into eight seconds of spinner, and eight seconds of spinner on a channel
 * change is the difference between a player that feels instant and one that feels
 * broken. A user who has watched a channel fail once will press the button again far
 * sooner than the third automatic attempt would arrive, and their press is better
 * information than our timer.
 *
 * [RetryPolicy] still exists and still governs a stream that *was* playing and dropped —
 * a rebuffer on a live channel is worth riding out. The two are deliberately separate:
 * one is about opening, the other about continuing, and conflating them is how a
 * "retry" ends up costing a channel change four seconds.
 */
enum class EngineId {
    /**
     * Hardware decoders, the platform's own extractors, the fastest path to a frame.
     *
     * This is what opens every source that has not already proved it needs otherwise.
     */
    PRIMARY,

    /**
     * The same platform decoders, tried more patiently, and a more forgiving reader.
     *
     * ## What this is not
     *
     * It is **not a software decoder**, and describing it as one — which this comment
     * previously did — is the kind of claim that sends somebody looking in the wrong place
     * when a file fails. Castivio ships no `media3-decoder-*` artifact, so there is no
     * bundled codec to fall back to, and `EXTENSION_RENDERER_MODE_PREFER` resolves nothing.
     *
     * ## What it actually is
     *
     * `enableDecoderFallback`: where the primary gives up when the first decoder the
     * platform lists refuses to initialise, this one walks the rest of the list. Devices
     * ship several decoders per format — a vendor one and an AOSP one — and the second is
     * frequently more tolerant than the first. Plus MPEG-TS extractor flags for transport
     * streams that declare nothing useful in their PMT.
     *
     * That is a real difference and a narrow one. It cannot play a format the device has no
     * decoder for at all, and `EngineProfileTest` holds both halves of that statement.
     */
    BACKUP,
}

/**
 * Which engine a source needed last time.
 *
 * The point is that a source pays the fallback cost **once**. A channel that engine 1
 * cannot open will never be able to open — the codec does not change — so remembering
 * the answer turns a two-attempt open into a one-attempt open for every play after the
 * first. On a provider whose whole bundle is one awkward container that is the
 * difference between every channel being slow and one channel having been slow once.
 *
 * Reads are synchronous because the caller is the opening path and the opening path may
 * not wait for anything. It is one string in a key-value file; an asynchronous store
 * here would mean either blocking on it or opening on the wrong engine while the answer
 * arrives, and both are worse than the store being small.
 */
interface EngineMemory {

    /** The engine this source needed, or null if it has never been played. */
    fun preferred(sourceKey: String): EngineId?

    /** Record what actually worked. Called after a frame, never before. */
    fun remember(sourceKey: String, engine: EngineId)

    /** Nothing remembered. The default in a test, and in a build with no store wired. */
    companion object {
        val NONE: EngineMemory = object : EngineMemory {
            override fun preferred(sourceKey: String): EngineId? = null
            override fun remember(sourceKey: String, engine: EngineId) = Unit
        }
    }
}

/**
 * The whole of the fallback decision, as pure functions.
 *
 * Written here rather than in the player's view model because every one of these is a
 * claim that can be wrong, and none of them needs a device to check. A test that has to
 * start ExoPlayer to ask "does a DRM failure offer the backup engine?" is a test nobody
 * runs.
 */
object FallbackPolicy {

    /**
     * How long engine 1 gets to produce a first frame before engine 2 is given the
     * source instead.
     *
     * Three seconds is chosen against the failure it is protecting from rather than
     * against a healthy stream. A healthy IPTV channel on a working connection renders
     * inside a second; one that has not rendered in three is not slow, it is refusing —
     * a codec the decoder will not take, a manifest the reader cannot parse, a host that
     * accepted the connection and then said nothing.
     *
     * Longer, and every genuinely broken source costs the user the whole deadline before
     * anything else is tried. Shorter, and a cold cellular connection on a high-bitrate
     * stream gets pulled off an engine that was about to succeed and handed to the slower
     * one, which is a worse outcome arrived at faster.
     */
    const val OPEN_DEADLINE_MS: Long = 3_000

    /**
     * Which engine opens this source right now.
     *
     * Memory first, and nothing else consulted: not the file extension, not the host, not
     * a guess from the container. Guessing from a URL is how a player ends up opening a
     * perfectly ordinary stream on the slow engine because its filename ends in `.ts`.
     */
    fun first(sourceKey: String, memory: EngineMemory): EngineId =
        memory.preferred(sourceKey) ?: EngineId.PRIMARY

    /**
     * Whether handing this failure to the other engine could plausibly fix it.
     *
     * The backup engine differs from the primary in exactly one way that matters on a
     * device: `enableDecoderFallback`, which walks the platform's own list of decoders for
     * the format instead of giving up on the first one. It is **not** a software decoder —
     * no `media3-decoder-*` artifact is on the classpath, so the extension renderer mode is
     * a no-op — and the extractor flags it also sets apply to MPEG-TS and MP3 and to
     * nothing else. `EngineProfileTest` asserts both of those facts rather than trusting
     * this comment.
     *
     * So the question is narrow and honest: *is this a failure that a different decoder
     * from the same device might not have?*
     *
     * - **DECODER_INIT** is exactly that. A decoder was listed and refused. Yes.
     * - **DECODING** is a decoder that started and broke on the stream. Yes.
     * - **CONTAINER** is the one case the extractor flags address, on a transport stream
     *   whose PMT declares nothing useful. Yes.
     * - **UNSUPPORTED_FORMAT** means the platform said it has no decoder for this. There
     *   is no second list to consult. No.
     * - **DRM** is not a decoding problem; the same device lacks the same keys. No.
     * - **NETWORK**, **NOT_FOUND**, **PERMISSION**, **SOURCE** are about getting the
     *   bytes. Swapping decoders cannot open a file. No.
     * - **TIMEOUT** is silence, and the budget exists precisely to spend the other engine
     *   on silence. Yes.
     * - **UNKNOWN** could be anything, so it is the one case the machine does not decide
     *   for itself. See [decideAutomatically].
     */
    fun canBackupHelp(error: PlaybackError): Boolean = when (error) {
        PlaybackError.DECODER_INIT -> true
        PlaybackError.DECODING -> true
        PlaybackError.CONTAINER -> true
        PlaybackError.TIMEOUT -> true
        PlaybackError.UNKNOWN -> true

        PlaybackError.UNSUPPORTED_FORMAT -> false
        PlaybackError.DRM -> false
        PlaybackError.NETWORK -> false
        PlaybackError.NOT_FOUND -> false
        PlaybackError.PERMISSION -> false
        PlaybackError.SOURCE -> false
    }

    /**
     * Whether the player should spend the fallback *without asking*.
     *
     * ## The defect this function exists to fix
     *
     * One predicate was answering two different questions, and the result was that the
     * "try the backup player" button could never appear: every reason that made the button
     * meaningful also triggered the automatic switch first, so by the time a card was
     * drawn the fallback had always already been spent. A button that cannot be reached is
     * worse than no button — it is a promise the design makes and the code does not keep.
     *
     * The split is **automatic where the evidence is specific, ask where it is not.** A
     * decoder that refused, a container that would not parse, a source that said nothing
     * inside the budget: concrete, and worth switching on silently because the user did not
     * ask for the switch and cannot act on it.
     *
     * [PlaybackError.UNKNOWN] is different by definition — nothing identified itself, so
     * spending the single fallback attempt on it is a guess. The card appears with the
     * backup offered and a person decides.
     */
    fun decideAutomatically(error: PlaybackError): Boolean =
        canBackupHelp(error) && error != PlaybackError.UNKNOWN

    /**
     * The key a source is remembered under.
     *
     * The URL without its query, because an Xtream stream URL carries credentials and a
     * session token in the query and both rotate. Keyed on the whole URL, a provider that
     * appends a token would look like a new source on every launch and the memory would
     * never hit — which is the failure mode of a cache nobody notices, because it simply
     * never helps.
     *
     * Not hashed: this is a local key in a private file, and a readable one is a
     * debuggable one.
     */
    fun sourceKey(url: String): String = url.substringBefore('?').trim()
}

/**
 * Where the picture goes.
 *
 * Opaque on purpose. This module compiles for every platform Castivio will ever render
 * on and may not name a `SurfaceView`, but a player that cannot be told where to draw is
 * not a player. So the handle is typed as a Castivio thing and its contents are the
 * platform's business — the Android engine knows it is holding a view, and nothing above
 * the engine needs to.
 */
sealed interface VideoOutput {

    /**
     * A view supplied by the platform, held as [Any] for the reason above.
     *
     * Passing the wrong object is a programming error the engine reports rather than a
     * case it handles: there is exactly one call site per platform, and a silent
     * no-op would be a black picture with no explanation.
     */
    data class Platform(val view: Any) : VideoOutput
}
