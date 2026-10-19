package com.teledrive.files.viewer

import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.teledrive.core.model.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ViewerScreen(stableId: String, imageIds: List<String> = emptyList(), onBack: () -> Unit, viewModel: ViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val pages = remember(imageIds, stableId) { (imageIds.ifEmpty { listOf(stableId) }).distinct() }
    val pagerState = rememberPagerState(initialPage = pages.indexOf(stableId).coerceAtLeast(0)) { pages.size }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> if (uri != null) viewModel.export(uri) }
    LaunchedEffect(stableId) { viewModel.load(stableId) }
    LaunchedEffect(pagerState.currentPage, pages) { pages.getOrNull(pagerState.currentPage)?.let(viewModel::load) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, "Back", tint = Color.White) }
            Text(state.file?.originalFilename ?: "Viewer", Modifier.weight(1f), color = Color.White, maxLines = 1)
        }
        when {
            state.loading -> Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (state.progress != null) LinearProgressIndicator(progress = { state.progress ?: 0f },  modifier = Modifier.fillMaxWidth(.72f)) else CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp)); Text(if (state.totalBytes != null) "Downloaded ${state.transferredBytes} of ${state.totalBytes} bytes" else "Downloading for offline viewing…", color = Color.White)
                TextButton(onClick = viewModel::cancelDownload) { Text("Cancel") }
            }
            state.file == null -> ViewerFailure(state.error ?: "File unavailable", viewModel::openExternal, { exportPicker.launch("file") })
            state.error != null -> ViewerFailure(state.error!!, viewModel::openExternal, { exportPicker.launch(state.file!!.originalFilename) })
            state.file!!.kind == FileKind.IMAGE -> HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                val current = state.file
                if (pages.getOrNull(page) == current?.stableId && current.localPath != null) ImageViewer(current.localPath!!, current.originalFilename, viewModel::openExternal, { exportPicker.launch(current.originalFilename) })
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            }
            state.file!!.kind == FileKind.VIDEO || state.file!!.kind == FileKind.AUDIO -> MediaViewer(state.file!!.localPath!!, state.file!!.kind == FileKind.VIDEO, state.file!!.originalFilename, viewModel::openExternal, { exportPicker.launch(state.file!!.originalFilename) })
            else -> ViewerFailure("No built-in viewer is available for this file type.", viewModel::openExternal, { exportPicker.launch(state.file!!.originalFilename) })
        }
    }
}

@Composable
private fun ViewerFailure(message: String, openExternal: () -> Unit, export: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = openExternal) { Text("Open in another app") }
        TextButton(onClick = export) { Text("Export", color = Color.White) }
    }
}

@Composable
private fun ImageViewer(path: String, name: String, openExternal: () -> Unit, export: () -> Unit) {
    val animated = Build.VERSION.SDK_INT >= 28 && path.substringAfterLast('.', "").lowercase() in setOf("gif", "webp")
    var decoded by remember(path) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) { value = if (animated) null else withContext(Dispatchers.IO) { decodeOriented(path) }; decoded = true }
    val drawable by produceState<Drawable?>(null, path, animated) { value = if (animated) withContext(Dispatchers.IO) { decodeAnimated(path) } else null; if (animated) decoded = true }
    DisposableEffect(bitmap, drawable) { onDispose { bitmap?.recycle(); (drawable as? AnimatedImageDrawable)?.stop() } }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var panX by remember(path) { mutableFloatStateOf(0f) }
    var panY by remember(path) { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().pointerInput(path) {
        detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            panX = if (scale > 1f) panX + pan.x else 0f
            panY = if (scale > 1f) panY + pan.y else 0f
        }
    }.pointerInput(path) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; panX = 0f; panY = 0f } else scale = 2.5f }) }, contentAlignment = Alignment.Center) {
        if (drawable != null) AndroidView(factory = { context -> ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        } }, modifier = Modifier.fillMaxSize())
        else bitmap?.let { Image(it.asImageBitmap(), name, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)) }
            ?: if (decoded) ViewerFailure("This image format could not be decoded.", openExternal, export) else CircularProgressIndicator(color = Color.White)
    }
}

private fun decodeOriented(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096 || (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 16_000_000) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
    if (degrees == 0f) return bitmap
    return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true).also { if (it !== bitmap) bitmap.recycle() }
}

private fun decodeAnimated(path: String): Drawable? {
    if (Build.VERSION.SDK_INT < 28) return null
    return runCatching {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            val scale = minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }.getOrNull()
}

@Composable
private fun ColumnScope.MediaViewer(path: String, video: Boolean, title: String, openExternal: () -> Unit, export: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var position by rememberSaveable(path) { mutableLongStateOf(0L) }
    var speed by rememberSaveable(path) { mutableFloatStateOf(1f) }
    val player = remember(path) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, FileDataSource.Factory()))).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(if (video) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(File(path).toUri()))
            prepare(); seekTo(position); setPlaybackSpeed(speed); playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { failure = "Can't play this media: ${error.errorCodeName}" }
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            })
        }
    }
    DisposableEffect(player, lifecycle, video) {
        val activity = context as? android.app.Activity
        val oldOrientation = activity?.requestedOrientation
        if (video) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (oldOrientation != null) activity?.requestedOrientation = oldOrientation
            player.release()
        }
    }
    SideEffect { val window = (context as? android.app.Activity)?.window; if (playing) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    var duration by remember(path) { mutableLongStateOf(0L) }
    LaunchedEffect(player) { while (true) { position = player.currentPosition.coerceAtLeast(0); duration = player.duration.coerceAtLeast(0); delay(500) } }
    if (video && failure == null) {
        val mediaPlayer = player
        AndroidView(factory = { viewContext -> PlayerView(viewContext).apply { this.player = mediaPlayer; useController = true } }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
    else Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Slider(value = if (duration > 0) position.toFloat() / duration else 0f, onValueChange = { player.seekTo((it * duration).toLong()) })
        Text("${timeLabel(position)} / ${timeLabel(duration)}", color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White) }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White) }
            IconButton(onClick = { player.seekTo(player.currentPosition + 10_000) }) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Color.White) }
            TextButton(onClick = { speed = if (speed >= 2f) 0.5f else speed + 0.5f; player.setPlaybackSpeed(speed) }) { Text("${speed}×", color = Color.White) }
        }
    }
    if (failure != null) ViewerFailure(failure!!, openExternal, export)
}

private fun timeLabel(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
