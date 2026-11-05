package com.castivio.feature.home

/**
 * Whether the band over the board, while it arranges or chooses what to hide, follows the reader's direction.
 *
 * In Arabic it does: the word that names the mode is at the right end of the band, its sentence runs from it toward the left,
 * and the two buttons are at the left end -- the end of the line. Drawn in `design/mockups/channels-mode-bar-fix-ar.html`.
 *
 * In every other language it is as it was: the word at the left, the buttons at the right. That is the owner's decision, and the
 * language is named here rather than "every right-to-left one" because it is the one he has seen and chosen for; another can be
 * added to this one line when he has seen it.
 *
 * Pure, so it is tested without a screen. [language] is the language code, `ar`.
 */
internal fun modeBandFollowsReader(language: String): Boolean = language.equals(FOLLOWS_READER, ignoreCase = true)

private const val FOLLOWS_READER = "ar"
