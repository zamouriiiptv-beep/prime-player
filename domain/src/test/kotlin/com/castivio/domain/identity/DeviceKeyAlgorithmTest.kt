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

    /**
     * Computed outside this codebase, from the written specification in [DeviceKeyV1].
     *
     * **v1 is no longer the version in force and these still have to pass.** A device
     * that minted a key under it has that string on disk, and `legacy()` exists so the
     * client can still produce it. The day this file is allowed to fail is the day
     * every key issued under v1 is orphaned.
     */
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
    fun `a v1 key is two groups of four with one hyphen`() {
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
    fun `no v1 key can contain a character that is misread`() {
        for (index in 0 until 500) {
            val key = DeviceKeyV1.derive(IdentitySeed.Os("seed-$index"), sha256).value

            for (symbol in key.replace("-", "")) {
                assertTrue("'$symbol' in $key", symbol in DeviceKeyV1.ALPHABET)
            }
        }
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `the same seed always derives the same v1 key`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")
        val first = DeviceKeyV1.derive(seed, sha256)

        repeat(64) {
            assertEquals(first, DeviceKeyV1.derive(seed, sha256))
        }
    }

    @Test
    fun `a different seed derives a different v1 key`() {
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
    fun `the v1 key and the address are independent derivations of one seed`() {
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
    fun `distinct v1 seeds do not collide at a scale a bug would show at`() {
        val keys = (0 until 500).map {
            DeviceKeyV1.derive(IdentitySeed.Os("seed-$it"), sha256).value
        }

        assertEquals(500, keys.toSet().size)
    }

    // ----------------------------------------------------------- the versions

    /**
     * The default is v2, v1 is still reachable by name, and an unknown version is
     * refused rather than silently answered by the current one — which is how a
     * device carrying data from a newer build would otherwise be handed the wrong key.
     */
    @Test
    fun `the algorithm defaults to v2, still serves v1, and refuses the rest`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")

        assertEquals(DeviceKeyV2.VERSION, DeviceKeyAlgorithm.CURRENT)
        assertEquals(listOf(DeviceKeyV1.VERSION, DeviceKeyV2.VERSION), DeviceKeyAlgorithm.known)

        assertEquals(DeviceKeyV2.derive(seed, sha256), DeviceKeyAlgorithm.derive(seed, sha256))
        assertEquals(
            DeviceKeyV1.derive(seed, sha256),
            DeviceKeyAlgorithm.derive(seed, sha256, version = DeviceKeyV1.VERSION),
        )
        assertEquals(
            DeviceKeyV2.derive(seed, sha256),
            DeviceKeyAlgorithm.derive(seed, sha256, version = DeviceKeyV2.VERSION),
        )

        val refused = runCatching { DeviceKeyAlgorithm.derive(seed, sha256, version = 99) }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    // ------------------------------------------------------------------ v2

    /** Computed outside this codebase, from the written specification in [DeviceKeyV2]. */
    @Test
    fun `v2 derives the pinned keys`() {
        val vectors = mapOf(
            IdentitySeed.Os("00000000abcdef01") to "132414",
            IdentitySeed.Os("9a1c33f0b27e45d8") to "150568",
            IdentitySeed.Os("1a2b3c4d5e6f0718") to "063067",
            IdentitySeed.Installation("2c9b7a41-0e58-4d3a-9f61-8bd0e7c41a52") to "515398",
        )

        for ((seed, expected) in vectors) {
            assertEquals(
                "seed ${seed.material}",
                expected,
                DeviceKeyV2.derive(seed, sha256).value,
            )
        }
    }

    @Test
    fun `the v2 label and width are frozen`() {
        assertEquals("castivio/device-key/v2", DeviceKeyV2.LABEL)
        assertEquals(6, DeviceKeyV2.DIGITS)
        assertEquals(2, DeviceKeyV2.VERSION)
    }

    /**
     * **The padding is part of the value.** `n mod 10^6` is a number, and a small one
     * prints as five characters or fewer. A key whose length varies is a key a user
     * mistypes and a field that cannot check its own shape, so the zero fill is
     * pinned here by a seed that actually produces a small remainder — two of them,
     * one from each provenance of the vector set above.
     */
    @Test
    fun `a v2 key is always six figures, zero filled`() {
        assertEquals("022971", DeviceKeyV2.derive(IdentitySeed.Os("pad-0"), sha256).value)
        assertEquals("063067", DeviceKeyV2.derive(IdentitySeed.Os("1a2b3c4d5e6f0718"), sha256).value)

        for (index in 0 until 2_000) {
            val key = DeviceKeyV2.derive(IdentitySeed.Os("seed-$index"), sha256).value

            assertEquals("seed-$index -> $key", 6, key.length)
            assertTrue("seed-$index -> $key", key.all { it in '0'..'9' })
        }
    }

    @Test
    fun `the same seed always derives the same v2 key`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")
        val first = DeviceKeyV2.derive(seed, sha256)

        repeat(64) {
            assertEquals(first, DeviceKeyV2.derive(seed, sha256))
        }
    }

    @Test
    fun `a different seed derives a different v2 key`() {
        val a = DeviceKeyV2.derive(IdentitySeed.Os("9a1c33f0b27e45d8"), sha256)
        val b = DeviceKeyV2.derive(IdentitySeed.Os("9a1c33f0b27e45d9"), sha256)

        assertNotEquals(a, b)
    }

    /**
     * Six digits is a million values, so this is **not** a uniqueness claim and must
     * not be read as one — [DeviceKeyV2] states the birthday bound, which is about
     * 1,180 devices. What it does catch is the failure that would make the space far
     * smaller than a million: a truncated digest, a reused byte, a modulus applied
     * twice. Two thousand seeds in a million-wide space expect about two collisions,
     * so the bar is deliberately loose and still a long way from what a broken
     * derivation would produce.
     */
    @Test
    fun `v2 spreads across its space rather than clustering`() {
        val keys = (0 until 2_000).map {
            DeviceKeyV2.derive(IdentitySeed.Os("spread-$it"), sha256).value
        }

        assertTrue("distinct ${keys.toSet().size} of ${keys.size}", keys.toSet().size >= 1_990)
    }

    /** The two versions answer the same seed differently, which is what a label is for. */
    @Test
    fun `v1 and v2 are independent derivations of one seed`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")

        assertNotEquals(DeviceKeyV1.LABEL, DeviceKeyV2.LABEL)
        assertNotEquals(
            DeviceKeyV1.derive(seed, sha256).value.filter { it.isDigit() },
            DeviceKeyV2.derive(seed, sha256).value,
        )
    }
}
