package dev.glasslab.glass

import android.util.Log
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.setFrom
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.os.Build
import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** The two intentionally small material variants exposed to the launcher. */
enum class GlassVariant { Regular, Clear }

@Immutable
data class GlassStyle(
    val variant: GlassVariant = GlassVariant.Clear,
    val blurRadius: Dp = 4.dp,
    val refraction: Dp = 8.dp,
    val tint: Color = Color.White,
    val tintAlpha: Float = 0.22f,
    val dark: Boolean = false,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val baseTint: Color = Color.Transparent,
)

/** A single wallpaper display list shared by all surfaces in a Compose root. */
@Stable
class GlassBackdropState internal constructor(internal val layer: androidx.compose.ui.graphics.layer.GraphicsLayer) {
    internal var positionInRoot by mutableStateOf(Offset.Zero)
    internal var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    internal var sourceAttached = false
    internal var captureReady by mutableStateOf(false)
    internal val drawRevision = mutableIntStateOf(0)

    /** False until the source has completed its first hardware draw. */
    val isCaptureReady: Boolean get() = captureReady

    internal fun sourceWasDrawn(hardware: Boolean) {
        // A surface reads drawRevision while drawing. Incrementing on every source draw would
        // make an idle surface invalidate itself forever when the root redraws its siblings.
        // The static wallpaper used by the launcher only needs the readiness transition; source
        // geometry and explicit state changes invalidate the scene independently.
        Snapshot.withoutReadObservation {
            val next = hardware
            if (captureReady != next) {
                captureReady = next
                drawRevision.intValue += 1
            }
        }
    }

    /** Called after source composition changes, without observing the revision from the source. */
    internal fun sourceContentChanged(contentVersion: Any? = null) {
        Snapshot.withoutReadObservation {
            if (contentVersion == null) {
                drawRevision.intValue += 1
                invalidateBlurredSnapshots()
            } else if (sourceContentVersion != contentVersion) {
                sourceContentVersion = contentVersion
                drawRevision.intValue += 1
                invalidateBlurredSnapshots()
            }
        }
    }

    // The backdrop is static, so each blur radius is rendered into an image once and shared by
    // every surface. Blurring each surface's patch on every frame was the dominant render cost.
    internal val blurredSnapshots = mutableStateMapOf<Int, ImageBitmap>()
    internal val requestedBlurRadii = mutableStateListOf<Int>()
    internal val snapshotRevision = mutableIntStateOf(0)
    internal var snapshotSize = IntSize.Zero

    /** The pre-blurred backdrop for [blurPx], or null until it has been rendered. */
    internal fun blurredSnapshot(blurPx: Float): ImageBitmap? {
        val key = blurPx.roundToInt()
        if (key <= 0) return null
        Snapshot.withoutReadObservation {
            if (key !in requestedBlurRadii) requestedBlurRadii += key
        }
        return blurredSnapshots[key]
    }

    internal fun invalidateBlurredSnapshots() {
        Snapshot.withoutReadObservation {
            blurredSnapshots.clear()
            snapshotRevision.intValue += 1
        }
    }

    private var sourceContentVersion: Any? = null
}

private val LocalCapturingBackdrop = staticCompositionLocalOf<GlassBackdropState?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdropState(layer) }
}

/**
 * Records the wallpaper and other sharp background content once. Glass surfaces must be siblings
 * of this composable so their optical output can never be captured back into the wallpaper.
 */
