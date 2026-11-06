package com.teledrive.playback.video

/** State rendered by the video workspace's library / playlist browser. */
data class VideoLibraryUiState(
    val all: List<VideoLibraryEntry> = emptyList(),
    val visible: List<VideoLibraryEntry> = emptyList(),
    val continueWatching: List<VideoLibraryEntry> = emptyList(),
    val recentlyPlayed: List<VideoLibraryEntry> = emptyList(),
    val playlists: List<VideoPlaylist> = emptyList(),
    val query: String = "",
    val sort: VideoSort = VideoSort(),
    val favoritesOnly: Boolean = false,
    val inProgressOnly: Boolean = false,
    val loading: Boolean = true,
) {
    val favorites: List<VideoLibraryEntry> get() = all.filter { it.favorite }
    fun playlist(playlistId: String): VideoPlaylist? = playlists.firstOrNull { it.id == playlistId }
    fun entry(stableId: String): VideoLibraryEntry? = all.firstOrNull { it.stableId == stableId }
    /** Resolve a playlist's ids to entries, keeping the playlist's own order. */
    fun playlistEntries(playlist: VideoPlaylist): List<VideoLibraryEntry> =
        playlist.stableIds.mapNotNull { entry(it) }
}
