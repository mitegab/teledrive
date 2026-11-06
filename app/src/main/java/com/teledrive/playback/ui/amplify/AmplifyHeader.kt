package com.teledrive.playback.ui.amplify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.playback.PlaybackItem

/** Slim toolbar: drawer affordance on phones, search and close on every width. */
@Composable
fun AmplifyTopBar(
    wide: Boolean,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenDrawer: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!wide) {
            AmplifyIconButton(
                icon = Icons.Rounded.Menu,
                contentDescription = "Open navigation",
                onClick = onOpenDrawer,
                size = 34.dp,
            )
        }
        if (searchOpen) {
            AmplifySearchField(
                value = query,
                onValueChange = onQueryChange,
                requestFocus = true,
                modifier = Modifier.weight(1f),
            )
            AmplifyIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Close search",
                onClick = onToggleSearch,
                size = 34.dp,
            )
        } else {
            Text(
                "Now Playing",
                style = AmplifyType.eyebrow.copy(letterSpacing = 2.4.sp),
                modifier = Modifier.weight(1f),
            )
            AmplifyIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = "Search tracks",
                onClick = onToggleSearch,
                size = 34.dp,
            )
            AmplifyIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Close player",
                onClick = onBack,
                size = 34.dp,
            )
        }
    }
}

/**
 * Playlist header: eyebrow, big title, track/duration line, Add Tracks action, and the
 * square cover card from the reference design.
 */
@Composable
fun AmplifyPlaylistHeader(
    eyebrow: String,
    title: String,
    subtitle: String,
    current: PlaybackItem?,
    artworkFor: suspend (String) -> ImageBitmap?,
    onAddTracks: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), style = AmplifyType.eyebrow, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = if (wide) AmplifyType.display else AmplifyType.display.copy(fontSize = 25.sp, lineHeight = 29.sp),
                maxLines = if (wide) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(if (wide) 6.dp else 4.dp))
            Text(subtitle, style = AmplifyType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(if (wide) 14.dp else 10.dp))
            AmplifyPill(
                text = "Add Tracks",
                icon = Icons.Rounded.Add,
                onClick = onAddTracks,
                compact = !wide,
            )
        }
        if (wide) {
            AmplifyCoverCard(
                current = current,
                artworkFor = artworkFor,
                modifier = Modifier.width(154.dp),
            )
        } else {
            AmplifyCoverCard(
                current = current,
                artworkFor = artworkFor,
                modifier = Modifier.width(88.dp),
            )
        }
    }
}

@Composable
private fun AmplifyCoverCard(
    current: PlaybackItem?,
    artworkFor: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .aspectRatio(0.82f)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, AmplifyColors.HairlineStrong, RoundedCornerShape(16.dp))
            .background(AmplifyColors.Panel),
    ) {
        AmplifyArtwork(
            title = current?.title ?: "TeleDrive",
            seed = current?.file?.stableId ?: "amplify-cover",
            localPath = current?.localPath,
            loadArtwork = artworkFor,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            iconSize = 30.dp,
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
        ) {
            Text(
                "NOW PLAYING",
                style = AmplifyType.eyebrow.copy(fontSize = 8.sp, letterSpacing = 1.4.sp),
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                current?.let { it.album ?: it.title } ?: "Nothing queued",
                style = AmplifyType.trackTitle.copy(fontSize = 11.5.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shuffle / edit actions plus the playlist sort selector. */
@Composable
fun AmplifyPlaylistControls(
    shuffleOn: Boolean,
    onToggleShuffle: () -> Unit,
    onEditQueue: () -> Unit,
    sort: AmplifySort,
    onSortChange: (AmplifySort) -> Unit,
    onClearFilters: () -> Unit,
    filtersActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmplifyPill(
                text = "Shuffle",
                icon = Icons.Rounded.Shuffle,
                selected = shuffleOn,
                onClick = onToggleShuffle,
                compact = true,
            )
            AmplifyPill(
                text = "Edit queue",
                icon = Icons.Rounded.PlaylistPlay,
                onClick = onEditQueue,
                compact = true,
            )
            if (filtersActive) {
                AmplifyPill(
                    text = "Clear filters",
                    icon = Icons.Rounded.Close,
                    onClick = onClearFilters,
                    compact = true,
                )
            }
        }
        AmplifySelect(
            options = AmplifySort.entries,
            optionLabel = { it.label },
            selected = sort,
            onSelect = onSortChange,
            leadingLabel = null,
        )
    }
}

/** Shared empty/error card so every section reports problems in the same visual language. */
@Composable
fun AmplifyNoticeCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tone: Color = AmplifyColors.Accent,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = AmplifyType.trackTitle)
                Spacer(Modifier.height(3.dp))
                Text(message, style = AmplifyType.body)
            }
            if (actionLabel != null && onAction != null) {
                AmplifyPill(text = actionLabel, onClick = onAction, compact = true)
            }
        }
    }
}
