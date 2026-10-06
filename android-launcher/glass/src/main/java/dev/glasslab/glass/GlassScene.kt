package dev.glasslab.glass

import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicLong

private val nextContributorId = AtomicLong(1L)

/**
 * Sharp, pre-glass foreground contributors used by a second glass surface such as a floating
 * navigation bar. A contributor never includes a [GlassSurface] optical result by itself; callers
 * attach this modifier only to the sharp content layer.
 */
@Stable
class GlassSceneState internal constructor() {
    internal val drawRevision = mutableIntStateOf(0)
    private val contributors = mutableStateMapOf<Long, GlassSceneContributorRecord>()

    internal fun register(record: GlassSceneContributorRecord) {
        contributors[record.id] = record
        bumpRevision()
    }

    internal fun unregister(record: GlassSceneContributorRecord) {
        if (contributors.remove(record.id) != null) bumpRevision()
    }

    /**
     * Replays contributors into a surface-local padded ROI. Both the source and destination
     * LayoutCoordinates are transformed at draw time, so scroll and ancestor graphicsLayer motion
     * do not depend on a stale positionInRoot value.
     */
    internal fun drawInto(
        drawScope: DrawScope,
        destinationCoordinates: LayoutCoordinates,
        paddingPx: Float,
    ) {
        // This read deliberately subscribes the destination draw to contributor updates.
        @Suppress("UNUSED_VARIABLE")
        val revision = drawRevision.intValue
        val recordsById = contributors.toMap()
        val orderedIds = orderedVisibleGlassContributors(
            recordsById.values.map { record ->
                GlassSceneOrderEntry(
                    id = record.id,
                    enabled = record.enabled,
                    alpha = record.alpha,
                    zIndex = record.zIndex,
                    captureReady = record.captureReady,
                    hasFallback = record.fallbackColor != null,
                )
            },
        )

        orderedIds.forEach { id ->
            val record = recordsById[id] ?: return@forEach
            val sourceCoordinates = record.coordinates ?: return@forEach
            if (!sourceCoordinates.isAttached) return@forEach
            val bounds = runCatching {
                destinationCoordinates.localBoundingBoxOf(
                    sourceCoordinates,
                    clipBounds = true,
                )
            }.getOrNull() ?: return@forEach
            if (!bounds.isFiniteAndNonEmpty()) return@forEach

            val matrix = Matrix()
            if (!runCatching {
                    destinationCoordinates.transformFrom(sourceCoordinates, matrix)
                }.isSuccess
            ) {
                return@forEach
            }

            val clipped = Rect(
                left = (bounds.left + paddingPx).coerceIn(0f, drawScope.size.width),
                top = (bounds.top + paddingPx).coerceIn(0f, drawScope.size.height),
                right = (bounds.right + paddingPx).coerceIn(0f, drawScope.size.width),
                bottom = (bounds.bottom + paddingPx).coerceIn(0f, drawScope.size.height),
            )
            if (clipped.isEmpty) return@forEach

            drawScope.clipRect(
                left = clipped.left,
                top = clipped.top,
                right = clipped.right,
                bottom = clipped.bottom,
                clipOp = ClipOp.Intersect,
            ) {
                withTransform({
                    translate(paddingPx, paddingPx)
                    transform(matrix)
                }) {
                    val drawContributor = {
                        val fallback = record.fallbackColor
                        if (fallback != null) {
                            drawRect(
                                color = fallback.copy(alpha = fallback.alpha * record.alpha),
                                size = Size(
                                    sourceCoordinates.size.width.toFloat(),
                                    sourceCoordinates.size.height.toFloat(),
                                ),
                            )
                        } else {
                            drawLayerWithAlpha(
                                layer = record.layer,
                                alpha = record.alpha,
                                sourceBounds = Rect(
                                    left = 0f,
                                    top = 0f,
                                    right = sourceCoordinates.size.width.toFloat(),
                                    bottom = sourceCoordinates.size.height.toFloat(),
                                ),
                            )
                        }
                    }
                    val radius = record.cornerRadiusPx
                        .coerceAtLeast(0f)
                        .coerceAtMost(
                            minOf(
                                sourceCoordinates.size.width,
                                sourceCoordinates.size.height,
                            ) / 2f,
                        )
                    if (radius > 0f) {
                        val path = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = 0f,
                                    top = 0f,
                                    right = sourceCoordinates.size.width.toFloat(),
                                    bottom = sourceCoordinates.size.height.toFloat(),
                                    cornerRadius = CornerRadius(radius, radius),
                                ),
                            )
                        }
                        clipPath(path) { drawContributor() }
                    } else {
                        drawContributor()
                    }
                }
            }
        }
    }

    private fun bumpRevision() {
        Snapshot.withoutReadObservation {
            drawRevision.intValue += 1
        }
    }
}

