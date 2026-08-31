package com.castivio.data.subtitles

import java.io.BufferedReader

/**
 * One caption: when it appears, when it goes, and what it says.
 *
 * Times in milliseconds from the start of the film, which is the unit every player in this
 * product already speaks. [lines] rather than one string with newlines in it, because a
 * caption is laid out line by line and joining them would push the decision about where a
 * line breaks from the person who wrote the subtitle onto whatever width the screen happens
 * to be.
 */
data class SubtitleCue(
    val fromMs: Long,
    val toMs: Long,
    val lines: List<String>,
)

/**
 * A subtitle file, parsed, with the cues in the order they are shown.
 *
 * Held in memory, and this is the one place in Castivio where holding a whole file is
 * right: a two-hour film's subtitles are around 1,500 cues and 60 KB of text. The streaming
 * rule exists because a playlist is 400,000 items and an EPG is a hundred megabytes; this
 * is neither, and paging it would mean a disk read every time a line of dialogue changed.
 */
data class SubtitleTrack(val cues: List<SubtitleCue>) {

    /**
     * The caption for a moment, or none.
     *
     * A binary search rather than a scan, because this is called several times a second for
     * the whole length of a film. A scan is 1,500 comparisons a tick to answer a question
     * whose answer is almost always the same as last time; this is eleven.
     *
     * Overlapping cues exist — a sung line held under a spoken one — and this returns the
     * first that covers the moment. Rendering both would need the layer to know how to stack
     * them, which is a thing to build when a source that does it turns up rather than now.
     */
    fun at(positionMs: Long): SubtitleCue? {
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val middle = (low + high) / 2
            val cue = cues[middle]
            when {
                positionMs < cue.fromMs -> high = middle - 1
                positionMs > cue.toMs -> low = middle + 1
                else -> return cue
            }
        }
        return null
    }
}

/**
 * SubRip, which is what OpenSubtitles hands over for almost everything.
 *
 * ## Written for the files that actually arrive, not for the specification
 *
 * SRT has no specification. It has a shape most files follow and a long tail of ways they
 * do not, and a parser that insisted on the shape would reject a good proportion of what a
 * real subtitle site serves. So every one of the following is tolerated, because every one
 * of them is in the wild:
 *
 *  - **A byte-order mark** at the start of the file. Ignored — the alternative is a first
 *    cue whose index is `﻿1`, which parses as no index at all and swallows the cue.
 *  - **Carriage returns.** Windows line endings are the majority of SRT files in existence.
 *  - **A missing or non-numeric index.** The index is decoration; the timing line is what
 *    identifies a cue, so a file with no indices at all still parses.
 *  - **Commas or full stops** in the timestamp. Both are common and both mean the same
 *    thing.
 *  - **Blank lines between cues, or none.** A new timing line ends the previous cue.
 *  - **Tags** — `<i>`, `<b>`, `{\an8}` — which are removed rather than rendered. Castivio
 *    draws captions in the viewer's chosen type; a source's italics would fight it, and a
 *    positioning tag would put words over the controls.
 *
 * What is *not* tolerated is a malformed timing line: it is skipped, along with its cue.
 * A cue with no time is a caption that could be shown at any moment, which is worse than a
 * caption that is missing.
 */
object SrtParser {

    /**
     * Read a whole file into a track.
     *
     * Line by line rather than by reading the text and splitting it: the file arrives from
     * a network stream, and `readText().split()` holds the file twice at its peak — once as
     * the string and once as the list — for no benefit.
     */
    fun parse(reader: BufferedReader): SubtitleTrack {
        val cues = mutableListOf<SubtitleCue>()
        var from: Long? = null
        var to: Long? = null
        val lines = mutableListOf<String>()

        fun close() {
            val start = from
            val end = to
            if (start != null && end != null && lines.isNotEmpty()) {
                cues += SubtitleCue(start, end, lines.toList())
            }
            from = null
            to = null
            lines.clear()
        }

        reader.forEachLine { raw ->
            val line = raw.removePrefix(BYTE_ORDER_MARK).trimEnd('\r').trim()
            val timing = timing(line)
            when {
                // A timing line always starts a cue, whatever came before it. That is what
                // makes a missing blank line between cues harmless, and a stray index line
                // in the middle of one no worse than a stray word.
                timing != null -> {
                    close()
                    from = timing.first
                    to = timing.second
                }

                // Before the first timing line there is nothing to collect: the index, and
                // any header the file happens to carry, belong to no cue.
                from == null -> Unit

                line.isEmpty() -> if (lines.isNotEmpty()) close()

                // An index line for the *next* cue, arriving before its timing line. Held
                // back rather than added: a bare number on its own at the end of a caption
                // is an index far more often than it is dialogue.
                line.isIndex() -> Unit

                else -> lines += line.withoutTags()
            }
        }
        close()

        // Sorted, because `at` binary-searches and a file whose cues are out of order — which
        // happens, in files assembled from two sources — would otherwise make the search
        // return nothing for perfectly good captions.
        return SubtitleTrack(cues.sortedBy { it.fromMs })
    }

    /** `00:01:02,500 --> 00:01:04,000`, in any of the ways files write it. */
    private fun timing(line: String): Pair<Long, Long>? {
        val match = TIMING.find(line) ?: return null
        val (fromText, toText) = match.destructured
        val from = clock(fromText) ?: return null
        val to = clock(toText) ?: return null
        // A cue that ends before it begins is a broken line, not a cue shown backwards.
        return if (to >= from) from to to else null
    }

    /** `hh:mm:ss,mmm`, with either separator and a tolerance for one- or two-digit hours. */
    private fun clock(text: String): Long? {
        val match = CLOCK.matchEntire(text.trim()) ?: return null
        val (hours, minutes, seconds, millis) = match.destructured
        return hours.toLong() * MS_IN_HOUR +
            minutes.toLong() * MS_IN_MINUTE +
            seconds.toLong() * MS_IN_SECOND +
            millis.padEnd(3, '0').take(3).toLong()
    }

    private fun String.isIndex(): Boolean = all { it.isDigit() }

    private fun String.withoutTags(): String = replace(TAGS, "").trim()

    private val TIMING = Regex("""([\d:,.]+)\s*-->\s*([\d:,.]+)""")
    private val CLOCK = Regex("""(\d{1,3}):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")

    /** `<i>`, `</b>`, `{\an8}` — markup this player does not honour. */
    private val TAGS = Regex("""<[^>]*>|\{[^}]*}""")

    private const val BYTE_ORDER_MARK = "﻿"
    private const val MS_IN_SECOND = 1_000L
    private const val MS_IN_MINUTE = 60 * MS_IN_SECOND
    private const val MS_IN_HOUR = 60 * MS_IN_MINUTE
}
