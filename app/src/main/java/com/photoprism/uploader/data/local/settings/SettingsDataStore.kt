package com.photoprism.uploader.data.local.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.photoprism.uploader.domain.model.ServerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * DataStore for persisting server settings.
 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
    }

    val settings: Flow<ServerSettings> = context.dataStore.data.map { prefs ->
        ServerSettings(
            baseUrl = prefs[Keys.BASE_URL] ?: ServerSettings.DEFAULT.baseUrl,
            username = prefs[Keys.USERNAME] ?: ServerSettings.DEFAULT.username,
            password = prefs[Keys.PASSWORD] ?: ServerSettings.DEFAULT.password
        )
    }

    suspend fun saveSettings(settings: ServerSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = settings.baseUrl
            prefs[Keys.USERNAME] = settings.username
            prefs[Keys.PASSWORD] = settings.password
        }
    }
}
