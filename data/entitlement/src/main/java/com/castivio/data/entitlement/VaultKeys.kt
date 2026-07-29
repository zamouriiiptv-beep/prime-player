package com.castivio.data.entitlement

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Where the sealing key comes from. The one part of this module a JVM cannot exercise.
 *
 * Two paths, because Castivio's floor is Android 5 and `AndroidKeyStore` did not learn
 * to hold an AES key until Android 6:
 *
 *  - **Android 6 and later** — a 256-bit AES key generated inside `AndroidKeyStore`.
 *    The key material never enters the app's address space; on most hardware it never
 *    leaves the secure element. Root does not hand it over.
 *  - **Android 5** — a 256-bit AES key generated here and stored beside the ciphertext.
 *    This is obfuscation, not protection: anyone who can read the file can read the
 *    key. It is still worth doing, because the thing it stops is a text editor, and it
 *    is worth saying out loud, because pretending otherwise would be worse than the
 *    weakness itself.
 *
 * The pre-Marshmallow alternative — an RSA keypair in the keystore wrapping an AES key
 * — was considered and rejected. It is real protection on paper, and on the cheap
 * Android 5 boxes this floor exists for, the keystore implementations are exactly the
 * ones that throw on generation and strand the app. A licence that cannot be read is
 * worse than a licence that can be edited, and the server is the authority in both
 * cases.
 *
 * If the key is ever lost — a keystore reset, a restore onto another device — the
 * stored entitlement becomes unreadable and the app treats it as absent. With a licence
 * server that costs one round trip, because the device's identity is unchanged and the
 * entitlement is re-fetched. It is another reason the record is a cache.
 */
internal object VaultKeys {

    private const val ALIAS = "castivio.entitlement.v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_BITS = 256
    private const val LEGACY_KEY = "vault.key"

    /**
     * @param fallbackStore where the Android 5 key lives. Ignored on Android 6 and later.
     */
    fun sealingKey(fallbackStore: SharedPreferences): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) keystoreKey() else localKey(fallbackStore)

    private fun keystoreKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    // Deliberately not tied to a screen lock. A television has no lock
                    // screen, and an entitlement that stops being readable when someone
                    // changes their PIN is a support case, not a security win.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }

    private fun localKey(store: SharedPreferences): SecretKey {
        store.getString(LEGACY_KEY, null)?.let { encoded ->
            decode(encoded)?.let { return SecretKeySpec(it, "AES") }
        }

        val material = ByteArray(KEY_BITS / 8).also { SecureRandom().nextBytes(it) }
        store.edit().putString(LEGACY_KEY, encode(material)).commit()
        return SecretKeySpec(material, "AES")
    }

    private fun encode(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun decode(text: String): ByteArray? {
        if (text.length != KEY_BITS / 4) return null
        return runCatching {
            ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
