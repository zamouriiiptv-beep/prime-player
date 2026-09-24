package com.castivio.domain.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The key derivation, pinned — for the same reason the address derivation is.
 *
 * A device key a provider has written down is a device key that must not change. The
 * vectors below are the specification a second implementation reproduces, and a change
 * that makes them fail is a change that orphans every activation done by key.
 */
class DeviceKeyAlgorithmTest {

    private val sha256 = Sha256 { MessageDigest.getInstance("SHA-256").digest(it) }

    // ------------------------------------------------------------- the vectors

    /** Computed outside this codebase, from the written specification in [DeviceKeyV1]. */
    @Test
    fun `v1 derives the pinned keys`() {
        val vectors = mapOf(
            IdentitySeed.Os("00000000abcdef01") to "BR0S-6097",
            IdentitySeed.Os("9a1c33f0b27e45d8") to "VA97-887Z",
            IdentitySeed.Os("1a2b3c4d5e6f0718") to "HZG2-A03R",
            IdentitySeed.Installation("2c9b7a41-0e58-4d3a-9f61-8bd0e7c41a52") to "WA7S-0H6D",
        )

        for ((seed, expected) in vectors) {
            assertEquals(
                "seed ${seed.material}",
                expected,
                DeviceKeyV1.derive(seed, sha256).value,
            )
        }
    }

    /** The label and the alphabet are both part of the output, so both are contract. */
    @Test
    fun `the v1 label and alphabet are frozen`() {
        assertEquals("castivio/device-key/v1", DeviceKeyV1.LABEL)
        assertEquals("0123456789ABCDEFGHJKMNPQRSTVWXYZ", DeviceKeyV1.ALPHABET)
        assertEquals(1, DeviceKeyV1.VERSION)
    }

    // ------------------------------------------------------------ the shape

    @Test
    fun `a key is two groups of four with one hyphen`() {
        val key = DeviceKeyV1.derive(IdentitySeed.Os("9a1c33f0b27e45d8"), sha256).value

        assertEquals(9, key.length)
        assertEquals('-', key[4])
        assertEquals(1, key.count { it == '-' })
    }

    /**
     * The alphabet exists to survive being read aloud. A key containing I, L, O or U
     * would be a key somebody mistypes as 1, 1, 0 or V, which is a support call.
     */
    @Test
    fun `no key can contain a character that is misread`() {
        for (index in 0 until 500) {
            val key = DeviceKeyV1.derive(IdentitySeed.Os("seed-$index"), sha256).value

            for (symbol in key.replace("-", "")) {
                assertTrue("'$symbol' in $key", symbol in DeviceKeyV1.ALPHABET)
            }
        }
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `the same seed always derives the same key`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")
        val first = DeviceKeyV1.derive(seed, sha256)

        repeat(64) {
            assertEquals(first, DeviceKeyV1.derive(seed, sha256))
        }
    }

    @Test
    fun `a different seed derives a different key`() {
        val a = DeviceKeyV1.derive(IdentitySeed.Os("9a1c33f0b27e45d8"), sha256)
        val b = DeviceKeyV1.derive(IdentitySeed.Os("9a1c33f0b27e45d9"), sha256)

        assertNotEquals(a, b)
    }

    /**
     * The same material under two labels must not produce related output. This is the
     * whole reason [DeviceIdentityV1] put a label in the derivation, and the test that
     * would catch someone "simplifying" the two to share one.
     */
    @Test
    fun `the key and the address are independent derivations of one seed`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")

        val key = DeviceKeyV1.derive(seed, sha256).value.replace("-", "")
        val address = DeviceIdentityV1.derive(seed, sha256).value.replace(":", "")

        assertNotEquals(address, key)
        assertNotEquals(DeviceKeyV1.LABEL, DeviceIdentityV1.LABEL)
    }

    /**
     * Five hundred seeds, five hundred distinct keys. Not a proof of the 2^40 space,
     * but it is the assertion that fails immediately if a future edit truncates the
     * digest, reuses a byte, or renders fewer symbols than it should.
     */
    @Test
    fun `distinct seeds do not collide at a scale a bug would show at`() {
        val keys = (0 until 500).map {
            DeviceKeyV1.derive(IdentitySeed.Os("seed-$it"), sha256).value
        }

        assertEquals(500, keys.toSet().size)
    }

    // ----------------------------------------------------------- the versions

    @Test
    fun `the algorithm dispatches to v1 and refuses what it does not know`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")

        assertEquals(DeviceKeyV1.derive(seed, sha256), DeviceKeyAlgorithm.derive(seed, sha256))
        assertEquals(DeviceKeyV1.VERSION, DeviceKeyAlgorithm.CURRENT)
        assertEquals(listOf(DeviceKeyV1.VERSION), DeviceKeyAlgorithm.known)

        val refused = runCatching { DeviceKeyAlgorithm.derive(seed, sha256, version = 99) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }
}
