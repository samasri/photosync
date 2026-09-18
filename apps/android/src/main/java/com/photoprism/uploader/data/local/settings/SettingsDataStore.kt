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
import kotlinx.coroutines.flow.onStart

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * DataStore for persisting server settings.
 */
class SettingsDataStore(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("delivery_url")
        val USERNAME = stringPreferencesKey("delivery_username")
        val PASSWORD = stringPreferencesKey("delivery_token")
    }

    val settings: Flow<ServerSettings> = context.dataStore.data.map { prefs ->
        ServerSettings(
            baseUrl = prefs[Keys.BASE_URL] ?: context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE).getString("url", "").orEmpty(),
            username = "",
            password = prefs[Keys.PASSWORD] ?: context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE).getString("token", "").orEmpty()
        )
    }.onStart {
        context.dataStore.edit { prefs ->
            if (!prefs.contains(Keys.BASE_URL)) {
                val backup = context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE)
                prefs[Keys.BASE_URL] = backup.getString("url", "").orEmpty()
                prefs[Keys.PASSWORD] = backup.getString("token", "").orEmpty()
            }
        }
    }

    suspend fun saveSettings(settings: ServerSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BASE_URL] = settings.baseUrl
            prefs[Keys.USERNAME] = settings.username
            prefs[Keys.PASSWORD] = settings.password
        }
    }
}
