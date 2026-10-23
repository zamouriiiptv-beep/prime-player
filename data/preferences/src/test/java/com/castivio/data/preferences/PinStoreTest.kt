package com.castivio.data.preferences

import android.content.Context
import com.castivio.domain.PinChange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The password: 0000 on a fresh install, and, once changed, the new one -- also after the app is started
 * again, which is a second store over the same file.
 *
 * The rules are `PinKeeperTest`'s and run without a device. What is asserted here is what only a device can
 * show: that the file keeps the change, that SHA-256 behaves as the keeper assumes of its digest, and that
 * the digits are not what is written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = context.getSharedPreferences("castivio_parental", Context.MODE_PRIVATE)

    @Before
    fun freshInstall() {
        file.edit().clear().commit()
    }

    private fun store() = PinStore(PrefsPinSlot(context), Sha256PinDigest())

    @Test
    fun `a fresh install has the default password and no file to read`() = runBlocking {
        val store = store()

        assertTrue(store.matches("0000"))
        assertFalse(store.matches("1234"))
        assertTrue("nothing is written until the password is changed", file.all.isEmpty())
    }

    @Test
    fun `a changed password is the one asked for after the app is started again`() = runBlocking {
        assertEquals(PinChange.Changed, store().change(current = "0000", new = "4821"))

        val afterRestart = store()

        assertTrue(afterRestart.matches("4821"))
        assertFalse("the default no longer opens it", afterRestart.matches("0000"))
    }

    @Test
    fun `a password can be changed again after a restart, with the one that was set`() = runBlocking {
        store().change("0000", "4821")

        val afterRestart = store()
        assertEquals(PinChange.WrongCurrent, afterRestart.change(current = "0000", new = "1357"))
        assertEquals(PinChange.Changed, afterRestart.change(current = "4821", new = "1357"))

        assertTrue(store().matches("1357"))
    }

    @Test
    fun `the digits are not what is written`() = runBlocking {
        store().change("0000", "4821")

        assertEquals(setOf("pin_salt", "pin_digest"), file.all.keys)
        val digest = file.getString("pin_digest", null)!!
        assertEquals("a SHA-256 digest, in hexadecimal", 64, digest.length)
        assertNotEquals("4821", digest)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `a digest is the same for the same salt and digits and differs for another salt`() {
        val digest = Sha256PinDigest()

        assertEquals(digest.of("salt", "4821"), digest.of("salt", "4821"))
        assertNotEquals(digest.of("salt", "4821"), digest.of("salt", "4822"))
        assertNotEquals(digest.of("salt-a", "4821"), digest.of("salt-b", "4821"))
    }

    @Test
    fun `salts are long and are not repeated`() {
        val digest = Sha256PinDigest()

        val salts = List(50) { digest.newSalt() }

        assertTrue(salts.all { it.length == 32 })
        assertEquals(50, salts.toSet().size)
    }
}
