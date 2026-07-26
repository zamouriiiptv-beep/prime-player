package com.castivio.core.navigation

/**
 * The forward shape of the app, declared so the depth budget can be tested.
 *
 * Design invariant 5 is *at most three navigation levels from Home to playback*. That
 * is the invariant most likely to be broken by accident: nobody adds a fourth level on
 * purpose, they add "just a genre screen" between a section and its grid, and the cost
 * only becomes visible months later when playing something takes four keypresses on a
 * remote. Written down like this, the fourth level is a failing test.
 *
 * The budget is measured as the **longest** route the interface offers, not the
 * shortest. A movie can be played straight from its poster in two levels, but the path
 * through its detail page costs three, and it is the three that has to fit — a shortest
 * path measurement would let a four-level route hide behind a two-level one to the same
 * player.
 */
enum class EdgeKind {

    /** Opens a screen on top of this one. Costs a level; Back returns here. */
    Push,

    /**
     * Changes a selection *within* this screen.
     *
     * Choosing a category beside a grid, or a season beside its episodes. Real
     * navigation the user performs, costing nothing against the budget because no
     * screen is pushed. That is not a loophole — it is the reason the two-pane layout
     * exists, and if a category ever became a pushed screen on television, movies would
     * need four levels and the test would say so.
     */
    Pane,

    /**
     * Goes somewhere else entirely, rather than deeper.
     *
     * "Show all in Movies" from a search result lands on Movies exactly as the rail
     * would: [BackPolicy] sends Back from a section to Home, not back to the search, so
     * the stack is replaced rather than extended. Modelling it as a push would count a
     * lateral move as depth.
     */
    Replace,
}

data class Step(val to: Route, val kind: EdgeKind = EdgeKind.Push)

object NavigationGraph {

    private val live = Route.Section(SectionKind.LIVE)
    private val movies = Route.Section(SectionKind.MOVIES)
    private val series = Route.Section(SectionKind.SERIES)
    private val radio = Route.Section(SectionKind.RADIO)

    /**
     * Every forward edge the shell offers, keyed by [Route.key].
     *
     * Routes are represented by one instance per screen: the graph is about shape, so
     * `Detail("movie")` stands for every movie detail page. Arguments matter to
     * navigation memory, not to the depth budget.
     */
    val edges: Map<String, List<Step>> = mapOf(
        Route.Home.key to listOf(
            // The spotlight plays directly — the shortest path in the product, and the
            // reason a live-first Home row is worth its space.
            Step(Route.Player("spotlight")),
            Step(live), Step(movies), Step(series), Step(radio),
            Step(Route.Favorites), Step(Route.ContinueWatching), Step(Route.History),
            Step(Route.Search()), Step(Route.Guide()), Step(Route.Settings()),
        ),

        // Live and radio play from the list. Two levels, which is the whole point of
        // not putting a detail page in front of a channel.
        live.key to listOf(
            Step(Route.Category(SectionKind.LIVE, "any"), EdgeKind.Pane),
            Step(Route.Player("channel")),
            Step(Route.Guide()),
        ),
        radio.key to listOf(
            Step(Route.Category(SectionKind.RADIO, "any"), EdgeKind.Pane),
            Step(Route.Player("station")),
        ),

        // Movies reach the player through a detail page, or straight past it: the
        // poster carries its own Play action, so the third level is optional.
        movies.key to listOf(
            Step(Route.Category(SectionKind.MOVIES, "any"), EdgeKind.Pane),
            Step(Route.Detail("movie")),
            Step(Route.Player("movie")),
        ),

        // Seasons and episodes are panes of the series screen, not screens of their
        // own. That is what keeps series inside the budget.
        series.key to listOf(
            Step(Route.Category(SectionKind.SERIES, "any"), EdgeKind.Pane),
            Step(Route.Series("series")),
        ),
        Route.Series("series").key to listOf(Step(Route.Player("episode"))),
        Route.Detail("movie").key to listOf(Step(Route.Player("movie"))),

        // A category behaves as its section does. On a phone it is a screen of its own,
        // and the level it costs there is the level the section's second pane did not.
        Route.Category(SectionKind.LIVE, "any").key to listOf(Step(Route.Player("channel"))),
        Route.Category(SectionKind.RADIO, "any").key to listOf(Step(Route.Player("station"))),
        Route.Category(SectionKind.MOVIES, "any").key to listOf(
            Step(Route.Detail("movie")),
            Step(Route.Player("movie")),
        ),
        Route.Category(SectionKind.SERIES, "any").key to listOf(Step(Route.Series("series"))),

        // The lists that mix kinds hand off to the destination for the item's kind.
        Route.Favorites.key to listOf(
            Step(Route.Player("favourite")),
            Step(Route.Detail("movie")),
            Step(Route.Series("series")),
        ),
        Route.ContinueWatching.key to listOf(Step(Route.Player("resume"))),
        Route.History.key to listOf(
            Step(Route.Player("again")),
            Step(Route.Detail("movie")),
        ),

        // Search plays or opens a result, and can widen into a whole section — which is
        // a lateral move, not a deeper one.
        Route.Search().key to listOf(
            Step(Route.Player("result")),
            Step(Route.Detail("movie")),
            Step(Route.Series("series")),
            Step(live, EdgeKind.Replace),
            Step(movies, EdgeKind.Replace),
            Step(series, EdgeKind.Replace),
        ),

        // The guide plays what is on now, and catch-up where the provider offers it.
        Route.Guide().key to listOf(Step(Route.Player("programme"))),

        Route.Settings().key to SettingsSection.entries.map { Step(Route.Settings(it)) },
    )

