package com.castivio.playback.api

/**
 * Which of the two engines is playing, and how the second one is reached.
 *
 * ## The rule this file exists to enforce
 *
 * **The fallback is a budget, not a retry loop.** Engine 1 gets exactly one attempt to
 * open a source, bounded by a deadline; if it has stopped making progress on a stream that
 * is reaching it, or has failed with something a different engine could plausibly fix,
 * engine 2 takes over — once. A source that has sent engine 1 nothing at all is not handed
 * over: a second engine cannot fix silence, and [FallbackPolicy.primaryVerdict] says what
 * happens instead. There is no third attempt and no exponential backoff on the opening path.
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
     * Media3/ExoPlayer on the platform's hardware decoders: the fastest path to a frame.
     *
     * This is what opens every source that has not already proved it needs otherwise.
     */
    PRIMARY,

    /**
     * LibVLC, decoding in software.
     *
     * ## Why this doc was rewritten
     *
     * It used to describe the backup as a second Media3 profile with
     * `enableDecoderFallback` turned on, and warned — correctly, at the time — that this
     * was *not* a software decoder: it only walked the device's own MediaCodec list, so a
     * format the device had no decoder for at all was unplayable on both engines. That is
     * no longer the shape of this product. `:playback:engine-vlc` bundles LibVLC's own
     * codecs, so the backup carries decoders the device does not have.
     *
     * That single fact is what makes [PlaybackError.UNSUPPORTED_FORMAT] worth falling back
     * on, where under the old arrangement it provably was not. It is also the reason the
     * backup is still *second*: software decoding is slower and warmer than hardware, so
     * it is what a source earns by failing, never what one starts on.
     */
    BACKUP,
}

/**
 * What [FallbackPolicy.primaryVerdict] says about a primary engine that has not drawn yet.
 */
enum class PrimaryVerdict {
    /** Neither bound has been reached: keep waiting on the engine that has the source. */
    WAIT,

    /** The stream is reaching the engine and it has stopped making progress: the backup can plausibly fix it. */
    FALL_OVER,

    /** Nothing has reached the engine in [FallbackPolicy.NO_ANSWER_DEADLINE_MS]: a card, because the backup cannot fix silence. */
    GIVE_UP,
}