@Composable
fun GlassBackdrop(
    state: GlassBackdropState,
    modifier: Modifier = Modifier,
    contentVersion: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    DisposableEffect(state) {
        check(!state.sourceAttached) {
            "Each GlassBackdropState requires exactly one GlassBackdrop."
        }
        state.sourceAttached = true
        onDispose {
            state.sourceAttached = false
            Snapshot.withoutReadObservation {
                state.captureReady = false
                state.drawRevision.intValue += 1
            }
        }
    }
    val graphicsContext = LocalGraphicsContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(state, graphicsContext) {
        snapshotFlow {
            Triple(state.snapshotRevision.intValue, state.captureReady, state.requestedBlurRadii.toList())
        }.collectLatest { (_, ready, radii) ->
            val size = state.snapshotSize
            if (!ready || size.width <= 0 || size.height <= 0) return@collectLatest
            for (radius in radii) {
                if (radius in state.blurredSnapshots) continue
                val blurLayer = graphicsContext.createGraphicsLayer()
                try {
                    blurLayer.record(density, layoutDirection, size) { drawLayer(state.layer) }
                    blurLayer.renderEffect = BlurEffect(radius.toFloat(), radius.toFloat(), TileMode.Clamp)
                    val rendered = runCatching { blurLayer.toImageBitmap() }.getOrNull() ?: continue
                    // Keep the shared image on the GPU; a software bitmap is re-uploaded every
                    // time a surface draws it.
                    val image = withContext(Dispatchers.Default) {
                        val bitmap = rendered.asAndroidBitmap()
                        if (Build.VERSION.SDK_INT >= 26 && bitmap.config != Bitmap.Config.HARDWARE) {
                            bitmap.copy(Bitmap.Config.HARDWARE, false)?.asImageBitmap() ?: rendered
                        } else {
                            rendered
                        }
                    }
                    state.blurredSnapshots[radius] = image
                } finally {
                    graphicsContext.releaseGraphicsLayer(blurLayer)
                }
            }
        }
    }
    CompositionLocalProvider(LocalCapturingBackdrop provides state) {
        // A wallpaper selection/content recomposition is an actual source change. This is kept
        // separate from the draw callback so sibling surface draws cannot create a feedback loop.
        SideEffect { state.sourceContentChanged(contentVersion) }
        Box(
            modifier = modifier
                .onGloballyPositioned {
                    if (state.snapshotSize != it.size) {
                        state.snapshotSize = it.size
                        state.invalidateBlurredSnapshots()
                    }
                    state.positionInRoot = it.positionInRoot()
                    if (state.coordinates !== it) {
                        state.coordinates = it
                        Snapshot.withoutReadObservation {
                            state.drawRevision.intValue += 1
                        }
                    }
                }
                // Keep wallpaper recording isolated from a sibling surface's draw invalidation.
                .graphicsLayer()
                .drawWithContent {
                    val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
                    if (hardware) {
                        state.layer.record { this@drawWithContent.drawContent() }
                        drawLayer(state.layer)
                    } else {
                        drawContent()
                    }
                    state.sourceWasDrawn(hardware)
                },
            content = content,
        )
    }
}

/**
 * A rounded glass material with sharp, ordinary Compose content above it. [scene] is optional;
 * when present, the surface composites sharp contributors after wallpaper replay and before its
 * own tint/effect. Contributors are expected to be siblings or other pre-glass content.
 */
@Composable
fun GlassSurface(
    backdrop: GlassBackdropState,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle(),
    cornerRadius: Dp = 28.dp,
    scene: GlassSceneState? = null,
    geometryVersion: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    check(LocalCapturingBackdrop.current !== backdrop) {
        "GlassSurface must be a sibling of its GlassBackdrop, not captured by that backdrop."
    }
    Box(
        modifier = modifier.glassSurface(
            backdrop = backdrop,
            style = style,
            cornerRadius = cornerRadius,
            scene = scene,
            geometryVersion = geometryVersion,
        ),
        content = content,
    )
}

/**
 * Applies the glass material while leaving the caller's foreground layout unchanged. The
 * modifier form is useful when a caller already owns a Box/Column and only wants to add the
 * background effect to that existing node.
 */
