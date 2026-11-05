package com.castivio.core.design.components

/**
 * Whether a provider's picture is drawn in so dark an ink that it is lost on the dark ground of the screen.
 *
 * A channel logo is usually a mark on a transparent ground, and the ground of this product is near-black: a logo whose ink is
 * near-black as well -- France 2's black digit beside its coloured dot, a black wordmark, a grey one -- keeps only its few bright
 * pixels, and what is left reads as a logo a third the size of its neighbours'. Enlarging it does not help, because the lost part
 * is the larger part. What helps is a light plate behind it, which [ProviderArtwork] draws where this says so.
 *
 * ## The rule, and why each clause is there
 *
 * Over a small sample of the picture -- [SAMPLE] pixels a side, which is all a decision needs:
 *
 * 1. **The mean luminance of its opaque pixels is under [DARK_BELOW].** Opaque is what the picture draws, so the transparent margin
 *    does not make a coloured logo look pale or a black one look pale-grounded. Luminance is the plain sRGB luma, 0 to 1, with
 *    no gamma: the threshold is a judgement of how it looks on a dark screen, not a measurement of light. A coloured logo with a
 *    little black in it stays over 0.30 and gets no plate; a white one is far above it; a black wordmark is far below.
 * 2. **Not when it fills its box.** A picture whose opaque pixels are [OWN_GROUND_SHARE] of the sample or more brings its own
 *    ground -- a poster, a black tile with a white mark -- and a plate under it would be hidden by it. It is judged by what it is
 *    drawn on, not by what it is made of.
 * 3. **Not when there is hardly anything.** Under [MIN_OPAQUE_SHARE] of the sample there is no mark to judge, and a plate under
 *    nothing is a light square on a dark list.
 *
 * Pure, so it is tested without a screen: the sampling of a decoded picture into the array is [ProviderArtwork]'s, off the main
 * thread, once per address.
 */
object ArtworkInk {

    /** The side of the square a picture is sampled into. */
    const val SAMPLE = 32

    /** Mean luma of the opaque pixels under which a logo is too dark for the ground. Drawn at this value in `channels-dark-logos-ar.html`. */
    const val DARK_BELOW = 0.30

    /** A pixel is the picture's, and not its margin, from this alpha up. */
    private const val OPAQUE_ALPHA = 128

    /** A picture that is this much opaque or more is its own ground. */
    const val OWN_GROUND_SHARE = 0.90

    /** A picture that is less opaque than this has no mark to judge. */
    const val MIN_OPAQUE_SHARE = 0.02

    private const val BYTE = 255.0
    private const val RED_LUMA = 0.2126
    private const val GREEN_LUMA = 0.7152
    private const val BLUE_LUMA = 0.0722

    /**
     * @param argb the sample, one `0xAARRGGBB` int per pixel -- straight, not premultiplied, as `Bitmap.getPixels` gives them.
     * @return true where the picture should be drawn on a light plate.
     */
    fun needsPlate(argb: IntArray): Boolean {
        if (argb.isEmpty()) return false
        var opaque = 0
        var luma = 0.0
        for (pixel in argb) {
            if (((pixel ushr ALPHA_SHIFT) and BYTE_MASK) < OPAQUE_ALPHA) continue
            opaque++
            val red = (pixel ushr RED_SHIFT) and BYTE_MASK
            val green = (pixel ushr GREEN_SHIFT) and BYTE_MASK
            val blue = pixel and BYTE_MASK
            luma += (RED_LUMA * red + GREEN_LUMA * green + BLUE_LUMA * blue) / BYTE
        }
        val share = opaque.toDouble() / argb.size
        return when {
            share < MIN_OPAQUE_SHARE -> false
            share >= OWN_GROUND_SHARE -> false
            else -> luma / opaque < DARK_BELOW
        }
    }

    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BYTE_MASK = 0xFF
}
