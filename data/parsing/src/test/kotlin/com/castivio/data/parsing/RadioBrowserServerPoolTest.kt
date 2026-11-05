package com.castivio.data.parsing

import com.castivio.domain.WorldListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Which server to ask: the names the directory owns, the one kept, the ones left alone for a while. */
class RadioBrowserServerPoolTest {

    private var now = 0L
    private val a = "a1.api.radio-browser.info"
    private val b = "b2.api.radio-browser.info"
    private val c = "c3.api.radio-browser.info"

    private fun pool(vararg seed: String) = RadioBrowserServerPool(clock = { now }, seed = seed.toList(), random = Random(7))

    @Test
    fun `only the directory's own hosts are accepted`() {
        assertTrue(RadioBrowserServerPool.accepts("de1.api.radio-browser.info"))
        assertTrue(RadioBrowserServerPool.accepts("ALL.api.radio-browser.info"))
        assertFalse("another domain", RadioBrowserServerPool.accepts("de1.api.radio-browser.info.evil.example"))
        assertFalse("a lookalike", RadioBrowserServerPool.accepts("de1.api.radio-browser.info.example.com"))
        assertFalse("a bare domain", RadioBrowserServerPool.accepts("api.radio-browser.info"))
        assertFalse("nested labels", RadioBrowserServerPool.accepts("x.y.api.radio-browser.info"))
        assertFalse("an address", RadioBrowserServerPool.accepts("10.0.0.1"))
        assertFalse("a path", RadioBrowserServerPool.accepts("de1/x.api.radio-browser.info"))
        assertFalse("empty", RadioBrowserServerPool.accepts(""))
        assertFalse("a leading dash", RadioBrowserServerPool.accepts("-de1.api.radio-browser.info"))
    }

    @Test
    fun `names that are not the directory's are dropped from the seed and from a replacement`() {
        val seeded = pool(a, "evil.example.com", b)
        assertEquals(2, seeded.size())

        seeded.replace(listOf("evil.example.com", c))

        assertEquals(listOf(c), seeded.candidates(5))
    }

    @Test
    fun `a replacement with nothing acceptable changes nothing`() {
        val p = pool(a, b)

        p.replace(listOf("evil.example.com", ""))

        assertEquals(2, p.size())
    }

    @Test
    fun `the server that answered is kept and tried first`() {
        val p = pool(a, b, c)

        p.succeeded(b)

        assertEquals(b, p.candidates(3).first())
        assertEquals(3, p.candidates(3).toSet().size)
    }

    @Test
    fun `a server that failed is left alone for the cooldown and back after it`() {
        val p = pool(a, b)
        p.succeeded(a)

        p.failed(a)

        assertEquals(listOf(b, a), p.candidates(2))
        now += RadioBrowserServerPool.COOLDOWN_MS - 1
        assertEquals(b, p.candidates(2).first())
        now += 1
        assertTrue("after the cooldown it is ready again", p.candidates(2).toSet() == setOf(a, b))
        assertEquals(setOf(a, b), p.candidates(2).toSet())
    }

    @Test
    fun `when every server is cooling the one that comes back first is still offered`() {
        val p = pool(a, b, c)
        p.failed(a)
        now += 10_000
        p.failed(b)
        now += 10_000
        p.failed(c)

        assertEquals(listOf(a, b, c), p.candidates(3))
    }

    @Test
    fun `a request is given only as many servers as it may try`() {
        val p = pool(a, b, c)

        assertEquals(2, p.candidates(2).size)
    }

    @Test
    fun `a failure of a server that was replaced away is forgotten`() {
        val p = pool(a, b)
        p.failed(a)

        p.replace(listOf(c))
        p.failed(a)

        assertEquals(listOf(c), p.candidates(3))
    }

    @Test
    fun `the fallback list is made of names the pool accepts`() {
        assertTrue(RadioBrowserServerPool.FALLBACK.all { RadioBrowserServerPool.accepts(it) })
        assertEquals(RadioBrowserServerPool.FALLBACK.size, RadioBrowserServerPool(clock = { 0L }).size())
    }

    // -------------------------------------------------------------------------------- the cache

    @Test
    fun `a list is fresh for a day, usable for two weeks, and gone after`() {
        val day = 24 * 60 * 60_000L

        assertEquals(DirectoryCachePolicy.Verdict.FRESH, DirectoryCachePolicy.verdict(day - 1))
        assertEquals(DirectoryCachePolicy.Verdict.REFRESH_BUT_KEEP, DirectoryCachePolicy.verdict(day))
        assertEquals(DirectoryCachePolicy.Verdict.REFRESH_BUT_KEEP, DirectoryCachePolicy.verdict(14 * day - 1))
        assertEquals(DirectoryCachePolicy.Verdict.EXPIRED, DirectoryCachePolicy.verdict(14 * day))
        assertEquals("a clock that went back is not a reason to throw a list away", DirectoryCachePolicy.Verdict.REFRESH_BUT_KEEP, DirectoryCachePolicy.verdict(-5))
    }

    @Test
    fun `pages are kept for as long as the list changes slowly`() {
        assertEquals(60 * 60_000L, DirectoryCachePolicy.pageFreshMs(WorldListing.Top))
        assertEquals(10 * 60_000L, DirectoryCachePolicy.pageFreshMs(WorldListing.OfCountry("MA")))
        assertEquals(10 * 60_000L, DirectoryCachePolicy.pageFreshMs(WorldListing.OfTag("news")))
        assertEquals(2 * 60_000L, DirectoryCachePolicy.pageFreshMs(WorldListing.Search("rim")))
    }
}
