package com.teledrive.playback.ui.amplify

import com.teledrive.core.model.LocalAvailability
import com.teledrive.playback.PlaybackItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Pure playlist rules behind the Amplify player shell.
 *
 * Everything here works on data the app genuinely has (file index + embedded audio tags).
 * Mood and genre filters are keyword/tag filters: they never invent attributes a track does
 * not have, they simply match the tag text the user's files already carry.
 */

enum class AmplifySection(val label: String) {
    DISCOVER("Discover"),
    LIBRARY("My Library"),
    QUEUE("Queue"),
    GENRES("Genres"),
}

enum class AmplifySort(val label: String) {
    CUSTOM("Custom"),
    DATE_ADDED("Date Added"),
    TITLE("Title"),
    ARTIST("Artist"),
    DURATION("Duration"),
}

/**
 * Mood buckets are matched against real metadata text (genre, album, title, artist).
 * A track only lands in "Late Night" when one of those fields actually says so.
 */
enum class AmplifyMood(val label: String, val keywords: List<String>) {
    CHILL(
        "Chill",
        listOf("chill", "ambient", "downtempo", "lo-fi", "lofi", "lounge", "acoustic", "bossa", "soft"),
    ),
    ENERGETIC(
        "Energetic",
        listOf("house", "techno", "dance", "edm", "drum", "bass", "rock", "punk", "metal", "rap", "hip hop", "trance"),
    ),
    FOCUS(
        "Focus",
        listOf("ambient", "instrumental", "classical", "piano", "drone", "minimal", "study", "concentration"),
    ),
    NOSTALGIC(
        "Nostalgic",
        listOf("soul", "funk", "jazz", "blues", "oldies", "retro", "indie", "80s", "90s", "vinyl"),
    ),
    ATMOSPHERIC(
        "Atmospheric",
        listOf("ambient", "cinematic", "soundtrack", "score", "experimental", "shoegaze", "post-rock", "space"),
    ),
    LATE_NIGHT(
        "Late Night",
        listOf("lo-fi", "lofi", "jazz", "soul", "r&b", "rnb", "trip hop", "downtempo", "night", "dark"),
    ),
}

data class AmplifyFilters(
    val query: String = "",
    val mood: AmplifyMood? = null,
    val genres: Set<String> = emptySet(),
) {
    val isActive: Boolean get() = query.isNotBlank() || mood != null || genres.isNotEmpty()

    fun toggleGenre(genre: String): AmplifyFilters =
        copy(genres = if (genre in genres) genres - genre else genres + genre)

    fun clear(): AmplifyFilters = AmplifyFilters()
}

/** A queue entry paired with its original queue index so row actions stay correct after sorting. */
data class AmplifyTrack(val index: Int, val item: PlaybackItem)

data class GenreBucket(val genre: String, val count: Int)

data class PlaylistStats(
    val trackCount: Int = 0,
    val knownDurations: Int = 0,
    val totalDurationMs: Long = 0L,
)

/** Playlist title: the shared album when there is one, otherwise the shared artist, otherwise a neutral label. */
fun playlistName(items: List<PlaybackItem>): String {
    if (items.isEmpty()) return "Audio Queue"
    val albums = items.mapNotNull { it.album?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    if (albums.size == 1 && albums.first().length > 1) return albums.first()
    val artists = items.mapNotNull { it.artist?.trim()?.takeIf(String::isNotEmpty) }.distinct()
    if (artists.size == 1 && artists.first().length > 1) return artists.first()
    if (items.size == 1) return items.first().title
    val folders = items.mapNotNull { it.file.folderId?.takeIf(String::isNotBlank) }.distinct()
    return if (folders.size == 1) "Folder mix" else "Audio Queue"
}

fun playlistStats(items: List<PlaybackItem>): PlaylistStats {
    val known = items.mapNotNull { it.durationMs?.takeIf { duration -> duration > 0L } }
    return PlaylistStats(items.size, known.size, known.sum())
}

fun playlistLengthLabel(stats: PlaylistStats): String = when {
    stats.trackCount == 0 -> "No tracks"
    stats.knownDurations == 0 -> "Duration pending"
    stats.knownDurations < stats.trackCount -> "${longDuration(stats.totalDurationMs)}+"
    else -> longDuration(stats.totalDurationMs)
}

fun playlistSubtitle(stats: PlaylistStats): String {
    if (stats.trackCount == 0) return "Queue a track to begin"
    val tracks = if (stats.trackCount == 1) "1 Track" else "${stats.trackCount} Tracks"
    return "$tracks · ${playlistLengthLabel(stats)}"
}

fun longDuration(ms: Long): String {
    if (ms <= 0L) return "0m"
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
        hours > 0L -> "${hours}h"
        totalMinutes > 0L -> "${totalMinutes}m"
        else -> "<1m"
    }
}

