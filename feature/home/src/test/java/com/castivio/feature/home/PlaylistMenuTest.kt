package com.castivio.feature.home

import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist menu's data: which playlists there are, what each is called, and which
 * one the tick belongs to.
 *
 * This is the half of the feature worth testing without an emulator, and it is
 * deliberately the half that decides what a user is told. The other half — that a
 * `DropdownMenu` draws where it is anchored, that a press dismisses it — is Compose's
 * behaviour, not ours, and a test of it would assert that the framework is the
 * framework. `:feature:home` has no Compose test harness and this change does not add
 * one: adding Robolectric and the Compose test runtime to a module to assert that a
 * tick is drawn is a large amount of machinery for a small amount of proof.
 *
 * What that leaves unproven is stated rather than hidden: the ✓ rendering and the
 * "+ Add playlist" press are verified by reading the composable, not by a test here.
 * Both are one expression each — `playlist.id == state.activePlaylistId` and the
 * existing `onAddSource` callback — and both are covered indirectly, the first by
 * [`the active playlist is the one the repository says is active`] below and the
 * second by the fact that no new route exists to get wrong.
 */
class PlaylistMenuTest {

    private fun source(id: String, label: String) = ProviderSource(
        id = id,
        kind = SourceKind.XTREAM,
        label = label,
        url = "http://example.test",
    )

    // ------------------------------------------------------------- the list

    @Test
    fun `every saved playlist reaches the menu, in the order they were added`() {
        val playlists = playlistsOf(
            listOf(source("a", "zamouri1"), source("b", "zamouri"), source("c", "work")),
        )

        assertEquals(3, playlists.size)
        assertEquals(listOf("a", "b", "c"), playlists.map { it.id })
        assertEquals(listOf("zamouri1", "zamouri", "work"), playlists.map { it.name })
    }

    @Test
    fun `no saved playlists is an empty menu, not a menu of one blank row`() {
        assertTrue(playlistsOf(emptyList()).isEmpty())
    }

    /**
     * One playlist is not a choice, and the rail control must not imply one. The state
     * says so rather than the screen counting the list in two places.
     */
    @Test
    fun `a single playlist is not a choice`() {
        val one = HomeState(playlists = playlistsOf(listOf(source("a", "only"))))
        val two = HomeState(playlists = playlistsOf(listOf(source("a", "x"), source("b", "y"))))

        assertTrue(!one.hasPlaylistChoice)
        assertTrue(two.hasPlaylistChoice)
    }

    // ------------------------------------------------------------- the names

    /**
     * **The name the user typed, untouched.** Not trimmed, not title-cased, not
     * suffixed with the host: a user who called their subscription `zamouri1` is
     * looking for `zamouri1` in this menu, and anything else is the application
     * deciding it knows better.
     */
    @Test
    fun `a playlist the user named carries that name exactly`() {
        val playlists = playlistsOf(listOf(source("a", "zamouri1")))

        assertEquals("zamouri1", playlists.single().name)
    }

    /**
     * **An unnamed playlist arrives unnamed, and numbered.**
     *
     * The empty name is the point. `RoomSourceRepository` stores no name rather than
     * substituting the host, so this layer can tell "they called it nothing" from
     * "they called it `mytv.example.com`" — and the screen supplies `Playlist 1`,
     * which is a translated string and therefore cannot be built here.
     *
     * The position is what the screen numbers with, so it is asserted here where the
     * ordering rule lives.
     */
    @Test
    fun `an unnamed playlist carries no name and its place in the order`() {
        val playlists = playlistsOf(
            listOf(source("a", ""), source("b", "zamouri"), source("c", "")),
        )

        assertEquals(listOf("", "zamouri", ""), playlists.map { it.name })
        assertEquals(listOf(1, 2, 3), playlists.map { it.position })
    }

    /**
     * The number is one-based because a user reads it. An index would put `Playlist 0`
     * on the first row, which reads as a fault in the application rather than a name.
     */
    @Test
    fun `numbering starts at one`() {
        assertEquals(1, playlistsOf(listOf(source("a", ""))).single().position)
    }

    /**
     * **Adding does not renumber.** The order is `created_at`, so a fourth playlist is
     * Playlist 4 and the three before it keep the numbers they already showed. A user
     * told "the second one" yesterday finds the second one today.
     */
    @Test
    fun `adding a playlist leaves the existing numbers where they were`() {
        val before = playlistsOf(listOf(source("a", ""), source("b", ""), source("c", "")))
        val after = playlistsOf(
            listOf(source("a", ""), source("b", ""), source("c", ""), source("d", "")),
        )

        assertEquals(before, after.take(before.size))
        assertEquals(4, after.last().position)
    }

    // ------------------------------------------------------------- the tick

    /**
     * The active playlist is identified by the repository's id and nothing else. There
     * is no `isActive` on the item on purpose: a second copy of that fact is a second
     * thing that can disagree with the store, and the store is the only writer.
     */
    @Test
    fun `the active playlist is the one the repository says is active`() {
        val state = HomeState(
            playlists = playlistsOf(listOf(source("a", "x"), source("b", "y"))),
            activePlaylistId = "b",
        )

        assertEquals(listOf(false, true), state.playlists.map { it.id == state.activePlaylistId })
    }

    /**
     * Before the first emission there is no active id, and every row must then be
     * untickled rather than the first one being assumed. A tick on the wrong playlist
     * is worse than no tick: it is an answer, and it is wrong.
     */
    @Test
    fun `no active id ticks nothing`() {
        val state = HomeState(playlists = playlistsOf(listOf(source("a", "x"), source("b", "y"))))

        assertTrue(state.playlists.none { it.id == state.activePlaylistId })
    }
}
