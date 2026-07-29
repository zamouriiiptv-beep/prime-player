package com.castivio.data.activation

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.DeviceIdentityAlgorithm
import com.castivio.domain.identity.DeviceIdentityRecord
import com.castivio.domain.identity.IdentityProvenance
import com.castivio.domain.identity.IdentitySeed
import com.castivio.domain.identity.MacAddress
import com.castivio.domain.identity.Sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's identity on Android.
 *
 * Two things are stored, and the difference between them is the whole design:
 *
 *  - **The seed**, written once and never again. Every algorithm version derives from
 *    the same material, so a future v2 changes what is done with the seed and never
 *    what the seed is. Without this, an operating-system upgrade that reformatted its
 *    identifier would quietly re-mint every address in the field.
 *  - **The derived address, keyed by version.** `mac.v1` is v1's answer. Raising
 *    [DeviceIdentityAlgorithm.CURRENT] adds `mac.v2` beside it and leaves `mac.v1`
 *    exactly where it is, which is what [legacy] hands to the licence server so a paid
 *    entitlement can be moved across rather than stranded.
 *
 * No permission is declared for any of this, and no hardware address is read. Android
 * has not let an app read one since Marshmallow without privileged access, and it
 * would be the wrong input regardless: Wi-Fi addresses are randomised per network, and
 * a device on Ethernet or with no radio at all has nothing to read.
 *
 * `Settings.Secure.ANDROID_ID` is not treated as a secret. It is scoped per signing
 * key on Android 8 and later, which is a stability property rather than a
 * confidentiality one — it means the value is ours and stays ours across reinstalls,
 * not that nobody else could learn it. Nothing here depends on it being unguessable;
 * the licence server, not the client, decides what an address is entitled to.
 */
@Singleton
class AndroidDeviceIdentity @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sha256: Sha256,
) : DeviceIdentity {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cached: DeviceIdentityRecord? = null

    override fun current(): DeviceIdentityRecord {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return resolve().also { cached = it }
        }
    }

    override fun legacy(): List<DeviceIdentityRecord> {
        val seed = storedSeed() ?: return emptyList()
        return DeviceIdentityAlgorithm.known
            .filter { it != DeviceIdentityAlgorithm.CURRENT }
            .mapNotNull { version ->
                // Only versions this device actually minted an address under. A device
                // that arrived after v2 shipped has no v1 history to reconcile.
                val mac = prefs.getString(macKey(version), null)?.let(MacAddress::parse)
                    ?: return@mapNotNull null
                DeviceIdentityRecord(mac, version, seed.provenance)
            }
            .sortedByDescending { it.algorithmVersion }
    }

    private fun resolve(): DeviceIdentityRecord {
        val seed = storedSeed() ?: mintSeed()
        val version = DeviceIdentityAlgorithm.CURRENT

        prefs.getString(macKey(version), null)?.let(MacAddress::parse)?.let { stored ->
            return DeviceIdentityRecord(stored, version, seed.provenance)
        }

        val mac = DeviceIdentityAlgorithm.derive(seed, sha256, version)
        prefs.edit().putString(macKey(version), mac.value).apply()
        return DeviceIdentityRecord(mac, version, seed.provenance)
    }

    private fun storedSeed(): IdentitySeed? {
        val value = prefs.getString(KEY_SEED_VALUE, null) ?: return null
        return when (prefs.getString(KEY_SEED_PROVENANCE, null)) {
            IdentityProvenance.DEVICE.name -> IdentitySeed.Os(value)
            IdentityProvenance.INSTALLATION.name -> IdentitySeed.Installation(value)
            else -> null
        }
    }

    /**
     * Chooses the seed, once in this device's life.
     *
     * The operating system's identifier is preferred because it outlives the app's own
     * storage. When it is missing or is one of the values whole production runs shipped
     * with, a random one is minted instead and labelled honestly — an address the user
     * loses if they clear the app's data is a support case, and it is a much smaller
     * one than several thousand devices sharing a licence.
     */
    private fun mintSeed(): IdentitySeed {
        val seed = IdentitySeed.fromOs(androidId())
            ?: IdentitySeed.Installation(UUID.randomUUID().toString())

        prefs.edit()
            .putString(KEY_SEED_VALUE, seedValue(seed))
            .putString(KEY_SEED_PROVENANCE, seed.provenance.name)
            .apply()

        return seed
    }

    private fun seedValue(seed: IdentitySeed): String = when (seed) {
        is IdentitySeed.Os -> seed.value
        is IdentitySeed.Installation -> seed.value
    }

    private fun androidId(): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()

    private fun macKey(version: Int): String = "mac.v$version"

    private companion object {
        /**
         * Its own file rather than the app's shared preferences, so that a future
         * "reset settings" cannot take the identity with it.
         */
        const val STORE = "castivio.identity"

        const val KEY_SEED_VALUE = "seed.value"
        const val KEY_SEED_PROVENANCE = "seed.provenance"
    }
}
