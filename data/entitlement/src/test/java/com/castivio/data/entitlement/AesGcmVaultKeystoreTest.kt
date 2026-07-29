package com.castivio.data.entitlement

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault against a key that behaves like the one it will actually be given.
 *
 * [AesGcmVaultTest] proves the envelope, the tag and the tampering, all with an ordinary
 * [javax.crypto.spec.SecretKeySpec]. That is the right key for those questions and the
 * wrong key for this one: it let a `seal` that could not possibly work on a device pass
 * every test and crash every phone. These tests run the same code against
 * [KeystoreLikeCipher], which enforces what `AndroidKeyStore` enforces.
 *
 * There are two halves, and both are needed. The first half proves the vault obeys the
 * contract. The second proves the contract is really being imposed — a fake that has
 * stopped refusing anything is worse than no test, because it reads like coverage.
 */
class AesGcmVaultKeystoreTest {

    @get:Rule
    val keystore = KeystoreLikeCipher()

    private val vault: Vault get() = AesGcmVault { keystore.key }

    private val plain = "plan=TRIAL\nmaxObservedTime=1772323200000\n".encodeToByteArray()

    // ------------------------------------------------------- the vault obeys the rules

    /**
     * The regression. This is the exact call that crashed on the device, and it fails
     * against the `seal` that shipped: `InvalidAlgorithmParameterException: Caller-provided
     * IV not permitted`, uncaught, straight out of the entitlement gate.
     */
    @Test
    fun `sealing works when the provider insists on choosing the nonce itself`() {
        val vault = vault

        assertArrayEquals(plain, vault.open(vault.seal(plain)))
    }

    /**
     * The other half of the same rule. The provider generates the nonce, so the envelope
     * has to carry back whatever it chose rather than whatever the app expected — and
     * `open` has to supply it, because a keystore refuses to decrypt without one.
     */
    @Test
    fun `the nonce the provider chose is the nonce that comes back`() {
        val vault = vault

        val first = vault.seal(plain)
        val second = vault.seal(plain)

        // Two seals, two nonces: the provider is not reusing one, which is the property
        // randomised encryption exists to guarantee.
        assertNotEquals(first.slice(1..12), second.slice(1..12))
        assertArrayEquals(plain, vault.open(first))
        assertArrayEquals(plain, vault.open(second))
    }

    @Test
    fun `the envelope is the same shape it has always been`() {
        val sealed = vault.seal(plain)

        // Version byte, twelve-byte nonce, then ciphertext and tag. A device that
        // upgrades into this fix has to be able to read what the previous build wrote,
        // so this layout is not free to drift.
        assertEquals(1.toByte(), sealed[0])
        assertEquals(1 + 12 + plain.size + 16, sealed.size)
    }

    @Test
    fun `an edited blob is still rejected`() {
        val vault = vault
        val sealed = vault.seal(plain)

        for (index in sealed.indices) {
            val edited = sealed.copyOf().also { it[index] = (it[index] + 1).toByte() }

            assertNull("byte $index", vault.open(edited))
        }
    }

    @Test
    fun `an empty payload round trips`() {
        val vault = vault

        assertArrayEquals(ByteArray(0), vault.open(vault.seal(ByteArray(0))))
    }

    // --------------------------------------------------- the rules are really imposed

    /**
     * The trap, armed. If this test ever stops throwing, [KeystoreLikeCipher] has stopped
     * modelling a keystore and every test above it has quietly become worthless.
     */
    @Test
    fun `the modelled keystore refuses an initialisation vector when encrypting`() {
        val thrown = assertThrows(GeneralSecurityException::class.java) {
            Cipher.getInstance("AES/GCM/NoPadding")
                .init(Cipher.ENCRYPT_MODE, keystore.key, GCMParameterSpec(128, ByteArray(12)))
        }

        // The message the device produced, carried through however the JCE wraps it.
        assertEquals(true, thrown.toString().contains("Caller-provided IV not permitted"))
    }

    /** And the mirror image, which is what makes `open` pass the nonce back. */
    @Test
    fun `the modelled keystore refuses to decrypt without an initialisation vector`() {
        assertThrows(GeneralSecurityException::class.java) {
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.DECRYPT_MODE, keystore.key)
        }
    }

    /**
     * And nothing else installed will serve the key, which is what stops the framework
     * from silently trying another provider when this one refuses — the reason the key is
     * opaque rather than an ordinary array of bytes.
     *
     * What is asserted is that no other provider *succeeds*, not which exception it
     * chooses. Which providers are installed depends on the JDK, and a test that pinned
     * the exception type would go red on a machine where one of them was merely rude
     * about a key it could not use.
     */
    @Test
    fun `no other provider will touch a keystore held key`() {
        for (provider in Security.getProviders()) {
            if (provider.name == KEYSTORE_LIKE) continue

            val cipher = runCatching {
                Cipher.getInstance("AES/GCM/NoPadding", provider)
            }.getOrNull() ?: continue

            val accepted = runCatching { cipher.init(Cipher.ENCRYPT_MODE, keystore.key) }

            assertTrue("${provider.name} accepted an opaque key", accepted.isFailure)
        }
    }

    private companion object {
        const val KEYSTORE_LIKE = "CastivioKeystoreLike"
    }
}
