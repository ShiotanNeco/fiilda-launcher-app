package com.fiilda.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

/** Real launcher components with in-memory fixtures; never changes layout or wallpaper storage. */
class GlassComponentsProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val light = intent.getBooleanExtra("light", false)
        setContent {
            val scope = rememberCoroutineScope()
            val wallpaper = remember {
                Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).apply {
                    val canvas = Canvas(this)
                    val paint = Paint()
                    canvas.drawColor(if (light) 0xFFB8B8B8.toInt() else 0xFF484848.toInt())
                    paint.color = if (light) 0xFFE4E4E4.toInt() else 0xFF808080.toInt()
                    canvas.drawRect(170f, 0f, 300f, 1200f, paint)
                    canvas.drawRect(0f, 460f, 800f, 610f, paint)
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
            val icon = remember {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE68A45.toInt())
                    setSize(128, 128)
                }
            }
            val app = remember {
                LaunchableApp("fixture", "fixture.App", "Glass App", icon,
                    0xFFE68A45.toInt(), 0xFF000000.toInt())
            }
            val shortcuts = remember {
                (1..3).map {
                    ResolvedLauncherShortcut("fixture-$it", "Shortcut $it", icon.constantState!!.newDrawable().mutate())
                }
            }
            CompositionLocalProvider(
                LocalLauncherTheme provides LauncherTheme.GLASS,
                LocalLauncherPalette provides launcherPaletteFor(LauncherTheme.GLASS),
                LocalGlassWallpaperController provides controller,
                LocalGlassReduceTransparency provides false,
                LocalShowAppLabels provides true,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    LauncherGlassHost(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 60.dp)
                                .semantics { contentDescription = "glass-components-probe" },
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(Modifier.weight(1f)) {
                                    AppTile(app, Posture.COVER, selected = false,
                                        size = AppTileSize.LARGE, shortcuts = shortcuts, onClick = {})
                                }
                                Box(Modifier.weight(1f)) {
                                    AppTile(app, Posture.COVER, selected = false,
                                        size = AppTileSize.SMALL, onClick = {})
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(Modifier.weight(1f).size(240.dp)) {
                                    FolderTile(HomeFolder("folder:probe", memberIds = listOf("fixture")),
                                        listOf(app), false, {}, {})
                                }
                                Box(Modifier.weight(1f).size(240.dp)) {
                                    HomeWidgetTile(HomeWidget.MEDIA, LocalDateTime.of(2026, 9, 22, 12, 0),
                                        Posture.COVER, WidgetGridSize(2, 2), UnknownBatteryStatus.copy(percent = 100), {}, {}, {}, null,
                                        true, {}, MediaSessionState(snapshot = MediaSnapshot(
                                            title = "Glass media", artist = "Sharp labels",
                                            position = 25, duration = 100), hasAccess = true), {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
