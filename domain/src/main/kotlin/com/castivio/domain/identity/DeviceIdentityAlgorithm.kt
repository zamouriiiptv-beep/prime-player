package com.castivio.domain.identity

/**
 * SHA-256, supplied by the platform.
 *
 * A port rather than a direct call because `:domain` compiles for platforms that have
 * no JVM. It is not a seam anyone should be creative in: [DeviceIdentityV1] pins a
 * test vector precisely so that an implementation which is not SHA-256 fails a test
 * rather than silently minting different addresses on different platforms.
 */
fun interface Sha256 {
    fun digest(bytes: ByteArray): ByteArray
}

/**
 * **DeviceIdentity algorithm v1.**
 *
 * ```
 * material := "castivio/device-identity/v1" ‖ "\n" ‖ seed.material
 * digest   := SHA-256(UTF-8(material))
 * octets   := digest[0 .. 5]
 * octets[0] := (octets[0] & 0xFE) | 0x02
 * address  := upper-case hex octets joined by ':'
 * ```
 *
 * Every part of that is fixed for as long as v1 exists. The label is included so that
 * a future derivation over the same seed — a device key, a portal token — cannot
 * collide with this one, and the version is inside the label so that changing the
 * version changes every byte of the output rather than some of them.
 *
 * The bit twiddling on the first octet sets the *locally administered* bit and clears
 * the *multicast* bit, putting the result in the range IEEE reserves for addresses
 * that no hardware vendor will ever assign. Castivio therefore never has to read a
 * real hardware address — which on modern Android it could not do anyway without
 * privileged permissions, and which would be the wrong answer regardless, since a
 * Wi-Fi address is randomised per network and a device with no Wi-Fi has none.
 *
 * ### Changing this
 *
 * Do not. A new derivation is a new object beside this one — `DeviceIdentityV2` — with
 * its own label and its own entry in [DeviceIdentityAlgorithm]. v1 stays exactly as it
 * is, forever, because devices in the field are licensed against its output and
 * [DeviceIdentity.legacy] has to be able to reproduce it. An edit to this file that
 * changes a byte is not a refactor; it is a mass revocation.
 */
object DeviceIdentityV1 {

    const val VERSION: Int = 1

    /** Frozen. One character of difference is a different address on every device. */
    const val LABEL: String = "castivio/device-identity/v1"

    fun derive(seed: IdentitySeed, sha256: Sha256): MacAddress {
        val digest = sha256.digest("$LABEL\n${seed.material}".encodeToByteArray())
        require(digest.size >= OCTETS) {
            "SHA-256 returns 32 bytes; this implementation returned ${digest.size}"
        }
        val octets = digest.copyOf(OCTETS)
        octets[0] = ((octets[0].toInt() and 0xFE) or 0x02).toByte()
        return MacAddress.of(octets)
    }

    private const val OCTETS = 6
}

/**
 * The versions that exist, and the one in force.
 *
 * The indirection is the migration path. [EntitlementRecord.identityVersion] records
 * which version minted the address a licence was issued against, so raising [CURRENT]
 * does not orphan anything: the client can still derive the old address from the same
 * stored seed and hand both to the licence server.
 *
 * [EntitlementRecord.identityVersion]: com.castivio.domain.entitlement.EntitlementRecord
 */
object DeviceIdentityAlgorithm {

    /** The version new derivations use. */
    const val CURRENT: Int = DeviceIdentityV1.VERSION

    /** Every version that can still be reproduced, oldest first. */
    val known: List<Int> = listOf(DeviceIdentityV1.VERSION)

    /**
     * @throws IllegalArgumentException for a version this build does not know, which
     *   means app data written by a newer build. Louder than guessing.
     */
    fun derive(seed: IdentitySeed, sha256: Sha256, version: Int = CURRENT): MacAddress =
        when (version) {
            DeviceIdentityV1.VERSION -> DeviceIdentityV1.derive(seed, sha256)
            else -> throw IllegalArgumentException("Unknown device identity algorithm v$version")
        }
}
