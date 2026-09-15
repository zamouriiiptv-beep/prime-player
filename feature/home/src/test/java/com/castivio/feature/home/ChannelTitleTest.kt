package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The quality tag, read out of the one place a provider ever writes it.
 *
 * Every title below is the shape a real subscription ships. The cases that matter are
 * not the easy ones — they are the substrings (`HDMI`), the two-word spelling
 * (`Full HD`), the tag that is not last (`TF1 HD +4`), and the channel that simply has
 * no tag, which must stay untagged rather than be captioned `SD`.
 */
class ChannelTitleTest {

    @Test
    fun `reads the tag a provider wrote into the name`() {
        assertEquals(StreamQuality.HD, qualityOf("|FR| TF1 HD"))
        assertEquals(StreamQuality.UHD, qualityOf("|FR| FRANCE 2 UHD"))
        assertEquals(StreamQuality.UHD, qualityOf("beIN SPORTS 1 4K"))
        assertEquals(StreamQuality.FHD, qualityOf("MBC 1 FHD"))
        assertEquals(StreamQuality.SD, qualityOf("|FR| FRANCE 3 GRAND EST SD"))
    }

    @Test
    fun `reads the two-word spelling as one tag`() {
        assertEquals(StreamQuality.FHD, qualityOf("SECRET STORY Full HD"))
        // And takes both tokens with it, rather than leaving a stray "Full" behind.
        assertEquals("SECRET STORY", titleWithoutQuality("SECRET STORY Full HD"))
    }

    @Test
    fun `reads a tag the provider wrapped in punctuation`() {
        assertEquals(StreamQuality.HD, qualityOf("Al Jazeera [HD]"))
        assertEquals(StreamQuality.UHD, qualityOf("Sky Sports (UHD)"))
    }

    /**
     * The defect a substring match would ship: `HD` is inside a great many words, and
     * tagging a third of a catalogue wrongly is worse than tagging none of it.
     */
    @Test
    fun `does not find a tag inside another word`() {
        assertNull(qualityOf("HDMI Test Channel"))
        assertNull(qualityOf("SHD Movies"))
        assertNull(qualityOf("Discovery Channel"))
    }

    /** No marker means no tag. Never a default, and never an assertion nobody made. */
    @Test
    fun `an unlabelled channel gets no tag`() {
        assertNull(qualityOf("|FR| RMC Story"))
        assertEquals("|FR| RMC Story", titleWithoutQuality("|FR| RMC Story"))
    }

    /** The awkward one: the tag is neither first nor last, and `+4` has to survive. */
    @Test
    fun `removes a tag from the middle and keeps what follows it`() {
        assertEquals(StreamQuality.HD, qualityOf("|FR| TF1 HD +4"))
        assertEquals("|FR| TF1 +4", titleWithoutQuality("|FR| TF1 HD +4"))
    }

    /** A row with no name at all is worse than a row that repeats its tag. */
    @Test
    fun `a name that is only a tag keeps it`() {
        assertEquals("HD", titleWithoutQuality("HD"))
    }

    @Test
    fun `an empty name is returned unchanged`() {
        assertNull(qualityOf(""))
        assertEquals("", titleWithoutQuality(""))
    }
}
