package com.castivio.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design invariant 5, as a test rather than as a hope.
 *
 * Nobody adds a fourth navigation level on purpose. They add "just a genre screen"
 * between a section and its grid, and the cost only shows up months later when playing
 * something takes four keypresses on a remote. This is where that fails the build.
 */
class NavigationGraphTest {

    private val depths = NavigationGraph.depthsFromHome()

    @Test
    fun `no route to playback exceeds the depth budget`() {
        val worst = NavigationGraph.worstPathToPlayback()

        assertTrue(
            "the costliest route to playback is ${worst.levels} levels, budget is " +
                "${NavigationGraph.MAX_LEVELS_TO_PLAYBACK}: ${worst.path.joinToString(" -> ")}",
            worst.levels <= NavigationGraph.MAX_LEVELS_TO_PLAYBACK,
        )
    }

    /**
     * The budget is measured on the longest route, not the shortest. A shortest-path
     * measurement would let a four-level route hide behind a two-level one to the same
     * player — which is exactly the bug these tests exist to catch, since a movie can
     * be played in two levels and read about first in three.
     */
    @Test
    fun `something actually costs the full budget, so the number is not vacuous`() {
        assertEquals(3, NavigationGraph.worstPathToPlayback().levels)
    }

    /** The journeys the specification names, measured one at a time. */
    @Test
    fun `each named journey costs what the specification says`() {
        val live = Route.Section(SectionKind.LIVE)
        val movies = Route.Section(SectionKind.MOVIES)
        val series = Route.Section(SectionKind.SERIES)

        // Live: two, because there is no detail page in front of a channel.
        assertEquals(2, NavigationGraph.levelsAlong(Route.Home, live, Route.Player("channel")))

        // Movies: two straight from the poster, three through the detail page.
        assertEquals(2, NavigationGraph.levelsAlong(Route.Home, movies, Route.Player("movie")))
        assertEquals(
            3,
            NavigationGraph.levelsAlong(
                Route.Home, movies, Route.Detail("movie"), Route.Player("movie"),
            ),
        )

        // And three even when a category is chosen on the way, because that is a pane.
        assertEquals(
            3,
            NavigationGraph.levelsAlong(
                Route.Home,
                movies,
                Route.Category(SectionKind.MOVIES, "any"),
                Route.Detail("movie"),
                Route.Player("movie"),
            ),
        )

        // Series: three, with seasons and episodes inside the series screen.
        assertEquals(
            3,
            NavigationGraph.levelsAlong(
                Route.Home, series, Route.Series("series"), Route.Player("episode"),
            ),
        )

        // The spotlight: one.
        assertEquals(1, NavigationGraph.levelsAlong(Route.Home, Route.Player("spotlight")))
    }

    @Test
    fun `a journey the shell does not offer cannot be measured`() {
        val refused = runCatching {
            NavigationGraph.levelsAlong(Route.Home, Route.Detail("movie"))
        }
        assertEquals(
            IllegalArgumentException::class,
            refused.exceptionOrNull()!!::class,
        )
    }

    @Test
    fun `the spotlight plays in one level`() {
        assertEquals(1, depths[Route.Player("spotlight").key])
    }

    @Test
    fun `live plays in two levels`() {
        assertEquals(1, depths[Route.Section(SectionKind.LIVE).key])
        assertEquals(2, depths[Route.Player("channel").key])
    }

    /**
     * The poster carries its own Play action, so the detail page is a choice rather
     * than a toll. Two levels to play, three if you want to read about it first.
     */
    @Test
    fun `a movie can be played without opening its detail page`() {
        assertEquals(1, depths[Route.Section(SectionKind.MOVIES).key])
        assertEquals(2, depths[Route.Detail("movie").key])
        assertEquals(2, depths[Route.Player("movie").key])
    }

    @Test
    fun `an episode plays in three levels`() {
        assertEquals(2, depths[Route.Series("series").key])
        assertEquals(3, depths[Route.Player("episode").key])
    }

    /**
     * Widening a search into a section is a lateral move: Back from a section goes to
     * Home, not back to the search, so the stack is replaced rather than extended.
     * Counting it as a push would make a movie four levels deep from a search.
     */
    @Test
    fun `widening a search into a section does not deepen the stack`() {
        assertEquals(1, depths[Route.Section(SectionKind.MOVIES).key])
        assertTrue(NavigationGraph.worstPathToPlayback().levels <= 3)
    }

    /**
     * A category is a pane on television and tablet, so selecting one is navigation the
     * user performs that costs nothing against the budget. If it ever became a pushed
     * screen on those form factors, movies would need four levels and the test above
     * would say so.
     */
    @Test
    fun `choosing a category costs no level`() {
        for (kind in SectionKind.entries) {
            assertEquals(
                "category of ${kind.name}",
                depths[Route.Section(kind).key],
                depths[Route.Category(kind, "any").key],
            )
        }
    }

    @Test
    fun `every top-level destination is one level from home`() {
        for (route in TOP_LEVEL_ROUTES) {
            val expected = if (route == Route.Home) 0 else 1
            assertEquals("depth of ${route.key}", expected, depths[route.key])
        }
    }

    @Test
    fun `continue watching resumes in two levels`() {
        assertEquals(2, depths[Route.Player("resume").key])
    }

    @Test
    fun `search reaches a result in two levels`() {
        assertEquals(1, depths[Route.Search().key])
        assertEquals(2, depths[Route.Player("result").key])
    }

    @Test
    fun `the guide plays what is on in two levels`() {
        assertEquals(2, depths[Route.Player("programme").key])
    }

    @Test
    fun `every settings page is two levels deep`() {
        for (section in SettingsSection.entries) {
            assertEquals("settings ${section.name}", 2, depths[Route.Settings(section).key])
        }
    }

    /** A route nothing leads to is a route to delete, not a route to leave lying around. */
    @Test
    fun `no declared screen is unreachable`() {
        assertEquals(emptyList<String>(), NavigationGraph.unreachable())
    }

}
