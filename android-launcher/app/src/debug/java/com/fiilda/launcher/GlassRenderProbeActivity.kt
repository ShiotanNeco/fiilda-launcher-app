package com.fiilda.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.glasslab.glass.GlassBackdrop
import dev.glasslab.glass.GlassStyle
import dev.glasslab.glass.GlassSurface
import dev.glasslab.glass.GlassVariant
import dev.glasslab.glass.glassSceneContributor
import dev.glasslab.glass.rememberGlassBackdrop
import dev.glasslab.glass.rememberGlassScene

/**
 * Debug-only, deterministic GPU-rendering fixture. ADB intent extras select otherwise identical
 * frames for optical A/B checks; no launcher preference or user content is read or changed.
 *
 * --ef refraction 0 / 8, --ef scale 1 / .96, --ef shift 0 / 24,
 * --es pattern checker / light / dark, --ez opaque true, --ez nativeFallback true.
 */
class GlassRenderProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val refraction = intent.getFloatExtra("refraction", 8f).coerceIn(0f, 32f)
        val scale = intent.getFloatExtra("scale", 1f).coerceIn(0.5f, 1.5f)
        val shift = intent.getFloatExtra("shift", 0f).coerceIn(-96f, 96f)
        val pattern = intent.getStringExtra("pattern") ?: "checker"
        val opaque = intent.getBooleanExtra("opaque", false)
        val nativeFallback = intent.getBooleanExtra("nativeFallback", false)
        val contributorAlpha = intent.getFloatExtra("alpha", 1f).coerceIn(0f, 1f)
        val useImageView = intent.getBooleanExtra("imageView", false)
        setContent {
            val backdrop = rememberGlassBackdrop()
            val scene = rememberGlassScene()
            var targetScale by remember { mutableFloatStateOf(scale) }
            var targetShift by remember { mutableFloatStateOf(shift) }
            var currentPattern by remember { mutableStateOf(pattern) }
            val liveScale by animateFloatAsState(targetScale, label = "probe scale")
            val liveShift by animateFloatAsState(targetShift, label = "probe foreground shift")
            val style = GlassStyle(
                variant = GlassVariant.Clear,
                blurRadius = 4.dp,
                refraction = refraction.dp,
                tint = Color.Black,
                tintAlpha = 0.08f,
                dark = true,
                reduceTransparency = opaque,
            )
            Box(Modifier.fillMaxSize()) {
                GlassBackdrop(backdrop, Modifier.fillMaxSize(), contentVersion = currentPattern) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cell = 24.dp.toPx()
                        val colors = when (currentPattern) {
                            "solid" -> listOf(Color(0xFF888888), Color(0xFF888888))
                            "light" -> listOf(Color.White, Color(0xFFEAF2F7))
                            "dark" -> listOf(Color(0xFF061016), Color(0xFF142530))
                            else -> listOf(Color(0xFFB9CFDD), Color(0xFF253C4B))
                        }
                        val rows = (size.height / cell).toInt() + 1
                        val columns = (size.width / cell).toInt() + 1
                        repeat(rows) { y ->
                            repeat(columns) { x ->
                                drawRect(
                                    color = colors[(x + y) % 2],
                                    topLeft = Offset(x * cell, y * cell),
                                    size = Size(cell, cell),
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .offset(x = 32.dp, y = 96.dp)
                        .graphicsLayer {
                            scaleX = liveScale
                            scaleY = liveScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                ) {
                    GlassSurface(
                        backdrop = backdrop,
                        modifier = Modifier
                            .size(width = 240.dp, height = 160.dp)
                            .semantics { contentDescription = "glass-probe-tile" },
                        style = style,
                        cornerRadius = 20.dp,
                        geometryVersion = liveScale,
                    ) {
                        Text(
                            "SHARP 123",
                            color = Color.White,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(4.dp),
                        )
                    }
                }
                val foregroundModifier = Modifier
                        .offset(x = 40.dp, y = (330f + liveShift).dp)
                        .size(width = 200.dp, height = 120.dp)
                        .graphicsLayer { alpha = contributorAlpha }
                        .glassSceneContributor(
                            scene = scene,
                            alpha = contributorAlpha,
                            cornerRadius = 20.dp,
                            fallbackColor = if (nativeFallback) Color(0xFF263B48) else null,
                            geometryVersion = liveShift,
                        )
                        .clip(RoundedCornerShape(20.dp))
                if (useImageView) {
                    val bitmap = remember {
                        android.graphics.Bitmap.createBitmap(200, 120, android.graphics.Bitmap.Config.ARGB_8888).apply {
                            val canvas = android.graphics.Canvas(this)
                            val paint = android.graphics.Paint()
                            canvas.drawColor(0xFFE83F93.toInt())
                            paint.color = 0xFF36E0DA.toInt()
                            canvas.drawRect(100f, 60f, 200f, 120f, paint)
                            paint.color = 0xFFF9DF69.toInt()
                            canvas.drawRect(0f, 60f, 100f, 120f, paint)
                        }
                    }
                    AndroidView(
                        factory = { context ->
                            android.widget.ImageView(context).apply {
                                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                                setImageBitmap(bitmap)
                            }
                        },
                        modifier = foregroundModifier,
                    )
                } else {
                Canvas(foregroundModifier) {
                    drawRect(Color(0xFFE83F93))
                    drawRect(
                        Color(0xFF36E0DA),
                        topLeft = Offset(size.width / 2f, size.height / 2f),
                        size = Size(size.width / 2f, size.height / 2f),
                    )
                    drawRect(
                        Color(0xFFF9DF69),
                        topLeft = Offset(0f, size.height / 2f),
                        size = Size(size.width / 2f, size.height / 2f),
                    )
                }
                }
                Text(
                    "Transform",
                    color = Color.White,
                    modifier = Modifier
                        .offset(x = 24.dp, y = 476.dp)
                        .background(Color.Black)
                        .clickable { targetScale = if (targetScale < 1f) 1f else 0.8f }
                        .padding(16.dp),
                )
                Text(
                    "Move foreground",
                    color = Color.White,
                    modifier = Modifier
                        .offset(x = 148.dp, y = 476.dp)
                        .background(Color.Black)
                        .clickable { targetShift = if (targetShift > 0f) 0f else 24f }
                        .padding(16.dp),
                )
                Text(
                    "Change wallpaper",
                    color = Color.White,
                    modifier = Modifier
                        .offset(x = 24.dp, y = 540.dp)
                        .background(Color.Black)
                        .clickable {
                            currentPattern = if (currentPattern == "light") "dark" else "light"
                        }
                        .padding(16.dp),
                )
                GlassSurface(
                    backdrop = backdrop,
                    scene = scene,
                    modifier = Modifier
                        .offset(x = 24.dp, y = 360.dp)
                        .size(width = 288.dp, height = 72.dp)
                        .semantics { contentDescription = "glass-probe-navigation" },
                    style = style,
                    cornerRadius = 28.dp,
                ) {
                    Text(
                        "NAV",
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}
