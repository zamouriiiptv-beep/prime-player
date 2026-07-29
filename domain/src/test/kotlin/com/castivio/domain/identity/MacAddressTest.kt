package com.castivio.domain.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address as a value.
 *
 * Parsing is generous because the string makes a round trip through a human: it is
 * read off a television, typed into a provider's panel, and sometimes typed back. What
 * comes back can be lower case, hyphenated or bare, and all of those are the same
 * device — so the type accepts them and stores one spelling.
 */
class MacAddressTest {

    @Test
    fun `every spelling in the wild parses to one canonical form`() {
        val canonical = "2F:19:EB:20:44:7C"
        val spellings = listOf(
            "2F:19:EB:20:44:7C",
            "2f:19:eb:20:44:7c",
            "2F-19-EB-20-44-7C",
            "2f19eb20447c",
            "2F19EB20447C",
            "  2f:19:eb:20:44:7c  ",
            "2f 19 eb 20 44 7c",
        )

        for (text in spellings) {
            assertEquals(text, canonical, MacAddress.parse(text)?.value)
        }
    }

    @Test
    fun `text that is not an address is refused`() {
        val refused = listOf(
            "",
            "2F:19:EB:20:44",          // five octets
            "2F:19:EB:20:44:7C:8D",    // seven
            "2G:19:EB:20:44:7C",       // not hexadecimal
            "not a mac address",
            "2F:19:EB:20:44:7",        // eleven digits
        )

        for (text in refused) {
            assertNull(text, MacAddress.parse(text))
        }
    }

    @Test
    fun `the alternative spellings are available for the panels that want them`() {
        val mac = MacAddress.parse("2F:19:EB:20:44:7C")!!

        assertEquals("2F19EB20447C", mac.compact)
        assertEquals("2f:19:eb:20:44:7c", mac.lowerCase)
        assertEquals("2F:19:EB:20:44:7C", mac.toString())
    }

    @Test
    fun `octets round trip through the canonical form`() {
        val octets = byteArrayOf(0x2F, 0x19, 0xEB.toByte(), 0x20, 0x44, 0x7C)

        assertEquals(MacAddress.parse("2F:19:EB:20:44:7C"), MacAddress.of(octets))
    }

    @Test
    fun `a high octet is not sign extended`() {
        val mac = MacAddress.of(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x80.toByte(), 0x00, 0x0F, 0xA0.toByte()))

        assertEquals("FE:FF:80:00:0F:A0", mac.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an address is exactly six octets`() {
        MacAddress.of(byteArrayOf(0x2F, 0x19, 0xEB.toByte(), 0x20, 0x44))
    }

    @Test
    fun `the administration and cast bits are readable`() {
        // 0x02 set, 0x01 clear: locally administered, unicast — what Castivio derives.
        val derived = MacAddress.parse("02:19:EB:20:44:7C")!!
        assertTrue(derived.isLocallyAdministered)
        assertTrue(derived.isUnicast)

        // 0x00: a vendor-assigned unicast address, the classic set-top box prefix.
        val vendor = MacAddress.parse("00:1A:79:20:44:7C")!!
        assertFalse(vendor.isLocallyAdministered)
        assertTrue(vendor.isUnicast)

        // 0x01 set: a group address, which is not a device.
        val group = MacAddress.parse("03:19:EB:20:44:7C")!!
        assertTrue(group.isLocallyAdministered)
        assertFalse(group.isUnicast)
    }

    @Test
    fun `two addresses with the same octets are the same value`() {
        assertEquals(MacAddress.parse("2f19eb20447c"), MacAddress.parse("2F:19:EB:20:44:7C"))
        assertEquals(
            MacAddress.parse("2f19eb20447c").hashCode(),
            MacAddress.parse("2F:19:EB:20:44:7C").hashCode(),
        )
    }
}
