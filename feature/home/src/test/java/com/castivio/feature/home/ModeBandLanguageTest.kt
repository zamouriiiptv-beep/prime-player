package com.castivio.feature.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which languages the mode band follows the reader's direction in: Arabic, and no other. */
class ModeBandLanguageTest {

    @Test
    fun `Arabic follows the reader`() {
        assertTrue(modeBandFollowsReader("ar"))
        assertTrue("whatever the case it is given in", modeBandFollowsReader("AR"))
    }

    @Test
    fun `no other language does, the right-to-left ones included`() {
        listOf("en", "fr", "de", "tr", "he", "fa", "ur", "ps", "ku", "").forEach {
            assertFalse("'$it'", modeBandFollowsReader(it))
        }
    }
}
