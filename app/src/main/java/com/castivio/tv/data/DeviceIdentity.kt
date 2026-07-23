package com.castivio.tv.data

import android.content.Context
import kotlin.random.Random

/**
 * Identity shown on the activation screen. Android blocks reading the real
 * hardware MAC address, so we generate a stable MAC-formatted ID once and
 * keep it forever in SharedPreferences — the portal only needs uniqueness.
 */
data class DeviceIdentity(val mac: String, val key: String) {

    companion object {
        private const val PREFS = "device_identity"
        private const val KEY_MAC = "mac"
        private const val KEY_DEVICE_KEY = "device_key"

        fun get(context: Context): DeviceIdentity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var mac = prefs.getString(KEY_MAC, null)
            var key = prefs.getString(KEY_DEVICE_KEY, null)
            if (mac == null || key == null) {
                mac = generateMac()
                key = generateKey()
                prefs.edit().putString(KEY_MAC, mac).putString(KEY_DEVICE_KEY, key).apply()
            }
            return DeviceIdentity(mac, key)
        }

        private fun generateMac(): String {
            val bytes = ByteArray(6).also { Random.nextBytes(it) }
            // Locally-administered, unicast address: set bit 1, clear bit 0.
            bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
            return bytes.joinToString(":") { "%02X".format(it) }
        }

        private fun generateKey(): String = Random.nextInt(100000, 999999).toString()
    }
}
