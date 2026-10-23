package com.castivio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Bouquets in the viewer's order: the ones they placed first, then the provider's own. */
class ArrangedBouquetsTest {

    private fun group(name: String) = MediaGroup(id = "src:$name", name = name, kind = MediaKind.LIVE, itemCount = 3)

    private val provider = listOf("Sports", "News", "Kids", "Docs").map(::group)

    private fun names(groups: List<MediaGroup>) = groups.map { it.name }

    @Test
    fun `with nothing placed the provider's order is the order`() {
        assertSame(provider, provider.arrangedBy(emptyMap()))
    }

    @Test
    fun `placed bouquets follow the places they were given`() {
        val order = mapOf("src:Kids" to 0L, "src:Sports" to 1L, "src:Docs" to 2L, "src:News" to 3L)

        assertEquals(listOf("Kids", "Sports", "Docs", "News"), names(provider.arrangedBy(order)))
    }

    @Test
    fun `places are compared, not counted`() {
        val order = mapOf("src:Docs" to 90L, "src:Sports" to 7L, "src:News" to 400L, "src:Kids" to 8L)

        assertEquals(listOf("Sports", "Kids", "Docs", "News"), names(provider.arrangedBy(order)))
    }

    @Test
    fun `a bouquet nothing placed follows the placed ones, in the provider's order`() {
        // The provider added Docs and Kids after the viewer arranged the other two.
        val order = mapOf("src:News" to 0L, "src:Sports" to 1L)

        assertEquals(listOf("News", "Sports", "Kids", "Docs"), names(provider.arrangedBy(order)))
    }

    @Test
    fun `a place for a bouquet that is not listed any more is ignored`() {
        val order = mapOf("src:Gone" to 0L, "src:Docs" to 1L, "src:Sports" to 2L)

        assertEquals(listOf("Docs", "Sports", "News", "Kids"), names(provider.arrangedBy(order)))
    }

    @Test
    fun `two bouquets with the same place keep the provider's order between them`() {
        val order = mapOf("src:Kids" to 5L, "src:News" to 5L)

        assertEquals(listOf("News", "Kids", "Sports", "Docs"), names(provider.arrangedBy(order)))
    }

    @Test
    fun `arranging is only an order, never a change to the bouquets`() {
        val arranged = provider.arrangedBy(mapOf("src:Docs" to 0L))

        assertEquals(provider.toSet(), arranged.toSet())
        assertEquals(provider.size, arranged.size)
    }
}
