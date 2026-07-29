package com.castivio.data.entitlement

import org.junit.rules.ExternalResource
import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.Key
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A cipher that behaves the way `AndroidKeyStore` behaves, installed for the length of
 * one test.
 *
 * This exists because of a defect that shipped. [AesGcmVault] generated its own
 * initialisation vector and handed it to `Cipher.init`, which the JVM's own provider
 * accepts happily — so every unit test passed — and which a key held in
 * `AndroidKeyStore` refuses outright:
 *
 * ```
 * java.security.InvalidAlgorithmParameterException: Caller-provided IV not permitted
 * ```
 *
 * The app crashed on the first launch of every real device and on none of the machines
 * that tested it. Robolectric has no keystore and no JVM has one, so the only way to
 * hold that contract is to model it: a provider that enforces the three rules a
 * keystore key enforces, in front of the real cipher that does the arithmetic.
 *
 * The rules, and what each one pins:
 *
 *  1. **An IV may not be supplied when encrypting.** The provider chooses it, because
 *     the key was created with randomised encryption required — the default, and one
 *     worth keeping. This is the rule that was broken.
 *  2. **An IV must be supplied when decrypting.** There is nothing for a keystore to
 *     guess, so the envelope has to carry the nonce and `open` has to pass it back.
 *  3. **The key is opaque.** [KeystoreLikeKey.getEncoded] returns null, exactly as an
 *     `AndroidKeyStoreKey` does. That is not decoration: it is what stops the JCE from
 *     quietly falling back to another provider when this one refuses a call, which is
 *     what happens on a device and what has to happen here or the test proves nothing.
 *
 * Rule 3 deserves a sentence more. `Cipher.getInstance` defers choosing a provider until
 * `init`, then tries the candidates in order and *skips any that throws*. With an
 * ordinary [SecretKeySpec] the JVM's provider would accept what this one rejects, the
 * exception would be swallowed by the framework, and the old broken `seal` would have
 * passed this test too. An opaque key leaves nobody to fall back to.
 *
 * Installed at position 1 so it is asked first, and removed afterwards so no other test
 * inherits it.
 */
class KeystoreLikeCipher : ExternalResource() {

    /** The sealing key to give [AesGcmVault], opaque in the same way a real one is. */
    val key: SecretKey = KeystoreLikeKey(ByteArray(32) { index -> (index * 7 + 3).toByte() })

    override fun before() {
        // Captured before ours goes in front of it, and after an init because the JCE
        // does not name the provider it chose until it has chosen one.
        val probe = Cipher.getInstance(GCM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"))
        }
        realProvider = probe.provider

        Security.insertProviderAt(KeystoreLikeProvider(), 1)
    }

    override fun after() {
        Security.removeProvider(PROVIDER_NAME)
        realProvider = null
    }
}

private const val GCM = "AES/GCM/NoPadding"
private const val PROVIDER_NAME = "CastivioKeystoreLike"

/**
 * The provider that does the arithmetic, once ours has decided the call is allowed.
 *
 * Held in a file-level variable rather than passed in because the JCE instantiates a
 * [CipherSpi] by name and gives it nothing.
 */
@Volatile
private var realProvider: Provider? = null

private fun realCipher(): Cipher = Cipher.getInstance(
    GCM,
    checkNotNull(realProvider) { "KeystoreLikeCipher is not installed; use it as a @Rule" },
)

private class KeystoreLikeProvider : Provider(
    PROVIDER_NAME,
    "1",
    "Models the AndroidKeyStore contract for AES/GCM so it can be tested off a device",
) {
    init {
        put("Cipher.$GCM", KeystoreLikeCipherSpi::class.java.name)
    }
}

/**
 * An AES key whose material never leaves the provider that holds it.
 *
 * `getEncoded` returning null is what an `AndroidKeyStoreKey` does, and it is why no
 * other provider in the JVM will touch this key: `RAW` is the only format they accept.
 */
class KeystoreLikeKey internal constructor(private val secret: ByteArray) : SecretKey {

    override fun getAlgorithm(): String = "AES"

    override fun getFormat(): String? = null

    override fun getEncoded(): ByteArray? = null

    /** Only [KeystoreLikeCipherSpi] may see the bytes, which is the point of the class. */
    internal fun material(): SecretKey = SecretKeySpec(secret, "AES")
}

/**
 * Public and no-arg because the JCE constructs it reflectively from the name registered
 * above. Everything it does not enforce, it delegates.
 */
class KeystoreLikeCipherSpi : CipherSpi() {

    private var delegate: Cipher? = null

    override fun engineSetMode(mode: String) {
        if (!mode.equals("GCM", ignoreCase = true)) throw NoSuchAlgorithmException(mode)
    }

    override fun engineSetPadding(padding: String) {
        if (!padding.equals("NoPadding", ignoreCase = true)) throw NoSuchPaddingException(padding)
    }

    override fun engineGetBlockSize(): Int = AES_BLOCK

    override fun engineGetKeySize(key: Key?): Int = KEY_BITS

    override fun engineGetOutputSize(inputLen: Int): Int = initialised().getOutputSize(inputLen)

    override fun engineGetIV(): ByteArray? = delegate?.iv

    override fun engineGetParameters(): AlgorithmParameters? = delegate?.parameters

    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
        val secret = material(key)

        // Rule 2. A keystore cannot invent the nonce a blob was sealed with.
        if (opmode == Cipher.DECRYPT_MODE || opmode == Cipher.UNWRAP_MODE) {
            throw InvalidAlgorithmParameterException("IV required when decrypting")
        }

        delegate = realCipher().apply { init(opmode, secret, random ?: SecureRandom()) }
    }

    override fun engineInit(
        opmode: Int,
        key: Key?,
        params: AlgorithmParameterSpec?,
        random: SecureRandom?,
    ) {
        if (params == null) return engineInit(opmode, key, random)

        val secret = material(key)
        refuseCallerIv(opmode)
        delegate = realCipher().apply { init(opmode, secret, params, random ?: SecureRandom()) }
    }

    override fun engineInit(
        opmode: Int,
        key: Key?,
        params: AlgorithmParameters?,
        random: SecureRandom?,
    ) {
        if (params == null) return engineInit(opmode, key, random)

        val secret = material(key)
        refuseCallerIv(opmode)
        delegate = realCipher().apply { init(opmode, secret, params, random ?: SecureRandom()) }
    }

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? =
        initialised().update(input, inputOffset, inputLen)

    override fun engineUpdate(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = initialised().update(input, inputOffset, inputLen, output, outputOffset)

    override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) {
        initialised().updateAAD(src, offset, len)
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
        initialised().doFinal(input, inputOffset, inputLen)

    override fun engineDoFinal(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = initialised().doFinal(input, inputOffset, inputLen, output, outputOffset)

    /** Rule 1, and the reason this class exists. */
    private fun refuseCallerIv(opmode: Int) {
        if (opmode == Cipher.ENCRYPT_MODE || opmode == Cipher.WRAP_MODE) {
            throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
        }
    }

    /** Rule 3. A keystore serves its own keys and no others. */
    private fun material(key: Key?): SecretKey = (key as? KeystoreLikeKey)?.material()
        ?: throw InvalidKeyException("Keystore-held keys only; got ${key?.javaClass?.name}")

    private fun initialised(): Cipher =
        checkNotNull(delegate) { "Cipher not initialised" }

    private companion object {
        const val AES_BLOCK = 16
        const val KEY_BITS = 256
    }
}
