package com.castivio.playback.api

import java.util.concurrent.atomic.AtomicInteger

/**
 * **Temporary, for development.** What the video decoder and the surface it draws on did with the frames they were
 * given: the half of a broken picture that is not in the bytes.
 *
 * A picture can break up because the stream lost a piece -- see [StreamIntegrity] -- or because the bytes were whole
 * and something after them went wrong: a decoder that dropped frames it was late with, an error it raised and went on
 * from, or a picture whose size changed under it. Read beside the stream's own figures it says which.
 *
 * Counts since the source was opened. A figure the engine cannot see is zero, and [available] says whether any was read.
 */
data class DecoderHealth(
    /** Whether the engine read anything: false for an engine that cannot, or a source with no video decoder yet. */
    val available: Boolean = false,
    /** Frames the decoder produced and put on the screen. */
    val rendered: Int = 0,
    /** Frames dropped because they were late. */
    val dropped: Int = 0,
    /** Frames skipped without being drawn, which a decoder does to catch up after a stall. */
    val skipped: Int = 0,
    /** Times the decoder dropped everything up to the next key frame to catch up. */
    val droppedToKeyFrame: Int = 0,
    /** The most frames dropped one after another. */
    val longestDropRun: Int = 0,
    /** Times the player said a run of frames was dropped, and how many frames it said in all. */
    val dropReports: Int = 0,
    val droppedReported: Int = 0,
    /** Errors the decoder raised that the player went on from, and the name of the last. */
    val codecErrors: Int = 0,
    val lastCodecError: String? = null,
    /** Times the size of the picture changed, and the last size. */
    val sizeChanges: Int = 0,
    val lastWidth: Int = 0,
    val lastHeight: Int = 0,
) {

    /** Whether anything about the decoder looks wrong: frames lost, an error raised, or a picture that changed size more than once. */
    val troubled: Boolean get() = dropped > 0 || skipped > 0 || codecErrors > 0 || sizeChanges > 1

    companion object {
        val NONE = DecoderHealth()
    }
}

/**
 * **Temporary, for development.** The events a player reports about its decoder, counted: what the decoder's own counters
 * do not keep.
 *
 * Written from the playback thread and read from another, so every figure is an atomic. Nothing is kept that names an
 * address, and an error is kept by the name of its class and nothing it says.
 */
class DecoderHealthLog {

    private val dropReports = AtomicInteger()
    private val droppedReported = AtomicInteger()
    private val codecErrors = AtomicInteger()
    private val sizeChanges = AtomicInteger()

    @Volatile
    private var lastCodecError: String? = null

    @Volatile
    private var lastWidth = 0

    @Volatile
    private var lastHeight = 0

    /** The player said [frames] frames were dropped over the last stretch. */
    fun dropped(frames: Int) {
        if (frames <= 0) return
        dropReports.incrementAndGet()
        droppedReported.addAndGet(frames)
    }

    /** The decoder raised an error and the player went on, named by its class. */
    fun codecError(name: String) {
        codecErrors.incrementAndGet()
        lastCodecError = name
    }

    /** The picture's size is now [width] by [height]: counted when it is not the size it was. */
    fun sized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == lastWidth && height == lastHeight) return
        lastWidth = width
        lastHeight = height
        sizeChanges.incrementAndGet()
    }

    /** The events counted, with the decoder's own counters: [rendered], [dropped], [skipped], [toKeyFrame] and [longestRun]. */
    fun snapshot(rendered: Int, dropped: Int, skipped: Int, toKeyFrame: Int, longestRun: Int): DecoderHealth = DecoderHealth(
        available = true,
        rendered = rendered,
        dropped = dropped,
        skipped = skipped,
        droppedToKeyFrame = toKeyFrame,
        longestDropRun = longestRun,
        dropReports = dropReports.get(),
        droppedReported = droppedReported.get(),
        codecErrors = codecErrors.get(),
        lastCodecError = lastCodecError,
        sizeChanges = sizeChanges.get(),
        lastWidth = lastWidth,
        lastHeight = lastHeight,
    )

    /** The events alone, for a decoder whose own counters are not there yet. */
    fun snapshotEvents(): DecoderHealth = snapshot(rendered = 0, dropped = 0, skipped = 0, toKeyFrame = 0, longestRun = 0)
        .copy(available = false)
}