    /** Steps that extend the current stack. A replace starts a new one. */
    private fun stackingEdges(key: String): List<Step> =
        edges[key].orEmpty().filter { it.kind != EdgeKind.Replace }

    /**
     * Fewest levels from Home to every reachable screen.
     *
     * Breadth-first, counting only pushes. Panes are expanded before pushes so a
     * selection is never recorded at the depth of a screen.
     */
    fun depthsFromHome(): Map<String, Int> {
        val depth = mutableMapOf(Route.Home.key to 0)
        val queue = ArrayDeque(listOf(Route.Home.key))

        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val here = depth.getValue(key)
            for (step in stackingEdges(key)) {
                val next = here + if (step.kind == EdgeKind.Push) 1 else 0
                val known = depth[step.to.key]
                if (known == null || next < known) {
                    depth[step.to.key] = next
                    if (step.kind == EdgeKind.Push) queue.addLast(step.to.key)
                    else queue.addFirst(step.to.key)
                }
            }
        }
        return depth
    }

    /**
     * The costliest route to playback the interface offers.
     *
     * @param levels how many screens the user pushes along it.
     * @param path the route itself, as keys, so a failing test can name the offender
     *   instead of only reporting a number.
     */
    data class WorstPath(val levels: Int, val path: List<String>)

    /**
     * Exhaustive over simple paths — every screen appears at most once, so a user
     * walking in circles is not counted as depth. The graph is a dozen nodes, so
     * enumerating it is cheaper than reasoning about it.
     */
    fun worstPathToPlayback(): WorstPath {
        var worst = WorstPath(0, emptyList())

        fun walk(key: String, levels: Int, path: List<String>, seen: Set<String>) {
            if (key.startsWith(PLAYER_PREFIX)) {
                if (levels > worst.levels) worst = WorstPath(levels, path)
                return
            }
            for (step in stackingEdges(key)) {
                val next = step.to.key
                if (next in seen) continue
                walk(
                    key = next,
                    levels = levels + if (step.kind == EdgeKind.Push) 1 else 0,
                    path = path + next,
                    seen = seen + next,
                )
            }
        }

        walk(Route.Home.key, 0, listOf(Route.Home.key), setOf(Route.Home.key))
        return worst
    }

    /**
     * What one named route costs, in levels.
     *
     * Useful where a test wants to pin down a specific journey — "a movie through its
     * detail page is three" — rather than whichever equally deep route the search
     * happens to find first. Rejects a journey the shell does not offer, so a test
     * cannot quietly measure a path that no longer exists.
     */
    fun levelsAlong(vararg routes: Route): Int {
        require(routes.size >= 2) { "a route needs at least two screens" }
        var levels = 0
        for (i in 0 until routes.lastIndex) {
            val from = routes[i].key
            val to = routes[i + 1].key
            val step = edges[from].orEmpty().firstOrNull { it.to.key == to }
                ?: throw IllegalArgumentException("the shell offers no step from $from to $to")
            if (step.kind == EdgeKind.Push) levels++
        }
        return levels
    }

    /** Screens nothing leads to — a dead route is a route to delete. */
    fun unreachable(): List<String> {
        val reached = depthsFromHome().keys
        return edges.keys.filterNot { it in reached }
    }

    /** The budget from `UI_ARCHITECTURE.md` §2 and design invariant 5. */
    const val MAX_LEVELS_TO_PLAYBACK = 3

    /** Every player destination shares this key prefix; see `Route.Player.key`. */
    private const val PLAYER_PREFIX = "player/"
}
