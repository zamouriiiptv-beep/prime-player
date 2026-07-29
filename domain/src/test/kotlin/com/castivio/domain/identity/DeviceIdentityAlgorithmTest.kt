package com.castivio.domain.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The derivation, pinned.
 *
 * This is the one file in the project where a passing test that used to fail means
 * something has gone badly wrong. Every address in the field is v1's output, so the
 * vectors below are not examples — they are the specification, and a change that makes
 * them fail is a change that revokes every licence Castivio has ever issued.
 */
class DeviceIdentityAlgorithmTest {

    private val sha256 = Sha256 { MessageDigest.getInstance("SHA-256").digest(it) }

    // ------------------------------------------------------------- the vectors

    /**
     * Computed independently of this codebase, from the written specification in
     * [DeviceIdentityV1]. They are what a second implementation — a licence server, an
     * iOS port, a support tool — has to reproduce.
     */
    @Test
    fun `v1 derives the pinned addresses`() {
        val vectors = mapOf(
            IdentitySeed.Os("00000000abcdef01") to "B6:CA:C2:D5:13:49",
            IdentitySeed.Os("9a1c33f0b27e45d8") to "B6:8A:C2:4B:1F:8D",
            IdentitySeed.Os("1a2b3c4d5e6f0718") to "A2:C4:D4:A3:61:37",
            IdentitySeed.Installation("2c9b7a41-0e58-4d3a-9f61-8bd0e7c41a52") to "7A:C8:D9:1C:95:B0",
        )

        for ((seed, expected) in vectors) {
            assertEquals(
                "seed ${seed.material}",
                expected,
                DeviceIdentityV1.derive(seed, sha256).value,
            )
        }
    }

    /** The label is part of the output, so it is part of the contract. */
    @Test
    fun `the v1 label is frozen`() {
        assertEquals("castivio/device-identity/v1", DeviceIdentityV1.LABEL)
        assertEquals(1, DeviceIdentityV1.VERSION)
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `the same seed always derives the same address`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")
        val first = DeviceIdentityV1.derive(seed, sha256)

        repeat(64) {
            assertEquals(first, DeviceIdentityV1.derive(seed, sha256))
        }
    }

    @Test
    fun `a different seed derives a different address`() {
        val a = DeviceIdentityV1.derive(IdentitySeed.Os("9a1c33f0b27e45d8"), sha256)
        val b = DeviceIdentityV1.derive(IdentitySeed.Os("9a1c33f0b27e45d9"), sha256)

        assertNotEquals(a, b)
    }

    /**
     * The two provenances are separate namespaces. Without the prefixes in
     * [IdentitySeed.material], a locally minted value that happened to read like an
     * operating-system identifier would derive the same address as the real thing.
     */
    @Test
    fun `an installation seed never collides with an operating system seed`() {
        val text = "9a1c33f0b27e45d8"

        assertNotEquals(
            DeviceIdentityV1.derive(IdentitySeed.Os(text), sha256),
            DeviceIdentityV1.derive(IdentitySeed.Installation(text), sha256),
        )
    }

    // ------------------------------------------------------------ the address

    /**
     * Locally administered and unicast, every time — the range no hardware vendor is
     * ever assigned, which is what lets Castivio synthesise an address at all.
     */
    @Test
    fun `every derived address is locally administered and unicast`() {
        for (i in 0 until 512) {
            val mac = DeviceIdentityV1.derive(IdentitySeed.Os(i.toString(16).padStart(16, '0')), sha256)

            assertTrue(mac.value, mac.isLocallyAdministered)
            assertTrue(mac.value, mac.isUnicast)
        }
    }

    @Test
    fun `derived addresses do not collide across a large sample`() {
        val seen = (0 until 5_000)
            .map { DeviceIdentityV1.derive(IdentitySeed.Os(it.toString(16).padStart(16, '0')), sha256) }
            .toSet()

        assertEquals(5_000, seen.size)
    }

    // ----------------------------------------------------------- the registry

    @Test
    fun `the registry derives the current version by default`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")

        assertEquals(1, DeviceIdentityAlgorithm.CURRENT)
        assertEquals(listOf(1), DeviceIdentityAlgorithm.known)
        assertEquals(
            DeviceIdentityV1.derive(seed, sha256),
            DeviceIdentityAlgorithm.derive(seed, sha256),
        )
    }

    /** Every known version stays reproducible — that is what makes a migration possible. */
    @Test
    fun `every known version can still be derived`() {
        val seed = IdentitySeed.Os("9a1c33f0b27e45d8")

        for (version in DeviceIdentityAlgorithm.known) {
            DeviceIdentityAlgorithm.derive(seed, sha256, version)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown version is refused rather than guessed`() {
        DeviceIdentityAlgorithm.derive(IdentitySeed.Os("9a1c33f0b27e45d8"), sha256, version = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a digest that is not sha256 is refused`() {
        DeviceIdentityV1.derive(IdentitySeed.Os("9a1c33f0b27e45d8"), Sha256 { ByteArray(4) })
    }

    // --------------------------------------------------------- seed hygiene

    @Test
    fun `an operating system identifier is normalised before it is used`() {
        val canonical = IdentitySeed.fromOs("9a1c33f0b27e45d8")

        assertEquals(canonical, IdentitySeed.fromOs("  9A1C33F0B27E45D8  "))
        assertEquals(canonical, IdentitySeed.fromOs("9A1C33F0B27E45D8"))
    }

    /**
     * `Settings.Secure.ANDROID_ID` is a 64-bit number printed as hexadecimal, so a
     * device whose top bits are zero prints a short string. Padding makes the short
     * and long spellings the same device, which they are — and this is exactly the
     * kind of drift that would otherwise strand a licence after an OS update changed
     * how the value is formatted.
     */
    @Test
    fun `a short operating system identifier is the same device as its padded form`() {
        assertEquals(
            IdentitySeed.fromOs("00000000abcdef01"),
            IdentitySeed.fromOs("abcdef01"),
        )
    }

    @Test
    fun `degenerate operating system identifiers are refused`() {
        val refused = listOf(
            null,
            "",
            "   ",
            // The value a batch of buggy ROMs shipped, shared by millions of devices.
            "9774d56d682e549c",
            "9774D56D682E549C",
            "1234567890abcdef",
            "0000000000000000",
            "ffffffffffffffff",
            // Not hexadecimal.
            "not-an-identifier",
            "9a1c33f0b27e45dg",
            // Too little to be a 64-bit value.
            "abc",
            "1234567",
            // Longer than the format allows: something else entirely.
            "9a1c33f0b27e45d8aa",
        )

        for (raw in refused) {
            assertNull("$raw", IdentitySeed.fromOs(raw))
        }
    }

    @Test
    fun `an accepted identifier carries device provenance`() {
        val seed = IdentitySeed.fromOs("9a1c33f0b27e45d8")

        assertEquals(IdentityProvenance.DEVICE, seed?.provenance)
        assertEquals("os:9a1c33f0b27e45d8", seed?.material)
    }

    @Test
    fun `a minted identifier says so`() {
        val seed = IdentitySeed.Installation("2c9b7a41-0e58-4d3a-9f61-8bd0e7c41a52")

        assertEquals(IdentityProvenance.INSTALLATION, seed.provenance)
        assertEquals("install:2c9b7a41-0e58-4d3a-9f61-8bd0e7c41a52", seed.material)
    }
}
