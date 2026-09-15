package com.castivio.feature.home

/**
 * The quality tag a provider writes into a channel's own name.
 *
 * ## Why this is read from the title rather than from a column
 *
 * Because there is no column, and there should not be one. A stream's real resolution is
 * something the decoder learns when it opens the stream, and the board has opened
 * nothing — the previous version of this screen said exactly that and drew the channel's
 * *category* where the reference draws a quality tag.
 *
 * But the tag is not missing from the data. Providers put it in the name, every one of
 * them, because that is the only field an M3U or an Xtream row gives them for it:
 * `|FR| TF1 HD`, `|FR| FRANCE 2 UHD`, `beIN SPORTS 1 4K`. Reading it back out is not
 * inventing a fact, it is *re-presenting one the user is already being shown* — and it
 * is what lets the name be drawn without the tag, so `|FR| TF1 HD` reads as `|FR| TF1`
 * with a tag beside it instead of repeating itself.
 *
 * ## What it will not do
 *
 * It will not guess. A channel whose name carries no marker gets no tag — not `SD`, not
 * a default, nothing. Half a provider's catalogue is unlabelled and captioning all of it
 * `SD` would be an assertion about picture quality that nobody made.
 */
internal enum class StreamQuality(val label: String) {
    /** 4K and its spellings. Kept distinct from [FHD]: it is the one a viewer looks for. */
    UHD("UHD"),
    FHD("FHD"),
    HD("HD"),

    /**
     * Only when the provider says so.
     *
     * Never inferred from the absence of anything else — see the class note.
     */
    SD("SD"),
}

/**
 * The tag in [title], or null when the provider did not write one.
 *
 * Matched on whole tokens, which is the whole of the care this needs: `HD` appears
 * inside `HDMI`, `SHD` and any number of channel names, and a substring match would tag
 * a third of a catalogue wrongly. The pair `FULL HD` is read before the bare `HD` for
 * the same reason a longest-match always comes first.
 */
internal fun qualityOf(title: String): StreamQuality? = scanQuality(title)?.first

/**
 * [title] with its quality tag removed, so the tag is drawn once rather than twice.
 *
 * Returns the title unchanged when there was no tag, and never returns an empty string:
 * a channel whose entire name is `HD` keeps it, because a row with no name at all is
 * worse than a row that repeats a tag.
 */
internal fun titleWithoutQuality(title: String): String {
    val found = scanQuality(title) ?: return title
    val tokens = title.split(' ').filter { it.isNotEmpty() }
    val kept = tokens.filterIndexed { index, _ -> index !in found.second }
    if (kept.isEmpty()) return title
    return kept.joinToString(" ")
}

/**
 * The tag and the token indices it occupies, scanning from the end.
 *
 * From the end because that is where a provider puts it — `|FR| TF1 HD +4` is the
 * awkward case, and the marker there is neither first nor last.
 */
private fun scanQuality(title: String): Pair<StreamQuality, Set<Int>>? {
    val tokens = title.split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null

    for (index in tokens.indices.reversed()) {
        val token = tokens[index].trim(*TRIM).uppercase()

        // The two-token spellings first: `FULL HD` must not be read as a bare `HD`
        // with a stray `FULL` left in the name.
        if (index > 0 && token == "HD") {
            val before = tokens[index - 1].trim(*TRIM).uppercase()
            if (before == "FULL") return StreamQuality.FHD to setOf(index - 1, index)
        }

        val quality = when (token) {
            "4K", "UHD", "UHDTV", "2160P" -> StreamQuality.UHD
            "FHD", "1080P" -> StreamQuality.FHD
            "HD", "720P" -> StreamQuality.HD
            "SD", "576P", "480P" -> StreamQuality.SD
            else -> null
        }
        if (quality != null) return quality to setOf(index)
    }
    return null
}

/**
 * Punctuation a provider wraps the tag in.
 *
 * `[HD]`, `(UHD)` and `TF1-HD` are all the same claim, and a scan that only matched the
 * bare word would tag the first two and miss the rest of a catalogue that spells it
 * differently three rows later.
 */
private val TRIM = charArrayOf('[', ']', '(', ')', '-', '_', '.', ',', ':', '|', '*')
