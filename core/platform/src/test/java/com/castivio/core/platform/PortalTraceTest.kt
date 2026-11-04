package com.castivio.core.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The line a refused portal says, in numbers: its words, its order, and that it keeps nothing when the instruments are off.
 */
class PortalTraceTest {

    @Before
    fun setUp() {
        DevDiagnostics.enable(true)
        PortalTrace.reset()
    }

    @After
    fun tearDown() {
        DevDiagnostics.enable(false)
        PortalTrace.reset()
    }

    @Test
    fun `each address is numbered in the order it was asked, and says what it answered`() {
        PortalTrace.begin()
        PortalTrace.record(PortalTrace.Answer.Status(404))
        PortalTrace.record(PortalTrace.Answer.NotAPortal)
        PortalTrace.record(PortalTrace.Answer.TimedOut)

        assertEquals("1: 404 · 2: 200 not a portal · 3: timeout", PortalTrace.line())
    }

    @Test
    fun `an address that answered as a portal says so`() {
        PortalTrace.begin()
        PortalTrace.record(PortalTrace.Answer.Status(403))
        PortalTrace.record(PortalTrace.Answer.Portal)

        assertEquals("1: 403 · 2: portal", PortalTrace.line())
    }

    @Test
    fun `a host that could not be reached says so`() {
        PortalTrace.begin()
        PortalTrace.record(PortalTrace.Answer.Unreachable)

        assertEquals("1: unreachable", PortalTrace.line())
    }

    @Test
    fun `a new handshake starts the line again`() {
        PortalTrace.begin()
        PortalTrace.record(PortalTrace.Answer.Status(404))
        PortalTrace.begin()
        PortalTrace.record(PortalTrace.Answer.Status(500))

        assertEquals("1: 500", PortalTrace.line())
    }

    @Test
    fun `nothing asked is no line`() {
        PortalTrace.begin()
        assertNull(PortalTrace.line())
    }

    @Test
    fun `with the instruments off nothing is kept and nothing is said`() {
        DevDiagnostics.enable(false)
        PortalTrace.begin()
        PortalTrace.record(PortalTrace.Answer.Status(404))

        assertNull(PortalTrace.line())
    }
}