fun amplifyClock(ms: Long?): String {
    if (ms == null || ms < 0L) return "--:--"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

fun genreLabel(item: PlaybackItem): String? {
    val raw = item.genre?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // Some taggers store numeric ID3v1 genre codes; keep them readable instead of hiding them.
    val readable = if (raw.all(Char::isDigit)) "Genre $raw" else raw
    return readable
}

fun genreBuckets(items: List<PlaybackItem>): List<GenreBucket> =
    items.mapNotNull { genreLabel(it) }
        .groupingBy { it }
        .eachCount()
        .map { GenreBucket(it.key, it.value) }
        .sortedWith(compareByDescending<GenreBucket> { it.count }.thenBy { it.genre.lowercase(Locale.ROOT) })

fun searchHaystack(item: PlaybackItem): String = buildString {
    append(item.title)
    append(' ')
    append(item.file.originalFilename)
    item.artist?.let { append(' ').append(it) }
    item.album?.let { append(' ').append(it) }
    item.genre?.let { append(' ').append(it) }
}.lowercase(Locale.ROOT)

fun matchesMood(item: PlaybackItem, mood: AmplifyMood): Boolean {
    val haystack = searchHaystack(item)
    return mood.keywords.any { haystack.contains(it) }
}

fun matchesFilters(item: PlaybackItem, filters: AmplifyFilters): Boolean {
    val query = filters.query.trim().lowercase(Locale.ROOT)
    if (query.isNotEmpty() && !searchHaystack(item).contains(query)) return false
    if (filters.mood != null && !matchesMood(item, filters.mood)) return false
    if (filters.genres.isNotEmpty()) {
        val genre = genreLabel(item)
        if (genre == null || filters.genres.none { it.equals(genre, ignoreCase = true) }) return false
    }
    return true
}

fun arrangeTracks(items: List<PlaybackItem>, filters: AmplifyFilters, sort: AmplifySort): List<AmplifyTrack> {
    val filtered = items.mapIndexedNotNull { index, item ->
        if (matchesFilters(item, filters)) AmplifyTrack(index, item) else null
    }
    return when (sort) {
        AmplifySort.CUSTOM -> filtered
        AmplifySort.TITLE -> filtered.sortedBy { it.item.title.lowercase(Locale.ROOT) }
        AmplifySort.ARTIST -> filtered.sortedWith(
            compareBy<AmplifyTrack> { it.item.artist?.lowercase(Locale.ROOT) ?: "\uFFFF" }
                .thenBy { it.item.title.lowercase(Locale.ROOT) }
        )
        AmplifySort.DURATION -> filtered.sortedBy { it.item.durationMs ?: Long.MAX_VALUE }
        AmplifySort.DATE_ADDED -> filtered.sortedByDescending { it.item.file.savedAtEpochSeconds }
    }
}

fun savedDate(epochSeconds: Long): String =
    if (epochSeconds <= 0L) "Date unknown"
    else SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochSeconds * 1000L))

fun sizeLabel(bytes: Long?): String = when {
    bytes == null || bytes <= 0L -> "Size unknown"
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** Explains why a queue row may need a download before it can play. */
fun offlineStateLabel(availability: LocalAvailability): String? = when (availability) {
    LocalAvailability.AVAILABLE -> null
    LocalAvailability.DOWNLOADING -> "Downloading"
    LocalAvailability.PARTIAL -> "Partial copy"
    LocalAvailability.FAILED -> "Download failed"
    LocalAvailability.NOT_DOWNLOADED -> "In Telegram"
}

/**
 * Deterministic pseudo-waveform for the timeline.
 *
 * The player has no real peak data for a Telegram-hosted file before it is analysed, so the
 * bar heights are a stable visual signature derived from the track id: the same track always
 * renders the same shape, and seeking still maps 1:1 onto the real playback position.
 */
fun waveformBars(key: String, count: Int): List<Float> {
    if (count <= 0) return emptyList()
    val random = Random(key.hashCode().toLong() * 31L + count)
    var previous = 0.34f
    return List(count) { index ->
        val accent = if (random.nextInt(12) == 0) 0.45f + random.nextFloat() * 0.55f else 0f
        val drift = random.nextFloat() * 0.4f - 0.2f
        val target = 0.34f + random.nextFloat() * 0.66f
        val smoothed = previous * 0.66f + target * 0.34f + drift
        val envelope = 0.76f + 0.24f * kotlin.math.sin(index.toFloat() / count * Math.PI).toFloat()
        val value = (smoothed * envelope + accent).coerceIn(0.12f, 1f)
        previous = value
        value
    }
}
