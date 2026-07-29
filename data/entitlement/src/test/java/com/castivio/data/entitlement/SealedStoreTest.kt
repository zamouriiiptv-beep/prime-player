package com.castivio.data.entitlement

import android.content.Context
import com.castivio.core.common.AppDispatchers
import com.castivio.domain.entitlement.EntitlementRecord
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.StorageFault
import com.castivio.domain.entitlement.StoredEntitlement
import com.castivio.domain.identity.MacAddress
import com.castivio.domain.time.ClockState
import com.castivio.domain.time.TimeAnchor
import com.castivio.domain.time.TimeAnchorSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/**
 * The licence on disk: what survives, and what happens when someone has been at it.
 *
 * A real [AesGcmVault] with an ordinary key, because the cipher is the part that decides
 * whether an edit is detected and swapping it for a fake here would test the fake. The
 * only thing stubbed is where the key comes from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SealedStoreTest {

    private val t0 = 1_772_323_200_000L
    private val day = 24L * 60 * 60 * 1000

    private lateinit var context: Context

    /**
     * Unconfined, not a `TestDispatcher`: one built outside `runTest` carries its own
     * scheduler and every suspending test fails with "detected use of different
     * schedulers". Nothing here depends on scheduling anyway — it is a file, read and
     * written inline.
     */
    private val dispatchers = object : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences(SealedStore.FILE, Context.MODE_PRIVATE)

    private fun store(keySeed: Byte = 1) =
        SealedStore(prefs(), AesGcmVault { SecretKeySpec(ByteArray(32) { keySeed }, "AES") })

    private val record = EntitlementRecord(
        macAddress = MacAddress.parse("2F:19:EB:20:44:7C")!!,
        identityVersion = 1,
        plan = Plan.TRIAL,
        trialStartedAtMs = t0,
        trialExpiresAtMs = t0 + 7 * day,
        establishedAtMs = t0,
        maxObservedTimeMs = t0,
    )

    // ------------------------------------------------------------- the entitlement

    @Test
    fun `a record survives being written and read back`() = runTest {
        val store = SealedEntitlementStore(store(), dispatchers)

        store.write(record)

        assertEquals(StoredEntitlement.Present(record), store.read())
    }

    @Test
    fun `a record survives the object being rebuilt`() = runTest {
        SealedEntitlementStore(store(), dispatchers).write(record)

        assertEquals(record, SealedEntitlementStore(store(), dispatchers).read().record)
    }

    @Test
    fun `nothing stored reads as nothing`() = runTest {
        assertEquals(StoredEntitlement.None, SealedEntitlementStore(store(), dispatchers).read())
    }

    @Test
    fun `clearing removes it`() = runTest {
        val store = SealedEntitlementStore(store(), dispatchers)
        store.write(record)

        store.clear()

        assertEquals(StoredEntitlement.None, store.read())
    }

    // ---------------------------------------------------------------- the sealing

    /** What lands on disk must not be the record with a different coat of paint. */
    @Test
    fun `the stored value does not contain the record`() = runTest {
        SealedEntitlementStore(store(), dispatchers).write(record)

        val onDisk = prefs().getString(SealedStore.KEY_ENTITLEMENT, "")!!

        assertEquals(false, onDisk.contains("TRIAL"))
        assertEquals(false, onDisk.contains("2F:19:EB"))
        assertEquals(false, onDisk.contains(record.trialExpiresAtMs.toString()))
    }

    /**
     * The attack this file is for: edit the blob to move the end of the trial. GCM makes
     * the edit fail to open — and the result is reported as *unreadable*, not as absent,
     * so a production build asks the server again instead of handing out a fresh trial.
     */
    @Test
    fun `an edited blob reads as unreadable, not as absent`() = runTest {
        SealedEntitlementStore(store(), dispatchers).write(record)

        val edited = prefs().getString(SealedStore.KEY_ENTITLEMENT, "")!!.let { encoded ->
            // Flip a character in the middle of the ciphertext.
            val middle = encoded.length / 2
            encoded.take(middle) + (if (encoded[middle] == 'A') 'B' else 'A') + encoded.drop(middle + 1)
        }
        prefs().edit().putString(SealedStore.KEY_ENTITLEMENT, edited).commit()

        assertEquals(
            StoredEntitlement.Unreadable(StorageFault.UNSEALABLE),
            SealedEntitlementStore(store(), dispatchers).read(),
        )
    }

    /** A key that no longer exists — a keystore reset, a restore onto another device. */
    @Test
    fun `a blob sealed with a lost key reads as unreadable`() = runTest {
        SealedEntitlementStore(store(keySeed = 1), dispatchers).write(record)

        assertEquals(
            StoredEntitlement.Unreadable(StorageFault.UNSEALABLE),
            SealedEntitlementStore(store(keySeed = 2), dispatchers).read(),
        )
    }

    /**
     * And it is **kept**, not deleted.
     *
     * Deleting it would erase the only evidence that this device was ever licensed, and
     * the next launch would find nothing and conclude the user never had anything —
     * which is the wrong sentence for them and a free trial for whoever edited the file.
     * A failed GCM open costs microseconds; a wrongly granted licence costs a customer.
     */
    @Test
    fun `an unreadable blob is kept so the next launch still knows something was there`() = runTest {
        SealedEntitlementStore(store(keySeed = 1), dispatchers).write(record)

        repeat(3) { SealedEntitlementStore(store(keySeed = 2), dispatchers).read() }

        assertNotEquals(null, prefs().getString(SealedStore.KEY_ENTITLEMENT, null))
        assertEquals(
            StoredEntitlement.Unreadable(StorageFault.UNSEALABLE),
            SealedEntitlementStore(store(keySeed = 2), dispatchers).read(),
        )
    }

    /** Writing over it clears the fault: the recovery path leaves no residue. */
    @Test
    fun `writing a good record replaces an unreadable one`() = runTest {
        SealedEntitlementStore(store(keySeed = 1), dispatchers).write(record)
        val recovered = SealedEntitlementStore(store(keySeed = 2), dispatchers)

        recovered.write(record)

        assertEquals(StoredEntitlement.Present(record), recovered.read())
    }

    @Test
    fun `rubbish in the file reads as unreadable`() = runTest {
        prefs().edit().putString(SealedStore.KEY_ENTITLEMENT, "!!! not base64 !!!").commit()

        assertEquals(
            StoredEntitlement.Unreadable(StorageFault.UNSEALABLE),
            SealedEntitlementStore(store(), dispatchers).read(),
        )
    }

    /**
     * The other fault: it opened, so the key is right and nobody edited it — what came
     * out simply is not a record this build understands. Worth telling apart, because
     * the causes are different and a diagnostic that conflates them helps nobody.
     */
    @Test
    fun `a blob that opens but does not decode is a different fault`() = runTest {
        store().write(SealedStore.KEY_ENTITLEMENT, "format=v1\nplan=TRIAL\n".encodeToByteArray())

        assertEquals(
            StoredEntitlement.Unreadable(StorageFault.UNDECODABLE),
            SealedEntitlementStore(store(), dispatchers).read(),
        )
    }

    // -------------------------------------------------------------------- the clock

    @Test
    fun `the clock state survives being written and read back`() {
        val state = ClockState(
            highWaterMarkMs = t0,
            anchor = TimeAnchor(t0, 90_000L, "boot-a", TimeAnchorSource.LICENCE_SERVER),
        )
        val clockStore = SealedClockStore(store())

        clockStore.save(state)

        assertEquals(state, SealedClockStore(store()).load())
    }

    @Test
    fun `a device with no history loads a fresh clock`() {
        assertEquals(ClockState(), SealedClockStore(store()).load())
    }

    /**
     * The mark is what stops a rolled-back clock buying a second week, so it is the one
     * value written all the way to disk rather than queued — a power cut between the
     * write and the flush would hand back exactly the time that was just spent.
     */
    @Test
    fun `the mark is on disk before save returns`() {
        SealedClockStore(store()).save(ClockState(highWaterMarkMs = t0 + 30 * day))

        // Read through a brand new preferences handle: nothing of the writer survives.
        val reloaded = context.getSharedPreferences(SealedStore.FILE, Context.MODE_PRIVATE)
            .getString(SealedStore.KEY_CLOCK, null)

        assertNotEquals(null, reloaded)
        assertEquals(
            t0 + 30 * day,
            SealedClockStore(store()).load().highWaterMarkMs,
        )
    }

    @Test
    fun `the clock is cached rather than decrypted on every reading`() {
        val clockStore = SealedClockStore(store())
        clockStore.save(ClockState(highWaterMarkMs = t0))

        // Remove the file underneath it. A cached reader keeps answering; one that went
        // back to disk every time would forget.
        prefs().edit().remove(SealedStore.KEY_CLOCK).commit()

        assertEquals(t0, clockStore.load().highWaterMarkMs)
    }

    @Test
    fun `an edited clock blob reads as a fresh clock`() {
        SealedClockStore(store()).save(ClockState(highWaterMarkMs = t0 + 30 * day))
        prefs().edit().putString(SealedStore.KEY_CLOCK, "tampered").commit()

        assertEquals(ClockState(), SealedClockStore(store()).load())
    }

    /** The two live in one file, and writing one must not disturb the other. */
    @Test
    fun `the record and the clock do not overwrite each other`() = runTest {
        val store = store()
        SealedEntitlementStore(store, dispatchers).write(record)
        SealedClockStore(store).save(ClockState(highWaterMarkMs = t0 + day))

        assertEquals(record, SealedEntitlementStore(store, dispatchers).read().record)
        assertEquals(t0 + day, SealedClockStore(store).load().highWaterMarkMs)
    }
}
