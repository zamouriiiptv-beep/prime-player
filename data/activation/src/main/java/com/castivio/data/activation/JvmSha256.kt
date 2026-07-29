package com.castivio.data.activation

import com.castivio.domain.identity.Sha256
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SHA-256 from the platform.
 *
 * A fresh [MessageDigest] per call because the class is stateful and not safe to
 * share; the allocation happens a handful of times in a device's life, and the
 * alternative is a thread-local for no measurable gain.
 *
 * The output of this is pinned by the test vectors in `DeviceIdentityAlgorithmTest`,
 * so an implementation that is not SHA-256 fails a test rather than minting different
 * addresses on different platforms.
 */
@Singleton
class JvmSha256 @Inject constructor() : Sha256 {
    override fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
