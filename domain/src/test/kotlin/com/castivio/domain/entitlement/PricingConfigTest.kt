package com.castivio.domain.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prices ship as configuration, not as constants scattered through the app.
 *
 * These tests pin the current commercial terms so a change to them is a deliberate
 * edit to one file rather than a surprise, and prove the shape supports being replaced
 * by a server response without anything above it changing.
 */
class PricingConfigTest {

    private val day = 24L * 60 * 60 * 1000
    private val config = PricingDefaults.config

    @Test
    fun `the shipped terms are seven days, six dollars a year, fifteen for life`() {
        assertEquals(7 * day, config.trialDurationMs)

        val annual = config.offer(Plan.ANNUAL)!!
        assertEquals(600L, annual.priceMinor)
        assertEquals("USD", annual.currency)
        assertEquals(365 * day, annual.periodMs)

        val lifetime = config.offer(Plan.LIFETIME)!!
        assertEquals(1_500L, lifetime.priceMinor)
        assertEquals("USD", lifetime.currency)
        assertNull("lifetime has no period", lifetime.periodMs)
    }

    /**
     * Money is an integer count of minor units. A price in a floating-point type is a
     * rounding error that has not happened yet.
     */
    @Test
    fun `prices are whole minor units`() {
        for (offer in config.plans) {
            assertTrue("${offer.plan} price must not be negative", offer.priceMinor >= 0)
        }
        assertEquals(0L, config.offer(Plan.TRIAL)!!.priceMinor)
    }

    @Test
    fun `a purchase screen is offered the paid plans only`() {
        val purchasable = config.purchasable.map { it.plan }

        assertEquals(listOf(Plan.ANNUAL, Plan.LIFETIME), purchasable)
    }

    /**
     * A plan can be withdrawn without deleting it — a region or a store may not sell
     * one — and a withdrawn plan must disappear from both lookups.
     */
    @Test
    fun `an unavailable plan is neither offered nor purchasable`() {
        val withoutLifetime = config.copy(
            plans = config.plans.map {
                if (it.plan == Plan.LIFETIME) it.copy(available = false) else it
            },
        )

        assertNull(withoutLifetime.offer(Plan.LIFETIME))
        assertEquals(listOf(Plan.ANNUAL), withoutLifetime.purchasable.map { it.plan })
        assertNotNull("annual is untouched", withoutLifetime.offer(Plan.ANNUAL))
    }

    @Test
    fun `grace is longer than the verification interval, or it would never be reached`() {
        assertTrue(config.offlineGraceMs > config.verifyIntervalMs)
    }

    /** A whole config can be swapped, which is what "the server owns pricing" means. */
    @Test
    fun `a replacement configuration is a plain value`() {
        val fromServer = PricingConfig(
            trialDurationMs = 3 * day,
            offlineGraceMs = 2 * day,
            verifyIntervalMs = 6 * 60 * 60 * 1000,
            plans = listOf(
                PlanOffer(Plan.ANNUAL, priceMinor = 900, currency = "EUR", periodMs = 365 * day),
            ),
        )

        assertEquals(3 * day, fromServer.trialDurationMs)
        assertEquals("EUR", fromServer.offer(Plan.ANNUAL)!!.currency)
        assertNull("lifetime is not sold in this configuration", fromServer.offer(Plan.LIFETIME))
    }
}
