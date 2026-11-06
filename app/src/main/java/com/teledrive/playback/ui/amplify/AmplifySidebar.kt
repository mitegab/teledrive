package com.teledrive.playback.ui.amplify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.playback.PlaybackItem
import com.teledrive.playback.PlaybackStatus

private fun sectionIcon(section: AmplifySection): ImageVector = when (section) {
    AmplifySection.DISCOVER -> Icons.Rounded.Explore
    AmplifySection.LIBRARY -> Icons.Rounded.LibraryMusic
    AmplifySection.QUEUE -> Icons.Rounded.QueueMusic
    AmplifySection.GENRES -> Icons.Rounded.Category
}

private fun moodIcon(mood: AmplifyMood): ImageVector = when (mood) {
    AmplifyMood.CHILL -> Icons.Rounded.Spa
    AmplifyMood.ENERGETIC -> Icons.Rounded.Bolt
    AmplifyMood.FOCUS -> Icons.Rounded.CenterFocusStrong
    AmplifyMood.NOSTALGIC -> Icons.Rounded.History
    AmplifyMood.ATMOSPHERIC -> Icons.Rounded.Waves
    AmplifyMood.LATE_NIGHT -> Icons.Rounded.Bedtime
}

/**
 * Persistent left rail from the reference design: brand, sections, the mood selector, the
 * genre filters that are actually present in the current track set, and a now-playing card.
 */
@Composable
fun AmplifySidebar(
    section: AmplifySection,
    onSectionChange: (AmplifySection) -> Unit,
    onSearch: () -> Unit,
    mood: AmplifyMood?,
    onMoodChange: (AmplifyMood?) -> Unit,
    genreBuckets: List<GenreBucket>,
    selectedGenres: Set<String>,
    onToggleGenre: (String) -> Unit,
    onClearGenres: () -> Unit,
    current: PlaybackItem?,
    status: PlaybackStatus,
    artworkFor: suspend (String) -> ImageBitmap?,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.07f),
                        Color.White.copy(alpha = 0.02f),
                        Color.Transparent,
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AMPLIFY", style = AmplifyType.brand, modifier = Modifier.weight(1f))
            AmplifyIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = "Search tracks",
                onClick = onSearch,
                size = 30.dp,
                iconSize = 16.dp,
            )
        }

        Spacer(Modifier.height(22.dp))

        AmplifySection.entries.forEach { entry ->
            SidebarDestination(
                icon = sectionIcon(entry),
                label = entry.label,
                selected = entry == section,
                onClick = { onSectionChange(entry) },
            )
            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(18.dp))
        AmplifyDivider()
        Spacer(Modifier.height(18.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AmplifySectionLabel("Mood Selector")
            Spacer(Modifier.height(12.dp))
            MoodGrid(selected = mood, onSelect = onMoodChange)

            Spacer(Modifier.height(22.dp))
            AmplifySectionLabel("Genre Filters") {
                if (selectedGenres.isNotEmpty()) {
                    Text(
                        "CLEAR",
                        style = AmplifyType.eyebrow.copy(color = AmplifyColors.Accent),
                        modifier = Modifier.clickable(onClick = onClearGenres).padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (genreBuckets.isEmpty()) {
                Text(
                    "No genre tags in this playlist yet. Filed covers keep the list scrollable until they appear.",
                    style = AmplifyType.meta,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                genreBuckets.take(8).forEach { bucket ->
                    GenreFilterRow(
                        bucket = bucket,
                        selected = bucket.genre in selectedGenres,
                        onClick = { onToggleGenre(bucket.genre) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        NowPlayingCard(
            current = current,
            status = status,
            artworkFor = artworkFor,
            onClick = onOpenQueue,
        )
    }
}

@Composable
private fun SidebarDestination(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color.White.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) AmplifyColors.Accent else AmplifyColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            style = AmplifyType.label,
            color = if (selected) AmplifyColors.TextPrimary else AmplifyColors.TextSecondary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun MoodGrid(selected: AmplifyMood?, onSelect: (AmplifyMood?) -> Unit) {
    val moods = AmplifyMood.entries
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        moods.chunked(3).forEach { rowMoods ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                rowMoods.forEach { mood ->
                    MoodButton(
                        mood = mood,
                        selected = mood == selected,
                        onClick = { onSelect(if (mood == selected) null else mood) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowMoods.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MoodButton(
    mood: AmplifyMood,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (selected) AmplifyColors.AccentSoft
                    else Color.White.copy(alpha = 0.06f)
                )
                .border(
                    1.dp,
                    if (selected) AmplifyColors.Accent.copy(alpha = 0.6f) else AmplifyColors.Hairline,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                moodIcon(mood),
                contentDescription = null,
                tint = if (selected) AmplifyColors.Accent else AmplifyColors.TextMuted,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            mood.label,
            style = AmplifyType.meta.copy(fontSize = 9.5.sp),
            color = if (selected) AmplifyColors.TextPrimary else AmplifyColors.TextMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun GenreFilterRow(
    bucket: GenreBucket,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) AmplifyColors.AccentSoft else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (selected) AmplifyColors.Accent.copy(alpha = 0.5f) else AmplifyColors.Hairline,
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            bucket.genre,
            style = AmplifyType.label,
            color = if (selected) AmplifyColors.Accent else AmplifyColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            bucket.count.toString(),
            style = AmplifyType.meta,
            color = AmplifyColors.TextMuted,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = if (selected) AmplifyColors.Accent else AmplifyColors.TextMuted,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun NowPlayingCard(
    current: PlaybackItem?,
    status: PlaybackStatus,
    artworkFor: suspend (String) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = AmplifyColors.PanelStrong,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AmplifyArtwork(
                title = current?.title ?: "Nothing playing",
                seed = current?.file?.stableId ?: "amplify-idle",
                localPath = current?.localPath,
                loadArtwork = artworkFor,
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(8.dp),
                iconSize = 18.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    current?.title ?: "Nothing playing",
                    style = AmplifyType.trackTitle.copy(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    current?.let { it.artist ?: statusLabel(status) } ?: "Queue a track",
                    style = AmplifyType.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AmplifyEqualizer(
                playing = status == PlaybackStatus.PLAYING,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun statusLabel(status: PlaybackStatus): String = when (status) {
    PlaybackStatus.PLAYING -> "Playing"
    PlaybackStatus.PAUSED -> "Paused"
    PlaybackStatus.BUFFERING, PlaybackStatus.PREPARING -> "Preparing"
    PlaybackStatus.WAITING_FOR_DOWNLOAD -> "Downloading"
    PlaybackStatus.RESOLVING -> "Resolving"
    PlaybackStatus.ENDED -> "Finished"
    PlaybackStatus.ERROR -> "Error"
    PlaybackStatus.IDLE -> "Ready"
}
