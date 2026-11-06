package com.teledrive.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.teledrive.playback.video.AudioStreamInfo
import com.teledrive.playback.video.MediaTrackKind
import com.teledrive.playback.video.MediaTrackSnapshot
import com.teledrive.playback.video.TrackDescriptor
import com.teledrive.playback.video.VideoStreamInfo
import com.teledrive.playback.video.trackKindForType
import com.teledrive.playback.video.trackOptions

/**
 * The only place where Media3's `Tracks`/`Format`/`TrackSelectionParameters` meet the plain
 * models the video UI renders. Keeping the mapping here means the screen never imports Media3
 * except for `PlayerView` itself.
 */
@OptIn(UnstableApi::class)
internal object VideoTrackAdapter {

    fun snapshot(
        tracks: Tracks?,
        videoFormat: Format?,
        audioFormat: Format?,
    ): MediaTrackSnapshot {
        val descriptors = tracks?.let(::descriptors).orEmpty()
        val options = trackOptions(descriptors)
        return MediaTrackSnapshot(
            video = options.filter { it.kind == MediaTrackKind.VIDEO },
            audio = options.filter { it.kind == MediaTrackKind.AUDIO },
            text = options.filter { it.kind == MediaTrackKind.TEXT },
            videoStream = videoFormat?.toVideoStreamInfo()
                ?: descriptors.firstOrNull { it.kind == MediaTrackKind.VIDEO }?.toVideoStreamInfo()
                ?: VideoStreamInfo(),
            audioStream = audioFormat?.toAudioStreamInfo()
                ?: descriptors.firstOrNull { it.kind == MediaTrackKind.AUDIO }?.toAudioStreamInfo()
                ?: AudioStreamInfo(),
        )
    }

    fun descriptors(tracks: Tracks): List<TrackDescriptor> {
        val result = mutableListOf<TrackDescriptor>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val kind = trackKindForType(group.type) ?: return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = runCatching { group.getTrackFormat(trackIndex) }.getOrNull() ?: continue
                val selected = runCatching { group.isTrackSelected(trackIndex) }.getOrDefault(false)
                result += TrackDescriptor(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    kind = kind,
                    language = format.language,
                    label = format.label,
                    mimeType = format.sampleMimeType,
                    codecs = format.codecs,
                    channelCount = format.channelCount.takeIf { it > 0 } ?: 0,
                    width = format.width.takeIf { it > 0 } ?: 0,
                    height = format.height.takeIf { it > 0 } ?: 0,
                    frameRate = format.frameRate.takeIf { it > 0f } ?: -1f,
                    bitrate = format.bitrate.takeIf { it > 0 } ?: 0,
                    colorTransfer = format.colorInfo?.colorTransfer,
                    isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                    isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0,
                    selected = selected,
                )
            }
        }
        return result
    }

    /** Parameters that force exactly the chosen track of the chosen group. */
    fun parametersForTrack(
        base: TrackSelectionParameters,
        tracks: Tracks,
        kind: MediaTrackKind,
        groupIndex: Int,
        trackIndex: Int,
    ): TrackSelectionParameters? {
        val type = media3Type(kind) ?: return null
        val group = tracks.groups.getOrNull(groupIndex) ?: return null
        if (group.type != type) return null
        if (trackIndex !in 0 until group.length) return null
        return base.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    /** Clears any manual override, letting DefaultTrackSelector pick again. */
    fun parametersAutomatic(base: TrackSelectionParameters, kind: MediaTrackKind): TrackSelectionParameters? {
        val type = media3Type(kind) ?: return null
        return base.buildUpon()
            .setTrackTypeDisabled(type, false)
            .clearOverridesOfType(type)
            .build()
    }

    /** Turns a whole track type on or off (used by the "captions off" switch). */
    fun parametersWithTypeEnabled(
        base: TrackSelectionParameters,
        kind: MediaTrackKind,
        enabled: Boolean,
    ): TrackSelectionParameters? {
        val type = media3Type(kind) ?: return null
        val builder = base.buildUpon().setTrackTypeDisabled(type, !enabled)
        if (!enabled) builder.clearOverridesOfType(type)
        return builder.build()
    }

    private fun media3Type(kind: MediaTrackKind): Int? = when (kind) {
        MediaTrackKind.VIDEO -> C.TRACK_TYPE_VIDEO
        MediaTrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
        MediaTrackKind.TEXT -> C.TRACK_TYPE_TEXT
    }
}

@OptIn(UnstableApi::class)
private fun Format.toVideoStreamInfo(): VideoStreamInfo = VideoStreamInfo(
    width = width.takeIf { it > 0 } ?: 0,
    height = height.takeIf { it > 0 } ?: 0,
    frameRate = frameRate.takeIf { it > 0f } ?: -1f,
    sampleMimeType = sampleMimeType,
    codecs = codecs,
    bitrate = bitrate.takeIf { it > 0 },
    colorTransfer = colorInfo?.colorTransfer,
)

@OptIn(UnstableApi::class)
private fun Format.toAudioStreamInfo(): AudioStreamInfo = AudioStreamInfo(
    sampleMimeType = sampleMimeType,
    codecs = codecs,
    channelCount = channelCount.takeIf { it > 0 } ?: 0,
    sampleRate = sampleRate.takeIf { it > 0 } ?: 0,
    language = language,
    bitrate = bitrate.takeIf { it > 0 },
)

private fun TrackDescriptor.toVideoStreamInfo(): VideoStreamInfo = VideoStreamInfo(
    width = width,
    height = height,
    frameRate = frameRate,
    sampleMimeType = mimeType,
    codecs = codecs,
    bitrate = bitrate.takeIf { it > 0 },
    colorTransfer = colorTransfer,
)

private fun TrackDescriptor.toAudioStreamInfo(): AudioStreamInfo = AudioStreamInfo(
    sampleMimeType = mimeType,
    codecs = codecs,
    channelCount = channelCount,
    language = language,
    bitrate = bitrate.takeIf { it > 0 },
)
