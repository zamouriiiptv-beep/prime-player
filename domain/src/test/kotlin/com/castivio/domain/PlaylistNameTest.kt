package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name an unnamed subscription is given, which is a rule and therefore a JVM test.
 *
 * What matters about it is not that it produces "Playlist 1" on an empty device — it is
 * that the number it produces is one no other subscription is using, after any history
 * of adding, deleting and renaming the user can have put the device through.
 */
class PlaylistNameTest {

    @Test
    fun `the first subscription is Playlist 1`() {
        assertEquals("Playlist 1", nextPlaylistName(emptyList()))
    }

    @Test
    fun `numbers continue past the ones already generated`() {
        assertEquals("Playlist 2", nextPlaylistName(listOf("Playlist 1")))
        assertEquals("Playlist 3", nextPlaylistName(listOf("Playlist 1", "Playlist 2")))
    }

    /** The whole reason it is the *smallest* free number rather than one past the last. */
    @Test
    fun `a deleted number is handed out again`() {
        assertEquals("Playlist 2", nextPlaylistName(listOf("Playlist 1", "Playlist 3")))
    }

    @Test
    fun `order does not matter`() {
        assertEquals("Playlist 4", nextPlaylistName(listOf("Playlist 3", "Playlist 1", "Playlist 2")))
    }

    /** Names the user typed hold their number too, or the generator would duplicate one. */
    @Test
    fun `a number the user typed by hand is taken`() {
        assertEquals("Playlist 2", nextPlaylistName(listOf("Playlist 1", "playlist 3", "Playlist 4")))
        // A high number taken by hand leaves every number below it free.
        assertEquals("Playlist 1", nextPlaylistName(listOf("Playlist 7")))
    }

    @Test
    fun `typed names that only look like one are left alone`() {
        // None of these reserves a number, so the next name is still the first free one.
        assertEquals(
            "Playlist 1",
            nextPlaylistName(listOf("My Playlist 2", "Playlist 2b", "Playlist", "Playlist 007", "")),
        )
    }

    @Test
    fun `spacing and case are tolerated`() {
        assertEquals("Playlist 2", nextPlaylistName(listOf("  playlist   1  ")))
        assertEquals("Playlist 2", nextPlaylistName(listOf("PLAYLIST 1")))
    }

    /** Ordinary names take no number at all. */
    @Test
    fun `named subscriptions do not reserve numbers`() {
        assertEquals("Playlist 1", nextPlaylistName(listOf("server 1", "قائمة الأفلام", "Arabic")))
    }

    /** A number no device will reach must not throw on the way past it. */
    @Test
    fun `a number too large to be ours is not ours`() {
        assertEquals("Playlist 1", nextPlaylistName(listOf("Playlist 99999999999999")))
    }
}
