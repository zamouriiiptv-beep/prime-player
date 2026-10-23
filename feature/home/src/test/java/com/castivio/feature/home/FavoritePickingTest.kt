package com.castivio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Favourite button does with the channels that were picked, and what a press on a row does to
 * the picks -- the two pure rules the board's mode is made of.
 */
class FavoritePickingTest {

    private val favorites = setOf("a", "b", "c")

    @Test
    fun `picks that are not all favourites are added`() {
        assertFalse(picksRemove(setOf("a", "x"), favorites))
        assertFalse(picksRemove(setOf("x"), favorites))
    }

    @Test
    fun `picks that all are favourites are removed`() {
        assertTrue(picksRemove(setOf("a", "b"), favorites))
        assertTrue(picksRemove(setOf("c"), favorites))
    }

    @Test
    fun `no picks is neither an add nor a removal`() {
        assertFalse(picksRemove(emptySet(), favorites))
        assertFalse(picksRemove(emptySet(), emptySet()))
    }

    @Test
    fun `with no favourites every pick is an add`() {
        assertFalse(picksRemove(setOf("a"), emptySet()))
    }

    @Test
    fun `a press picks a channel, and the next press puts it back`() {
        val once = emptySet<String>().toggled("a")
        assertEquals(setOf("a"), once)
        assertEquals(emptySet<String>(), once.toggled("a"))
    }

    @Test
    fun `picks are kept in any order and without repeats`() {
        val picked = emptySet<String>().toggled("b").toggled("a").toggled("b").toggled("c")

        assertEquals(setOf("a", "c"), picked)
    }

    @Test
    fun `the board says what a row is while it picks, and nothing outside the mode`() {
        val picking = FavoritesUi(ids = setOf("a"), picked = setOf("a", "b"), toggle = {}, press = {})
        assertEquals(RowPick(picked = true, favorite = true), picking.markOf("a"))
        assertEquals(RowPick(picked = true, favorite = false), picking.markOf("b"))
        assertEquals(RowPick(picked = false, favorite = false), picking.markOf("z"))

        assertEquals(null, FavoritesUi.None.markOf("a"))
        assertFalse(FavoritesUi.None.picking)
    }

    @Test
    fun `the button's word follows the picks`() {
        assertTrue(FavoritesUi(ids = setOf("a", "b"), picked = setOf("a", "b"), toggle = {}, press = {}).removes)
        assertFalse(FavoritesUi(ids = setOf("a"), picked = setOf("a", "b"), toggle = {}, press = {}).removes)
        assertFalse(FavoritesUi(ids = setOf("a"), picked = null, toggle = {}, press = {}).removes)
        assertFalse(FavoritesUi(ids = setOf("a"), picked = emptySet(), toggle = {}, press = {}).removes)
    }
}
