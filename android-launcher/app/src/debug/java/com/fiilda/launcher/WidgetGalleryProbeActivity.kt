package com.fiilda.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

/**
 * Renders every built-in widget except media/photo for one theme and footprint, with fixture
 * battery data. Weather and agenda use the device's real sources. Never touches launcher storage.
 *
 * adb shell am start -n com.fiilda.launcher/.WidgetGalleryProbeActivity --es theme windows8 --es size wide
 */
class WidgetGalleryProbeActivity : ComponentActivity() {
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val theme = parseLauncherThemeToken(intent.getStringExtra("theme"))
        val size = intent.getStringExtra("size") ?: "square"
        val cell = intent.getIntExtra("cell", 96).dp
        val from = intent.getIntExtra("from", 0)
        val cycle = intent.getBooleanExtra("cycle", false)
        if (intent.getBooleanExtra("publishSession", false)) {
            publishProbeSession()
            return
        }
        setContent {
            val scope = rememberCoroutineScope()
            val wallpaper = remember {
                Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).apply {
                    val canvas = Canvas(this)
                    canvas.drawColor(0xFF4F6470.toInt())
                    val paint = Paint().apply { color = 0xFF8FA6B2.toInt() }
                    canvas.drawCircle(560f, 380f, 260f, paint)
                    paint.color = 0xFF2E3E47.toInt()
                    canvas.drawRect(0f, 760f, 800f, 1200f, paint)
                }
            }
            val controller = remember {
                GlassWallpaperController(
                    store = object : GlassWallpaperStore {
                        override fun load() = GlassWallpaperLoadResult(bitmap = wallpaper)
                        override fun importWallpaper(uri: Uri) = GlassWallpaperImportResult()
                        override fun reset() = false
                    },
                    preferences = GlassThemePreferences(this),
                    scope = scope,
                )
            }
            DisposableEffect(controller) {
                controller.start()
                onDispose { controller.invalidatePendingWork() }
            }
            val colorScheme = if (theme == LauncherTheme.MATERIAL && Build.VERSION.SDK_INT >= 31) {
                dynamicDarkColorScheme(this)
            } else {
                darkColorScheme()
            }
            val palette = if (theme == LauncherTheme.MATERIAL) {
                launcherPaletteFromMaterialColorScheme(colorScheme, isLight = false)
            } else {
                launcherPaletteFor(theme)
            }
            CompositionLocalProvider(
                LocalLauncherTheme provides theme,
                LocalLauncherPalette provides palette,
                LocalGlassWallpaperController provides controller,
                LocalGlassReduceTransparency provides false,
                LocalShowAppLabels provides true,
            ) {
                MaterialTheme(colorScheme = colorScheme) {
                    val gallery = @Composable {
                        Gallery(size = size, cell = cell, from = from, cycle = cycle)
                    }
                    if (theme == LauncherTheme.GLASS) {
                        LauncherGlassHost(Modifier.fillMaxSize()) { gallery() }
                    } else {
                        Box(Modifier.fillMaxSize().background(palette.background)) { gallery() }
                    }
                }
            }
        }
    }
}

private var probeSession: android.media.session.MediaSession? = null

/**
 * Publishes a playing session with sample metadata so the real home media tile can be reviewed,
 * then steps aside. The session lives until this process is stopped.
 */
private fun ComponentActivity.publishProbeSession() {
    val session = probeSession ?: android.media.session.MediaSession(applicationContext, "widget-gallery-publish").also {
        probeSession = it
    }
    session.setMetadata(
        android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "サンプル曲（Live Session 2026）")
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "サンプルアーティスト")
            .putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, "probe-" + intent.getStringExtra("art"))
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 214_000L)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, probeArtwork(bright = intent.getStringExtra("art") == "bright"))
            .build(),
    )
    session.setPlaybackState(
        android.media.session.PlaybackState.Builder()
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .setState(android.media.session.PlaybackState.STATE_PLAYING, 83_000L, 1f)
            .build(),
    )
    session.isActive = true
    moveTaskToBack(true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Gallery(size: String, cell: androidx.compose.ui.unit.Dp, from: Int, cycle: Boolean = false) {
    if (size == "media") {
        MediaGallery(cell, cycle)
        return
    }
    val footprints = when (size) {
        "wide" -> listOf(WidgetGridSize(4, 2))
        "strip" -> listOf(WidgetGridSize(4, 1))
        else -> listOf(WidgetGridSize(2, 2), WidgetGridSize(2, 1))
    }
    val widgets = HomeWidget.values().filter { it != HomeWidget.MEDIA && it != HomeWidget.PHOTO }.drop(from)
    val gap = 8.dp
    val battery = UnknownBatteryStatus.copy(
        percent = 72,
        charging = true,
        source = BatteryPowerSource.USB,
        temperatureC = 31.4f,
        healthLabel = tr("良好", "Good"),
        chargeTimeRemainingMillis = 80 * 60_000L,
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(gap * 2),
    ) {
        footprints.forEach { footprint ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                widgets.forEach { widget ->
                    Box(
                        Modifier.size(
                            width = cell * footprint.columnSpan + gap * (footprint.columnSpan - 1),
                            height = cell * footprint.rowSpan + gap * (footprint.rowSpan - 1),
                        ),
                    ) {
                        BuiltInWidgetTile(
                            widget = widget,
                            now = LocalDateTime.now(),
                            posture = Posture.INNER_PORTRAIT,
                            gridSize = footprint,
                            battery = battery,
                            onWeather = {},
                            onCalendar = {},
                        )
                    }
                }
            }
        }
    }
}

