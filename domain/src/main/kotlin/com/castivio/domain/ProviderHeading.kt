package com.castivio.domain

/**
 * An entry a provider put in its playlist as a *heading* rather than as a channel.
 *
 * ## Why this exists
 *
 * An M3U playlist and an Xtream stream list have one row type. A provider that wants to
 * divide a long category into sections has nowhere to put a heading, so it writes one as
 * a row: `##### UHD 3840P #####`, `###### ARABIC ######`, `#### CABEL TV ####`. Every
 * IPTV catalogue in the wild is full of them, and to the database they are channels like
 * any other — with a stream url that usually plays nothing.
 *
 * The board has to tell them apart for one visible reason and one invisible one. The
 * visible one is the number: a heading with a channel number beside it says it is the
 * first channel of the category, which it is not. The invisible one is that a viewer who
 * presses one gets a player that fails, and a screen that knows the row is a heading can
 * eventually say so instead.
 *
 * ## What counts, and what deliberately does not
 *
 * A run of **three or more** of the same punctuation mark, at the start or the end of the
 * title, with something else in between. Three is the threshold because it is the point
 * at which a mark stops being punctuation and becomes decoration — `Channel #1` and
 * `M+ #Vamos` are channel names that happen to contain the character, and no provider
 * has ever named a real channel `### Sport ###`.
 *
 * It is matched on a run of *one* character repeated, not on "any three punctuation
 * marks", so `4K| SKY SPORTS [EVENT]` and `US| SPECTRUM NET…` are untouched.
 *
 * A title that is *only* decoration — `##########` — is a heading too, and the one with
 * nothing in between is the case that would otherwise fall through both branches.
 *
 * ## Why it is in the domain
 *
 * Because it is a fact about what a provider's catalogue contains, not a decision about
 * how to draw it. Every renderer of this catalogue needs the same answer, and this way
 * it is one pure function with tests that run without an emulator.
 */
fun isProviderHeading(title: String): Boolean {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.leadingRun() >= HEADING_RUN || trimmed.trailingRun() >= HEADING_RUN
}

/** How many times the first character repeats, if it is one of [HEADING_MARKS]. */
private fun String.leadingRun(): Int {
    val mark = first()
    if (mark !in HEADING_MARKS) return 0
    var run = 0
    while (run < length && this[run] == mark) run++
    return run
}

private fun String.trailingRun(): Int {
    val mark = last()
    if (mark !in HEADING_MARKS) return 0
    var run = 0
    while (run < length && this[length - 1 - run] == mark) run++
    return run
}

/**
 * The characters providers decorate with.
 *
 * Every one of these was taken from a real playlist. `|` is absent on purpose and is the
 * near miss worth naming: providers use it as a *separator* — `4K| SKY SPORTS`, `ES-C| LA
 * 1` — so a run of it would be a country prefix and not a heading.
 */
private val HEADING_MARKS = charArrayOf('#', '=', '*', '-', '~', '+', '_', '★', '•', '▬', '═')

/**
 * Three.
 *
 * Two is a real name — `C++`, `**` in a provider's shorthand. Three is where a mark stops
 * being punctuation and starts being a rule drawn in text.
 */
private const val HEADING_RUN = 3
