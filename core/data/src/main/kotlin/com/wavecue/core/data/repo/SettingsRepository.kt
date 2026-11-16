package com.wavecue.core.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wavecue.core.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(name = "wavecue_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val bufferMs = intPreferencesKey("buffer_ms")
        val threshold = floatPreferencesKey("silence_threshold_db")
        val minDuration = longPreferencesKey("silence_min_ms")
        val includeEdges = booleanPreferencesKey("silence_include_edges")
        val exclusive = booleanPreferencesKey("exclusive_mode")
        val bitPerfect = booleanPreferencesKey("bit_perfect_usb")
        val followPlayhead = booleanPreferencesKey("follow_playhead")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppSettings(
                bufferMs = preferences[Keys.bufferMs] ?: 300,
                silenceThresholdDb = preferences[Keys.threshold] ?: -60f,
                silenceMinDurationMs = preferences[Keys.minDuration] ?: 2000L,
                includeEdgeSilence = preferences[Keys.includeEdges] ?: false,
                exclusiveMode = preferences[Keys.exclusive] ?: true,
                bitPerfectUsb = preferences[Keys.bitPerfect] ?: false,
                followPlayhead = preferences[Keys.followPlayhead] ?: true,
            )
        }

    suspend fun setBufferMs(value: Int) {
        context.settingsStore.edit { it[Keys.bufferMs] = value.coerceIn(50, 1500) }
    }

    suspend fun setSilenceThreshold(value: Float) {
        context.settingsStore.edit { it[Keys.threshold] = value.coerceIn(-80f, -20f) }
    }

    suspend fun setSilenceMinDuration(value: Long) {
        context.settingsStore.edit { it[Keys.minDuration] = value.coerceIn(200L, 15000L) }
    }

    suspend fun setIncludeEdgeSilence(value: Boolean) {
        context.settingsStore.edit { it[Keys.includeEdges] = value }
    }

    suspend fun setExclusiveMode(value: Boolean) {
        context.settingsStore.edit { it[Keys.exclusive] = value }
    }

    suspend fun setBitPerfectUsb(value: Boolean) {
        context.settingsStore.edit { it[Keys.bitPerfect] = value }
    }

    suspend fun setFollowPlayhead(value: Boolean) {
        context.settingsStore.edit { it[Keys.followPlayhead] = value }
    }
}
