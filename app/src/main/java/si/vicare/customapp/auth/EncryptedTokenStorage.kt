package si.vicare.customapp.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val Context.tokenDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_tokens")

/**
 * Stores [Tokens] in DataStore, encrypted with Tink AES-256-GCM. The Tink keyset itself is
 * wrapped by a non-exportable Android Keystore key, so the ciphertext is useless off-device.
 */
class EncryptedTokenStorage(private val context: Context) : TokenStorage {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override suspend fun read(): Tokens? = withContext(Dispatchers.IO) {
        val encoded = context.tokenDataStore.data.first()[KEY_TOKENS] ?: return@withContext null
        try {
            val plain = aead.decrypt(Base64.decode(encoded, Base64.NO_WRAP), ASSOCIATED_DATA)
            Json.decodeFromString(Tokens.serializer(), plain.decodeToString())
        } catch (_: GeneralSecurityException) {
            // Keystore key lost (e.g. after a device restore). Treat as signed out.
            clear()
            null
        } catch (_: IllegalArgumentException) {
            clear()
            null
        }
    }

    override suspend fun write(tokens: Tokens) = withContext(Dispatchers.IO) {
        val plain = Json.encodeToString(Tokens.serializer(), tokens).encodeToByteArray()
        val encoded = Base64.encodeToString(aead.encrypt(plain, ASSOCIATED_DATA), Base64.NO_WRAP)
        context.tokenDataStore.edit { it[KEY_TOKENS] = encoded }
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        context.tokenDataStore.edit { it.remove(KEY_TOKENS) }
        Unit
    }

    private companion object {
        val KEY_TOKENS = stringPreferencesKey("tokens")
        val ASSOCIATED_DATA = "viessmann-tokens-v1".encodeToByteArray()
        const val KEYSET_NAME = "token_keyset"
        const val KEYSET_PREFS = "token_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://viessmann_token_master_key"
    }
}
