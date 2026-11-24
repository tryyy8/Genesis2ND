package app.genesis.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("genesis-secure", Context.MODE_PRIVATE)
    private val alias = "genesis-worker-token-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    fun save(token: String) {
        require(token.isNotBlank()) { "Worker credential is empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val saved = prefs.edit()
            .putString("worker_token", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
            .commit()
        if (!saved) throw IllegalStateException("Android could not commit the encrypted worker credential to local storage")
        if (get() != token) throw IllegalStateException("Android stored the credential but could not decrypt and verify it afterward")
    }

    fun get(): String? {
        val encoded = prefs.getString("worker_token", null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun clear() { prefs.edit().remove("worker_token").apply() }
}
