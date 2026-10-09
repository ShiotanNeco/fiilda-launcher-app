package com.fiilda.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Built in, photo, media, and external widget tile rendering. */
private const val MediaProgressTrackAlpha = 0.65f

@Composable
internal fun HomeWidgetTile(
    widget: HomeWidget,
    now: LocalDateTime,
    posture: Posture,
    gridSize: WidgetGridSize,
    battery: BatteryStatus,
    onWeather: () -> Unit,
    onCalendar: () -> Unit,
    onMedia: () -> Unit,
    photoUri: String?,
    isVideoMuted: Boolean,
    onVideoMuteChanged: (Boolean) -> Unit,
    mediaState: MediaSessionState,
    onOpenMediaSettings: () -> Unit,
) {
    when (widget) {
        HomeWidget.MEDIA -> if (gridSize.rowSpan == 1) {
            CompactMediaTile(
                columns = gridSize.columnSpan,
                onMedia = onMedia,
                mediaState = mediaState,
                onOpenMediaSettings = onOpenMediaSettings,
            )
        } else {
            MediaControlTile(
                state = mediaState,
                modifier = Modifier.fillMaxSize(),
                onClick = onMedia,
                onOpenSettings = onOpenMediaSettings,
                exact = true,
            )
        }
        HomeWidget.PHOTO -> PhotoFrameTile(
            uriString = photoUri,
            gridSize = gridSize,
            isMuted = isVideoMuted,
            onMuteChanged = onVideoMuteChanged,
        )
        else -> BuiltInWidgetTile(
            widget = widget,
            now = now,
            posture = posture,
            gridSize = gridSize,
            battery = battery,
            onWeather = onWeather,
            onCalendar = onCalendar,
        )
    }
}

/** One-row media strip: artwork thumbnail, title, and themed transport buttons. */
@Composable
private fun CompactMediaTile(
    columns: Int,
    onMedia: () -> Unit,
    mediaState: MediaSessionState,
    onOpenMediaSettings: () -> Unit,
) {
    val snapshot = mediaState.snapshot
    val metroColor = rememberMetroMediaTileColor(snapshot)
    ProvideWidgetStyle(HomeWidget.MEDIA, metroTileColor = metroColor) {
        FiiLDATile(
            modifier = Modifier.fillMaxSize(),
            onClick = onMedia,
            widget = HomeWidget.MEDIA,
            metroSurfaceColor = metroColor,
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                snapshot.albumArt?.let { art ->
                    androidx.compose.foundation.Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (snapshot.albumArt != null) 10.dp else 0.dp, end = 6.dp),
                ) {
                    Text(
                        text = snapshot.title,
                        color = FiiLDAInk,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = snapshot.artist,
                        color = FiiLDAMuted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!mediaState.hasAccess) {
                    WidgetActionButton(
                        text = tr("通知アクセスを有効化", "Allow notification access"),
                        onClick = onOpenMediaSettings,
                        modifier = Modifier.width(if (columns >= 4) 180.dp else 120.dp),
                    )
                } else {
                    MediaTransportRow(
                        state = mediaState,
                        height = 40.dp,
                        showPrevious = columns >= 4,
                        modifier = Modifier.width(if (columns >= 4) 170.dp else 96.dp),
                    )
                }
            }
        }
    }
}

private data class PhotoFrameLoadState(
    val bitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val hasSelection: Boolean = false,
)

