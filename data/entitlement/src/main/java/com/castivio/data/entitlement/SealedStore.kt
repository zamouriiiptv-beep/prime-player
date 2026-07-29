package com.castivio.data.entitlement

import android.content.SharedPreferences
import android.util.Base64

/**
 * One small file, sealed, holding the two things a licence depends on.
 *
 * The entitlement record and the clock's high-water mark live together because they are
 * the same secret in two halves: a stored expiry is only meaningful against a stored
 * "furthest instant seen", and someone editing one would edit the other. Splitting them
 * across two files with two protections would be two chances to get it wrong.
 *
 * Its own preferences file, not the app's, so that a future "reset settings" cannot
 * take a paid licence with it.
 */
internal class SealedStore(
    private val prefs: SharedPreferences,
    private val vault: Vault,
) {

    /** Null when nothing is stored, or when what is stored will not open. */
    fun read(key: String): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        val sealed = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()

        val plain = sealed?.let(vault::open)
        if (plain == null) {
            // Unreadable and never going to become readable: an edited blob, or a key
            // that no longer exists. Dropping it stops every future launch paying to
            // fail at the same byte, and "nothing stored" is a state the app already
            // handles correctly -- it routes to the licence screen.
            prefs.edit().remove(key).apply()
            return null
        }
        return plain
    }

    fun write(key: String, plain: ByteArray) {
        val encoded = Base64.encodeToString(vault.seal(plain), Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).apply()
    }

    /**
     * Written all the way to disk before returning.
     *
     * Used for the clock's high-water mark, which is the one value where losing the
     * last write to a power cut would hand someone a rewindable clock.
     */
    fun writeNow(key: String, plain: ByteArray) {
        val encoded = Base64.encodeToString(vault.seal(plain), Base64.NO_WRAP)
        prefs.edit().putString(key, encoded).commit()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        /** The file. Named for what it holds, so a future reader knows not to clear it. */
        const val FILE = "castivio.entitlement"

        const val KEY_ENTITLEMENT = "entitlement"
        const val KEY_CLOCK = "clock"
    }
}
