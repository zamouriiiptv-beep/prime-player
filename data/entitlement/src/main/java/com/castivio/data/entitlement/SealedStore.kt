package com.castivio.data.entitlement

import android.content.SharedPreferences
import android.util.Base64

/** What came out of the file. */
internal sealed interface SealedRead {

    /** Nothing was written under this key. */
    data object Absent : SealedRead

    data class Opened(val bytes: ByteArray) : SealedRead {
        // ByteArray in a data class needs these; the default identity comparison would
        // make two reads of the same blob unequal.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Opened && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** Something was written and will not open. */
    data object Unsealable : SealedRead
}

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

    /**
     * Distinguishes "nothing here" from "something here that will not open", because
     * those are opposite facts about the device and only the caller knows what to do
     * about the second one.
     *
     * The unreadable blob is deliberately **left where it is**. Deleting it would erase
     * the only evidence that this device was ever licensed, and the next launch would
     * read [SealedRead.Absent] and conclude the user never had anything — which is the
     * wrong sentence for them and a free trial for whoever edited the file. It costs one
     * failed GCM open per read to keep it, which is microseconds.
     */
    fun read(key: String): SealedRead {
        val encoded = prefs.getString(key, null) ?: return SealedRead.Absent
        val sealed = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return SealedRead.Unsealable

        return vault.open(sealed)?.let(SealedRead::Opened) ?: SealedRead.Unsealable
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
