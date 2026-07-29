package com.castivio.data.activation

import android.content.Context
import android.provider.Settings
import com.castivio.domain.identity.DeviceIdentityAlgorithm
import com.castivio.domain.identity.DeviceIdentityV1
import com.castivio.domain.identity.IdentityProvenance
import com.castivio.domain.identity.IdentitySeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Android half of the identity: what survives what.
 *
 * The derivation itself is proved pure and pinned in `DeviceIdentityAlgorithmTest`.
 * What is left to prove here is the part that decides whether a paying customer keeps
 * their licence — which of a reinstall, a data wipe and a factory reset the address
 * lives through, and that nothing about the device changing its mind can move it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDeviceIdentityTest {

    private val sha256 = JvmSha256()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        setAndroidId("9a1c33f0b27e45d8")
        clearAppData()
    }

    private fun setAndroidId(value: String?) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, value)
    }

    /** Uninstalling, or "Clear storage": the app's own files go, the OS does not. */
    private fun clearAppData() {
        context.getSharedPreferences("castivio.identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun identity() = AndroidDeviceIdentity(context, sha256)

    // ------------------------------------------------------------- determinism

    @Test
    fun `the same device always answers the same address`() {
        val first = identity().current()

        repeat(8) {
            assertEquals(first, identity().current())
        }
    }

    @Test
    fun `the address is the one the pure derivation produces`() {
        val expected = DeviceIdentityV1.derive(IdentitySeed.Os("9a1c33f0b27e45d8"), sha256)

        val record = identity().current()

        assertEquals(expected, record.macAddress)
        assertEquals(DeviceIdentityAlgorithm.CURRENT, record.algorithmVersion)
        assertEquals(IdentityProvenance.DEVICE, record.provenance)
    }

    @Test
    fun `two devices do not share an address`() {
        val first = identity().current().macAddress

        setAndroidId("1122334455667788")
        clearAppData()

        assertNotEquals(first, identity().current().macAddress)
    }

    // ----------------------------------------------------------- what survives

    /**
     * Uninstall and reinstall. `ANDROID_ID` is scoped to our signing key and outlives
     * the app's storage, so the seed is re-read and the derivation lands on the same
     * six octets. The licence follows the device, and the user does nothing.
     */
    @Test
    fun `the address survives a reinstall`() {
        val before = identity().current()

        clearAppData()

        assertEquals(before, identity().current())
    }

    /** "Clear data" is the same event from the app's point of view, and ends the same way. */
    @Test
    fun `the address survives clearing app data`() {
        val before = identity().current().macAddress

        clearAppData()

        assertEquals(before, identity().current().macAddress)
    }

    /**
     * A factory reset issues a new `ANDROID_ID`, so the address changes. That is the
     * operating system declaring the device to be a new one, and it is the boundary the
     * licence server has to handle rather than the client — hence the recovery code.
     */
    @Test
    fun `a factory reset produces a new address`() {
        val before = identity().current().macAddress

        setAndroidId("00ff00ff00ff00ff")
        clearAppData()

        assertNotEquals(before, identity().current().macAddress)
    }

    /**
     * Once a seed is stored it is never re-read, so a ROM that changes its mind about
     * `ANDROID_ID` between boots — or an OS that reformats it — cannot move an
     * installed device onto a different licence.
     */
    @Test
    fun `the stored seed outranks the operating system changing its answer`() {
        val before = identity().current().macAddress

        setAndroidId("ffff0000ffff0001")

        assertEquals(before, identity().current().macAddress)
    }

    // ------------------------------------------------------ the weaker identity

    @Test
    fun `a degenerate operating system identifier falls back to a minted one`() {
        setAndroidId("9774d56d682e549c")
        clearAppData()

        val record = identity().current()

        assertEquals(IdentityProvenance.INSTALLATION, record.provenance)
        assertTrue(record.macAddress.isLocallyAdministered)
    }

    @Test
    fun `a missing operating system identifier falls back to a minted one`() {
        setAndroidId(null)
        clearAppData()

        assertEquals(IdentityProvenance.INSTALLATION, identity().current().provenance)
    }

    /** Weaker, but not unstable: it holds for as long as the app's data does. */
    @Test
    fun `a minted identity is stable while the app data lives`() {
        setAndroidId(null)
        clearAppData()
        val first = identity().current()

        repeat(4) { assertEquals(first, identity().current()) }
    }

    /**
     * And it is honest about its limit. This is the case the recovery code exists for,
     * which is why the provenance travels with the address to the licence server.
     */
    @Test
    fun `a minted identity does not survive clearing app data`() {
        setAndroidId(null)
        clearAppData()
        val before = identity().current().macAddress

        clearAppData()

        assertNotEquals(before, identity().current().macAddress)
    }

    /**
     * A device that started out minting its own identity keeps it even after the
     * operating system starts answering properly. Stability outranks provenance: an
     * address that improved itself is still an address the licence was not issued to.
     */
    @Test
    fun `a minted identity is not replaced when the operating system recovers`() {
        setAndroidId(null)
        clearAppData()
        val before = identity().current()

        setAndroidId("9a1c33f0b27e45d8")

        assertEquals(before, identity().current())
    }

    // ----------------------------------------------------------- the migration

    @Test
    fun `there is no legacy identity while only one version exists`() {
        identity().current()

        assertEquals(emptyList<Any>(), identity().legacy())
    }
}
