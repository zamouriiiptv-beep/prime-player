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
     * Software decoders and a permissive reader, for the streams the fast path refuses.
     *
     * Slower to start and heavier on the CPU, which is exactly why it is not the
     * default: paying that cost on every channel to rescue the few that need it would
     * make the whole product slower to be right about a minority of it.
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
     * This is the question the three error cards differ on, and it is answered here once
     * so that the card and the automatic fallback can never disagree — an "unsupported"
     * card offering a backup button that the automatic path already knows is useless
     * would be the player lying about what it can do.
     *
     * - A **container or codec** the first engine refused is precisely what the second
     *   one exists for. Yes.
     * - **UNSUPPORTED_FORMAT** is the answer after both have refused. There is no third
     *   engine, so no.
     * - **DRM** is not a decoding problem. The device lacks the keys or the security
     *   level, and a different decoder on the same device lacks them identically. No.
     * - **NETWORK** and **NOT_FOUND** are about the source, not the reader. Swapping
     *   engines cannot make a host answer. No — this is a retry, if anything.
     */
    fun canBackupHelp(error: PlaybackError): Boolean = when (error) {
        PlaybackError.DECODER -> true
        PlaybackError.UNKNOWN -> true
        PlaybackError.UNSUPPORTED_FORMAT -> false
        PlaybackError.DRM -> false
        PlaybackError.NETWORK -> false
        PlaybackError.NOT_FOUND -> false
    }

    /**
     * What the failure becomes once the engines have been exhausted.
     *
     * The distinction the three cards are built on. A decoder failure that engine 2 has
     * *also* refused stops being "this engine could not read it" and becomes "nothing
     * here can read it" — which is a different sentence to the user and a different set
     * of buttons, and getting it from the same function as the fallback decision is what
     * keeps the two in step.
     */
    fun exhausted(error: PlaybackError): PlaybackError = when (error) {
        PlaybackError.DECODER, PlaybackError.UNKNOWN -> PlaybackError.UNSUPPORTED_FORMAT
        else -> error
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
