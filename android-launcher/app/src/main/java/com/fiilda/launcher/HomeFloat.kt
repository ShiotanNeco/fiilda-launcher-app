package com.fiilda.launcher

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tunables for the Home "floating on water" scroll effect, in pixels.
 *
 * While the board scrolls, every tile is pushed away from the tile the scroll started on, in
 * proportion to its distance, so the gaps between neighbours open up. Each tile then follows its target on its own
 * under-damped spring; farther tiles use softer springs, so they settle later and out of phase.
 * Pulling past either scroll edge spreads the tiles too, in proportion to the pull distance.
 * When the whole surface slides sideways (page switch, Drawer transition), tiles lag behind like
 * objects with inertia and sway back; each tile has its own gain and spring so they move apart.
 */
internal data class HomeFloatConfig(
    val spreadPerPx: Float = 0.105f,
    val crossAxisSpreadRatio: Float = 0.15f,
    val maxOffsetPx: Float,
    val reachPx: Float,
    val fullSpeedPxPerSecond: Float,
    val edgePullFullPx: Float,
    val stiffness: Float = 240f,
    val farStiffnessRatio: Float = 0.55f,
    val dampingRatio: Float = 0.42f,
    val speedSmoothingSeconds: Float = 0.05f,
    val edgeReleaseSeconds: Float = 0.12f,
    /** Extra spring stiffness at a full edge pull, so tiles track the finger instead of trailing it. */
    val edgeTrackingStiffnessBoost: Float = 2f,
    /** A full edge pull (or a fling hitting the edge) opens this share of the scrolling spread. */
    val edgeSpreadRatio: Float = 0.4f,
    val swaySecondsOfVelocity: Float = 0.012f,
    val maxSwayPx: Float,
) {
    companion object {
        fun forDensity(density: Float) = HomeFloatConfig(
            maxOffsetPx = 45f * density,
            reachPx = 420f * density,
            fullSpeedPxPerSecond = 1400f * density,
            edgePullFullPx = 56f * density,
            maxSwayPx = 20f * density,
        )
    }
}

