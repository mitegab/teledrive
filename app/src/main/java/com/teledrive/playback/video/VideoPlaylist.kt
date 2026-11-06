package com.teledrive.playback.video

import java.util.Locale

/**
 * Playlist and library rules for the video workspace. Pure Kotlin so ordering, search,
 * serialisation and drag-and-drop moves can be tested without Android.
 */

data class VideoPlaylist(
    val id: String,
    val name: String,
    val stableIds: List<String> = emptyList(),
    val createdAtEpochSeconds: Long = 0L,
) {
    val size: Int get() = stableIds.size
    val isEmpty: Boolean get() = stableIds.isEmpty()
    val summary: String get() = if (size == 1) "1 video" else "$size videos"
}

enum class VideoSortField(val label: String) {
    NAME("Name"),
    DURATION("Duration"),
    DATE("Date added"),
    SIZE("Size"),
}

data class VideoSort(
    val field: VideoSortField = VideoSortField.DATE,
    val ascending: Boolean = false,
) {
    fun toggled(): VideoSort = copy(ascending = !ascending)
    fun withField(next: VideoSortField): VideoSort =
        if (next == field) toggled() else copy(field = next, ascending = next == VideoSortField.NAME)
}

/** One indexed video plus the playback facts we can know without opening it. */
data class VideoLibraryEntry(
    val stableId: String,
    val filename: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val savedAtEpochSeconds: Long = 0L,
    val durationMs: Long? = null,
    val localPath: String? = null,
    val favorite: Boolean = false,
    val downloaded: Boolean = false,
    val resumeMs: Long = 0L,
) {
    val extension: String get() = filename.substringAfterLast('.', "").uppercase(Locale.ROOT)
    val hasProgress: Boolean get() = resumeMs > 0L
    val watchedFraction: Float
        get() = watchProgressFraction(resumeMs, durationMs ?: 0L)
}

fun videoExtensionLabel(filename: String?): String {
    val ext = filename?.substringAfterLast('.', "")?.uppercase(Locale.ROOT).orEmpty()
    return when (ext) {
        "" -> "VIDEO"
        "MP4", "M4V", "MKV", "WEBM", "MOV", "AVI", "FLV", "TS", "MTS", "M2TS", "3GP", "3G2", "WMV", "MPG", "MPEG", "F4V", "HEVC" -> ext
        else -> ext.take(4)
    }
}

/**
 * Search + filters + sort for the video library.
 * [favoritesOnly] keeps starred files, [inProgressOnly] the "continue watching" shelf.
 */
fun filterAndSortVideos(
    entries: List<VideoLibraryEntry>,
    query: String = "",
    sort: VideoSort = VideoSort(),
    favoritesOnly: Boolean = false,
    inProgressOnly: Boolean = false,
): List<VideoLibraryEntry> {
    val needle = query.trim().lowercase(Locale.ROOT)
    val filtered = entries.filter { entry ->
        val matchesQuery = needle.isEmpty() || entry.filename.lowercase(Locale.ROOT).contains(needle)
        val matchesFavorite = !favoritesOnly || entry.favorite
        val matchesProgress = !inProgressOnly || entry.hasProgress
        matchesQuery && matchesFavorite && matchesProgress
    }
    val comparator: Comparator<VideoLibraryEntry> = when (sort.field) {
        VideoSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.filename }
        VideoSortField.SIZE -> compareBy(nullsLast()) { it.sizeBytes }
        VideoSortField.DURATION -> compareBy(nullsLast()) { it.durationMs }
        VideoSortField.DATE -> compareBy { it.savedAtEpochSeconds }
    }
    val ordered = filtered.sortedWith(comparator)
    return if (sort.ascending) ordered else ordered.reversed()
}

/** Recently played shelf: newest first, no duplicates, bounded. */
fun recentlyPlayed(entries: List<VideoLibraryEntry>, limit: Int = 12): List<VideoLibraryEntry> =
    entries.filter { it.hasProgress }
        .sortedByDescending { it.savedAtEpochSeconds }
        .take(limit)

// ---- playlist mutations -------------------------------------------------------

fun moveEntry(ids: List<String>, from: Int, to: Int): List<String> {
    if (from !in ids.indices) return ids
    val target = to.coerceIn(0, ids.size - 1)
    if (target == from) return ids
    val mutable = ids.toMutableList()
    val moved = mutable.removeAt(from)
    mutable.add(target, moved)
    return mutable
}

fun addEntry(ids: List<String>, stableId: String, at: Int? = null): List<String> {
    if (stableId.isBlank() || stableId in ids) return ids
    val index = (at ?: ids.size).coerceIn(0, ids.size)
    return ids.toMutableList().also { it.add(index, stableId) }
}

fun removeEntry(ids: List<String>, stableId: String): List<String> = ids.filterNot { it == stableId }

// ---- playlist persistence (Preferences datastore friendly) ---------------------

private const val RECORD_SEPARATOR = "\n"
private const val FIELD_SEPARATOR = "\t"
private const val ID_SEPARATOR = ","

fun sanitizePlaylistName(name: String): String =
    name.replace('\n', ' ').replace('\t', ' ').trim().take(60)

/**
 * Encodes playlists into a single preferences string:
 * `id \t createdAt \t name \t id1,id2,id3` per line.
 * Ids never contain commas or tabs (they are `chatId:messageId`).
 */
fun encodePlaylists(playlists: List<VideoPlaylist>): String = playlists.joinToString(RECORD_SEPARATOR) { playlist ->
    listOf(
        playlist.id,
        playlist.createdAtEpochSeconds.toString(),
        sanitizePlaylistName(playlist.name),
        playlist.stableIds.joinToString(ID_SEPARATOR),
    ).joinToString(FIELD_SEPARATOR)
}

fun decodePlaylists(encoded: String?): List<VideoPlaylist> {
    if (encoded.isNullOrBlank()) return emptyList()
    return encoded.split(RECORD_SEPARATOR).mapNotNull { record ->
        val fields = record.split(FIELD_SEPARATOR)
        if (fields.size < 3) return@mapNotNull null
        val id = fields[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val created = fields[1].toLongOrNull() ?: 0L
        val name = fields[2].takeIf { it.isNotBlank() } ?: "Playlist"
        val ids = fields.getOrNull(3)?.split(ID_SEPARATOR)?.filter { it.isNotBlank() } ?: emptyList()
        VideoPlaylist(id = id, name = name, stableIds = ids, createdAtEpochSeconds = created)
    }
}

/** Newest-first default name for a freshly created playlist. */
fun defaultPlaylistName(existing: List<VideoPlaylist>, nowEpochSeconds: Long): String {
    val base = "New playlist"
    if (existing.none { it.name == base }) return base
    var index = 2
    while (existing.any { it.name == "$base $index" }) index++
    return "$base $index"
}

/** Splits a comma/newline separated list of ids, dropping blanks and duplicates. */
fun parseIdList(raw: String?): List<String> =
    raw.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Human summary of the formats the app can index, used by the empty state of the library. */
val supportedVideoExtensions: List<String> =
    listOf("MP4", "MKV", "AVI", "MOV", "WebM", "FLV", "TS", "M4V", "3GP", "MTS", "WMV", "MPG")
