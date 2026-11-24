package app.genesis.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Per-install RSA key for receiving an approved worker credential. */
class PairingKeyStore {
    private val store: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun alias(deviceId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray(StandardCharsets.UTF_8))
        return "genesis_pair_" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun getOrCreatePublicKey(deviceId: String): String {
        val keyAlias = alias(deviceId)
        if (!store.containsAlias(keyAlias)) {
            val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
            store.load(null)
        }
        val certificate = store.getCertificate(keyAlias)
            ?: throw IllegalStateException("Android Keystore did not retain the pairing public key")
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun decryptWorkerToken(deviceId: String, encryptedToken: String): String {
        require(encryptedToken.isNotBlank() && encryptedToken.length <= 4096) {
            "Invalid encrypted worker credential"
        }
        val key = store.getKey(alias(deviceId), null) as? PrivateKey
            ?: throw IllegalStateException("This device's pairing private key is unavailable; pair again")
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val params = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.DECRYPT_MODE, key, params)
        val clear = cipher.doFinal(Base64.decode(encryptedToken, Base64.DEFAULT))
        return String(clear, StandardCharsets.UTF_8)
    }
}
