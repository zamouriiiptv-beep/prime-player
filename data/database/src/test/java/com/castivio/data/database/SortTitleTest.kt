package com.castivio.data.database

import org.junit.Assert.assertEquals
import org.junit.Test

class SortTitleTest {
    @Test fun `decoration is stripped`() {
        assertEquals("matrix", sortTitle("[4K] The Matrix"))
        assertEquals("zulu dawn", sortTitle("|AR| Zulu Dawn"))
        assertEquals("alpha", sortTitle("••• Alpha"))
        assertEquals("nova sports 1", sortTitle("[HD] Nova Sports 1"))
        assertEquals("قناة الرياضة", sortTitle("قناة الرياضة"))
    }
    @Test fun `articles are dropped only in english`() {
        assertEquals("matrix", sortTitle("The Matrix"))
        assertEquals("bug's life", sortTitle("A Bug's Life"))
        assertEquals("el clasico", sortTitle("El Clasico"))
    }
    @Test fun `sort order matches what a user expects`() {
        val titles = listOf("[4K] The Matrix", "|AR| Zulu Dawn", "••• Alpha")
        assertEquals(listOf("••• Alpha", "[4K] The Matrix", "|AR| Zulu Dawn"), titles.sortedBy { sortTitle(it) })
    }
    @Test fun `all decoration falls back to the raw title`() {
        assertEquals("***", sortTitle("***"))
    }
    @Test fun `search text folds case and includes the show`() {
        assertEquals("pilot breaking bad", searchText("Pilot", "Breaking Bad"))
        assertEquals("новости", searchText("Новости", null))
        assertEquals("nova sports", searchText("Nova Sports", "Nova Sports"))
    }
}
