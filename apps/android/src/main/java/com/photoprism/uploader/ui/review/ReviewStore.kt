package com.photoprism.uploader.ui.review

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.photoprism.uploader.domain.model.MediaImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

private val Context.reviewData by preferencesDataStore("photo_review")

data class ReviewProgress(val nextDate: Long? = null, val remaining: Int? = null, val throughDate: Long? = null)

class ReviewStore(context: Context, private val dataStore: androidx.datastore.core.DataStore<Preferences> = context.reviewData) {
    companion object {
        val START_DATE: LocalDate = LocalDate.of(2026, 1, 1)
        private val INITIALIZED = booleanPreferencesKey("start_2026_initialized")
        private val NEXT = longPreferencesKey("next_date")
        private val REMAINING = intPreferencesKey("remaining")
        private val THROUGH = longPreferencesKey("through_date")
        private val IMAGE_KEY = Regex("\\d+:\\d+")
    }

    val progress = dataStore.data.map { ReviewProgress(it[NEXT], it[REMAINING], it[THROUGH]) }

    // Once only: mark the older images currently present, never blanket-filter future imports.
    suspend fun initialize(images: List<MediaImage>) {
        val cutoff = START_DATE.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        dataStore.edit { prefs ->
            if (prefs[INITIALIZED] != true) {
                images.filter { reviewDate(it) < cutoff }.forEach { image ->
                    val key = stringPreferencesKey(image.uploadKey)
                    if (prefs[key] == null) prefs[key] = "ignored"
                }
                prefs[INITIALIZED] = true
            }
        }
    }

    suspend fun reviewedKeys(): Set<String> = dataStore.data.first().asMap().keys
        .map { it.name }.filter { IMAGE_KEY.matches(it) }.toSet()

    suspend fun record(image: MediaImage, decision: String) {
        dataStore.edit { it[stringPreferencesKey(image.uploadKey)] = decision }
    }

    suspend fun undo(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun saveProgress(nextDate: Long?, remaining: Int, throughDate: Long?) {
        dataStore.edit {
            if (nextDate == null) it.remove(NEXT) else it[NEXT] = nextDate
            if (throughDate == null) it.remove(THROUGH) else it[THROUGH] = throughDate
            it[REMAINING] = remaining
        }
    }
}