/**
 * Which engine a source needed last time.
 *
 * The point is that a source pays the fallback cost **once**. A channel that engine 1
 * cannot decode will never be able to decode — the codec does not change — so remembering
 * the answer turns a two-attempt open into a one-attempt open for every play after the
 * first. On a provider whose whole bundle is one awkward container that is the
 * difference between every channel being slow and one channel having been slow once.
 *
 * **Only what is true of the stream is remembered.** A source that is handed to the backup
 * is never offered to engine 1 again, so a wrong entry is never corrected: it is a
 * permanent verdict from a single open. That is right when the verdict is "this device
 * cannot decode it" and wrong when it is "nothing arrived in three seconds", which is a
 * statement about the network at that moment. [FallbackPolicy.shouldRemember] draws the
 * line.
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
     * How long an engine may go without a sign of life before it is given up on.
     *
     * For the **primary** the three seconds are counted from its last sign of life, not from
     * the press: from the first bytes of the stream, and again from its tracks being known.
     * See [primaryVerdict]. Before any byte has reached it there is nothing to count from,
     * and [NO_ANSWER_DEADLINE_MS] is the bound. For the **backup** they are the time to
     * start, counted from the open, as they always were.
     *
     * Three seconds is chosen against the failure it protects from rather than against a
     * healthy stream: bytes arriving that the engine cannot make a picture of -- a transport
     * stream whose table the strict reader will not parse, a decoder that never draws. On a
     * working connection a channel goes from its first bytes to a picture in a second or two
     * (0.75 s in the one measurement taken on a phone), so three seconds without a step is not
     * slow, it is stuck.
     *
     * Longer, and every stream the primary cannot read costs the user the whole deadline
     * before anything else is tried. Shorter, and a link that delivers the first keyframe
     * slowly gets pulled off an engine that was about to succeed and handed to the slower
     * one, which is a worse outcome arrived at faster.
     */
    const val OPEN_DEADLINE_MS: Long = 3_000

    /**
     * How long the primary is waited on while nothing of the stream has reached it at all.
     *
     * ## Why this is not [OPEN_DEADLINE_MS]
     *
     * The three seconds were chosen on the reading that a source silent for three seconds is
     * refusing. The opening panel on a phone over 4G read otherwise: channels to which neither
     * engine got a single byte in six seconds -- the primary for three, then the backup for
     * three -- and the same kind of channel playing when pressed again a little later. A
     * provider that starts a channel for its first viewer, or a link that is still warming up,
     * is slow and not refusing; and handing a silent source to the backup is the one switch
     * that cannot help, because the backup puts the same question to the same server, and on
     * an account that allows one connection may put it while the first is still being let go.
     *
     * So before the first byte the primary is left on its connection, and what ends the wait
     * is this bound, which is a card -- the viewer's retry, or the backup by hand -- and not
     * an automatic switch. Long enough for a provider's cold start and the primary's own
     * connection retry; short enough that a dead host does not turn the spinner for ever.
     */
    const val NO_ANSWER_DEADLINE_MS: Long = 12_000

    /**
     * How long a **backup** that has started gets to show its first picture, counted from the
     * same open as [OPEN_DEADLINE_MS].
     *
     * [OPEN_DEADLINE_MS] is measured, for the backup, to [PlaybackEngine.startedAtMs]. For
     * Media3 that is the first frame, and the primary is timed by [primaryVerdict] instead, so
     * this never comes into it. LibVLC starts before it draws: it fills its
     * network cache — a second for a film, half of one for a channel — and then waits for a
     * picture it can decode, which on a live stream can be a keyframe several seconds away.
     * A backup that had started inside three seconds always counted as open; giving its
     * picture the three seconds meant for silence would put a timeout card in front of
     * sources that play.
     *
     * So this is a bound, not a budget: three seconds to start, a second of cache, a long
     * keyframe interval of five, and one spare. A source that has started and still shows
     * nothing by then gets the existing timeout card — the alternative is a spinner that
     * turns for ever over a sound with no picture.
     */
    const val PICTURE_DEADLINE_MS: Long = 10_000

    /**
     * What to do about a primary engine that has not drawn yet, from what has reached it.
     *
     * ## Silence after progress, not a flat budget from the press
     *
     * A flat three seconds from the press handed to the backup every source whose server was
     * slow to start, and that is the one switch that cannot help: the backup asks the same
     * server the same question. What the engine has been *given* separates the two cases the
     * flat budget could not tell apart:
     *
     * - **Nothing has reached it** -- no byte of the stream. The source is slow or gone, and
     *   no other engine decodes what has not arrived. Wait, up to [NO_ANSWER_DEADLINE_MS],
     *   and then [PrimaryVerdict.GIVE_UP]: a card, with the backup left to the viewer.
     * - **Bytes are arriving and it cannot make a picture of them.** That is the engine's
     *   problem, and the backup's demuxer and decoders are what can fix it. After
     *   [OPEN_DEADLINE_MS] without a step -- from the first bytes, and again from the tracks
     *   being known, because a stream that has told the engine what it carries has done
     *   what the strict reader could not do for the streams the backup exists for --
     *   [PrimaryVerdict.FALL_OVER].
     *
     * A frame ends all of this, and is the caller's to notice: this only answers for an
     * engine that has not drawn.
     *
     * Pure, over times the caller measures on one clock, so the rule is tested without a
     * player. A failure the engine reports is not this function's: it is decided by
     * [decideAutomatically] the moment it arrives, however long the engine has waited.
     *
     * @param elapsedMs since the engine was asked to open the source.
     * @param bytesAtMs when the first bytes of the stream were seen, on the same clock, or null.
     * @param tracksAtMs when the stream's tracks were seen, or null. Tracks imply bytes.
     */
    fun primaryVerdict(elapsedMs: Long, bytesAtMs: Long?, tracksAtMs: Long?): PrimaryVerdict {
        val lastStepAt = tracksAtMs ?: bytesAtMs
            ?: return if (elapsedMs >= NO_ANSWER_DEADLINE_MS) PrimaryVerdict.GIVE_UP else PrimaryVerdict.WAIT
        return if (elapsedMs - lastStepAt >= OPEN_DEADLINE_MS) PrimaryVerdict.FALL_OVER else PrimaryVerdict.WAIT
    }

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
     * The engine to hand this failure to, or null when nothing is left to try.
     *
     * The ordering half of the policy, kept separate from [canBackupHelp] so that "is
     * there another engine" and "would it help" cannot drift apart. There are two engines
     * and the fallback is spent once, so [EngineId.BACKUP] never has a successor: a
     * failure on the backup is the end of the automatic path, and what happens next is a
     * person reading the card.
     */
    fun nextEngine(current: EngineId, error: PlaybackError): EngineId? = when (current) {
        EngineId.PRIMARY -> if (canBackupHelp(error)) EngineId.BACKUP else null
        EngineId.BACKUP -> null
    }

    /**
     * Whether handing this failure to the other engine could plausibly fix it.
     *
     * The backup is LibVLC with its own bundled decoders, so the question this answers is
     * *is this a decoding problem at all?* — not, as it once was, *might a second
     * MediaCodec entry on this same device be more tolerant?* The narrower question is why
     * [PlaybackError.UNSUPPORTED_FORMAT] used to answer `false` here; with real software
     * decoders behind the boundary it now answers `true`, and that is the one line in this
     * file the VLC engine changed.
     *
     * - **DECODER_INIT** — a decoder was listed and refused. VLC does not use it. Yes.
     * - **DECODING** — a decoder started and broke on the stream. Yes.
     * - **CONTAINER** — VLC's demuxers read containers Media3's extractors reject. Yes.
     * - **UNSUPPORTED_FORMAT** — the platform has no decoder for this. VLC ships its own,
     *   so this is now precisely the case the backup exists for. Yes.
     * - **DRM** — not a decoding problem; the same device lacks the same keys. No.
     * - **NETWORK**, **NOT_FOUND**, **PERMISSION**, **SOURCE** — about getting the bytes.
     *   A different decoder cannot open a file. No.
     * - **TIMEOUT** — silence, and the budget exists precisely to spend the other engine
     *   on silence. Yes.
     * - **UNKNOWN** — could be anything, so it is the one case the machine does not decide
     *   for itself. See [decideAutomatically].
     */
    fun canBackupHelp(error: PlaybackError): Boolean = when (error) {
        PlaybackError.DECODER_INIT -> true
        PlaybackError.DECODING -> true
        PlaybackError.CONTAINER -> true
        PlaybackError.UNSUPPORTED_FORMAT -> true
        PlaybackError.TIMEOUT -> true
        PlaybackError.UNKNOWN -> true

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
     * Whether a failure of the primary engine says something about the *source*, which is what
     * [EngineMemory] is allowed to keep.
     *
     * ## Why the memory needs its own question
     *
     * [canBackupHelp] asks whether the backup could play this *now*. The memory asks whether
     * the primary will *never* play it, because a remembered source is not given the primary
     * again, and so never gets the chance to prove the memory wrong. The two answers differ
     * for exactly the reasons that are about the moment and not about the stream:
     *
     * - **DECODER_INIT, DECODING, CONTAINER, UNSUPPORTED_FORMAT** — the stream and this device's
     *   decoders disagree. That does not change between one press and the next. Lasting.
     * - **TIMEOUT** — nothing arrived inside the budget. On a cold cellular link, or a provider
     *   starting a channel for its first viewer, the first byte can take longer than the budget
     *   and the same channel opens in a second on the next press. Not lasting: the device
     *   measured the network, and the memory is about the stream.
     * - **UNKNOWN** — nothing identified itself, so there is no claim to remember.
     * - **DRM, NETWORK, NOT_FOUND, PERMISSION, SOURCE** — about getting the bytes or the keys.
     *   They never hand a source to the backup, and are here so that a new one is decided on
     *   purpose.
     */
    fun isLasting(error: PlaybackError): Boolean = when (error) {
        PlaybackError.DECODER_INIT -> true
        PlaybackError.DECODING -> true
        PlaybackError.CONTAINER -> true
        PlaybackError.UNSUPPORTED_FORMAT -> true

        PlaybackError.TIMEOUT -> false
        PlaybackError.UNKNOWN -> false
        PlaybackError.DRM -> false
        PlaybackError.NETWORK -> false
        PlaybackError.NOT_FOUND -> false
        PlaybackError.PERMISSION -> false
        PlaybackError.SOURCE -> false
    }

    /**
     * Whether the engine that has just drawn a frame is to be written into [EngineMemory].
     *
     * @param engine the engine that drew the frame.
     * @param primaryLeftFor why the primary gave the source up during this open, or null when it
     *   did not: the source opened on the primary, or the memory sent it straight to the backup.
     *   A source the memory sent to the backup is remembered again, which changes nothing; it is
     *   not a new claim.
     */
    fun shouldRemember(engine: EngineId, primaryLeftFor: PlaybackError?): Boolean = when (engine) {
        EngineId.PRIMARY -> true
        EngineId.BACKUP -> primaryLeftFor == null || isLasting(primaryLeftFor)
    }

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
 * platform's business — the Android engines know they are holding a view, and nothing
 * above the engine needs to.
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
