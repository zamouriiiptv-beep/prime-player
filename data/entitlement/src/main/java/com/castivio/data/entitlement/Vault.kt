package com.castivio.data.entitlement

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Turns bytes into bytes nobody can read or edit, and back.
 *
 * A port rather than a call so that the *cipher* can be tested on a JVM while the *key*
 * comes from a keystore that no JVM has. Everything interesting is on this side of the
 * line; what is on the other side is twenty lines of key management that only a real
 * device can exercise.
 */
internal interface Vault {

    fun seal(plain: ByteArray): ByteArray

    /** Null when the blob is not ours, has been edited, or was sealed with another key. */
    fun open(sealed: ByteArray): ByteArray?
}

/**
 * AES-256-GCM.
 *
 * GCM because the threat here is **tampering**, not reading. Nothing in an entitlement
 * record is a secret — the licence server knows all of it and so does the customer —
 * but the trial expiry and the high-water mark are exactly what someone wanting a
 * second free week would edit, and on a rooted television that is otherwise a one-line
 * change with a text editor. GCM's tag makes an edited blob fail to open rather than
 * open with different numbers in it.
 *
 * It is worth being plain about what this is not: an attacker with root can read the
 * key out of a software keystore, and no amount of client-side cryptography survives
 * that. The defence is that the licence server is the authority and this file is a
 * cache of what it said. This raises the cost of casual tampering, which is the honest
 * description of it.
 *
 * @param key supplied lazily, because fetching it may involve a keystore and must not
 *   happen at construction — and because a test can supply an ordinary one. Resolved
 *   once and held: a keystore lookup per cipher operation would be paid on every read
 *   of the clock, which is the hottest path this module has.
 */
internal class AesGcmVault(key: () -> SecretKey) : Vault {

    private val secret: SecretKey by lazy(key)

    /**
     * Encrypts with an initialisation vector **the key's own provider chooses**.
     *
     * This is not a style preference. A key generated in `AndroidKeyStore` is created
     * with randomized encryption required — the default, and one worth keeping — and
     * such a key refuses an IV supplied by the caller:
     *
     * ```
     * java.security.InvalidAlgorithmParameterException:
     *     Caller-provided IV not permitted
     * ```
     *
     * The JVM's own provider is happy to accept one, which is exactly why generating
     * the IV here passed every test and failed on every real device. So the provider
     * generates it and hands it back through [Cipher.getIV]; the envelope is byte for
     * byte what it was, because the IV still travels in front of the ciphertext.
     */
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secret)
        }

        val iv = cipher.iv
        // Twelve is GCM's standard nonce and what both AndroidKeyStore and the JVM
        // produce. The envelope has no length field, so a provider that chose another
        // size must fail loudly here rather than write a blob nothing can parse.
        require(iv != null && iv.size == IV_BYTES) {
            "AES-GCM needs a $IV_BYTES byte nonce; this provider produced ${iv?.size}"
        }

        val body = cipher.doFinal(plain)

        return ByteArray(1 + IV_BYTES + body.size).also { out ->
            out[0] = FORMAT
            iv.copyInto(out, destinationOffset = 1)
            body.copyInto(out, destinationOffset = 1 + IV_BYTES)
        }
    }

    override fun open(sealed: ByteArray): ByteArray? {
        // Every failure is the same failure as far as the caller is concerned: there is
        // nothing readable here. Distinguishing "edited" from "wrong key" would tell an
        // attacker something and would tell the app nothing it could act on.
        if (sealed.size <= 1 + IV_BYTES || sealed[0] != FORMAT) return null

        return runCatching {
            val iv = sealed.copyOfRange(1, 1 + IV_BYTES)
            val body = sealed.copyOfRange(1 + IV_BYTES, sealed.size)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
            }.doFinal(body)
        }.getOrElse { failure ->
            if (failure is GeneralSecurityException || failure is IllegalArgumentException) null else throw failure
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** The version of this envelope, so a future cipher is a different first byte. */
        const val FORMAT: Byte = 1

        /** Twelve bytes is GCM's native nonce size; anything else costs a rehash. */
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
