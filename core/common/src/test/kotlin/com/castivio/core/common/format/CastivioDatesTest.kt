package com.castivio.core.common.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * A time is one shape in every language: twenty-four hours, Western digits.
 *
 * Run with the device in Arabic, which is the language that used to turn `21:04` into `٩:٠٤ م`.
 */
class CastivioDatesTest {

    private lateinit var locale: Locale
    private lateinit var zone: TimeZone

    @Before
    fun arabicDevice() {
        locale = Locale.getDefault()
        zone = TimeZone.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restore() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    @Test
    fun `a time is twenty-four hours in Western digits whatever the language`() {
        assertEquals("21:04", CastivioDates.time(EVENING))
        assertEquals("07:30", CastivioDates.time(MORNING))
    }

    private companion object {
        /** 2026-10-22 21:04 UTC. */
        const val EVENING = 1_792_703_040_000L

        /** 2026-10-22 07:30 UTC. */
        const val MORNING = 1_792_654_200_000L
    }
}
