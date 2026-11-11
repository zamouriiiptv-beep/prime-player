package com.castivio.core.common.config

/**
 * Where the app asks the Castivio portal for what its owner sent from the website.
 *
 * ## Why a value, when [ActivationDestination] is a constant
 *
 * That one is printed on a screen and encoded in a QR code, and the two must never
 * disagree, so it is written once. This is an address the app *calls*, and which one
 * depends on the build: a debug APK asks the test deployment the website is being built
 * on, and a release APK asks the product's own host. `:app` states which, from
 * `BuildConfig`, and nothing below it knows the difference.
 *
 * **Blank means the product's own address.** The one statement of that address is
 * [PRODUCTION], here, and not a second copy in a build script that could drift from it.
 *
 * Plain Kotlin, like everything in this module: a platform that is not Android asks the
 * same portal at the same address.
 */
class PortalConfig private constructor(val baseUrl: String) {

    /** The address of [path] under the portal's API. */
    fun api(path: String): String = "$baseUrl/api/v1/${path.trimStart('/')}"

    override fun equals(other: Any?): Boolean = other is PortalConfig && other.baseUrl == baseUrl

    override fun hashCode(): Int = baseUrl.hashCode()

    override fun toString(): String = "PortalConfig($baseUrl)"

    companion object {

        /**
         * The product's address. The host is final, as [ActivationDestination] says of the
         * same one; this is the root of the portal's API on it, not a page.
         */
        const val PRODUCTION: String = "https://castivio.app"

        /**
         * @param configured what the build says, or null/blank for the product's own address.
         * @throws IllegalArgumentException for something that is not a web address, which is a
         *   mistake in a build script and is better stopped at start-up than at the first press.
         */
        fun of(configured: String?): PortalConfig {
            val trimmed = configured?.trim()?.trimEnd('/').orEmpty()
            if (trimmed.isEmpty()) return PortalConfig(PRODUCTION)
            require(trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
                "The portal address must start with https:// or http://, not \"$trimmed\""
            }
            return PortalConfig(trimmed)
        }
    }
}
