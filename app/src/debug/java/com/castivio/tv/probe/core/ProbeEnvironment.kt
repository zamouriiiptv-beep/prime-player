package com.castivio.tv.probe.core

/**
 * What the device says about the network at one moment.
 *
 * [handle] tells two networks apart and is never printed; [transport] is a word ("wifi", "cellular", ...).
 */
data class NetworkSnapshot(
    val handle: String?,
    val transport: String,
    val metered: Boolean?,
) {
    fun note(changed: Boolean = false): NetworkNote = NetworkNote(transport, metered, changed)

    fun sameNetworkAs(other: NetworkSnapshot): Boolean = handle == other.handle && transport == other.transport
}

/**
 * The two things the probe asks of the platform, behind an interface so that nothing else in the tool
 * touches an Android class and a test can answer for the device.
 */
interface ProbeEnvironment {
    /** Bytes this application's uid has received so far, or null where the platform does not count them. */
    fun uidRxBytes(): Long?

    fun network(): NetworkSnapshot
}
