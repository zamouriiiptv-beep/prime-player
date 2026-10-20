package com.castivio.feature.home

import com.castivio.domain.Recorded
import org.junit.Assert.assertEquals
import org.junit.Test

/** The subscription card's look, one test per answer the provider can have given. */
class ProviderToneTest {

    /** Never asked is a blank, not a verdict: neither the active green nor the refused red. */
    @Test
    fun `a provider never asked is drawn as unchecked`() {
        assertEquals(ProviderTone.Unchecked, providerTone(null))
    }

    @Test
    fun `a provider that answered yes is drawn as usable, with or without a date`() {
        assertEquals(ProviderTone.Usable, providerTone(answer(usable = true, expiresAtMs = 1_800_000_000_000L)))
        assertEquals(ProviderTone.Usable, providerTone(answer(usable = true, expiresAtMs = null)))
    }

    @Test
    fun `a provider that answered no is drawn as refused`() {
        assertEquals(ProviderTone.Refused, providerTone(answer(usable = false, expiresAtMs = null)))
    }

    private fun answer(usable: Boolean, expiresAtMs: Long?) =
        Recorded(usable = usable, expiresAtMs = expiresAtMs, label = null, atMs = 1L)
}
