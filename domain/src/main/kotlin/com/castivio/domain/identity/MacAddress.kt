package com.castivio.domain.identity

/**
 * A device identity written the way a set-top box writes one: six octets, upper case
 * hexadecimal, colon separated — `2F:19:EB:20:44:7C`.
 *
 * The form matters more than it looks. This is the string a user reads off a
 * television and sends to a provider by hand, so it is short, unambiguous when spoken,
 * and already familiar to anyone who has set up an IPTV box before. It is *not* the
 * hardware address of any network interface, and Castivio never reads one — see
 * [DeviceIdentityV1] for where these six octets actually come from.
 *
 * Constructed only through [parse] and [of], so a value of this type is always
 * canonical and comparing two of them is comparing two strings.
 */
@JvmInline
value class MacAddress private constructor(val value: String) {

    /** The same octets with no separators: `2F19EB20447C`. Some panels want this. */
    val compact: String get() = value.replace(":", "")

    /** Lower case with colons: `2f:19:eb:20:44:7c`. What most portal panels display. */
    val lowerCase: String get() = value.lowercase()

    /**
     * True when the address is in the range IEEE reserves for addresses that are not
     * assigned by a hardware vendor. Every address Castivio derives is, which is what
     * guarantees it can never collide with a real network card.
     */
    val isLocallyAdministered: Boolean get() = firstOctet and 0x02 != 0

    /** True when the address names one device rather than a group. */
    val isUnicast: Boolean get() = firstOctet and 0x01 == 0

    private val firstOctet: Int get() = value.substring(0, 2).toInt(16)

    override fun toString(): String = value

    companion object {
        private const val HEX = "0123456789ABCDEF"

        /**
         * Accepts every spelling in the wild — colons, hyphens, spaces, bare hex, any
         * case — and returns the canonical one. Null when the text is not a MAC.
         */
        fun parse(text: String): MacAddress? {
            val hex = text.trim().uppercase().filterNot { it == ':' || it == '-' || it == '.' || it == ' ' }
            if (hex.length != 12 || hex.any { it !in HEX }) return null
            return MacAddress(hex.chunked(2).joinToString(":"))
        }

        /** Builds an address from exactly six octets. */
        fun of(octets: ByteArray): MacAddress {
            require(octets.size == 6) { "A MAC address is six octets, not ${octets.size}" }
            return MacAddress(
                octets.joinToString(":") { octet ->
                    val value = octet.toInt() and 0xFF
                    "${HEX[value ushr 4]}${HEX[value and 0x0F]}"
                },
            )
        }
    }
}
