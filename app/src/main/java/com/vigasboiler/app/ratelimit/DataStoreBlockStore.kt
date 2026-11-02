package com.vigasboiler.app.ratelimit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.rateLimitDataStore: DataStore<Preferences> by preferencesDataStore(name = "rate_limit")

class DataStoreBlockStore(private val context: Context) : RateLimitBlockStore {
    override suspend fun read(): RateLimitBlock? {
        val prefs = context.rateLimitDataStore.data.first()
        val until = prefs[UNTIL] ?: return null
        return RateLimitBlock(until, prefs[NAME])
    }

    override suspend fun write(block: RateLimitBlock?) {
        context.rateLimitDataStore.edit { prefs ->
            if (block == null) {
                prefs.remove(UNTIL)
                prefs.remove(NAME)
            } else {
                prefs[UNTIL] = block.untilMillis
                if (block.limitName != null) prefs[NAME] = block.limitName else prefs.remove(NAME)
            }
        }
    }

    private companion object {
        val UNTIL = longPreferencesKey("blocked_until")
        val NAME = stringPreferencesKey("limit_name")
    }
}
