package com.castivio.feature.activation

/**
 * Values a debug build shows so the approved composition can be judged on a real
 * device, and that a release build cannot reach.
 *
 * The rule these exist under is narrow and worth stating exactly. `482731` is
 * **not** a device key. Nothing derives it — not from the MAC, not from a random
 * source — and no temporary issuing scheme was stood up to make the row work. It
 * is a constant, in one file, behind `BuildConfig.DEBUG`, so that the screen the
 * design approved is the screen that goes on a phone. See
 * `design/activation-spec.md` §4.2 and §5.3.1.
 *
 * When the issuing contract exists, the key arrives from it and this file goes.
 * Not extended, not made conditional on a second flag: a fixture with two ways to
 * be reached is a fixture that eventually is.
 */
internal object DebugFixtures {

    /**
     * The six-digit key, in a debug build only.
     *
     * Null in release, which is the honest answer there: no contract issues one
     * yet, so there is nothing to show and the row is not composed. That is a
     * different thing from the debug build, where the row is present precisely so
     * the composition can be reviewed.
     */
    fun deviceKey(): String? = if (BuildConfig.DEBUG) DEVICE_KEY else null

    /** Six decimal digits, one group, no separator — the final format. */
    private const val DEVICE_KEY = "482731"
}
