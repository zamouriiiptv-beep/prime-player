package com.castivio.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a password is, and which one every device starts with.
 *
 * Four digits, because that is what a remote's number keys and a keypad on a handset can both
 * type without a keyboard in front of the screen. Nothing else is a password: not three digits,
 * not five, not a digit and a letter.
 */
object PinRules {

    /** How many digits a password has. */
    const val LENGTH = 4

    /** The password until it is changed. Public on purpose: it is what the user is told it is. */
    const val DEFAULT = "0000"

    /** Whether [text] is exactly [LENGTH] ASCII digits. */
    fun isWellFormed(text: String): Boolean = text.length == LENGTH && text.all { it in '0'..'9' }
}

/** How a request to change the password ended. */
enum class PinChange {
    /** The new password is stored and the old one no longer works. */
    Changed,

    /** The current password was not the one stored. Nothing changed. */
    WrongCurrent,

    /** The new password is not [PinRules.LENGTH] digits. Nothing changed. */
    Malformed,
}

/**
 * The password: asked whether a thing typed is it, and told to change it.
 *
 * Suspending because the storage behind it is a file, and nothing that touches a file runs on the
 * thread a press is handled on.
 */
interface PinVault {

    /** Whether [pin] is the password. A [pin] that is not [PinRules.LENGTH] digits never is. */
    suspend fun matches(pin: String): Boolean

    /** Replaces [current] with [new], and only when [current] is the password. */
    suspend fun change(current: String, new: String): PinChange
}

/** The password as it is kept: never the digits, only what they come to under [PinDigest]. */
data class StoredPin(val salt: String, val digest: String)

/** Where the stored password is kept. A file on a device, a map in a test. */
interface PinSlot {

    /** The stored password, or null when it has never been changed from [PinRules.DEFAULT]. */
    suspend fun read(): StoredPin?

    suspend fun write(pin: StoredPin)
}

/** How a password is turned into what is stored, and the salt it is turned with. */
interface PinDigest {

    /** A salt nobody has used before. */
    fun newSalt(): String

    /** What [pin] comes to under [salt], as text. The same inputs give the same answer. */
    fun of(salt: String, pin: String): String
}

/**
 * The rules of the password, over a slot and a digest.
 *
 * ## The default is not stored
 *
 * Until the password has been changed there is nothing in the slot and the answer is
 * [PinRules.DEFAULT]. A device that was never touched therefore has no file to read, and the day
 * the default changes is a change to one constant rather than a migration.
 *
 * ## What the stored form is for
 *
 * A salted digest, so the password is not sitting in the preferences file in the clear for anyone
 * who looks at it. It is not a defence against somebody with the device's files in their hands:
 * there are ten thousand four-digit passwords and checking them all takes no time at all. The lock
 * is a household's -- it keeps a child out of a channel -- and it is built to that, not to more.
 *
 * ## Compared without stopping at the first difference
 *
 * [sameText] takes as long for a near miss as for a far one. Here it matters little, and it costs
 * nothing, which is the whole of the argument for it.
 */
class PinKeeper(
    private val slot: PinSlot,
    private val digest: PinDigest,
) : PinVault {

    /** One change at a time, so two of them cannot each read the old password and both win. */
    private val writing = Mutex()

    override suspend fun matches(pin: String): Boolean {
        if (!PinRules.isWellFormed(pin)) return false
        val stored = slot.read() ?: return sameText(pin, PinRules.DEFAULT)
        return sameText(digest.of(stored.salt, pin), stored.digest)
    }

    override suspend fun change(current: String, new: String): PinChange = writing.withLock {
        when {
            !matches(current) -> PinChange.WrongCurrent
            !PinRules.isWellFormed(new) -> PinChange.Malformed
            else -> {
                val salt = digest.newSalt()
                slot.write(StoredPin(salt, digest.of(salt, new)))
                PinChange.Changed
            }
        }
    }
}

/** Whether [a] and [b] are the same text, in time that depends on their length and not on where they differ. */
internal fun sameText(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
    return difference == 0
}
