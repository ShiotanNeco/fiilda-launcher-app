package com.fiilda.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.PI
import kotlin.math.sin

// The wide app list's tiles pop in like the FiiLDA logo's cells: one swell per tile, staggered
// along diagonals from the top-left visible tile.
internal const val DrawerRevealStepMs = 35f
internal const val DrawerRevealTileMs = 280f
internal const val DrawerRevealMaxOrder = 10

internal const val DrawerRevealDurationMs: Float = DrawerRevealMaxOrder * DrawerRevealStepMs + DrawerRevealTileMs

/** The app list leaves faster than it arrives, so Home takes over sooner. */
internal const val DrawerExitTimeScale = 0.5f

/** Diagonal order from the top-left visible tile, capped so tiles further down do not wait. */
internal fun drawerRevealOrder(appIndex: Int, columns: Int, firstVisibleRow: Int): Int {
    val row = (appIndex / columns - firstVisibleRow).coerceAtLeast(0)
    return (row + appIndex % columns).coerceAtMost(DrawerRevealMaxOrder)
}

/** How far one tile has popped in (0 to 1) [elapsedMs] after the reveal started. */
internal fun drawerRevealTileProgress(order: Int, elapsedMs: Float): Float =
    ((elapsedMs - order * DrawerRevealStepMs) / DrawerRevealTileMs).coerceIn(0f, 1f)

/** Grows from half size with a slight swell past full size before settling, like the logo. */
internal fun drawerRevealTileScale(progress: Float): Float =
    0.5f + 0.5f * progress + 0.2f * sin(PI * progress).toFloat()

internal fun drawerRevealTileAlpha(progress: Float): Float = (progress * 2.5f).coerceAtMost(1f)

/**
 * Order of a wide Home tile as Home answers the app list's exit: the wave starts at the
 * bottom-right visible cell, where the list's tiles leave first. [columnEnd] and [rowEnd] are
 * the tile's last cell, so a large tile pops in with its bottom-right corner.
 */
internal fun homeRevealOrder(
    columnEnd: Int,
    rowEnd: Int,
    visibleColumns: IntRange,
    rows: Int,
): Int {
    val visibleWidth = (visibleColumns.last - visibleColumns.first).coerceAtLeast(0)
    val farthest = visibleWidth + (rows - 1).coerceAtLeast(0)
    val diagonal = (columnEnd - visibleColumns.first).coerceIn(0, visibleWidth) + rowEnd.coerceAtLeast(0)
    return (farthest - diagonal).coerceIn(0, DrawerRevealMaxOrder)
}

/** Elapsed ms of the wide Home pop-in; it rests at the end, where tiles draw normally. */
internal class WideHomeReveal {
    val elapsed = Animatable(DrawerRevealDurationMs)
}

private const val HomeRevealMaxBlurDp = 18f
private const val HomeRevealBlurMs = DrawerRevealDurationMs - 100f

/** Holds most of its blur while the app list leaves, then clears just before the last tiles land. */
internal fun homeRevealBlurDp(elapsedMs: Float): Float {
    val t = (elapsedMs / HomeRevealBlurMs).coerceIn(0f, 1f)
    return HomeRevealMaxBlurDp * (1f - t * t)
}

internal val LocalWideHomeReveal = staticCompositionLocalOf<WideHomeReveal?> { null }
