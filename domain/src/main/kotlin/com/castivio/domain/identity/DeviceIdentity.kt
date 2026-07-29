package com.castivio.domain.identity

/**
 * Who this device is, as far as Castivio is concerned.
 *
 * One device, one address, one licence. The address is what a trial is granted to,
 * what an annual subscription renews for, what a lifetime purchase is bound to, and
 * what a user reads out to a provider who activates by MAC — so its stability is not
 * a nicety. An identity that drifts is a customer who paid and is locked out.
 *
 * It is *derived*, never generated: the same device produces the same six octets on
 * every launch of every install, because the derivation is a pure function of a seed
 * the operating system already keeps. See [DeviceIdentityV1].
 */
interface DeviceIdentity {

    /**
     * This device's address under the current algorithm.
     *
     * Never fails and never blocks on the network. A device with no usable operating
     * system identifier gets an [IdentityProvenance.INSTALLATION] address instead of
     * an error, because "we could not work out who you are" is not a screen anyone
     * can act on.
     */
    fun current(): DeviceIdentityRecord

    /**
     * Addresses this device minted under earlier algorithm versions, newest first.
     *
     * Empty on every device that has only ever known one version, which is all of
     * them today. It exists so that the day a v2 is needed, the client can present
     * both the new address and the old one and let the licence server move the
     * entitlement across — rather than the upgrade quietly minting a stranger and
     * stranding a paid licence on an address nothing asks about any more.
     */
    fun legacy(): List<DeviceIdentityRecord>
}

/** An address, and everything that has to be known about how it was produced. */
data class DeviceIdentityRecord(
    val macAddress: MacAddress,

    /** Which [DeviceIdentityAlgorithm] version derived it. Stored, never assumed. */
    val algorithmVersion: Int,

    val provenance: IdentityProvenance,
)

/**
 * How durable an identity is — which is really a statement about what would have to
 * happen for the user to lose their licence.
 *
 * Worth reporting to the licence server, because the two deserve different treatment:
 * a device identity that reappears after a reinstall is the same customer, while an
 * installation identity that reappears is only probably one.
 */
enum class IdentityProvenance {

    /**
     * Derived from the identifier the operating system assigns to this device.
     * Survives uninstalling the app, clearing its data, and a cache wipe. Lost on a
     * factory reset, which is the OS deliberately declaring the device to be new.
     */
    DEVICE,

    /**
     * Minted here, because the operating system's identifier was missing or is one of
     * the known-degenerate values that whole batches of devices shipped with. Survives
     * only as long as the app's data does.
     */
    INSTALLATION,
}

/**
 * The exact bytes an algorithm version hashes, and where they came from.
 *
 * The seed is stored verbatim once chosen, so that every future algorithm version
 * derives from the same material as the first — the version can change what is done
 * with the seed, but never what the seed is.
 */
sealed interface IdentitySeed {

    val provenance: IdentityProvenance

    /**
     * The material fed to the derivation. Frozen: a change here is a change to every
     * address in the field, and therefore a new algorithm version.
     *
     * The two cases are prefixed differently on purpose, so an operating-system
     * identifier and a locally minted one that happened to have the same text could
     * never derive the same address.
     */
    val material: String

    /** The operating system's own device identifier, normalised. */
    data class Os(val value: String) : IdentitySeed {
        override val provenance: IdentityProvenance get() = IdentityProvenance.DEVICE
        override val material: String get() = "os:$value"
    }

    /** A random value minted once by this installation. */
    data class Installation(val value: String) : IdentitySeed {
        override val provenance: IdentityProvenance get() = IdentityProvenance.INSTALLATION
        override val material: String get() = "install:$value"
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        /**
         * Values that are not identifiers even though the operating system returned
         * them.
         *
         * The first is the famous one: a run of devices shipped a ROM that returned a
         * constant, so millions of them share it. Deriving from it would put every one
         * of those devices on the same licence. Catching them here — rather than
         * papering over the collision by mixing in `MANUFACTURER` and `MODEL`, which
         * are neither secret nor as stable as they look — keeps the derivation honest
         * about what it does and does not know.
         */
        private val DEGENERATE = setOf(
            "9774d56d682e549c",
            "1234567890abcdef",
        )

        /**
         * Accepts the operating system's identifier, or refuses it.
         *
         * Refusing is the safe answer: the caller falls back to an
         * [IdentitySeed.Installation] seed, and the provenance recorded alongside the
         * address says plainly that it is the weaker kind.
         *
         * The value is left-padded to sixteen digits because it is a 64-bit number
         * printed as hexadecimal, and a number whose top bits are zero prints short.
         * Padding makes `1a2b3c4d5e6f` and `00001a2b3c4d5e6f` the same device — which
         * they are.
         */
        fun fromOs(raw: String?): Os? {
            val trimmed = raw?.trim()?.lowercase().orEmpty()
            if (trimmed.length !in 8..16) return null
            if (trimmed.any { it !in HEX }) return null
            val padded = trimmed.padStart(16, '0')
            if (padded in DEGENERATE) return null
            // All one digit: zeroes from an uninitialised field, f's from a stub.
            if (padded.toSet().size == 1) return null
            return Os(padded)
        }
    }
}
