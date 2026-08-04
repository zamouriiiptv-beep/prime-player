package com.castivio.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackPolicyTest {

    @Test
    fun `back from home asks before leaving`() {
        assertEquals(BackTarget.ConfirmExit, BackPolicy.from(Route.Home))
    }

    @Test
    fun `back from a section goes to home`() {
        for (kind in SectionKind.entries) {
            assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.Section(kind)))
        }
    }

    @Test
    fun `back from a category goes to its own section, not to home`() {
        assertEquals(
            BackTarget.To(Route.Section(SectionKind.MOVIES)),
            BackPolicy.from(Route.Category(SectionKind.MOVIES, "action")),
        )
    }

    /**
     * The reason the policy is fixed rather than a stack pop: the same keypress on the
     * same screen must go to the same place, whether the user arrived from the rail,
     * from search, or from a deep link.
     */
    @Test
    fun `a section answers the same way however it was reached`() {
        val fromRail = BackPolicy.from(Route.Section(SectionKind.MOVIES), canPop = true)
        val fromDeepLink = BackPolicy.from(Route.Section(SectionKind.MOVIES), canPop = false)

        assertEquals(fromRail, fromDeepLink)
        assertEquals(BackTarget.To(Route.Home), fromRail)
    }

    @Test
    fun `every other top-level destination goes to home`() {
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.Favorites))
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.ContinueWatching))
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.History))
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.Search()))
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.Settings()))
    }

    // ------------------------------------------------------------------ detours

    @Test
    fun `a detail page returns to wherever it was opened from`() {
        assertEquals(BackTarget.Pop, BackPolicy.from(Route.Detail("m1")))
        assertEquals(BackTarget.Pop, BackPolicy.from(Route.Series("s1")))
        assertEquals(BackTarget.Pop, BackPolicy.from(Route.Player("m1")))
        assertEquals(BackTarget.Pop, BackPolicy.from(Route.Guide()))
    }

    /**
     * A detour opened by deep link has nothing underneath it, and popping would strand
     * the user on a blank stack.
     */
    @Test
    fun `a detour with nothing underneath falls back to home`() {
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.Detail("m1"), canPop = false))
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.Player("m1"), canPop = false))
    }

    // ----------------------------------------------------------------- settings

    @Test
    fun `a settings page returns to the settings root, and the root to home`() {
        assertEquals(
            BackTarget.To(Route.Settings()),
            BackPolicy.from(Route.Settings(SettingsSection.PLAYBACK)),
        )
        assertEquals(BackTarget.To(Route.Home), BackPolicy.from(Route.Settings()))
    }

    // --------------------------------------------------------------- activation

    @Test
    fun `choosing an activation method can be undone without leaving the app`() {
        assertEquals(
            BackTarget.To(Route.Activation()),
            BackPolicy.from(Route.Activation(ActivationMethod.XTREAM)),
        )
    }

    /**
     * The licence gate is answered before anything else exists, so Back must leave the
     * app rather than slip past a gate that has not been satisfied.
     */
    @Test
    fun `back from the licence gate leaves the app rather than bypassing it`() {
        for (reason in LicenceDenial.entries) {
            assertEquals(
                "back from licence/${reason.name}",
                BackTarget.ConfirmExit,
                BackPolicy.from(Route.Licence(reason)),
            )
        }
        assertTrue(BackPolicy.handles(Route.Licence(LicenceDenial.TRIAL_EXPIRED)))
    }

    @Test
    fun `every licence denial is the same destination`() {
        val keys = LicenceDenial.entries.map { Route.Licence(it).key }.toSet()

        assertEquals("the reason is wording, not routing", 1, keys.size)
    }

    @Test
    fun `the activation chooser is the bottom of the stack on first run`() {
        assertEquals(BackTarget.ConfirmExit, BackPolicy.from(Route.Activation()))
        assertEquals(BackTarget.ConfirmExit, BackPolicy.from(Route.Splash))
    }

    // ---------------------------------------------------------------- interception

    @Test
    fun `the shell intercepts back everywhere except an ordinary pop`() {
        assertTrue(BackPolicy.handles(Route.Home))
        assertTrue(BackPolicy.handles(Route.Section(SectionKind.LIVE)))
        assertTrue(BackPolicy.handles(Route.Settings(SettingsSection.ABOUT)))
        assertFalse(BackPolicy.handles(Route.Detail("m1")))
        assertTrue(BackPolicy.handles(Route.Detail("m1"), canPop = false))
    }

    /** Every destination answers. A screen with no rule is a screen that traps focus. */
    @Test
    fun `every top-level destination has an answer`() {
        for (route in TOP_LEVEL_ROUTES) {
            val target = BackPolicy.from(route)
            if (route == Route.Home) {
                assertEquals(BackTarget.ConfirmExit, target)
            } else {
                assertEquals("back from ${route.key}", BackTarget.To(Route.Home), target)
            }
        }
    }

    /**
     * The exit question is asked at exactly one rung, and Back never does two
     * things at once.
     *
     * Both halves matter. "Ask at the root" is the requirement; "ask *only* at
     * the root" is the part that decays, because every new overlay is a chance
     * for somebody to forget that a confirmation is not a navigation.
     */
    @Test
    fun `only the root with nothing over it asks before leaving`() {
        for (dialog in listOf(true, false)) {
            for (overlay in listOf(true, false)) {
                for (root in listOf(true, false)) {
                    val asks = BackPolicy.fromShell(dialog, overlay, root) == ShellBack.ConfirmExit
                    assertEquals(
                        "dialog=$dialog overlay=$overlay root=$root should " +
                            (if (!dialog && !overlay && root) "ask" else "not ask"),
                        !dialog && !overlay && root,
                        asks,
                    )
                }
            }
        }
    }

    /**
     * An open confirmation closes and takes nothing with it.
     *
     * The requirement is that Back on an open dialog closes *only* the dialog —
     * so it must win over every other rung, including the one it was asking
     * about.
     */
    @Test
    fun `back on an open confirmation closes only the confirmation`() {
        for (overlay in listOf(true, false)) {
            for (root in listOf(true, false)) {
                assertEquals(
                    "an open dialog did not win over overlay=$overlay root=$root",
                    ShellBack.CloseDialog,
                    BackPolicy.fromShell(dialogOpen = true, overlayOpen = overlay, atRoot = root),
                )
            }
        }
    }

    /** Navigating inside the app is never confirmed. */
    @Test
    fun `moving back through the app does not ask`() {
        assertEquals(
            ShellBack.CloseOverlay,
            BackPolicy.fromShell(dialogOpen = false, overlayOpen = true, atRoot = true),
        )
        assertEquals(
            ShellBack.GoToRoot,
            BackPolicy.fromShell(dialogOpen = false, overlayOpen = false, atRoot = false),
        )
    }
}