@Composable
internal fun PhotoPreviewDialog(
    uriString: String?,
    isMuted: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val isVideo = remember(uriString) {
        isPhotoFrameVideo(context, uriString)
    }
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = uriString,
        key2 = isVideo,
    ) {
        value = if (isVideo || uriString.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            decodePhotoFrameBitmap(context, uriString)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LauncherDialogSystemBars(
            barColor = Color.Black,
            lightBars = false,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo && !uriString.isNullOrBlank()) {
                LoopingVideoFrame(
                    uriString = uriString,
                    isMuted = isMuted,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                )
            } else if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = tr("拡大表示中の画像", "Enlarged image"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                )
            } else {
                Text(
                    text = if (uriString.isNullOrBlank()) tr("画像または動画がありません", "No image or video") else tr("読み込み中…", "Loading…"),
                    color = FiiLDAPhotoPreviewInk,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun PhotoFrameTile(
    uriString: String?,
    gridSize: WidgetGridSize,
    isMuted: Boolean,
    onMuteChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val isVideo = remember(uriString) {
        isPhotoFrameVideo(context, uriString)
    }
    val initialLoadState = remember(uriString, isVideo) {
        PhotoFrameLoadState(
            isLoading = false,
            hasSelection = !uriString.isNullOrBlank(),
        )
    }
    val loadState by produceState(
        initialValue = initialLoadState,
        key1 = uriString,
        key2 = isVideo,
    ) {
        when {
            uriString.isNullOrBlank() || isVideo -> value = PhotoFrameLoadState(
                hasSelection = !uriString.isNullOrBlank(),
            )
            else -> {
                value = PhotoFrameLoadState(isLoading = true, hasSelection = true)
                value = PhotoFrameLoadState(
                    bitmap = withContext(Dispatchers.IO) {
                        decodePhotoFrameBitmap(context, uriString)
                    },
                    hasSelection = true,
                )
            }
        }
    }
    val compact = gridSize.rowSpan == 1
    val framePadding = if (compact) 4.dp else 7.dp
    val bitmap = loadState.bitmap
    val glassEnabled = LocalLauncherGlass.current.enabled

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (LocalLauncherGlass.current.enabled) {
                    Modifier.launcherGlassTile(fallbackColor = FiiLDAPhotoFrameSurface)
                } else {
                    Modifier.launcherShapedSurface(
                        LauncherTileShape,
                        FiiLDALineStrong,
                        FiiLDAPhotoFrameSurface,
                    )
                },
            )
            .padding(framePadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .launcherBorder(1.dp, FiiLDALine)
                .then(if (glassEnabled) Modifier else Modifier.background(FiiLDABlack))
                // Compose bitmaps participate in the sharp scene; VideoView does not, so publish
                // only the stable frame fallback for that path.
                .launcherGlassContributor(
                    fallbackColor = FiiLDAPhotoFrameSurface.takeIf { isVideo },
                ),
        ) {
            if (isVideo && !uriString.isNullOrBlank()) {
                LoopingVideoFrame(
                    uriString = uriString,
                    isMuted = isMuted,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                )
            } else if (bitmap != null) {
                // Compose owns the crop and clip rectangle. AndroidView/ImageView can retain a
                // provider-sized drawing layer across remeasurement and paint outside this frame.
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer { clip = true },
                )
            }

            if (!isVideo && bitmap == null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when {
                            loadState.isLoading -> tr("読み込み中…", "Loading…")
                            loadState.hasSelection -> tr("画像を読み込めません", "Couldn't load the image")
                            else -> tr("画像または動画を選択", "Choose an image or video")
                        },
                        color = FiiLDAInk,
                        fontSize = if (compact) 9.sp else 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!loadState.isLoading) {
                        Text(
                            text = tr("タップして変更", "Tap to change"),
                            color = FiiLDAMuted,
                            fontSize = if (compact) 7.sp else 9.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (isVideo && !uriString.isNullOrBlank()) {
                PhotoFrameMuteToggle(
                    isMuted = isMuted,
                    onToggle = { onMuteChanged(!isMuted) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoFrameMuteToggle(
    isMuted: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionLabel = if (isMuted) tr("音声をオンにする", "Unmute") else tr("ミュートする", "Mute")
    Box(
        modifier = modifier
            .size(34.dp)
            .then(
                if (LocalLauncherTheme.current == LauncherTheme.WINDOWS_8) {
                    Modifier.clipToBounds()
                } else {
                    Modifier.clip(CircleShape)
                },
            )
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Switch
                contentDescription = if (isMuted) tr("ミュート中", "Muted") else tr("音声オン", "Sound on")
                stateDescription = if (isMuted) tr("ミュート中。$actionLabel", "Muted. $actionLabel") else tr("音声オン。$actionLabel", "Sound on. $actionLabel")
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier
                .size(20.dp)
                // Difference blending turns the white logo black over white and white over black,
                // keeping the transparent control legible without a solid backdrop.
                .graphicsLayer {
                    blendMode = BlendMode.Difference
                    alpha = 0.94f
                },
        )
    }
}

/**
 * Plays a selected video directly inside the photo-frame tile. VideoView's MediaPlayer is set
 * to looping so playback restarts without exposing controls or a second screen to the user.
 */
@Composable
private fun LoopingVideoFrame(
    uriString: String,
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = LocalHomeBoardVisible.current,
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PassiveVideoView(viewContext).apply {
                tag = uriString
                setMuted(isMuted)
                setVideoURI(Uri.parse(uriString))
                setOnPreparedListener { player ->
                    mediaPlayer = player
                    player.isLooping = true
                    applyCurrentMute()
                    if (!isPlaying && this.active) start()
                }
                // Some providers report completion even when MediaPlayer.isLooping is set. Keep
                // the explicit restart as a fallback for those implementations.
                setOnCompletionListener {
                    if (!isPlaying && this.active) start()
                }
                setOnErrorListener { _, _, _ -> true }
            }
        },
        update = { videoView ->
            videoView.setMuted(isMuted)
            videoView.active = active
            if (videoView.tag != uriString) {
                videoView.tag = uriString
                videoView.stopPlayback()
                videoView.mediaPlayer = null
                videoView.setVideoURI(Uri.parse(uriString))
            }
        },
        onRelease = { videoView ->
            videoView.mediaPlayer = null
            videoView.stopPlayback()
        },
    )
}

/**
 * The launcher owns the tile tap, long-press, and reorder gestures. VideoView normally consumes
 * touch events to toggle media controls, so let those events bubble back to the Compose board.
 */
private class PassiveVideoView(context: Context) : VideoView(context) {
    var mediaPlayer: MediaPlayer? = null

    /** Plays only while its Home page is shown; a composed but hidden page stays paused. */
    var active = true
        set(value) {
            if (field == value) return
            field = value
            if (mediaPlayer != null) {
                if (value) {
                    if (!isPlaying) start()
                } else if (isPlaying) {
                    pause()
                }
            }
        }
    private var muted = true

    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        applyCurrentMute()
    }

    fun applyCurrentMute() {
        mediaPlayer?.let { player ->
            runCatching {
                val volume = if (muted) 0f else 1f
                player.setVolume(volume, volume)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

internal fun isPhotoFrameVideo(context: Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
    val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    if (mimeType?.startsWith("video/", ignoreCase = true) == true) return true
    if (mimeType?.startsWith("image/", ignoreCase = true) == true) return false

    val path = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
    return listOf(
        ".3gp",
        ".avi",
        ".m4v",
        ".mkv",
        ".mov",
        ".mp4",
        ".mpeg",
        ".mpg",
        ".ts",
        ".webm",
    ).any(path::endsWith)
}

private fun decodePhotoFrameBitmap(context: Context, uriString: String): Bitmap? = runCatching {
    val uri = Uri.parse(uriString)
    val resolver = context.contentResolver
    // EXIF is optional metadata. Some document providers expose a valid image stream that
    // ExifInterface cannot parse; that must not prevent the independent BitmapFactory decode.
    val exifOrientation = readPhotoFrameExifOrientation(context, uri)
    val decoded = resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)
    } ?: return@runCatching null
    applyPhotoFrameExifTransform(decoded, photoFrameExifTransform(exifOrientation))
}.getOrNull()

private fun readPhotoFrameExifOrientation(context: Context, uri: Uri): Int = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun applyPhotoFrameExifTransform(
    bitmap: Bitmap,
    transform: PhotoFrameExifTransform,
): Bitmap {
    if (transform.rotationDegrees == 0 && !transform.mirrorHorizontal) return bitmap
    val matrix = Matrix().apply {
        setRotate(transform.rotationDegrees.toFloat())
        if (transform.mirrorHorizontal) postScale(-1f, 1f)
    }
    val transformed = runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrNull() ?: return bitmap
    if (transformed !== bitmap && !bitmap.isRecycled) bitmap.recycle()
    return transformed
}

@Composable
internal fun ExternalWidgetTile(
    descriptor: LauncherWidgetDescriptor,
    appWidgetHost: AppWidgetHost,
    appWidgetManager: AppWidgetManager,
    gridSize: WidgetGridSize,
    sizeLabel: String,
    cellWidth: Dp,
    gap: Dp,
    onLongPressAccepted: (x: Float, y: Float, generation: Long) -> Boolean,
    onGestureStarted: () -> Long,
    onPointerMove: (x: Float, y: Float) -> Unit,
    onPointerUp: (x: Float, y: Float) -> Boolean,
    onGestureCancel: () -> Unit,
    onGestureEnabled: () -> Boolean,
    onHostBoundsChanged: (HomeItemBounds?) -> Unit,
    glassSceneEnabled: Boolean = true,
    glassSceneAlpha: Float = 1f,
    glassSceneZIndex: Float = 0f,
    glassSceneGeometryVersion: Any? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val currentOnHostBoundsChanged = rememberUpdatedState(onHostBoundsChanged)
    val infoState = remember(descriptor) {
        mutableStateOf(
            runCatching { appWidgetManager.getAppWidgetInfo(descriptor.appWidgetId) }.getOrNull(),
        )
    }
    // A retained widget whose provider is locked (paused work profile, private space) has no info
    // yet. Retry on resume so it reappears once the profile unlocks, without recreating the tile.
    LifecycleResumeEffect(descriptor) {
        if (infoState.value == null) {
            infoState.value = runCatching {
                appWidgetManager.getAppWidgetInfo(descriptor.appWidgetId)
            }.getOrNull()
        }
        onPauseOrDispose { }
    }
    val info = infoState.value
    val width = cellWidth * gridSize.columnSpan + gap * (gridSize.columnSpan - 1)
    val height = cellWidth * gridSize.rowSpan + gap * (gridSize.rowSpan - 1)
    val hostInset = 1.dp
    val contentWidth = maxOf(1.dp, width - hostInset * 2)
    val contentHeight = maxOf(1.dp, height - hostInset * 2)
    // Key the measurement by the new requested footprint so a previous posture's dimensions can
    // never be sent after resize. The first update uses the exact requested Dp fallback; the next
    // layout pass replaces it with the actual AppWidgetHostView size converted back to Dp. The
    // updateAppWidgetSize API receives this full host footprint and subtracts framework padding
    // internally; AUTO span resolution still accounts for that padding separately.
    val measuredInnerSizePx = remember(
        descriptor.appWidgetId,
        width,
        height,
        density.density,
    ) { mutableStateOf<Pair<Int, Int>?>(null) }
    val measuredSize = measuredInnerSizePx.value
    val measuredHostWidthDp = measuredSize?.first?.let { it / density.density } ?: contentWidth.value
    val measuredHostHeightDp = measuredSize?.second?.let { it / density.density } ?: contentHeight.value
    val actualWidthDp = measuredHostWidthDp.coerceAtLeast(1f)
    val actualHeightDp = measuredHostHeightDp.coerceAtLeast(1f)
    val options = remember(actualWidthDp, actualHeightDp) {
        Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, actualWidthDp.roundToInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, actualWidthDp.roundToInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, actualHeightDp.roundToInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, actualHeightDp.roundToInt())
        }
    }

    if (info == null) {
        SideEffect { currentOnHostBoundsChanged.value(null) }
        EmptyPanel(text = tr("このウィジェットは利用できません", "This widget is unavailable"), modifier = Modifier.height(height))
        return
    }

    // AppWidgetHostView does not reapply the provider's RemoteViews for a night-mode change.
    // Recreate only this host subtree so night-qualified widget resources are resolved with the
    // current configuration while the launcher catalog and icon state survive.
    val nightMode = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK
    key(descriptor.appWidgetId, nightMode) {
        val hostView = remember(descriptor.appWidgetId) {
            runCatching { appWidgetHost.createView(context, descriptor.appWidgetId, info) }.getOrNull()
        }
        if (hostView == null) {
            SideEffect { currentOnHostBoundsChanged.value(null) }
            EmptyPanel(text = tr("ウィジェットを表示できません", "Couldn't show the widget"), modifier = Modifier.height(height))
        } else {
            val currentOnLongPressAccepted = rememberUpdatedState(onLongPressAccepted)
            val currentOnGestureStarted = rememberUpdatedState(onGestureStarted)
            val currentOnPointerMove = rememberUpdatedState(onPointerMove)
            val currentOnPointerUp = rememberUpdatedState(onPointerUp)
            val currentOnGestureCancel = rememberUpdatedState(onGestureCancel)
            val currentOnGestureEnabled = rememberUpdatedState(onGestureEnabled)
            val launcherHostView = hostView as? LauncherAppWidgetHostView
            androidx.compose.runtime.DisposableEffect(hostView) {
                launcherHostView?.apply {
                    onWidgetLongPressAccepted = { x, y, generation ->
                        currentOnLongPressAccepted.value(x, y, generation)
                    }
                    onWidgetGestureStarted = { currentOnGestureStarted.value() }
                    onWidgetPointerMove = { x, y ->
                        currentOnPointerMove.value(x, y)
                    }
                    onWidgetPointerUp = { x, y ->
                        currentOnPointerUp.value(x, y)
                    }
                    onWidgetGestureCancel = {
                        currentOnGestureCancel.value()
                    }
                    onWidgetGestureEnabled = {
                        currentOnGestureEnabled.value()
                    }
                }
                onDispose {
                    launcherHostView?.apply {
                        onWidgetLongPressAccepted = null
                        onWidgetGestureStarted = null
                        onWidgetPointerMove = null
                        onWidgetPointerUp = null
                        onWidgetGestureCancel = null
                        onWidgetGestureEnabled = null
                    }
                    currentOnHostBoundsChanged.value(null)
                }
            }

            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .then(
                        if (LocalLauncherGlass.current.enabled) {
                            Modifier
                                .launcherGlassTile(fallbackColor = FiiLDASurface)
                                // AppWidgetHostView is a native surface. Publish only its stable
                                // fallback representation to the scene registry.
                                .launcherGlassContributor(
                                    enabled = glassSceneEnabled,
                                    alpha = glassSceneAlpha,
                                    zIndex = glassSceneZIndex,
                                    fallbackColor = FiiLDASurface,
                                    geometryVersion = glassSceneGeometryVersion,
                                )
                        } else {
                            Modifier.launcherShapedSurface(
                                LauncherTileShape,
                                FiiLDALine,
                                FiiLDASurface,
                            )
                        },
                    )
                    .semantics(mergeDescendants = false) {
                        // Keep the provider's own AndroidView semantics as child nodes. A content
                        // description on AppWidgetHostView itself would turn many RemoteViews into an
                        // accessibility leaf and hide their controls.
                        contentDescription = descriptor.label.ifBlank { tr("ウィジェット", "Widget") }
                        stateDescription = tr("ホームウィジェット、サイズ $sizeLabel", "Home widget, size $sizeLabel")
                    },
            ) {
                // Keep a thin launcher-owned border outside the AndroidView. The border remains a
                // HomeDragItem long-press target; touches in the inset child region belong to the custom
                // host view, which can cancel provider clicks after its own long-press timeout.
                Box(modifier = Modifier.fillMaxSize().padding(hostInset)) {
                    AndroidView(
                        factory = { hostView },
                        update = { view: AppWidgetHostView ->
                            // Provider options are sent from the measured board footprint, not from a fixed
                            // phone-size assumption.  The host view also receives the same options so
                            // providers can re-layout their remote views for the current posture.
                            val currentPadding = info.provider?.let { provider ->
                                centeredWidgetHostPadding(defaultWidgetPaddingForProvider(context, provider))
                            } ?: Rect()
                            if (view is LauncherAppWidgetHostView) {
                                view.updateAppWidgetSizeIfNeeded(
                                    provider = info.provider,
                                    options = options,
                                    widthDp = actualWidthDp,
                                    heightDp = actualHeightDp,
                                    padding = currentPadding,
                                )
                            } else {
                                // The launcher always creates LauncherAppWidgetHostView, but retain a
                                // defensive fallback for a custom host supplied by a future caller.
                                runCatching {
                                    if (
                                        view.paddingLeft != currentPadding.left ||
                                        view.paddingTop != currentPadding.top ||
                                        view.paddingRight != currentPadding.right ||
                                        view.paddingBottom != currentPadding.bottom
                                    ) {
                                        view.setPadding(
                                            currentPadding.left,
                                            currentPadding.top,
                                            currentPadding.right,
                                            currentPadding.bottom,
                                        )
                                    }
                                    val widthDp = actualWidthDp.roundToInt()
                                    val heightDp = actualHeightDp.roundToInt()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        view.updateAppWidgetSize(
                                            options,
                                            listOf(SizeF(actualWidthDp, actualHeightDp)),
                                        )
                                    } else {
                                        view.updateAppWidgetSize(
                                            options,
                                            widthDp,
                                            heightDp,
                                            widthDp,
                                            heightDp,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                val measured = coordinates.size.width to coordinates.size.height
                                if (measuredInnerSizePx.value != measured) {
                                    measuredInnerSizePx.value = measured
                                }
                                val position = coordinates.positionInRoot()
                                val size = coordinates.size
                                currentOnHostBoundsChanged.value(
                                    HomeItemBounds(
                                        id = descriptor.homeId,
                                        left = position.x,
                                        top = position.y,
                                        right = position.x + size.width,
                                        bottom = position.y + size.height,
                                    ),
                                )
                            },
                    )
                }
            }
        }
    }
}

/** A static 1×1 home tile for a website or other publisher-provided shortcut. */
@Composable
internal fun PinnedShortcutTile(
    shortcut: ResolvedPinnedShortcut,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (LocalLauncherGlass.current.enabled) {
                    Modifier.launcherGlassTile(fallbackColor = FiiLDASurface)
                } else {
                    Modifier.launcherShapedSurface(LauncherTileShape, FiiLDALine, FiiLDASurface)
                },
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.launcherGlassContributor(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                },
                update = { imageView ->
                    imageView.setImageDrawable(
                        shortcut.icon ?: imageView.context.getDrawable(android.R.drawable.sym_def_app_icon),
                    )
                },
                modifier = Modifier.size(42.dp),
            )
            if (shouldRenderAppLabel(LocalShowAppLabels.current)) {
                Text(
                    text = shortcut.label,
                    color = FiiLDAInk,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MediaControlTile(
    state: MediaSessionState,
    modifier: Modifier,
    onClick: () -> Unit,
    onOpenSettings: () -> Unit,
    exact: Boolean = false,
) {
    val snapshot = state.snapshot
    var tickerElapsedRealtime by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(
        snapshot.position,
        snapshot.positionUpdateTime,
        snapshot.playbackSpeed,
        snapshot.isPlaying,
        snapshot.duration,
    ) {
        if (!snapshot.isPlaying || snapshot.duration <= 0L) return@LaunchedEffect
        while (true) {
            tickerElapsedRealtime = SystemClock.elapsedRealtime()
            delay(250L)
        }
    }
    val displayedPosition = mediaPositionAt(
        position = snapshot.position,
        duration = snapshot.duration,
        positionUpdateTime = snapshot.positionUpdateTime,
        playbackSpeed = snapshot.playbackSpeed,
        isPlaying = snapshot.isPlaying,
        nowElapsedRealtime = tickerElapsedRealtime,
    )
    val progress = if (snapshot.duration > 0L) {
        (displayedPosition.toFloat() / snapshot.duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val controller = state.controller
    val albumArt = snapshot.albumArt
    val glassEnabled = LocalLauncherGlass.current.enabled
    val palette = LocalLauncherPalette.current
    val metroColor = rememberMetroMediaTileColor(snapshot)
    val tileSurface = metroColor ?: palette.surface
    // Cover art can use arbitrary colors, so use the theme's high-contrast ink for secondary
    // media content only while the artwork is present. Preserve the established roles on plain
    // themed tiles.
    val mediaSecondaryColor = if (albumArt != null) FiiLDAInk else FiiLDAMuted
    val mediaProgressTrackColor = if (albumArt != null) {
        FiiLDAInk.copy(alpha = MediaProgressTrackAlpha)
    } else {
        FiiLDALine
    }
    val mediaProgressFillColor = if (albumArt != null) FiiLDAInk else FiiLDACyan
    val rootModifier = modifier
        .let { if (exact) it.fillMaxSize() else it.aspectRatio(1f) }
        .then(
            if (glassEnabled) {
                // Artwork remains sharp foreground. The glass is visible in its letterbox
                // margins and around its rounded footprint, like other built-in tiles.
                Modifier.launcherGlassTile(fallbackColor = tileSurface)
            } else {
                // Without artwork, preserve the normal opaque themed tile surface. Artwork
                // tiles receive their own contrast layer below so the image stays sharp.
                // Metro keeps its artwork-colored surface visible in the letterbox margins.
                Modifier.launcherShapedSurface(
                    LauncherTileShape,
                    FiiLDALine,
                    tileSurface.takeIf { albumArt == null || metroColor != null },
                )
            },
        )
        .clickable(onClick = onClick)

    ProvideWidgetStyle(HomeWidget.MEDIA, metroTileColor = metroColor) {
    Box(modifier = rootModifier) {
        // Register the sharp media artwork/controls below the tile glass. The outer optical
        // surface itself must never be replayed into the navigation scene.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .launcherGlassContributor(),
        ) {
        albumArt?.let { albumArtBitmap ->
            AndroidView(
                factory = { viewContext ->
                    ImageView(viewContext).apply {
                        // 横長のYouTubeサムネイルも左右を切り取らず、ウィジェット内に収める。
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        alpha = 1f
                    }
                },
                update = { imageView ->
                    imageView.setImageBitmap(albumArtBitmap)
                    imageView.alpha = 1f
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Only the title and control bands are shaded, so the artwork itself stays at full
        // brightness in the middle of the tile.
        if (albumArt != null) {
            MediaArtworkScrim(modifier = Modifier.fillMaxSize())
        }

        MediaPerimeterProgress(
            progress = progress,
            trackColor = mediaProgressTrackColor,
            progressColor = mediaProgressFillColor,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val hasArtwork = albumArt != null
            Column(modifier = Modifier.padding(top = 6.dp).fillMaxWidth().mediaArtworkBand(hasArtwork)) {
                Text(
                    text = snapshot.title,
                    color = FiiLDAInk,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = mediaTextStyle(hasArtwork),
                )
                Text(
                    text = snapshot.artist,
                    color = mediaSecondaryColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = mediaTextStyle(hasArtwork),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (!state.hasAccess) {
                WidgetActionButton(
                    text = tr("通知へのアクセスを有効化", "Allow notification access"),
                    onClick = onOpenSettings,
                )
            }

            MediaTransportRow(
                state = state,
                height = 46.dp,
                modifier = Modifier.fillMaxWidth().mediaArtworkBand(albumArt != null),
            )
        }
        }
    }
    }
}

/** A point on the rectangular media progress path, kept as pure data for focused geometry tests. */
internal data class MediaPerimeterPoint(
    val x: Float,
    val y: Float,
)

/**
 * Returns the visible portion of a clockwise perimeter path. The path starts at the inner
 * top-center (12 o'clock) and proceeds across the top, down the right, across the bottom, then up
 * the left. A complete path omits the duplicate closing point; callers close it explicitly so the
 * final corner remains joined at 100%.
 */
internal fun mediaPerimeterProgressPoints(
    width: Float,
    height: Float,
    inset: Float,
    progress: Float,
): List<MediaPerimeterPoint> {
    val safeWidth = width.coerceAtLeast(0f)
    val safeHeight = height.coerceAtLeast(0f)
    val safeInset = inset.coerceAtLeast(0f)
    val left = safeInset.coerceAtMost(safeWidth / 2f)
    val top = safeInset.coerceAtMost(safeHeight / 2f)
    val right = (safeWidth - safeInset).coerceAtLeast(left)
    val bottom = (safeHeight - safeInset).coerceAtLeast(top)
    val topCenter = (left + right) / 2f
    val corners = listOf(
        MediaPerimeterPoint(topCenter, top),
        MediaPerimeterPoint(right, top),
        MediaPerimeterPoint(right, bottom),
        MediaPerimeterPoint(left, bottom),
        MediaPerimeterPoint(left, top),
        MediaPerimeterPoint(topCenter, top),
    )
    val lengths = corners.zipWithNext { from, to ->
        kotlin.math.abs(to.x - from.x) + kotlin.math.abs(to.y - from.y)
    }
    val perimeter = lengths.sum()
    val normalizedProgress = progress.coerceIn(0f, 1f)
    if (perimeter <= 0f || normalizedProgress <= 0f) return emptyList()
    if (normalizedProgress >= 1f) return corners.dropLast(1)

    var remaining = perimeter * normalizedProgress
    val points = mutableListOf(corners.first())
    lengths.forEachIndexed { index, length ->
        if (remaining <= 0f) return@forEachIndexed
        val from = corners[index]
        val to = corners[index + 1]
        if (remaining < length) {
            val fraction = if (length == 0f) 0f else remaining / length
            points += MediaPerimeterPoint(
                x = from.x + (to.x - from.x) * fraction,
                y = from.y + (to.y - from.y) * fraction,
            )
            remaining = 0f
        } else {
            if (index < lengths.lastIndex) points += to
            remaining -= length
        }
    }
    return points
}

/** Rounded perimeter for the media tile; falls back to the square path when the radius is zero. */
internal fun mediaRoundedPerimeterProgressPoints(
    width: Float,
    height: Float,
    inset: Float,
    progress: Float,
    cornerRadius: Float,
): List<MediaPerimeterPoint> {
    val safeWidth = width.coerceAtLeast(0f)
    val safeHeight = height.coerceAtLeast(0f)
    val safeInset = inset.coerceAtLeast(0f)
    val left = safeInset.coerceAtMost(safeWidth / 2f)
    val top = safeInset.coerceAtMost(safeHeight / 2f)
    val right = (safeWidth - safeInset).coerceAtLeast(left)
    val bottom = (safeHeight - safeInset).coerceAtLeast(top)
    val radius = cornerRadius.coerceIn(0f, minOf((right - left) / 2f, (bottom - top) / 2f))
    if (radius <= 0f) {
        return mediaPerimeterProgressPoints(width, height, inset, progress)
    }

    val points = mutableListOf<MediaPerimeterPoint>()
    val topCenter = MediaPerimeterPoint((left + right) / 2f, top)
    fun addArc(centerX: Float, centerY: Float, startDegrees: Float, sweepDegrees: Float) {
        val samples = 8
        repeat(samples + 1) { index ->
            // Skip the first point of every arc because it is the tangent point already emitted by
            // the preceding straight segment.
            if (index == 0) return@repeat
            val degrees = startDegrees + sweepDegrees * (index / samples.toFloat())
            val radians = Math.toRadians(degrees.toDouble())
            points += MediaPerimeterPoint(
                x = centerX + kotlin.math.cos(radians).toFloat() * radius,
                y = centerY + kotlin.math.sin(radians).toFloat() * radius,
            )
        }
    }

    points += topCenter
    points += MediaPerimeterPoint(right - radius, top)
    addArc(right - radius, top + radius, -90f, 90f)
    points += MediaPerimeterPoint(right, bottom - radius)
    addArc(right - radius, bottom - radius, 0f, 90f)
    points += MediaPerimeterPoint(left + radius, bottom)
    addArc(left + radius, bottom - radius, 90f, 90f)
    points += MediaPerimeterPoint(left, top + radius)
    addArc(left + radius, top + radius, 180f, 90f)
    points += topCenter
    points += points.first()

    return progressPointsOnPolyline(points, progress)
}

private fun progressPointsOnPolyline(
    points: List<MediaPerimeterPoint>,
    progress: Float,
): List<MediaPerimeterPoint> {
    if (points.size < 2) return emptyList()
    val lengths = points.zipWithNext { from, to ->
        kotlin.math.hypot(to.x - from.x, to.y - from.y)
    }
    val perimeter = lengths.sum()
    val normalizedProgress = progress.coerceIn(0f, 1f)
    if (perimeter <= 0f || normalizedProgress <= 0f) return emptyList()
    if (normalizedProgress >= 1f) return points.dropLast(1)
    var remaining = perimeter * normalizedProgress
    val output = mutableListOf(points.first())
    lengths.forEachIndexed { index, length ->
        if (remaining <= 0f) return@forEachIndexed
        val from = points[index]
        val to = points[index + 1]
        if (remaining < length) {
            val fraction = if (length == 0f) 0f else remaining / length
            output += MediaPerimeterPoint(
                x = from.x + (to.x - from.x) * fraction,
                y = from.y + (to.y - from.y) * fraction,
            )
            remaining = 0f
        } else {
            if (index < lengths.lastIndex) output += to
            remaining -= length
        }
    }
    return output
}

private fun mediaRoundedPerimeterPath(
    width: Float,
    height: Float,
    inset: Float,
    progress: Float,
    cornerRadius: Float,
): Path = Path().apply {
    val points = mediaRoundedPerimeterProgressPoints(
        width = width,
        height = height,
        inset = inset,
        progress = progress,
        cornerRadius = cornerRadius,
    )
    points.firstOrNull()?.let { first ->
        moveTo(first.x, first.y)
        points.drop(1).forEach { point -> lineTo(point.x, point.y) }
        if (progress >= 1f) close()
    }
}

@Composable
private fun MediaPerimeterProgress(
    progress: Float,
    trackColor: Color,
    progressColor: Color,
    modifier: Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 4.dp.toPx()
        val borderWidth = 1.dp.toPx()
        // Keep the 4dp stroke entirely inside the existing 1dp tile border.
        val inset = borderWidth + strokeWidth / 2f
        val stroke = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Butt,
            join = StrokeJoin.Round,
        )
        // Every theme uses the rounded tile corners, so the progress follows the same radius.
        val perimeterPath = { progress: Float ->
            mediaRoundedPerimeterPath(
                width = size.width,
                height = size.height,
                inset = inset,
                progress = progress,
                cornerRadius = (LauncherTileCornerRadius.toPx() - inset).coerceAtLeast(0f),
            )
        }
        drawPath(
            path = perimeterPath(1f),
            color = trackColor,
            style = stroke,
        )
        if (progress > 0f) {
            drawPath(
                path = perimeterPath(progress),
                color = progressColor,
                style = stroke,
            )
        }
    }
}
