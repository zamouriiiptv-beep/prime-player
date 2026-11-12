package com.castivio.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a channel's logo slot draws its tile -- see [ArtworkPolicy] and [ArtworkSlot].
 *
 * The failure this file exists for: the tile was drawn when a logo's request *failed*, and for an address that does not answer that is the
 * client's twelve or twenty seconds, so a channel whose logo could not be fetched showed an empty slot for as long as that took, and again
 * each time its row came back. The rule is held here, without a screen, in the four states a slot is ever in.
 */
class ArtworkPolicyTest {

    // -------------------------------------------------------------- the numbers

    @Test
    fun `the tile comes before the request is given up, and the failure is remembered longer than either`() {
        assertTrue(
            "a tile that came after the request ended would be the wait it was meant to cut",
            ArtworkPolicy.TILE_AFTER_MS < ArtworkPolicy.CALL_TIMEOUT_MS,
        )
        assertTrue(
            "a failure forgotten before the request that made it could end would be asked again at once",
            ArtworkPolicy.CALL_TIMEOUT_MS < ArtworkPolicy.FAILURE_MEMORY_MS,
        )
    }

    @Test
    fun `a logo is given up on sooner than the client gives up on anything`() {
        // The client's own limits are twelve seconds to connect and twenty between packets (`HttpClientProvider`): a logo's are less.
        assertTrue(ArtworkPolicy.CALL_TIMEOUT_MS < 12_000L)
    }

    // -------------------------------------------------------------- the address

    @Test
    fun `a blank address is no address, and any other is kept as it came`() {
        assertNull(ArtworkSlot.address(null))
        assertNull(ArtworkSlot.address(""))
        assertNull(ArtworkSlot.address("   "))
        assertEquals("http://logos.example/a b.png", ArtworkSlot.address("http://logos.example/a b.png"))
    }

    // -------------------------------------------------------------- the four states of a slot

    @Test
    fun `a channel with no address is a tile at once, whatever else is said`() {
        for (failed in listOf(false, true)) for (arrived in listOf(false, true)) for (waitedOut in listOf(false, true)) {
            assertTrue(
                "failed=$failed arrived=$arrived waitedOut=$waitedOut",
                ArtworkSlot.showsTile(address = null, failed = failed, arrived = arrived, waitedOut = waitedOut),
            )
        }
    }

    @Test
    fun `an address that failed is a tile at once, without waiting for anything`() {
        assertTrue(ArtworkSlot.showsTile(ADDRESS, failed = true, arrived = false, waitedOut = false))
        assertTrue(ArtworkSlot.showsTile(ADDRESS, failed = true, arrived = false, waitedOut = true))
    }

    @Test
    fun `a picture on its way leaves the slot empty until the wait is out`() {
        assertFalse(
            "a tile that appeared and was at once replaced would flicker down a list",
            ArtworkSlot.showsTile(ADDRESS, failed = false, arrived = false, waitedOut = false),
        )
    }

    @Test
    fun `a picture still on its way when the wait is out is a tile, and the picture replaces it when it comes`() {
        assertTrue(
            "this is the wait the rule exists to cut, from the request's failure to the wait",
            ArtworkSlot.showsTile(ADDRESS, failed = false, arrived = false, waitedOut = true),
        )
        assertFalse(
            "the tile is not left under a logo that has a transparent ground",
            ArtworkSlot.showsTile(ADDRESS, failed = false, arrived = true, waitedOut = true),
        )
    }

    @Test
    fun `a picture that came in time never had a tile`() {
        assertFalse(ArtworkSlot.showsTile(ADDRESS, failed = false, arrived = true, waitedOut = false))
    }

    private companion object {
        const val ADDRESS = "http://logos.example/channel.png"
    }
}
