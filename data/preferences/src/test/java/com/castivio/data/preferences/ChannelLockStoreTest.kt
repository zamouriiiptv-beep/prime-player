package com.castivio.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The locked channels: kept in a real preferences file, and still there when the app is started again.
 *
 * "Started again" is a second store over the same file, which is exactly what a new process builds --
 * nothing is carried between the two but what was written to disc. The store does its work on a
 * background thread, so each test waits for what it expects instead of assuming it has happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChannelLockStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = context.getSharedPreferences("castivio_channel_locks", Context.MODE_PRIVATE)

    @Before
    fun freshInstall() {
        file.edit().clear().commit()
    }

    private fun store() = ChannelLockStore(context)

    /** The set, once the file has been read and [accept] is satisfied by it. */
    private fun ChannelLockStore.waitFor(accept: (Set<String>) -> Boolean): Set<String> = runBlocking {
        withTimeout(WAIT_MS) { locked.filterNotNull().first(accept) }
    }

    /** Waits until the file on disc, not the flow in memory, holds exactly [ids]. */
    private fun awaitOnDisc(ids: Set<String>) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (file.getStringSet("locked", null).orEmpty() != ids) {
            check(System.currentTimeMillis() < deadline) { "the file never held $ids" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `nothing is locked on a fresh install, and it says so only once the file has been read`() {
        val store = store()

        assertEquals(emptySet<String>(), store.waitFor { true })
    }

    @Test
    fun `a locked channel is in the set`() {
        val store = store()
        store.waitFor { true }

        store.lock("c1")

        assertEquals(setOf("c1"), store.waitFor { "c1" in it })
    }

    @Test
    fun `a lock is still there when the app is started again`() {
        val first = store()
        first.waitFor { true }
        first.lock("c1")
        first.lock("c2")
        awaitOnDisc(setOf("c1", "c2"))

        val afterRestart = store()

        assertEquals(setOf("c1", "c2"), afterRestart.waitFor { true })
    }

    @Test
    fun `an unlock is still there when the app is started again`() {
        val first = store()
        first.waitFor { true }
        first.lock("c1")
        first.lock("c2")
        first.unlock("c1")
        awaitOnDisc(setOf("c2"))

        val afterRestart = store()

        assertEquals(setOf("c2"), afterRestart.waitFor { true })
    }

    @Test
    fun `locking one channel locks that channel only`() {
        val store = store()
        store.waitFor { true }

        store.lock("c1")

        val locked = store.waitFor { "c1" in it }
        assertTrue("c2" !in locked)
        assertEquals(1, locked.size)
    }

    @Test
    fun `a lock followed at once by an unlock of the same channel ends unlocked`() {
        val store = store()
        store.waitFor { true }

        store.lock("c1")
        store.unlock("c1")
        store.lock("c2")

        assertEquals(setOf("c2"), store.waitFor { "c2" in it })
        awaitOnDisc(setOf("c2"))
    }

    @Test
    fun `a lock asked for before the file has been read is added to what was stored, not to nothing`() {
        file.edit().putStringSet("locked", setOf("old")).commit()

        val store = store()
        store.lock("new")

        assertEquals(setOf("old", "new"), store.waitFor { "new" in it })
    }

    @Test
    fun `locking a channel twice, or unlocking one that is not locked, changes nothing`() {
        val store = store()
        store.waitFor { true }
        store.lock("c1")
        store.waitFor { "c1" in it }

        store.lock("c1")
        store.unlock("never-locked")
        store.lock("c3")

        assertEquals(setOf("c1", "c3"), store.waitFor { "c3" in it })
    }

    @Test
    fun `the file holds ids and nothing else`() {
        val store = store()
        store.waitFor { true }
        store.lock("c1")
        awaitOnDisc(setOf("c1"))

        assertEquals(setOf("locked"), file.all.keys)
        assertNull(file.getString("pin_digest", null))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