/** Pure spring simulation for the float effect. Positions are in root pixels. */
internal class HomeFloatSimulation(
    private val config: HomeFloatConfig,
    private val scrollOrientation: Orientation = Orientation.Vertical,
) {
    private class Body(var centerX: Float, var centerY: Float, id: String) {
        // Fixed per tile so neighbours sway by different amounts and out of phase.
        val swayGain = 0.75f + 0.5f * hashFraction(id, 0)
        val swayStiffness = 0.7f + 0.6f * hashFraction(id, 8)
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
    }

    private val bodies = HashMap<String, Body>()
    private var filteredSpeed = 0f
    private var edgePull = 0f
    private var horizontalVelocity = 0f

    /** Tile center relative to the board origin. */
    fun place(id: String, centerX: Float, centerY: Float) {
        val body = bodies[id]
        if (body == null) {
            bodies[id] = Body(centerX, centerY, id)
        } else {
            body.centerX = centerX
            body.centerY = centerY
        }
    }

    fun remove(id: String) {
        bodies.remove(id)
    }

    fun offsetOf(id: String): Offset = bodies[id]?.let { Offset(it.x, it.y) } ?: Offset.Zero

    fun centerOf(id: String): Offset? = bodies[id]?.let { Offset(it.centerX, it.centerY) }

    /** The tile whose center is closest to [point], or null when no tile is placed. */
    fun nearestTo(point: Offset): String? = bodies.minByOrNull { (_, body) ->
        hypot(body.centerX - point.x, body.centerY - point.y)
    }?.key

    /**
     * Advances one frame. [scrollDeltaPx] is the scroll distance since the previous frame,
     * [edgePullPx] is how far the board was pulled past an edge in that time, and [anchor] is
     * the center the tiles spread from, relative to the board origin. [horizontalDeltaPx] is how far the whole
     * surface moved sideways. Returns false once every tile has come to rest while the board is idle.
     */
    fun step(
        dtSeconds: Float,
        scrollDeltaPx: Float,
        anchor: Offset,
        scrolling: Boolean,
        edgePullPx: Float = 0f,
        horizontalDeltaPx: Float = 0f,
    ): Boolean {
        if (dtSeconds <= 0f) return true
        val instantSpeed = abs(scrollDeltaPx) / dtSeconds
        val blend = 1f - exp(-dtSeconds / config.speedSmoothingSeconds)
        filteredSpeed += (instantSpeed - filteredSpeed) * blend
        horizontalVelocity += (horizontalDeltaPx / dtSeconds - horizontalVelocity) * blend
        val sway = -horizontalVelocity * config.swaySecondsOfVelocity
        // A held pull keeps the gaps open; they close only once the finger lets go.
        edgePull += abs(edgePullPx)
        if (!scrolling) edgePull *= exp(-dtSeconds / config.edgeReleaseSeconds)
        val edgeIntensity = min(edgePull / config.edgePullFullPx, 1f)
        val intensity = maxOf(
            easeOut(min(filteredSpeed / config.fullSpeedPxPerSecond, 1f)),
            easeOut(edgeIntensity) * config.edgeSpreadRatio,
        )
        // Once the finger lets go, the normal springs return so the tiles still bob back.
        val stiffnessBoost = if (scrolling) 1f + config.edgeTrackingStiffnessBoost * edgeIntensity else 1f
        var moving = scrolling
        for (body in bodies.values) {
            val dx = body.centerX - anchor.x
            val dy = body.centerY - anchor.y
            val distance = hypot(dx, dy)
            val spread = config.spreadPerPx * intensity
            val horizontal = scrollOrientation == Orientation.Horizontal
            var targetX = dx * spread * if (horizontal) 1f else config.crossAxisSpreadRatio
            var targetY = dy * spread * if (horizontal) config.crossAxisSpreadRatio else 1f
            val targetLength = hypot(targetX, targetY)
            if (targetLength > config.maxOffsetPx) {
                val scale = config.maxOffsetPx / targetLength
                targetX *= scale
                targetY *= scale
            }
            targetX += (sway * body.swayGain).coerceIn(-config.maxSwayPx, config.maxSwayPx)
            val farness = min(distance / config.reachPx, 1f)
            val stiffness = config.stiffness * stiffnessBoost *
                (1f - (1f - config.farStiffnessRatio) * farness)
            val stiffnessX = stiffness * if (horizontal) 1f else body.swayStiffness
            val stiffnessY = stiffness * if (horizontal) body.swayStiffness else 1f
            val dampingX = 2f * config.dampingRatio * sqrt(stiffnessX)
            val dampingY = 2f * config.dampingRatio * sqrt(stiffnessY)
            body.vx += (stiffnessX * (targetX - body.x) - dampingX * body.vx) * dtSeconds
            body.vy += (stiffnessY * (targetY - body.y) - dampingY * body.vy) * dtSeconds
            body.x += body.vx * dtSeconds
            body.y += body.vy * dtSeconds
            if (abs(body.x) > REST_OFFSET_PX || abs(body.y) > REST_OFFSET_PX ||
                abs(body.vx) > REST_SPEED_PX || abs(body.vy) > REST_SPEED_PX
            ) {
                moving = true
            }
        }
        val horizontalSettled = abs(horizontalVelocity) < REST_SPEED_PX
        if (!moving && filteredSpeed < REST_SPEED_PX && edgePull < REST_OFFSET_PX && horizontalSettled) {
            filteredSpeed = 0f
            edgePull = 0f
            horizontalVelocity = 0f
            for (body in bodies.values) {
                body.x = 0f
                body.y = 0f
                body.vx = 0f
                body.vy = 0f
            }
        }
        return moving || filteredSpeed >= REST_SPEED_PX || edgePull >= REST_OFFSET_PX || !horizontalSettled
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    private companion object {
        const val REST_OFFSET_PX = 0.25f
        const val REST_SPEED_PX = 4f

        fun hashFraction(id: String, shift: Int): Float = ((id.hashCode() ushr shift) and 0xFF) / 255f
    }
}

/** Compose-facing holder shared by one Home page and its board. */
@Stable
internal class HomeFloatState(
    config: HomeFloatConfig,
    /** True when tiles report live root centers (lazy Drawer) instead of fixed board centers. */
    private val centersMoveWithScroll: Boolean,
    private val scrollOrientation: Orientation = Orientation.Vertical,
) {
    internal val simulation = HomeFloatSimulation(config, scrollOrientation)

    /** Bumped once per simulated frame; tile layers read it to redraw without recomposing. */
    internal val frame = mutableIntStateOf(0)
    internal var boardOrigin = Offset.Zero
    internal var viewportCoordinates: LayoutCoordinates? = null
    private var anchorId: String? = null
    private var anchorPoint: Offset? = null

    /** The viewport's x in root; it changes while the page or the whole surface slides sideways. */
    internal val viewportX = mutableFloatStateOf(Float.NaN)

    /** Distance the list scrolled since the last frame, as reported by nested scrolling. */
    internal var pendingScroll = 0f

    /** Scroll left over at either scroll edge since the last frame; snapshot state to wake the loop. */
    internal val pendingEdgePull = mutableFloatStateOf(0f)

    /** Observes the configured scroll axis without consuming input. */
    internal val scrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            pendingScroll += if (scrollOrientation == Orientation.Horizontal) consumed.x else consumed.y
            pendingEdgePull.floatValue += abs(
                if (scrollOrientation == Orientation.Horizontal) available.x else available.y,
            )
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // A fling that hits an edge gives the tiles one kick proportional to its leftover speed.
            pendingEdgePull.floatValue += abs(
                if (scrollOrientation == Orientation.Horizontal) available.x else available.y,
            ) * EDGE_FLING_SECONDS
            return Velocity.Zero
        }
    }

    fun offsetOf(id: String): Offset {
        frame.intValue
        return simulation.offsetOf(id)
    }

    /** Anchors the effect on the tile under the finger when a gesture starts. */
    internal fun anchorAt(pointInRoot: Offset) {
        val point = pointInRoot - boardOrigin
        anchorId = simulation.nearestTo(point)
        anchorPoint = anchorId?.let(simulation::centerOf) ?: point
    }

    /**
     * The anchor tile's current center. If a lazy list disposed that tile, keep its last point and
     * move it with the content so the spread stays centered where the tile would be.
     */
    internal fun anchorOnBoard(scrollDeltaPx: Float): Offset {
        val center = anchorId?.let(simulation::centerOf)
        anchorPoint = when {
            center != null -> center
            centersMoveWithScroll -> anchorPoint?.let {
                it + if (scrollOrientation == Orientation.Horizontal) {
                    Offset(scrollDeltaPx, 0f)
                } else {
                    Offset(0f, scrollDeltaPx)
                }
            }
            else -> anchorPoint
        }
        return anchorPoint ?: viewportCoordinates?.let { coordinates ->
            coordinates.positionInRoot() - boardOrigin + Offset(
                coordinates.size.width / 2f,
                coordinates.size.height / 2f,
            )
        } ?: Offset.Zero
    }
}

