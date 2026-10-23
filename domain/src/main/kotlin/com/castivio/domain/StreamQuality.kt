package com.castivio.domain

import com.castivio.core.common.FrameSize

/**
 * What a stream's picture is, in the four words the board uses for it: SD, HD, FHD and 4K.
 *
 * ## It is measured, never read
 *
 * There is no quality column and there should not be one. A provider's channel name may say
 * `HD` or `4K`, and a name is a claim somebody typed -- a channel called `UHD` that is a 576p
 * upscale, a `FHD` that was never anything of the kind. The board used to draw those claims as
 * a badge, which put a guess where a fact belongs. A badge is now only the size a decoder
 * actually produced: see [of].
 */
enum class StreamQuality(val label: String) {
    SD("SD"),
    HD("HD"),
    FHD("FHD"),

    /** The one a viewer looks for, written the way a viewer writes it. */
    UHD("4K");

    companion object {

        /**
         * The class of a decoded picture.
         *
         * By the **lines it has**, and a picture that is wide and short is still its width's
         * class: a cinema transfer broadcast at 1920x800 is a full-HD channel, not a lesser one
         * because the film is letterboxed. So the height is taken as the larger of what the
         * frame has and what a 16:9 frame of this width would have.
         *
         * | lines | class |
         * |---|---|
         * | 2160 and up | 4K |
         * | 1080 and up | FHD |
         * | 720 and up | HD |
         * | anything less | SD |
         */
        fun of(frame: FrameSize): StreamQuality {
            val lines = maxOf(frame.height, frame.width * 9 / 16)
            return when {
                lines >= 2_160 -> UHD
                lines >= 1_080 -> FHD
                lines >= 720 -> HD
                else -> SD
            }
        }

        /** The class stored under [name], or null for a value this build does not know. */
        fun named(name: String?): StreamQuality? = entries.firstOrNull { it.name == name }
    }
}
