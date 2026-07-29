package com.castivio.data.entitlement

import android.content.Context
import com.castivio.core.common.AppDispatchers
import com.castivio.data.activation.AndroidClockSignals
import com.castivio.data.activation.AndroidDeviceIdentity
import com.castivio.data.activation.JvmSha256
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.Licensing
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.PricingDefaults
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.entitlement.StoredEntitlement
import com.castivio.domain.entitlement.startDestination
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.time.ClockStore
import com.castivio.domain.time.MonotonicClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.SecretKey

/**
 * The first thing that happens when somebody installs Castivio, run end to end.
 *
 * This test exists because the app crashed on first launch of a real device while every
 * other test in the repository was green. Each piece of the startup path was covered on
 * its own with its awkward collaborator replaced — and the defect was in the one seam
 * nothing joined up: the real cipher talking to a real keystore key.
 *
 * So this joins them up. Everything below is the production class: the real device
 * identity, the real clock signals, the real monotonic clock, the real sealed stores, the
 * real AES-GCM vault, the real repository, and [startDestination] from `:domain`. Two
 * things are not the production ones, and both are named honestly:
 *
 *  - **The key** comes from [KeystoreLikeCipher] rather than [VaultKeys], because no JVM
 *    has an `AndroidKeyStore`. What it does have is the *contract* — an opaque key that
 *    refuses a caller-supplied nonce — which is the part that broke.
 *  - **The wiring** is written out by hand rather than resolved by Hilt. That is this
 *    test's remaining blind spot, and it is a narrow one: the graph is `@Provides`
 *    functions with no conditionals in them, and a missing binding fails the build rather
 *    than the device.
 *
 * The assertion that matters most is the last line of the first test. A debug build on a
 * device that has never been activated must open the MAC screen — which is exactly what a
 * user reported it not doing.
 *
 * `AndroidKeyStore` itself is still only provable on hardware. That instrumented test is
 * a release gate in `RELEASE_CHECKLIST.md`, and this is what can be held without one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstLaunchTest {

    /**
     * The cipher behaves like a keystore for the length of the test. Without this the
     * whole file passes against the `seal` that crashed every phone.
     */
    @get:Rule
    val keystore = KeystoreLikeCipher()

    private lateinit var context: Context

    /**
     * Unconfined rather than a `TestDispatcher`: one built outside `runTest` carries its
     * own scheduler and every suspending test then fails on mismatched schedulers.
     * Nothing here depends on scheduling — it is a file, read and written inline.
     */
    private val dispatchers = object : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun virginDevice() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(SealedStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(IDENTITY_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ------------------------------------------------------------------ first launch

    @Test
    fun `a device nobody has ever activated reaches the activation screen`() = runTest {
        val startup = Startup(context, keystore.key, dispatchers)

        val entitlement = startup.repository.establish()

        assertTrue("$entitlement", entitlement is EntitlementState.TrialActive)
        assertTrue("$entitlement", entitlement.allowsUse)

        // No provider registered yet, so gate two sends the user to activation. This is
        // the screen the crash was standing in front of.
        assertEquals(StartDestination.Activation, startDestination(entitlement, source = null))
    }

    @Test
    fun `the trial is written down where the next launch will find it`() = runTest {
        val startup = Startup(context, keystore.key, dispatchers)
        startup.repository.establish()

        // Read through a second store over the same file: what is asserted is what
        // reached the disk, not what an object happened to be holding.
        val stored = Startup(context, keystore.key, dispatchers).store.read()

        assertTrue("$stored", stored is StoredEntitlement.Present)
        val record = (stored as StoredEntitlement.Present).record
        assertEquals(Plan.TRIAL, record.plan)
        assertEquals(startup.identity.current().macAddress, record.macAddress)
        assertNotNull(record.trialExpiresAtMs)
    }

    /**
     * The clock's high-water mark is the first thing the startup path seals, and it was
     * the call that actually threw on the device — before the entitlement record, before
     * anything was drawn.
     */
    @Test
    fun `the clock remembers how far time had got`() = runTest {
        Startup(context, keystore.key, dispatchers).repository.establish()

        val remembered = Startup(context, keystore.key, dispatchers).clockStore.load()

        assertTrue("${remembered.highWaterMarkMs}", remembered.highWaterMarkMs > 0)
    }

    /**
     * Relaunching is not re-activating. The same file, the same key, a whole new object
     * graph — a second free trial must not fall out of asking twice, and the answer must
     * be identical rather than merely also-valid.
     */
    @Test
    fun `the second launch reads the same trial back rather than granting another`() = runTest {
        val first = Startup(context, keystore.key, dispatchers).repository.establish()
        val second = Startup(context, keystore.key, dispatchers).repository.establish()

        assertEquals(first, second)
    }

    /**
     * And the address is stable, because it is the licence's name for this device. A
     * startup that minted a new one on every launch would be a new customer every launch.
     */
    @Test
    fun `the device keeps the same address across launches`() {
        val first = Startup(context, keystore.key, dispatchers).identity.current()
        val second = Startup(context, keystore.key, dispatchers).identity.current()

        assertEquals(first.macAddress, second.macAddress)
        assertEquals(first.algorithmVersion, second.algorithmVersion)
    }

    private companion object {
        /** `AndroidDeviceIdentity.STORE`, which is private to it and has to be. */
        const val IDENTITY_FILE = "castivio.identity"
    }
}

/**
 * The object graph `EntitlementModule` builds, written out so a test can hold it.
 *
 * Constructed fresh per launch on purpose: everything that must survive a restart has to
 * survive it through the preferences file, which is the only thing shared between two of
 * these.
 */
private class Startup(
    context: Context,
    key: SecretKey,
    dispatchers: AppDispatchers,
) {
    private val prefs = context.getSharedPreferences(SealedStore.FILE, Context.MODE_PRIVATE)
    private val sealed = SealedStore(prefs, AesGcmVault { key })
    private val config = PricingDefaults.config

    val store = SealedEntitlementStore(sealed, dispatchers)
    val clockStore: ClockStore = SealedClockStore(sealed)
    val identity: DeviceIdentity = AndroidDeviceIdentity(context, JvmSha256())

    val repository: EntitlementRepository = DefaultEntitlementRepository(
        store = store,
        identity = identity,
        clock = MonotonicClock(AndroidClockSignals(), clockStore),
        config = config,
        // A debug build, which is what the APK under test is. Production has nowhere to
        // put a trial grantor, so this is the only shape that can grant one.
        licensing = Licensing.Development(trials = LocalEntitlementSource(config), source = null),
    )
}
