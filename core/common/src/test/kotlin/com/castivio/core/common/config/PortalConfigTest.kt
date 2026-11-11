package com.castivio.core.common.config

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Where the application asks the portal: the product's own address unless the build says
 * otherwise, and never a malformed one.
 */
class PortalConfigTest {

    @Test
    fun `blank means the product's own address`() {
        assertEquals(PortalConfig.PRODUCTION, PortalConfig.of(null).baseUrl)
        assertEquals(PortalConfig.PRODUCTION, PortalConfig.of("").baseUrl)
        assertEquals(PortalConfig.PRODUCTION, PortalConfig.of("   ").baseUrl)
    }

    @Test
    fun `the product's address is the host the QR and the typed address already name`() {
        // One host, stated twice in this module: if the product moves, both lines move.
        assertEquals(true, ActivationDestination.URL.startsWith(PortalConfig.PRODUCTION + "/"))
    }

    @Test
    fun `a configured address is kept, without a trailing slash`() {
        assertEquals("https://test.example.app", PortalConfig.of("https://test.example.app").baseUrl)
        assertEquals("https://test.example.app", PortalConfig.of("  https://test.example.app/  ").baseUrl)
        assertEquals("http://10.0.2.2:3000", PortalConfig.of("http://10.0.2.2:3000//").baseUrl)
    }

    @Test
    fun `api addresses are joined with exactly one slash`() {
        val config = PortalConfig.of("https://test.example.app/")

        assertEquals("https://test.example.app/api/v1/device/inbox", config.api("device/inbox"))
        assertEquals("https://test.example.app/api/v1/device/inbox", config.api("/device/inbox"))
    }

    @Test
    fun `something that is not a web address is stopped at start-up`() {
        for (bad in listOf("castivio.app", "ftp://x.example", "javascript:alert(1)")) {
            try {
                PortalConfig.of(bad)
                fail("$bad must be refused")
            } catch (expected: IllegalArgumentException) {
                // a mistake in a build script, found before the first press
            }
        }
    }

    @Test
    fun `two configs for the same address are equal`() {
        assertEquals(PortalConfig.of("https://a.example"), PortalConfig.of("https://a.example/"))
    }
}