/** Minimal, pure ordering/filtering contract used by the scene registry and unit tests. */
internal data class GlassSceneOrderEntry(
    val id: Long,
    val enabled: Boolean,
    val alpha: Float,
    val zIndex: Float,
    val captureReady: Boolean,
    val hasFallback: Boolean,
)

internal fun orderedVisibleGlassContributors(
    entries: Iterable<GlassSceneOrderEntry>,
): List<Long> = entries
    .filter {
        it.enabled &&
            it.alpha > 0f &&
            it.alpha.isFinite() &&
            (it.captureReady || it.hasFallback)
    }
    .sortedWith(compareBy<GlassSceneOrderEntry> { it.zIndex }.thenBy { it.id })
    .map { it.id }

@Composable
fun rememberGlassScene(): GlassSceneState = remember { GlassSceneState() }

internal class GlassSceneContributorRecord(
    val id: Long = nextContributorId.getAndIncrement(),
    val layer: GraphicsLayer,
) {
    var enabled by mutableStateOf(true)
    var alpha by mutableStateOf(1f)
    var zIndex by mutableStateOf(0f)
    var fallbackColor by mutableStateOf<Color?>(null)
    var geometryVersion by mutableStateOf<Any?>(null)
    var cornerRadiusPx by mutableStateOf(0f)
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var geometrySnapshot: GlassContributorGeometrySnapshot? = null
    var captureReady by mutableStateOf(false)

    fun update(
        enabled: Boolean,
        alpha: Float,
        zIndex: Float,
        fallbackColor: Color?,
        geometryVersion: Any?,
        cornerRadiusPx: Float,
    ): Boolean {
        val safeAlpha = alpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val safeZIndex = zIndex.takeIf { it.isFinite() } ?: 0f
        val safeCornerRadius = cornerRadiusPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val changed = this.enabled != enabled || this.alpha != safeAlpha ||
            this.zIndex != safeZIndex || this.fallbackColor != fallbackColor ||
            this.geometryVersion != geometryVersion || this.cornerRadiusPx != safeCornerRadius
        this.enabled = enabled
        this.alpha = safeAlpha
        this.zIndex = safeZIndex
        this.fallbackColor = fallbackColor
        this.geometryVersion = geometryVersion
        this.cornerRadiusPx = safeCornerRadius
        return changed
    }

    fun updateCoordinates(coordinates: LayoutCoordinates): Boolean {
        val snapshot = GlassContributorGeometrySnapshot(
            size = coordinates.size,
            rootBounds = runCatching {
                coordinates.findRootCoordinates().localBoundingBoxOf(
                    coordinates,
                    clipBounds = false,
                )
            }.getOrNull(),
        )
        if (this.coordinates === coordinates && geometrySnapshot == snapshot) return false
        this.coordinates = coordinates
        geometrySnapshot = snapshot
        return true
    }
}

private data class GlassContributorGeometrySnapshot(
    val size: androidx.compose.ui.unit.IntSize,
    val rootBounds: Rect?,
)

/**
 * Registers sharp content in a reusable GraphicsLayer while leaving the visible child and its
 * semantics/gestures unchanged. Pass [fallbackColor] for View/SurfaceView-backed content whose
 * pixels are not guaranteed to be present in a Compose display list.
 */
