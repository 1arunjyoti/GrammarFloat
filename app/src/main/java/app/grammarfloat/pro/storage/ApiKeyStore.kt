package app.grammarfloat.pro.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import app.grammarfloat.pro.api.Provider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {
    // We use a different pref name than the old EncryptedSharedPreferences one to avoid crash/conflicts
    private val prefs: SharedPreferences = context.getSharedPreferences("api_keys_secure", Context.MODE_PRIVATE)

    companion object {
        private const val ALIAS = "GrammarFloatApiKeyAlias"

        private val keyStoreInit: Unit by lazy {
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                if (!keyStore.containsAlias(ALIAS)) {
                    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                    keyGenerator.init(keyGenParameterSpec)
                    keyGenerator.generateKey()
                }
            } catch (e: Exception) {
                android.util.Log.e("ApiKeyStore", "Failed to initialize KeyStore", e)
            }
            Unit
        }

        private fun encrypt(plainText: String): String? {
            keyStoreInit
            return try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                val secretKey = keyStore.getKey(ALIAS, null) as SecretKey
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                
                val combined = ByteArray(iv.size + encrypted.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
                Base64.encodeToString(combined, Base64.NO_WRAP)
            } catch (e: Exception) {
                android.util.Log.e("ApiKeyStore", "Failed to encrypt text", e)
                null
            }
        }

        private fun decrypt(encryptedText: String): String? {
            keyStoreInit
            return try {
                val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                val secretKey = keyStore.getKey(ALIAS, null) as SecretKey
                
                val iv = combined.copyOfRange(0, 12)
                val encrypted = combined.copyOfRange(12, combined.size)
                
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.e("ApiKeyStore", "Failed to decrypt text", e)
                null
            }
        }
    }

    fun getApiKey(provider: Provider): String? {
        val encrypted = prefs.getString("api_key_${provider.name}", null) ?: return null
        return decrypt(encrypted)
    }

    fun setApiKey(provider: Provider, key: String): Boolean {
        val encrypted = encrypt(key) ?: return false
        prefs.edit { putString("api_key_${provider.name}", encrypted) }
        return true
    }


    fun getActiveProvider(): Provider {
        val providerName = prefs.getString("active_provider", Provider.ANTHROPIC.name) ?: Provider.ANTHROPIC.name
        return try {
            Provider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            Provider.ANTHROPIC
        }
    }

    fun setActiveProvider(provider: Provider) {
        prefs.edit { putString("active_provider", provider.name) }
    }
}
