package com.teledrive.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.teleDriveDataStore by preferencesDataStore(name = "teledrive_preferences")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val theme: Flow<AppTheme> = context.teleDriveDataStore.data.map { preferences ->
        preferences[THEME_KEY]?.let { value -> runCatching { AppTheme.valueOf(value) }.getOrDefault(AppTheme.SYSTEM) } ?: AppTheme.SYSTEM
    }

    val gridView: Flow<Boolean> = context.teleDriveDataStore.data.map { it[GRID_VIEW_KEY] ?: false }
    val syncMetadataEnabled: Flow<Boolean> = context.teleDriveDataStore.data.map { it[SYNC_METADATA_KEY] ?: true }

    suspend fun setTheme(theme: AppTheme) = context.teleDriveDataStore.edit { it[THEME_KEY] = theme.name }
    suspend fun setGridView(enabled: Boolean) = context.teleDriveDataStore.edit { it[GRID_VIEW_KEY] = enabled }
    suspend fun setSyncMetadataEnabled(enabled: Boolean) = context.teleDriveDataStore.edit { it[SYNC_METADATA_KEY] = enabled }

    suspend fun getOrCreateDeviceId(): String {
        var value = ""
        context.teleDriveDataStore.edit { preferences ->
            value = preferences[DEVICE_ID_KEY] ?: UUID.randomUUID().toString().also { preferences[DEVICE_ID_KEY] = it }
        }
        return value
    }

    suspend fun nextMetadataSequence(): Long {
        var next = 0L
        context.teleDriveDataStore.edit { preferences ->
            next = (preferences[METADATA_SEQUENCE_KEY] ?: 0L) + 1L
            preferences[METADATA_SEQUENCE_KEY] = next
        }
        return next
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme")
        val GRID_VIEW_KEY = booleanPreferencesKey("grid_view")
        val SYNC_METADATA_KEY = booleanPreferencesKey("sync_metadata")
        val DEVICE_ID_KEY = stringPreferencesKey("metadata_device_id")
        val METADATA_SEQUENCE_KEY = androidx.datastore.preferences.core.longPreferencesKey("metadata_sequence")
    }
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}
