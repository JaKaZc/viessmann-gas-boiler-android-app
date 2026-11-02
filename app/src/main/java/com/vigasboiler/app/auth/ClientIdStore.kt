package com.vigasboiler.app.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The user's own API client ID from the Viessmann developer portal, entered in the setup wizard.
 * It is a public identifier (PKCE client without secret), so it is stored unencrypted.
 */
interface ClientIdStore {
    val clientId: Flow<String?>
    suspend fun read(): String? = clientId.first()
    suspend fun write(clientId: String)
    suspend fun clear()
}

private val Context.clientDataStore: DataStore<Preferences> by preferencesDataStore(name = "client")

class DataStoreClientIdStore(private val context: Context) : ClientIdStore {
    override val clientId: Flow<String?> = context.clientDataStore.data.map { it[CLIENT_ID] }

    override suspend fun write(clientId: String) {
        context.clientDataStore.edit { it[CLIENT_ID] = clientId }
    }

    override suspend fun clear() {
        context.clientDataStore.edit { it.remove(CLIENT_ID) }
    }

    private companion object {
        val CLIENT_ID = stringPreferencesKey("client_id")
    }
}

object ClientId {
    private val PATTERN = Regex("[A-Za-z0-9._-]{16,128}")

    /** Removes whitespace and line breaks that come along when copying from the portal. */
    fun normalize(input: String): String = input.filterNot { it.isWhitespace() }

    /** Portal client IDs are 32 hex characters; accept any plausible ID rather than guess the format. */
    fun isValid(clientId: String): Boolean = PATTERN.matches(clientId)
}
