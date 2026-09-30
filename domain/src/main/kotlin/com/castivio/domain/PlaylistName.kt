package com.castivio.domain

/**
 * The name a subscription gets when the user does not give it one.
 *
 * ## Why there is a name at all
 *
 * "Playlist name" is optional on both add forms, and for a while an unnamed
 * subscription stayed unnamed in the database: the repository stored the blank rather
 * than filling it with the host, and every screen that had to show something worked out
 * a placeholder of its own. That kept the data honest and made the *product* dishonest —
 * a list of three blanks is a list nobody can choose from, and a placeholder computed
 * from a row's position renumbers itself the moment a row above it is deleted.
 *
 * So the name is decided once, when the subscription is registered, and stored with it.
 * From that moment it is an ordinary name: it shows everywhere a typed name shows, and
 * the user can change it from the subscriptions screen exactly as they would change one
 * they typed themselves.
 *
 * ## Why it is not a translated string
 *
 * Because it is data, not copy. A stored name cannot be a string resource — it would
 * have to be rewritten in every row each time the user changed language, and two devices
 * restoring the same backup would disagree about what a subscription is called. So
 * [PLAYLIST] is a literal and the name reads the same in every language, which is the
 * correct behaviour for something the user is free to rename.
 *
 * ## The number
 *
 * The smallest positive integer that no existing subscription is already using, so a
 * device that has held Playlist 1, 2 and 3 and then lost Playlist 2 calls the next one
 * Playlist 2 rather than Playlist 4. Uniqueness is the requirement; tidiness is what
 * the smallest free number buys on top of it.
 *
 * Names the user typed are considered too — somebody who calls their subscription
 * "Playlist 7" by hand has taken that number, and the generator must not hand it out a
 * second time.
 *
 * @param taken every name already in use, typed or generated. Order is irrelevant.
 */
fun nextPlaylistName(taken: Collection<String>): String {
    val used = taken.mapNotNull(::playlistNumberOf).toSet()
    var n = 1
    while (n in used) n++
    return "$PLAYLIST $n"
}

/**
 * The number in a generated name, or null when this is not one.
 *
 * Case-insensitive and tolerant of surrounding space, because the names it is asked
 * about include whatever the user has typed. Deliberately strict about everything else:
 * "Playlist 2b" and "My Playlist 2" are names somebody chose, not numbers this generator
 * handed out, and treating them as taken would make it skip a number for no reason the
 * user could see.
 *
 * Leading zeros are rejected for the same reason: "Playlist 007" is a name, and reading
 * it as 7 would let one subscription silently reserve another's number.
 */
private fun playlistNumberOf(name: String): Int? {
    val trimmed = name.trim()
    if (!trimmed.startsWith(PLAYLIST, ignoreCase = true)) return null
    val digits = trimmed.drop(PLAYLIST.length).trimStart()
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
    if (digits.length > 1 && digits[0] == '0') return null
    // A number too large to be one of ours is not one of ours. Guards the parse rather
    // than the product: nobody has 2^31 subscriptions, but `toInt()` throws on the text
    // that says they do.
    return digits.toIntOrNull()
}

/** The stem every generated name is built on. See the note above on why it is a literal. */
private const val PLAYLIST = "Playlist"