/** True when launcher settings or the system animator scale ask for reduced motion. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val reduceMotion = LocalReduceMotion.current
    return remember(reduceMotion) {
        reduceMotion || Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * Returns null under reduced motion. The list must use [homeFloatViewport] so its scrolling is
 * observed; [isScrollInProgress] keeps the spread open while a drag or fling is running.
 */
@Composable
internal fun rememberHomeFloatState(
    centersMoveWithScroll: Boolean = false,
    scrollOrientation: Orientation = Orientation.Vertical,
    isScrollInProgress: () -> Boolean,
): HomeFloatState? {
    val currentIsScrollInProgress = rememberUpdatedState(isScrollInProgress)
    val density = LocalDensity.current.density
    val reduceMotion = rememberReduceMotion()
    val state = remember(density, reduceMotion, centersMoveWithScroll, scrollOrientation) {
        if (reduceMotion) {
            null
        } else {
            HomeFloatState(HomeFloatConfig.forDensity(density), centersMoveWithScroll, scrollOrientation)
        }
    } ?: return null
    LaunchedEffect(state) {
        var lastX = state.viewportX.floatValue
        while (true) {
            snapshotFlow {
                val x = state.viewportX.floatValue
                currentIsScrollInProgress.value() ||
                    state.pendingEdgePull.floatValue > 0f ||
                    (!x.isNaN() && x != lastX)
            }.first { it }
            state.pendingScroll = 0f
            var lastFrameNanos = withFrameNanos { it }
            do {
                val frameNanos = withFrameNanos { it }
                val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_SECONDS)
                lastFrameNanos = frameNanos
                val delta = state.pendingScroll
                state.pendingScroll = 0f
                val x = state.viewportX.floatValue
                val horizontalDelta = if (x.isNaN() || lastX.isNaN()) 0f else x - lastX
                lastX = x
                val scrolling = currentIsScrollInProgress.value()
                val edgePull = state.pendingEdgePull.floatValue
                state.pendingEdgePull.floatValue = 0f
                val active = state.simulation.step(
                    dtSeconds = dt,
                    scrollDeltaPx = delta,
                    anchor = state.anchorOnBoard(delta),
                    scrolling = scrolling,
                    edgePullPx = edgePull,
                    horizontalDeltaPx = horizontalDelta,
                )
                state.frame.intValue++
            } while (active || scrolling || horizontalDelta != 0f)
            lastX = state.viewportX.floatValue
        }
    }
    return state
}