/** Synthetic covers: a bright one (worst case for light controls) and a dark saturated one. */
private fun probeArtwork(bright: Boolean): Bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
    val canvas = Canvas(this)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, 512f, 512f,
        if (bright) 0xFFFFF4C2.toInt() else 0xFF2B0F54.toInt(),
        if (bright) 0xFF9AD8FF.toInt() else 0xFFE0457B.toInt(),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, 512f, 512f, paint)
    paint.shader = null
    paint.color = if (bright) 0xFFFFFFFF.toInt() else 0xFFFFB347.toInt()
    canvas.drawCircle(330f, 210f, 120f, paint)
    paint.color = if (bright) 0xFFFFD166.toInt() else 0xFF120726.toInt()
    canvas.drawRect(0f, 380f, 512f, 512f, paint)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaGallery(cell: androidx.compose.ui.unit.Dp, cycle: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = remember { android.media.session.MediaSession(context, "widget-gallery-probe") }
    DisposableEffect(session) { onDispose { session.release() } }
    val bright = remember { probeArtwork(bright = true) }
    val dark = remember { probeArtwork(bright = false) }
    // With cycle, the first tile switches bright → dark → none every 3 s to show the color fade.
    var step by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    if (cycle) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(3_000L)
                step = (step + 1) % 3
            }
        }
    }
    val cycledArt = listOf(bright, dark, null)[step]
    fun state(art: Bitmap?, playing: Boolean, access: Boolean = true) = MediaSessionState(
        controller = if (access) session.controller else null,
        snapshot = MediaSnapshot(
            artworkKey = art?.let { if (it === bright) "bright" else "dark" },
            title = if (art == null) "再生していません" else "サンプル曲（Live Session 2026）",
            artist = if (art == null) "メディア" else "サンプルアーティスト",
            albumArt = art,
            position = 70_000L,
            duration = 200_000L,
            isPlaying = playing,
        ),
        hasAccess = access,
    )
    val gap = 8.dp
    val tiles = listOf(
        WidgetGridSize(2, 2) to state(if (cycle) cycledArt else bright, playing = true),
        WidgetGridSize(2, 2) to state(dark, playing = false),
        WidgetGridSize(2, 2) to state(null, playing = false),
        WidgetGridSize(4, 2) to state(bright, playing = true),
        WidgetGridSize(2, 2) to state(null, playing = false, access = false),
        WidgetGridSize(2, 1) to state(dark, playing = true),
        WidgetGridSize(4, 1) to state(bright, playing = false),
    )
    FlowRow(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        tiles.forEach { (footprint, mediaState) ->
            Box(
                Modifier.size(
                    width = cell * footprint.columnSpan + gap * (footprint.columnSpan - 1),
                    height = cell * footprint.rowSpan + gap * (footprint.rowSpan - 1),
                ),
            ) {
                HomeWidgetTile(
                    widget = HomeWidget.MEDIA,
                    now = LocalDateTime.now(),
                    posture = Posture.INNER_PORTRAIT,
                    gridSize = footprint,
                    battery = UnknownBatteryStatus,
                    onWeather = {},
                    onCalendar = {},
                    onMedia = {},
                    photoUri = null,
                    isVideoMuted = true,
                    onVideoMuteChanged = {},
                    mediaState = mediaState,
                    onOpenMediaSettings = {},
                )
            }
        }
    }
}
