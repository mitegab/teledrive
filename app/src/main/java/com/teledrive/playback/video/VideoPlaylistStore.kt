package com.teledrive.playback.video

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.videoPlaylistPrefs by preferencesDataStore(
    name = "video_playlists",
    // Playlists are rebuilt from the index; recovering is always better than crashing.
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
)

/**
 * Persists named video playlists and the "recently played" order. Layout is a single encoded
 * string per concern so the file stays a plain Preferences datastore (see [encodePlaylists]).
 */
@Singleton
class VideoPlaylistStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val playlists: Flow<List<VideoPlaylist>> =
        context.videoPlaylistPrefs.data.map { prefs -> decodePlaylists(prefs[PLAYLISTS_KEY]) }

    val recents: Flow<List<String>> =
        context.videoPlaylistPrefs.data.map { prefs -> parseIdList(prefs[RECENTS_KEY]) }

    suspend fun savePlaylists(playlists: List<VideoPlaylist>) {
        context.videoPlaylistPrefs.edit { prefs ->
            prefs[PLAYLISTS_KEY] = encodePlaylists(playlists.filter { it.id.isNotBlank() })
        }
    }

    suspend fun recordRecent(stableId: String, limit: Int = 30) {
        if (stableId.isBlank()) return
        context.videoPlaylistPrefs.edit { prefs ->
            val current = parseIdList(prefs[RECENTS_KEY])
            val updated = (listOf(stableId) + current.filterNot { it == stableId }).take(limit)
            prefs[RECENTS_KEY] = updated.joinToString(",")
        }
    }

    suspend fun clearRecents() {
        context.videoPlaylistPrefs.edit { prefs -> prefs.remove(RECENTS_KEY) }
    }

    suspend fun snapshotPlaylists(): List<VideoPlaylist> = playlists.first()

    private companion object {
        val PLAYLISTS_KEY = stringPreferencesKey("video_playlists")
        val RECENTS_KEY = stringPreferencesKey("video_recents")
    }
}
