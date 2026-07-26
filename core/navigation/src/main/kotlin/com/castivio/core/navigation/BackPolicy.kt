package com.castivio.core.navigation

/**
 * Where Back goes, decided once for the whole product.
 *
 * This is design invariant 4 — *no duplicate navigation patterns* — in the one place
 * it can actually be held. A feature that decided its own Back behaviour would be a
 * feature the user has to learn separately, and on a remote Back is pressed more than
 * any other key: it has to be predictable before it is clever.
 *
 * The rule the specification asks for is deliberately stronger than "pop the stack":
 * from a section, Back goes to Home. Popping would be *nearly* the same thing, and
 * would differ exactly when the user arrived from somewhere unusual — search results,
 * a deep link, a notification. Then the same keypress from the same screen would go
 * somewhere else depending on history the user has forgotten, which is how a remote
 * starts to feel untrustworthy. A fixed answer is worth losing a little history for.
 */
sealed interface BackTarget {

    /** Go to this destination, clearing anything above it. */
    data class To(val route: Route) : BackTarget

    /**
     * Pop whatever is underneath — the right answer for a screen that is genuinely a
     * detour (a detail page, the player, the guide), where "the previous screen" is
     * what the user means.
     */
    data object Pop : BackTarget

    /**
     * Nowhere left to go. The shell asks before leaving, because on a television an
     * accidental exit costs a cold start and a lost place.
     */
    data object ConfirmExit : BackTarget
}

object BackPolicy {

    /**
     * @param route the screen Back was pressed on.
     * @param canPop whether anything is underneath it. A screen reached by deep link
     *   has nothing to pop to, and [BackTarget.Pop] would strand the user — so the
     *   answer falls back to the route's own parent.
     */
    fun from(route: Route, canPop: Boolean = true): BackTarget = when (route) {

        // Nowhere above Home. Splash and the first-run method chooser are the same
        // case: they are the bottom of the stack on the run where they appear.
        Route.Home -> BackTarget.ConfirmExit
        Route.Splash -> BackTarget.ConfirmExit

        // Picking a method is one level below choosing one, so Back re-opens the
        // chooser rather than leaving the app mid-activation.
        is Route.Activation ->
            if (route.method != null) BackTarget.To(Route.Activation()) else BackTarget.ConfirmExit

        // A category is the same screen with a different selection on TV and tablet,
        // and its own screen on a phone. Either way its parent is its section.
        is Route.Category -> BackTarget.To(Route.Section(route.kind))

        // A settings sub-page returns to the settings root; the root returns Home.
        is Route.Settings ->
            if (route.section != null) BackTarget.To(Route.Settings()) else BackTarget.To(Route.Home)

        // Every other top-level destination is a sibling of Home, not a child of
        // wherever the user happened to come from.
        is Route.Section, Route.Favorites, Route.ContinueWatching, Route.History, is Route.Search ->
            BackTarget.To(Route.Home)

        // Detours. These are the screens where "the previous screen" is what Back
        // means — a detail page reached from search should go back to the search.
        is Route.Detail, is Route.Series, is Route.Player, is Route.Guide ->
            if (canPop) BackTarget.Pop else BackTarget.To(Route.Home)
    }

    /**
     * True when Back should be intercepted rather than handed to the platform.
     *
     * The platform's own Back would close the app from Home without asking, which is
     * the one outcome the confirmation exists to prevent.
     */
    fun handles(route: Route, canPop: Boolean = true): Boolean =
        from(route, canPop) != BackTarget.Pop
}