fun Modifier.glassSurface(
    backdrop: GlassBackdropState,
    style: GlassStyle = GlassStyle(),
    cornerRadius: Dp = 28.dp,
    scene: GlassSceneState? = null,
    geometryVersion: Any? = null,
): Modifier = composed(
    inspectorInfo = {
        name = "glassSurface"
        properties["style"] = style
        properties["cornerRadius"] = cornerRadius
        properties["scene"] = scene
        properties["geometryVersion"] = geometryVersion
    },
) {
    var surfacePosition by remember { mutableStateOf(Offset.Zero) }
    var surfaceCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val geometryVersionState = rememberUpdatedState(geometryVersion)
    val sampledLayer = rememberGraphicsLayer()
    val directPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val shader = remember {
        runCatching { GlassPlatformEffects.createShader(GLASS_REFRACTION) }
            .onFailure {
                Log.e("LiquidGlass", "AGSL compilation failed; using the non-shader path.", it)
            }
            .getOrNull()
    }
    val safeCorner = cornerRadius.value.takeIf { it.isFinite() && it >= 0f }?.dp ?: 28.dp
    val shape = RoundedCornerShape(safeCorner)

    this
        .onGloballyPositioned {
            surfacePosition = it.positionInRoot()
            surfaceCoordinates = it
        }
        // Clear is intended to stay visually close to the wallpaper. Its padded optical patch
        // already supplies the edge treatment, so an external shadow would form a dark square
        // halo outside the rounded surface. Regular keeps its existing depth cue.
        .then(
            if (style.variant == GlassVariant.Clear) {
                Modifier
            } else {
                Modifier.shadow(14.dp, shape)
            },
        )
        .clip(shape)
        .drawWithCache {
            val parameters = resolveGlassParameters(
                width = size.width,
                height = size.height,
                density = density,
                blurDp = style.blurRadius.value,
                refractionDp = style.refraction.value,
                tintAlpha = style.tintAlpha,
                cornerDp = safeCorner.value,
                clear = style.variant == GlassVariant.Clear,
            )
            val padding = parameters.paddingPx.toFloat()
            val patchSize = IntSize(
                ceil(size.width).toInt() + parameters.paddingPx * 2,
                ceil(size.height).toInt() + parameters.paddingPx * 2,
            )
            // Surfaces without a scene sample only the static backdrop, so they can use the shared
            // pre-blurred image and keep just the per-surface refraction. Until it is ready (and
            // for the scene-sampling navigation surface) the patch is blurred as before.
            val preBlurred = if (scene == null && !style.reduceTransparency && !style.highContrast) {
                backdrop.blurredSnapshot(parameters.blurPx)
            } else {
                null
            }
            val effect = if (
                !style.reduceTransparency &&
                !style.highContrast &&
                size.width > 0f &&
                size.height > 0f
            ) {
                GlassPlatformEffects.createEffect(
                    shader = shader,
                    surfaceWidth = size.width,
                    surfaceHeight = size.height,
                    paddingPx = padding,
                    radiusPx = parameters.cornerPx,
                    refractionPx = parameters.refractionPx,
                    blurPx = if (preBlurred != null) 0f else parameters.blurPx,
                    saturation = if (style.variant == GlassVariant.Clear) 1.04f else 1.13f,
                )
            } else {
                null
            }
            sampledLayer.renderEffect = effect
            val bitmapShader = preBlurred?.let {
                android.graphics.BitmapShader(
                    it.asAndroidBitmap(),
                    android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            val sourceMatrix = Matrix()
            val localMatrix = android.graphics.Matrix()

            val corner = CornerRadius(parameters.cornerPx)
            val sampleClipPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = corner,
                    ),
                )
            }
            // Fallback surfaces are deliberately neutral. The launcher owns its palette, while
            // the renderer must not introduce a blue cast when transparency is unavailable.
            val solid = if (style.dark) Color(0xFF202020) else Color(0xFFF4F4F4)
            val tint = style.tint
            val edgeWidth = (if (style.highContrast) 1.5.dp else 0.9.dp).toPx()
            val edgeInset = edgeWidth / 2f
            val edgeSize = Size(
                (size.width - edgeWidth).coerceAtLeast(0f),
                (size.height - edgeWidth).coerceAtLeast(0f),
            )
            val edgeCorner = CornerRadius((parameters.cornerPx - edgeInset).coerceAtLeast(0f))
            val edgeBrush = Brush.linearGradient(
                colors = if (style.highContrast) {
                    val edge = if (style.dark) Color.White else Color(0xFF404040)
                    listOf(edge, edge)
                } else {
                    listOf(
                        Color.White.copy(alpha = if (style.dark) 0.56f else 0.90f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = if (style.dark) 0.28f else 0.60f),
                    )
                },
                start = Offset.Zero,
                end = Offset(size.width * 0.80f, size.height),
            )
            val sheen = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (style.dark) 0.08f else 0.14f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.035f),
                ),
                start = Offset.Zero,
                end = Offset(size.width * 0.40f, size.height),
            )
            onDrawBehind {
                val sourceRevision = backdrop.drawRevision.intValue
                @Suppress("UNUSED_VARIABLE")
                val sceneRevision = scene?.drawRevision?.intValue
                scene?.observeGeometry()
                @Suppress("UNUSED_VARIABLE")
                val geometryTick = resolveGlassGeometry(geometryVersionState.value)
                val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
                val opaque = style.reduceTransparency || style.highContrast ||
                    !backdrop.captureReady || !hardware
                if (opaque) {
                    drawRoundRect(solid, cornerRadius = corner)
                } else if (preBlurred != null && size.width > 0f && size.height > 0f) {
                    // Sample the shared pre-blurred backdrop directly with the refraction shader:
                    // no per-surface offscreen layer, so each surface is a single draw.
                    drawPreBlurredBackdrop(
                        bitmapShader = bitmapShader!!,
                        sourceMatrix = sourceMatrix,
                        localMatrix = localMatrix,
                        paint = directPaint,
                        backdrop = backdrop,
                        destination = surfaceCoordinates,
                        surfacePosition = surfacePosition,
                        shader = if (parameters.refractionPx > 0f) shader else null,
                        cornerPx = parameters.cornerPx,
                        refractionPx = parameters.refractionPx,
                        saturation = if (style.variant == GlassVariant.Clear) 1.04f else 1.13f,
                    )
                    drawRoundRect(style.baseTint, cornerRadius = corner)
                    drawRoundRect(tint.copy(alpha = parameters.tintAlpha), cornerRadius = corner)
                    drawRoundRect(sheen, cornerRadius = corner)
                } else if (size.width > 0f && size.height > 0f) {
                    sampledLayer.record(size = patchSize) {
                        drawBackdropIntoPatch(
                            backdrop = backdrop,
                            preBlurred = preBlurred,
                            destination = surfaceCoordinates,
                            surfacePosition = surfacePosition,
                            padding = padding,
                        )
                        if (scene != null && surfaceCoordinates != null) {
                            scene.drawInto(
                                drawScope = this,
                                destinationCoordinates = surfaceCoordinates!!,
                                paddingPx = padding,
                            )
                        }
                    }
                    // The sampled layer is intentionally padded for blur/refraction, but replay
                    // it through the surface-local rounded mask. Without this second, explicit
                    // clip the effect can expose the padded layer's rectangular corners before
                    // the outer graphicsLayer clip is applied.
                    clipPath(sampleClipPath) {
                        translate(-padding, -padding) { drawLayer(sampledLayer) }
                    }
                    drawRoundRect(style.baseTint, cornerRadius = corner)
                    drawRoundRect(tint.copy(alpha = parameters.tintAlpha), cornerRadius = corner)
                    drawRoundRect(sheen, cornerRadius = corner)
                }
                drawRoundRect(
                    brush = edgeBrush,
                    topLeft = Offset(edgeInset, edgeInset),
                    size = edgeSize,
                    cornerRadius = edgeCorner,
                    style = Stroke(edgeWidth),
                )
            }
        }
}

