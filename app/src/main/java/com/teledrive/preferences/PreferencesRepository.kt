package com.teledrive.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme")
        val GRID_VIEW_KEY = booleanPreferencesKey("grid_view")
        val SYNC_METADATA_KEY = booleanPreferencesKey("sync_metadata")
    }
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}
