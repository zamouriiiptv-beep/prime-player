package com.castivio.domain.identity

/**
 * The short code a user reads out, or photographs, and sends to their provider.
 *
 * It is **not** the identity. [MacAddress] is what a licence is issued against and
 * what the licence server decides entitlements for; this is a human-sized handle on
 * the same device, for the half of the IPTV world whose panels ask for a "device key",
 * a "serial" or a "device ID" beside the address. A user who cannot produce one
 * cannot be activated, and telling them to find it in Settings fails exactly the user
 * who needed it printed on the screen in the first place.
 *
 * Derived, never generated, from the same seed [DeviceIdentityV1] derives the address
 * from — so it is the same code on every launch of every install, it survives a
 * reinstall wherever the address does, and a support tool or a licence server can
 * reproduce it from the seed without the device being present.
 */
@JvmInline
value class DeviceKey(val value: String) {
    override fun toString(): String = value
}

/**
 * **DeviceKey algorithm v1.**
 *
 * ```
 * material := "castivio/device-key/v1" ‖ "\n" ‖ seed.material
 * digest   := SHA-256(UTF-8(material))
 * bits     := digest[0 .. 4]                      // exactly 40
 * symbols  := Crockford base-32 of bits, most significant first   // exactly 8
 * key      := symbols[0..3] ‖ "-" ‖ symbols[4..7]
 * ```
 *
 * ### Why 40 bits and not eight decimal digits
 *
 * The approved drawing showed `4827-3159`, and eight decimal digits is a hundred
 * million values — which sounds ample and is not. Collisions among random draws start
 * at the square root of the space, so two Castivio devices would begin sharing a key
 * at around ten thousand installs. The same eight characters over a 32-symbol
 * alphabet is 2^40, about 1.1 trillion, and the birthday bound moves out past a
 * million devices. The shape the drawing asked for is kept exactly — two groups of
 * four, one hyphen — and only the alphabet is wider.
 *
 * Crockford's base 32 is the alphabet because this value is *read aloud and typed by
 * hand*: it drops I, L, O and U, so there is no one/ell, zero/oh or accidental
 * profanity to misread. Thirty-two symbols also divide 40 bits exactly, so there is
 * no padding and no truncated final symbol.
 *
 * ### Why this is a different label
 *
 * [DeviceIdentityV1] reserves the label mechanism for exactly this: "a future
 * derivation over the same seed — a device key, a portal token — cannot collide with
 * this one". The label differs, so the key cannot be turned back into the address and
 * knowing one says nothing about the other, although both are derived from the same
 * material.
 *
 * ### Changing this
 *
 * The same rule [DeviceIdentityV1] states. A device whose provider has recorded its
 * key does not get a new one because we improved the format; a new format is
 * `DeviceKeyV2` beside this, with its own label and its own entry in
 * [DeviceKeyAlgorithm].
 */
object DeviceKeyV1 {

    const val VERSION: Int = 1

    /** Frozen. One character of difference is a different key on every device. */
    const val LABEL: String = "castivio/device-key/v1"

    /**
     * Crockford's base 32: the digits, then the letters without I, L, O and U.
     *
     * Frozen with the label. Reordering it silently re-mints every key in the field.
     */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun derive(seed: IdentitySeed, sha256: Sha256): DeviceKey {
        val digest = sha256.digest("$LABEL\n${seed.material}".encodeToByteArray())
        require(digest.size >= BYTES) {
            "SHA-256 returns 32 bytes; this implementation returned ${digest.size}"
        }

        // The first five bytes as one 40-bit number, most significant byte first, so
        // the rendering below is a plain base conversion rather than a bit-order
        // convention a second implementation would have to guess at.
        var bits = 0L
        for (index in 0 until BYTES) {
            bits = (bits shl 8) or (digest[index].toLong() and 0xFF)
        }

        val symbols = CharArray(SYMBOLS)
        for (index in 0 until SYMBOLS) {
            val shift = BITS_PER_SYMBOL * (SYMBOLS - 1 - index)
            symbols[index] = ALPHABET[((bits ushr shift) and SYMBOL_MASK).toInt()]
        }

        val text = String(symbols)
        return DeviceKey(text.substring(0, GROUP) + SEPARATOR + text.substring(GROUP))
    }

    /** Five bytes is forty bits, which is eight symbols with nothing left over. */
    private const val BYTES = 5
    private const val SYMBOLS = 8
    private const val BITS_PER_SYMBOL = 5
    private const val SYMBOL_MASK = 31L
    private const val GROUP = 4
    private const val SEPARATOR = "-"
}

/**
 * The key versions that exist, and the one in force.
 *
 * The same indirection [DeviceIdentityAlgorithm] has, for the same reason: the day a
 * v2 is needed, a device must still be able to reproduce the key its provider already
 * has on file.
 */
object DeviceKeyAlgorithm {

    /** The version new derivations use. */
    const val CURRENT: Int = DeviceKeyV1.VERSION

    /** Every version that can still be reproduced, oldest first. */
    val known: List<Int> = listOf(DeviceKeyV1.VERSION)

    /**
     * @throws IllegalArgumentException for a version this build does not know, which
     *   means app data written by a newer build. Louder than guessing.
     */
    fun derive(seed: IdentitySeed, sha256: Sha256, version: Int = CURRENT): DeviceKey =
        when (version) {
            DeviceKeyV1.VERSION -> DeviceKeyV1.derive(seed, sha256)
            else -> throw IllegalArgumentException("Unknown device key algorithm v$version")
        }
}
