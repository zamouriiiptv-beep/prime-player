package com.castivio.data.preferences

import android.content.Context
import com.castivio.playback.api.EngineId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Which engine each source needed, between launches -- and the one-time forgetting of what an older build wrote.
 *
 * The rule for *what* is remembered is `FallbackPolicyTest`'s and runs without a device. What only a device can
 * show is the file: that an entry survives the app being started again, and that the file an earlier build wrote,
 * whose entries include sources the backup played only because the primary was slow, is gone and is never read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineMemoryStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val legacy get() = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
    private val current get() = context.getSharedPreferences(CURRENT_FILE, Context.MODE_PRIVATE)

    @Before
    fun freshInstall() {
        legacy.edit().clear().commit()
        current.edit().clear().commit()
    }

    private fun store() = EngineMemoryStore(context)

    @Test
    fun `a source the backup was needed for is the backup's after the app is started again`() {
        store().remember(KEY, EngineId.BACKUP)

        assertEquals(EngineId.BACKUP, store().preferred(KEY))
    }

    @Test
    fun `a source nobody has played has no engine remembered`() {
        assertNull(store().preferred(KEY))
    }

    @Test
    fun `the primary playing a source removes the row it was once remembered under`() {
        val store = store()
        store.remember(KEY, EngineId.BACKUP)

        store.remember(KEY, EngineId.PRIMARY)

        assertNull(store.preferred(KEY))
        assertFalse("the primary is the default, so it is not written down", current.contains(KEY))
    }

    // ------------------------------------------------------------------ what an older build wrote

    /** Entries made when a slow start was written down as a verdict cannot be told from the real ones, so none is kept. */
    @Test
    fun `what an older build remembered is not read`() {
        legacy.edit().putString(KEY, "backup").putString(OTHER_KEY, "backup").commit()

        val store = store()

        assertNull("a verdict made when a timeout was one", store.preferred(KEY))
        assertNull(store.preferred(OTHER_KEY))
    }

    @Test
    fun `the older file is removed at the first read, and only that one`() {
        legacy.edit().putString(KEY, "backup").commit()
        val parental = context.getSharedPreferences(OTHER_FILE, Context.MODE_PRIVATE)
        parental.edit().putString("pin", "kept").commit()

        store().preferred(KEY)

        assertTrue("the older file is empty once the store has read", legacy.all.isEmpty())
        assertEquals("a preference file that is not this store's is not touched", "kept", parental.getString("pin", null))
    }

    @Test
    fun `entries made after the reset are kept, and the older file does not come back`() {
        legacy.edit().putString(KEY, "backup").commit()
        val first = store()
        first.preferred(KEY)

        first.remember(OTHER_KEY, EngineId.BACKUP)
        val afterRestart = store()

        assertNull(afterRestart.preferred(KEY))
        assertEquals(EngineId.BACKUP, afterRestart.preferred(OTHER_KEY))
        assertTrue(legacy.all.isEmpty())
    }

    private companion object {
        const val LEGACY_FILE = "castivio.engines"
        const val CURRENT_FILE = "castivio.engines.v2"
        const val OTHER_FILE = "castivio_parental"
        const val KEY = "http://provider.tv/live/1.ts"
        const val OTHER_KEY = "http://provider.tv/live/2.ts"
    }
}
