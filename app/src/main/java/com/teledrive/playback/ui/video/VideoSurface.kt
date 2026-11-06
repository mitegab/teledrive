package com.teledrive.playback.ui.video

import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.teledrive.playback.video.CaptionPalette
import com.teledrive.playback.video.CaptionSize
import com.teledrive.playback.video.VideoAspect
import com.teledrive.playback.video.normalizeRotation

/**
 * The actual picture, rendered by Media3's [PlayerView] without its controller so the
 * Compose chrome owns every control.
 *
 * Caption styling is applied to the player's own subtitle view so embedded SSA/ASS styling
 * and fixed text sizes can be overridden by the user's choices in the settings sheet.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    player: Player,
    aspect: VideoAspect,
    rotationDegrees: Int,
    captionSize: CaptionSize,
    captionPalette: CaptionPalette,
    captionsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val rotation = normalizeRotation(rotationDegrees)
    AndroidView(
        modifier = modifier.rotateSurface(rotation),
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShutterBackgroundColor(AndroidColor.BLACK)
                setBackgroundColor(AndroidColor.BLACK)
                resizeMode = aspect.toResizeMode()
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                this.player = player
            }
        },
        update = { view ->
            view.resizeMode = aspect.toResizeMode()
            view.player = player
            view.subtitleView?.let { subtitle ->
                subtitle.setStyle(captionPalette.toCaptionStyle())
                subtitle.setApplyEmbeddedStyles(false)
                subtitle.setApplyEmbeddedFontSizes(false)
                subtitle.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * captionSize.scale)
                subtitle.setBottomPaddingFraction(0.11f)
                subtitle.visibility = if (captionsVisible) View.VISIBLE else View.GONE
            }
        },
        onRelease = { view ->
            // The player is a singleton owned by the controller; never release it here.
            view.player = null
        },
    )
}

private fun VideoAspect.toResizeMode(): Int = when (this) {
    VideoAspect.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoAspect.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoAspect.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

private fun CaptionPalette.toCaptionStyle(): CaptionStyleCompat = CaptionStyleCompat(
    foreground.toInt(),
    background.toInt(),
    AndroidColor.TRANSPARENT,
    if (outlined) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
    AndroidColor.BLACK,
    null,
)

/**
 * Rotates a view by 90° steps. A quarter turn must also swap the measured width and height,
 * otherwise the picture is squeezed into a portrait box. 0/180 keep the normal measurement.
 */
private fun Modifier.rotateSurface(degrees: Int): Modifier {
    val normalized = normalizeRotation(degrees)
    if (normalized == 0) return this
    val swapped = normalized == 90 || normalized == 270
    val rotated = if (swapped) {
        layout { measurable, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val placeable = measurable.measure(
                androidx.compose.ui.unit.Constraints.fixed(height, width)
            )
            layout(width, height) {
                placeable.place((width - height) / 2, (height - width) / 2)
            }
        }
    } else {
        this
    }
    return rotated.graphicsLayer { rotationZ = normalized.toFloat() }
}