private fun DrawScope.drawBackdropIntoPatch(
    backdrop: GlassBackdropState,
    preBlurred: ImageBitmap?,
    destination: LayoutCoordinates?,
    surfacePosition: Offset,
    padding: Float,
) {
    val source = backdrop.coordinates
    if (source != null && destination != null && source.isAttached && destination.isAttached) {
        val matrix = Matrix()
        if (runCatching { destination.transformFrom(source, matrix) }.isSuccess) {
            withTransform({
                translate(padding, padding)
                transform(matrix)
            }) {
                drawBackdropSource(backdrop, preBlurred)
            }
            return
        }
    }

    // Coordinate data is normally available. This translation-only fallback keeps the source
    // visible during a short attach/detach window and covers older coordinate implementations.
    val offset = surfacePosition - backdrop.positionInRoot
    translate(padding - offset.x, padding - offset.y) {
        drawBackdropSource(backdrop, preBlurred)
    }
}

private fun DrawScope.drawBackdropSource(backdrop: GlassBackdropState, preBlurred: ImageBitmap?) {
    if (preBlurred != null) drawImage(preBlurred) else drawLayer(backdrop.layer)
}

/** Maps backdrop coordinates into this surface's local coordinates. */
private fun backdropToSurfaceMatrix(
    backdrop: GlassBackdropState,
    destination: LayoutCoordinates?,
    surfacePosition: Offset,
    matrix: Matrix,
): Matrix {
    matrix.reset()
    val source = backdrop.coordinates
    if (source != null && destination != null && source.isAttached && destination.isAttached &&
        runCatching { destination.transformFrom(source, matrix) }.isSuccess
    ) {
        return matrix
    }
    val offset = surfacePosition - backdrop.positionInRoot
    matrix.reset()
    matrix.translate(-offset.x, -offset.y)
    return matrix
}

private fun DrawScope.drawPreBlurredBackdrop(
    bitmapShader: android.graphics.BitmapShader,
    sourceMatrix: Matrix,
    localMatrix: android.graphics.Matrix,
    paint: android.graphics.Paint,
    backdrop: GlassBackdropState,
    destination: LayoutCoordinates?,
    surfacePosition: Offset,
    shader: GlassShaderHandle?,
    cornerPx: Float,
    refractionPx: Float,
    saturation: Float,
) {
    localMatrix.setFrom(backdropToSurfaceMatrix(backdrop, destination, surfacePosition, sourceMatrix))
    bitmapShader.setLocalMatrix(localMatrix)
    paint.shader = GlassPlatformEffects.refractedShader(
        shader = shader,
        input = bitmapShader,
        surfaceWidth = size.width,
        surfaceHeight = size.height,
        radiusPx = cornerPx,
        refractionPx = refractionPx,
        saturation = saturation,
    ) ?: bitmapShader
    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
}

