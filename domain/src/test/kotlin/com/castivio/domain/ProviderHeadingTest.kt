package com.castivio.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line between a provider's heading and a provider's channel.
 *
 * Both lists below are real rows, taken from the catalogue the board was tested against.
 * The second list is the one that matters: a heuristic that catches every heading and one
 * real channel has made a channel unreachable, which is worse than a heading with a
 * number beside it.
 */
class ProviderHeadingTest {

    @Test
    fun `a run of decoration is a heading`() {
        listOf(
            "##### UHD 3840P #####",
            "###### ARABIC ######",
            "#### CABEL TV RAW ####",
            "####### SOCCER PPV #######",
            "===== SPAIN =====",
            "*** PPV EVENTS ***",
            "--- 24/7 ---",
            "▬▬▬ SPORTS ▬▬▬",
            "★★★ VIP ★★★",
            // Decoration on one side only, which is how plenty of them are written.
            "##### NEWS",
            "SPORTS #####",
            // Nothing but the rule.
            "##########",
        ).forEach { assertTrue("`$it` should read as a heading", isProviderHeading(it)) }
    }

    @Test
    fun `a channel a provider actually ships is not a heading`() {
        listOf(
            "4K| SKY SPORTS MAIN EVENT",
            "ES-C| LA 1 RAW",
            "ELEVEN SPORTS 1 UHD 3840P",
            "M+ #Vamos",
            "Channel #1",
            "TNT ULTIMATE 4K+ [LIVE]",
            "US| SPECTRUM NETWORK",
            "beIN SPORTS 1 4K",
            "V SPORT+ UHD 3840P",
            "UK| PRIME RAW 60fps",
            // Two marks, not three: the boundary, from the side that must not move.
            "## NEWS",
            "C++",
            "**",
        ).forEach { assertFalse("`$it` should read as a channel", isProviderHeading(it)) }
    }

    @Test
    fun `a separator between country and name is never decoration`() {
        // `|` is excluded from the marks for exactly this: a run of it is a prefix.
        listOf("|||| FRANCE", "FR|| TF1", "||| BEIN |||")
            .forEach { assertFalse("`$it` should read as a channel", isProviderHeading(it)) }
    }

    @Test
    fun `surrounding space does not change the answer`() {
        assertTrue(isProviderHeading("   ### SPORT ###   "))
        assertFalse(isProviderHeading("   TF1 HD   "))
    }

    @Test
    fun `an empty title is not a heading`() {
        assertFalse(isProviderHeading(""))
        assertFalse(isProviderHeading("    "))
    }
}
