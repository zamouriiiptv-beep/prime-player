package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.castivio.domain.PinDigest
import com.castivio.domain.PinKeeper
import com.castivio.domain.PinSlot
import com.castivio.domain.PinVault
import com.castivio.domain.StoredPin
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The password, kept in a preferences file of its own and asked through [PinKeeper].
 *
 * The rules -- four digits, `0000` until changed, the current one needed to change it -- are the
 * keeper's and are tested without a device. This is the part that has to be on one: the file, and
 * the arithmetic that turns a password into what the file holds.
 *
 * A file of its own rather than a key in another store's, so that clearing the board's measurements
 * or any other preference can never reset the password, and resetting the password never touches
 * them.
 */
@Singleton
class PinStore @Inject constructor(
    slot: PrefsPinSlot,
    digest: Sha256PinDigest,
) : PinVault by PinKeeper(slot, digest)

/** The stored password, in the file. Read and written off the main thread. */
@Singleton
class PrefsPinSlot @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PinSlot {

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override suspend fun read(): StoredPin? = withContext(Dispatchers.IO) {
        val salt = prefs.getString(SALT, null)
        val digest = prefs.getString(DIGEST, null)
        if (salt == null || digest == null) null else StoredPin(salt, digest)
    }

    override suspend fun write(pin: StoredPin) {
        withContext(Dispatchers.IO) {
            // Both keys in one commit: a reader never sees a new salt beside an old digest.
            prefs.edit().putString(SALT, pin.salt).putString(DIGEST, pin.digest).commit()
        }
    }

    private companion object {
        const val FILE = "castivio_parental"
        const val SALT = "pin_salt"
        const val DIGEST = "pin_digest"
    }
}

/** SHA-256 over the salt and the digits, as hexadecimal text; the salt is sixteen random bytes. */
@Singleton
class Sha256PinDigest @Inject constructor() : PinDigest {

    private val random = SecureRandom()

    override fun newSalt(): String = ByteArray(SALT_BYTES).also(random::nextBytes).toHex()

    override fun of(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val SALT_BYTES = 16
    }
}
