package com.castivio.domain.provider

import com.castivio.domain.PlaylistSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the portal form will and will not let through.
 *
 * Two fields, and the asymmetry between them is the whole of it: the name is the
 * user's and may be anything or nothing, while the address is the subscription and
 * has to be an address. Everything else a portal needs — the device's own identity —
 * is the application's to supply, which is why there is no third field to check.
 */
class PortalFormCheckTest {

    @Test
    fun `an address alone is enough to connect`() {
        val check = PortalFormCheck.of(name = "", url = "http://portal.example:8080/c/")

        assertTrue(check.canSubmit)
        assertNull("an empty name means 'choose one for me'", check.label)
        // The field normalises what it was given, and the trailing slash is the one
        // part of a portal address that carries no meaning: `/c` and `/c/` are the
        // same installation, and `PortalUrls` trims it again before it builds a
        // request. Asserted as the stored value rather than as what was typed, so
        // that a change to the normalisation is seen here rather than in a duplicate
        // subscription on somebody's device.
        assertEquals(PlaylistSource.Portal("http://portal.example:8080/c"), check.source)
    }

    @Test
    fun `a name is kept when the user gives one`() {
        val check = PortalFormCheck.of(name = "  Main portal  ", url = "http://portal.example/c/")

        assertEquals("Main portal", check.label)
        assertTrue(check.canSubmit)
    }

    /* ------------------------------------------------------------ what is refused */

    @Test
    fun `an empty address cannot be submitted`() {
        val check = PortalFormCheck.of(name = "Portal", url = "   ")

        assertFalse(check.canSubmit)
        assertNull("a form that cannot be submitted must not produce a source", check.source)
    }

    /**
     * A scheme this application cannot open is refused at the form.
     *
     * The alternative is a handshake that fails with a network error, which tells the
     * user their provider is down when what is wrong is the line they pasted.
     */
    @Test
    fun `an address with an unusable scheme is refused`() {
        val check = PortalFormCheck.of(name = "", url = "ftp://portal.example/c/")

        assertFalse(check.canSubmit)
        assertEquals(FieldProblem.UNSUPPORTED_SCHEME, check.url.problem)
    }

    @Test
    fun `an address with no host is refused`() {
        assertFalse(PortalFormCheck.of(name = "", url = "http://").canSubmit)
        assertFalse(PortalFormCheck.of(name = "", url = "portal example").canSubmit)
    }

    /**
     * A bad port is caught here, where it is a typo, rather than at the socket.
     *
     * `host:80800` is one keystroke from `host:8080` and is the commonest thing to
     * get wrong about an address a provider read out over the phone.
     */
    @Test
    fun `an impossible port is refused`() {
        val check = PortalFormCheck.of(name = "", url = "http://portal.example:80800/c/")

        assertFalse(check.canSubmit)
        assertEquals(FieldProblem.INVALID_PORT, check.url.problem)
    }

    /* ------------------------------------------------------- what is not asked for */

    /**
     * The address keeps its path, which is what tells two portals on one host apart.
     *
     * The opposite of the Xtream field beside it, which normalises to an origin
     * because an Xtream client builds its own paths. A portal *is* a path, and a form
     * that trimmed it would silently turn one subscription into another.
     */
    @Test
    fun `the path a provider gave is not trimmed away`() {
        val check = PortalFormCheck.of(name = "", url = "http://host.example/stalker_portal/c/")

        assertNotNull(check.source)
        assertTrue(
            "the installation directory was dropped: ${check.source?.url}",
            check.source?.url?.contains("stalker_portal") == true,
        )
    }

    /**
     * There is no MAC on this form, and this is the assertion that keeps it off.
     *
     * A portal source has one field. The device address the protocol needs is the one
     * the activation screen already shows and the user already sent their provider; a
     * second field here would be asking them to copy it back in. If somebody adds one,
     * this fails.
     */
    @Test
    fun `a portal source is an address and nothing else`() {
        val source = PortalFormCheck.of(name = "", url = "http://host.example/c/").source!!

        assertEquals(
            "a portal source grew a field",
            1,
            PlaylistSource.Portal::class.java.declaredFields.count { !it.isSynthetic },
        )
        assertEquals("http://host.example/c", source.url)
    }
}
