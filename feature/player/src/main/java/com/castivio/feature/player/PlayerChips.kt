package com.castivio.feature.player

import com.castivio.domain.StreamQuality
import com.castivio.playback.api.Track
import java.util.Locale

/**
 * One of the small words under the title: what the picture and the sound of this stream are.
 *
 * [highlighted] is the first of them, the class of the picture, which the reference draws with the
 * focus colour round it -- see `design/mockups/player-reference.png`.
 */
internal data class TechChip(val text: String, val highlighted: Boolean = false)

/**
 * The words that say what is playing -- `FHD`, `H.264`, `16:9`, `2.0`, `ENG` -- each only when it is known.
 *
 * ## Known, never assumed
 *
 * Every chip is read from what the engine has already reported: the decoded picture's size, the video
 * track the container declared, the shape the decoder gave the picture, the selected sound's channels and
 * language. A word whose source has not spoken is left out rather than guessed, so a stream that has
 * shown nothing yet has no chips at all and one that has shown everything has five -- never a `H.264`
 * that was the last channel's. The class of the picture is [StreamQuality]'s, which measures and never
 * reads a name: the same four words the channels board uses.
 *
 * Pure, so the arithmetic is tested without a screen and nothing is worked out in composition.
 */
internal fun techChips(state: PlayerState): List<TechChip> = buildList {
    state.videoSize?.let { add(TechChip(StreamQuality.of(it).label, highlighted = true)) }
    codecWord(state.videoTracks)?.let { add(TechChip(it)) }
    aspectWord(state.videoAspectRatio)?.let { add(TechChip(it)) }
    val sound = state.audioTracks.firstOrNull { it.selected } ?: state.audioTracks.firstOrNull()
    sound?.channels?.let(::channelsWord)?.let { add(TechChip(it)) }
    sound?.language?.let(::languageWord)?.let { add(TechChip(it)) }
}

/**
 * The codec of the picture, as people write it, or null when the track says something else.
 *
 * The engine names a video track by its container's label, or failing that by the second half of its
 * MIME type -- `AVC`, `HEVC` -- so those are what is recognised. A label that is anything else (a
 * rendition's name, a resolution) is not a codec and is not printed as one.
 */
internal fun codecWord(videoTracks: List<Track>): String? {
    val track = videoTracks.firstOrNull { it.selected } ?: videoTracks.firstOrNull() ?: return null
    return when (track.label.uppercase(Locale.ROOT).replace("-", "").replace(".", "").replace(" ", "")) {
        "AVC", "AVC1", "H264" -> "H.264"
        "HEVC", "HEV1", "HVC1", "H265" -> "H.265"
        "AV1", "AV01" -> "AV1"
        "VP9" -> "VP9"
        "MPEG2", "MPEG2VIDEO" -> "MPEG-2"
        "MPEG4", "MP4VES" -> "MPEG-4"
        else -> null
    }
}

/**
 * The shape of the picture as a ratio people know, or as a figure when it is not one of them.
 *
 * Within a few hundredths of a known shape it is that shape, because a decoder's `1.778` is `16:9`
 * and nobody wants to read the first.
 */
internal fun aspectWord(ratio: Float?): String? {
    if (ratio == null || ratio <= 0f) return null
    val known = KNOWN_SHAPES.firstOrNull { kotlin.math.abs(it.first - ratio) <= SHAPE_TOLERANCE }
    return known?.second ?: String.format(Locale.ROOT, "%.2f:1", ratio)
}

private val KNOWN_SHAPES = listOf(
    1f to "1:1",
    4f / 3f to "4:3",
    16f / 9f to "16:9",
    21f / 9f to "21:9",
)

private const val SHAPE_TOLERANCE = 0.03f

/** The sound's layout as it is written on a box: stereo is `2.0`, six channels are `5.1`. */
internal fun channelsWord(channels: Int): String? = when {
    channels <= 0 -> null
    channels == 6 -> "5.1"
    channels == 7 -> "6.1"
    channels == 8 -> "7.1"
    channels in 1..5 -> "$channels.0"
    else -> "$channels ch"
}

/**
 * The sound's language as three capital letters, or null when it says none.
 *
 * `en` and `en-US` and `eng` are all `ENG`. A track that declares no language, or declares
 * "undetermined", has no chip: three letters that say nothing are worse than none.
 */
internal fun languageWord(tag: String): String? {
    val code = tag.trim().lowercase(Locale.ROOT)
    if (code.isEmpty() || code == "und" || code == "zxx" || code == "mul") return null
    val language = code.substringBefore('-').substringBefore('_')
    if (language.length == 3) return language.uppercase(Locale.ROOT)
    return runCatching { Locale.forLanguageTag(language).isO3Language }
        .getOrNull()
        ?.takeIf { it.length == 3 }
        ?.uppercase(Locale.ROOT)
}
