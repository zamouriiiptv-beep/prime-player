package com.castivio.core.common.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The word that sends every empty card to ask once more. */
class ArtworkKickTest {

    @Test
    fun `each kick is a new number, so a second one is as good a reason as the first`() {
        val before = ArtworkKick.count.value

        ArtworkKick.kick()
        ArtworkKick.kick()

        assertEquals(before + 2, ArtworkKick.count.value)
    }

    @Test
    fun `a load that ends is said once, on the edge`() {
        var said = 0
        val watch = KickWhenLoadEnds { said++ }

        watch.reading(running = true)
        assertEquals("nothing is said while it runs", 0, said)
        watch.reading(running = false)
        assertEquals("it is said when it ends", 1, said)
        watch.reading(running = false)
        assertEquals("and not again while it stays over", 1, said)
    }

    @Test
    fun `a section that was never loading says nothing`() {
        var said = 0
        val watch = KickWhenLoadEnds { said++ }

        watch.reading(running = false)
        watch.reading(running = false)

        assertEquals(0, said)
    }

    @Test
    fun `a second load of the session is as good a reason as the first`() {
        var said = 0
        val watch = KickWhenLoadEnds { said++ }

        watch.reading(true)
        watch.reading(false)
        watch.reading(true)
        watch.reading(true)
        watch.reading(false)

        assertEquals(2, said)
    }

    @Test
    fun `by default it says the word every card listens to`() {
        val before = ArtworkKick.count.value
        val watch = KickWhenLoadEnds()

        watch.reading(true)
        watch.reading(false)

        assertTrue(ArtworkKick.count.value > before)
    }
}
