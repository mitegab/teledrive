package com.teledrive.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.teledrive.core.model.PdfReadingProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
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

    /**
     * Reading position for one document, keyed by a hash of its stable id so names with
     * separators never collide. Corrupt or legacy values are ignored, never crash.
     */
    suspend fun pdfProgress(stableId: String): PdfReadingProgress? {
        val raw = context.teleDriveDataStore.data.map { it[pdfProgressKey(stableId)] }.first()
        return raw?.let { decodePdfProgress(it, stableId) }
    }

    suspend fun setPdfProgress(progress: PdfReadingProgress) {
        val key = pdfProgressKey(progress.documentId)
        context.teleDriveDataStore.edit { preferences -> preferences[key] = encodePdfProgress(progress) }
    }

    private fun pdfProgressKey(stableId: String) =
        stringPreferencesKey("pdf_progress_" + MessageDigest.getInstance("SHA-256")
            .digest(stableId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24))

    private fun encodePdfProgress(progress: PdfReadingProgress): String = listOf(
        progress.pageIndex,
        progress.pageCount,
        progress.zoom,
        progress.scrollOffsetFraction,
        progress.updatedAtEpochMillis,
    ).joinToString("|")

    private fun decodePdfProgress(raw: String, stableId: String): PdfReadingProgress? {
        val parts = raw.split('|')
        if (parts.size < 5) return null
        val page = parts[0].toIntOrNull() ?: return null
        val count = parts[1].toIntOrNull() ?: return null
        val zoom = parts[2].toFloatOrNull() ?: return null
        val offset = parts[3].toFloatOrNull() ?: 0f
        val updatedAt = parts[4].toLongOrNull() ?: 0L
        return PdfReadingProgress(stableId, page, count, zoom, updatedAt, offset)
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
        val METADATA_SEQUENCE_KEY = longPreferencesKey("metadata_sequence")
    }
}

enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK,
}