private const val MAX_FRAME_SECONDS = 1f / 20f
private const val EDGE_FLING_SECONDS = 0.06f

/** Tracks where the viewport sits, so a sideways slide of the whole surface makes tiles sway. */
internal fun Modifier.homeFloatPosition(state: HomeFloatState?): Modifier =
    if (state == null) {
        this
    } else {
        onGloballyPositioned { coordinates ->
            state.viewportCoordinates = coordinates
            state.viewportX.floatValue = coordinates.positionInRoot().x
        }
    }

/** Adds the start-of-gesture anchor and scroll tracking to [homeFloatPosition]; consumes no input. */
internal fun Modifier.homeFloatViewport(state: HomeFloatState?): Modifier =
    if (state == null) {
        this
    } else {
        this
            .homeFloatPosition(state)
            .nestedScroll(state.scrollConnection)
            .pointerInput(state) {
                awaitEachGesture {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val down = event.changes.firstOrNull { it.pressed }
                    val coordinates = state.viewportCoordinates
                    if (down != null && coordinates != null && coordinates.isAttached) {
                        state.anchorAt(coordinates.localToRoot(down.position))
                    }
                }
            }
    }

/** Blur at a surface that is a full width away from its settled place (page or Drawer slide). */
internal val MaxSurfaceMotionBlur = 14.dp

/** 0 when settled, rising quickly to 1 as the surface slides by [widthsAway] viewport widths. */
internal fun surfaceMotionBlurFraction(widthsAway: Float): Float {
    val t = min(abs(widthsAway), 1f)
    return 1f - (1f - t) * (1f - t)
}

/** Blurs this layer by [radiusPx]; read in the draw phase so a moving pager does not recompose. */
internal fun Modifier.motionBlur(radiusPx: () -> Float): Modifier = graphicsLayer {
    val radius = radiusPx()
    renderEffect = if (radius >= 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BlurEffect(radius, radius, TileMode.Decal)
    } else {
        null
    }
}

internal fun Modifier.homeFloatOffset(state: HomeFloatState, id: String, enabled: Boolean): Modifier =
    graphicsLayer {
        if (enabled) {
            val offset = state.offsetOf(id)
            translationX = offset.x
            translationY = offset.y
        }
    }
