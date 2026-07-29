package com.castivio.data.entitlement

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * The cipher, tested without a keystore.
 *
 * The key arrives through a lambda precisely so this file can exist: on a real device it
 * comes from `AndroidKeyStore`, which no JVM has, and here it is an ordinary array of
 * bytes. What is proved is everything that is not key management — the envelope, the
 * nonce, the tag, and what happens to a blob somebody has edited.
 */
class AesGcmVaultTest {

    private fun key(seed: Byte = 1) = SecretKeySpec(ByteArray(32) { seed }, "AES")

    private fun vault(seed: Byte = 1) = AesGcmVault { key(seed) }

    private val plain = "plan=LIFETIME\nmaxObservedTime=1772323200000\n".encodeToByteArray()

    @Test
    fun `what goes in comes out`() {
        val vault = vault()

        assertArrayEquals(plain, vault.open(vault.seal(plain)))
    }

    @Test
    fun `an empty payload round trips`() {
        val vault = vault()

        assertArrayEquals(ByteArray(0), vault.open(vault.seal(ByteArray(0))))
    }

    @Test
    fun `a large payload round trips`() {
        val vault = vault()
        val large = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }

        assertArrayEquals(large, vault.open(vault.seal(large)))
    }

    /** A fresh nonce every time, so identical records do not produce identical files. */
    @Test
    fun `sealing the same bytes twice produces different blobs`() {
        val vault = vault()

        val first = vault.seal(plain)
        val second = vault.seal(plain)

        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(plain, vault.open(first))
        assertArrayEquals(plain, vault.open(second))
    }

    @Test
    fun `the plain text is not visible in the blob`() {
        val sealed = vault().seal(plain).decodeToString()

        assertEquals(false, sealed.contains("LIFETIME"))
    }

    // ------------------------------------------------------------------ tampering

    /**
     * The reason this is GCM and not CTR. An edited blob has to *fail*, not decrypt into
     * a different expiry — the whole point is that someone with a hex editor cannot move
     * the end of their trial.
     */
    @Test
    fun `a single edited byte makes the blob unreadable`() {
        val vault = vault()
        val sealed = vault.seal(plain)

        for (index in sealed.indices) {
            val edited = sealed.copyOf().also { it[index] = (it[index] + 1).toByte() }

            assertNull("byte $index", vault.open(edited))
        }
    }

    @Test
    fun `a truncated blob is unreadable`() {
        val vault = vault()
        val sealed = vault.seal(plain)

        assertNull(vault.open(sealed.copyOf(sealed.size - 1)))
        assertNull(vault.open(sealed.copyOf(8)))
        assertNull(vault.open(ByteArray(0)))
    }

    @Test
    fun `a blob from another key is unreadable`() {
        val sealed = vault(seed = 1).seal(plain)

        assertNull(vault(seed = 2).open(sealed))
    }

    /**
     * The first byte is the envelope version, so a future cipher is a different byte
     * rather than a silent misparse of the same one.
     */
    @Test
    fun `a blob from an unknown envelope is unreadable`() {
        val vault = vault()
        val sealed = vault.seal(plain).also { it[0] = 9 }

        assertNull(vault.open(sealed))
    }

    @Test
    fun `rubbish is unreadable rather than fatal`() {
        val vault = vault()

        assertNull(vault.open("not a sealed blob at all, just some text".encodeToByteArray()))
        assertNull(vault.open(ByteArray(64)))
    }
}