private fun Modifier.glassSceneContributorImpl(
    scene: GlassSceneState,
    enabled: Boolean,
    alpha: Float,
    zIndex: Float,
    fallbackColor: Color?,
    geometryVersion: Any?,
    cornerRadius: Dp,
): Modifier = composed(
    inspectorInfo = {
        name = "glassSceneContributor"
        properties["enabled"] = enabled
        properties["alpha"] = alpha
        properties["zIndex"] = zIndex
        properties["fallbackColor"] = fallbackColor
        properties["geometryVersion"] = geometryVersion
        properties["cornerRadius"] = cornerRadius
    },
) {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val record = remember(scene) { GlassSceneContributorRecord(layer = layer) }
    val geometryVersionState = rememberUpdatedState(geometryVersion)
    val density = LocalDensity.current
    val cornerRadiusPx = (cornerRadius.value * density.density)
        .takeIf { it.isFinite() }
        ?.coerceAtLeast(0f)
        ?: 0f
    DisposableEffect(scene, record) {
        scene.register(record)
        onDispose { scene.unregister(record) }
    }
    // A draw-time signal changes without recomposition; forward its changes to the scene so the
    // navigation glass resamples, without re-recording this contributor's own content.
    if (geometryVersion is GlassGeometrySignal) {
        LaunchedEffect(scene, geometryVersion) {
            snapshotFlow { resolveGlassGeometry(geometryVersion) }
                .drop(1)
                .collect { scene.invalidate() }
        }
    }
    SideEffect {
        if (record.update(
            enabled = enabled,
            alpha = alpha,
            zIndex = zIndex,
            fallbackColor = fallbackColor,
            geometryVersion = geometryVersion,
            cornerRadiusPx = cornerRadiusPx,
        )) {
            scene.invalidate()
        }
    }
    this
        .onGloballyPositioned {
            if (record.updateCoordinates(it)) scene.invalidate()
        }
        .drawWithContent {
            @Suppress("UNUSED_VARIABLE")
            val geometryTick = geometryVersionState.value
            val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
            if (enabled && fallbackColor == null && hardware) {
                record.layer.record { this@drawWithContent.drawContent() }
                drawLayer(record.layer)
                scene.markCapture(record, ready = true)
            } else {
                // A fallback deliberately leaves the real child drawing untouched. The scene gets
                // the fallback color instead of a potentially incomplete external View snapshot.
                drawContent()
                scene.markCapture(record, ready = false)
            }
        }
}

/** Public contributor modifier with defaults suitable for ordinary Compose content. */
fun Modifier.glassSceneContributor(
    scene: GlassSceneState,
    enabled: Boolean = true,
    alpha: Float = 1f,
    zIndex: Float = 0f,
    fallbackColor: Color? = null,
    geometryVersion: Any? = null,
    cornerRadius: Dp = 0.dp,
): Modifier = glassSceneContributorImpl(
    scene = scene,
    enabled = enabled,
    alpha = alpha,
    zIndex = zIndex,
    fallbackColor = fallbackColor,
    geometryVersion = geometryVersion,
    cornerRadius = cornerRadius,
)

private fun GlassSceneState.markCapture(
    record: GlassSceneContributorRecord,
    ready: Boolean,
) {
    Snapshot.withoutReadObservation {
        val changed = record.captureReady != ready
        record.captureReady = ready
        // A destination surface reads drawRevision while it is drawing. Incrementing it for
        // every source draw would make the destination invalidate itself forever. Readiness is a
        // state transition; parameter/geometry changes invalidate the scene separately above.
        if (changed) drawRevision.intValue += 1
    }
}

private fun GlassSceneState.invalidate() {
    Snapshot.withoutReadObservation {
        drawRevision.intValue += 1
    }
}

private fun Rect.isFiniteAndNonEmpty(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        right > left && bottom > top

private fun DrawScope.drawLayerWithAlpha(
    layer: GraphicsLayer,
    alpha: Float,
    sourceBounds: Rect,
) {
    if (alpha >= 0.999f) {
        drawLayer(layer)
        return
    }
    val paint = Paint().apply { this.alpha = alpha.coerceIn(0f, 1f) }
    drawIntoCanvas { canvas ->
        // The caller may already have an active source-to-destination transform. Save-layer
        // bounds must therefore be expressed in source-local coordinates; destination-space
        // bounds would be transformed a second time and clip scaled/translated contributors.
        canvas.saveLayer(sourceBounds, paint)
        drawLayer(layer)
        canvas.restore()
    }
}
