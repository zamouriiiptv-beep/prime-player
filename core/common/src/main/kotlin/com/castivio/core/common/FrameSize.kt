package com.castivio.core.common

/**
 * The size of a decoded picture, in pixels, as the decoder reported it.
 *
 * What the board's quality badge is read from, and the only reliable thing it can be read from:
 * a stream's resolution is a property of the stream, learned when a decoder opens it. Neither a
 * channel's name nor its category says it.
 *
 * Both sides are positive -- a size with a zero in it is the decoder not yet having said, and
 * that is a null where one of these is wanted, not a degenerate instance of it.
 */
data class FrameSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "a frame of ${width}x$height" }
    }

    companion object {
        /** The size, or null when either side is not positive: nothing decoded yet. */
        fun of(width: Int, height: Int): FrameSize? =
            if (width > 0 && height > 0) FrameSize(width, height) else null
    }
}
