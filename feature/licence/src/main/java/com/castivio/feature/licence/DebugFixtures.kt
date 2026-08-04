package com.castivio.feature.licence

/**
 * The values a debug build shows so a composition can be judged on a device, and
 * a release build cannot reach.
 *
 * Deliberately a second copy of the activation screen's fixture rather than a
 * shared one, and the reason is the boundary rather than tidiness: `BuildConfig`
 * is generated per module, so a shared fixture would have to be gated on
 * somebody else's `DEBUG` flag. A fixture whose release-safety depends on which
 * module happened to compile it is not a fixture, it is a leak waiting for a
 * refactor.
 *
 * There is nothing to keep in step: neither is a value the product produces.
 */
internal object DebugFixtures {

    /**
     * The six-digit key, in a debug build only.
     *
     * Null in release, which is the honest answer there: no contract issues one
     * yet, so there is nothing to show and the capsule is not composed.
     */
    fun deviceKey(): String? = if (BuildConfig.DEBUG) DEVICE_KEY else null

    /** Six decimal digits, one group, no separator — the final format. */
    private const val DEVICE_KEY = "482731"
}
