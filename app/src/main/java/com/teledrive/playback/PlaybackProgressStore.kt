package com.teledrive.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.playbackPrefs by preferencesDataStore(name = "playback_progress")

@Singleton
class PlaybackProgressStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun observePosition(stableId: String): Flow<Long> =
        context.playbackPrefs.data.map { it[longPreferencesKey(posKey(stableId))] ?: 0L }

    suspend fun getPosition(stableId: String): Long =
        context.playbackPrefs.data.map { it[longPreferencesKey(posKey(stableId))] ?: 0L }.first()

    suspend fun setPosition(stableId: String, positionMs: Long, durationMs: Long?) {
        // Don't save positions very close to end (within 5 seconds or 95%+)
        val shouldClear = when {
            durationMs == null || durationMs <= 0L -> false
            positionMs <= 0L -> false
            positionMs >= durationMs - 5000L -> true
            durationMs > 0 && positionMs.toDouble() / durationMs.toDouble() >= 0.95 -> true
            else -> false
        }
        context.playbackPrefs.edit { prefs ->
            if (shouldClear) {
                prefs.remove(longPreferencesKey(posKey(stableId)))
                prefs.remove(longPreferencesKey(durKey(stableId)))
            } else {
                prefs[longPreferencesKey(posKey(stableId))] = positionMs.coerceAtLeast(0L)
                if (durationMs != null) prefs[longPreferencesKey(durKey(stableId))] = durationMs
            }
        }
    }

    suspend fun clear(stableId: String) {
        context.playbackPrefs.edit { prefs ->
            prefs.remove(longPreferencesKey(posKey(stableId)))
            prefs.remove(longPreferencesKey(durKey(stableId)))
        }
    }

    private fun posKey(id: String) = "pos_$id"
    private fun durKey(id: String) = "dur_$id"
}
