package com.fiilda.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.sin

/** The role of one cell in the 3×3 FiiLDA logo, which spells an "F" with two accent cells. */
internal enum class FiiLDALogoTile { BASE, PRIMARY, SECONDARY, OUTLINE, EMPTY }

/** Logo cells by row (top first), then column. */
internal val FiiLDALogoTiles: List<List<FiiLDALogoTile>> = listOf(
    listOf(FiiLDALogoTile.BASE, FiiLDALogoTile.BASE, FiiLDALogoTile.SECONDARY),
    listOf(FiiLDALogoTile.BASE, FiiLDALogoTile.PRIMARY, FiiLDALogoTile.OUTLINE),
    listOf(FiiLDALogoTile.BASE, FiiLDALogoTile.BASE, FiiLDALogoTile.EMPTY),
)

internal const val FiiLDALogoWaveStepMs = 70f
internal const val FiiLDALogoWaveTileMs = 260f
private const val FiiLDALogoWaveLift = 0.24f

/** Diagonal distance from the bottom-left cell, so the wave starts there and ends top-right. */
internal fun fiildaLogoWaveOrder(row: Int, column: Int): Int = column + (2 - row)

internal val FiiLDALogoWaveDurationMs: Float =
    fiildaLogoWaveOrder(row = 0, column = 2) * FiiLDALogoWaveStepMs + FiiLDALogoWaveTileMs

/** Scale of one cell [elapsedMs] after the wave starts: a single swell that returns to 1. */
internal fun fiildaLogoWaveScale(row: Int, column: Int, elapsedMs: Float): Float {
    val local = (elapsedMs - fiildaLogoWaveOrder(row, column) * FiiLDALogoWaveStepMs) / FiiLDALogoWaveTileMs
    if (local <= 0f || local >= 1f) return 1f
    return 1f + FiiLDALogoWaveLift * sin(PI * local).toFloat()
}

/** Colors of the two accent cells, which take the place of the logo's blues in each theme. */
internal class FiiLDALogoAccents(val primary: Color, val secondary: Color)

/**
 * The theme's accent stands in for the logo's blues. Classic keeps red as its single accent and
 * uses its blue-grey line for the second cell. On an accent fill ([onAccent]) both become a
 * lighter [tint] so they never vanish into the fill.
 */
internal fun fiildaLogoAccents(
    language: WidgetLanguage,
    palette: LauncherPalette,
    tint: Color,
    onAccent: Boolean,
): FiiLDALogoAccents {
    if (onAccent) {
        val light = tint.copy(alpha = tint.alpha * 0.55f)
        return FiiLDALogoAccents(light, light)
    }
    val secondary = if (language == WidgetLanguage.NISHIKIGOI) {
        palette.line
    } else {
        palette.accent.copy(alpha = palette.accent.alpha * 0.55f)
    }
    return FiiLDALogoAccents(palette.accent, secondary)
}

/** Cell corner radius as a fraction of the cell, following each theme's widget cells. */
internal fun fiildaLogoCornerFraction(language: WidgetLanguage): Float = when (language) {
    WidgetLanguage.KEYBOARD -> 0.18f
    WidgetLanguage.NISHIKIGOI -> 0.08f
    WidgetLanguage.METRO -> 0f
    WidgetLanguage.MATERIAL -> 0.35f
    WidgetLanguage.GLASS -> 0.25f
}

/**
 * How far one cell has assembled when the whole logo is [assembly] of the way from collapsed (0)
 * to whole (1). Cells assemble in wave order from the bottom left, so collapsing runs the other
 * way and the top-right cell goes first.
 */
internal fun fiildaLogoTileAssembly(row: Int, column: Int, assembly: Float): Float =
    (assembly * 2f - fiildaLogoWaveOrder(row, column) * 0.25f).coerceIn(0f, 1f)

/**
 * The FiiLDA tile logo used as the app-list icon, drawn in the current theme's language. [tint]
 * colors the "F" cells; [onAccent] means it sits on the accent fill of a selected tab. Each
 * change of [waveKey] while it is shown plays the wave once; its first value never does.
 * [assembly] below 1 draws the cells gathering into the centre (read while drawing only).
 */
@Composable
internal fun FiiLDALogoIcon(
    tint: Color,
    onAccent: Boolean,
    modifier: Modifier = Modifier,
    waveKey: Int = 0,
    assembly: () -> Float = { 1f },
) {
    val language = widgetLanguageFor(LocalLauncherTheme.current)
    val accents = fiildaLogoAccents(language, LocalLauncherPalette.current, tint, onAccent)
    val cornerFraction = fiildaLogoCornerFraction(language)
    val motionEnabled = LocalLauncherGlassMotionEnabled.current
    val elapsed = remember { Animatable(FiiLDALogoWaveDurationMs) }
    // A logo shown again after a press (for example the wide switcher returning from the app list)
    // must not replay that press, so only a change seen by this composition plays the wave.
    val shownWaveKey = remember { intArrayOf(waveKey) }
    LaunchedEffect(waveKey, motionEnabled) {
        val pressed = waveKey != shownWaveKey[0]
        shownWaveKey[0] = waveKey
        if (!pressed || !motionEnabled) {
            elapsed.snapTo(FiiLDALogoWaveDurationMs)
            return@LaunchedEffect
        }
        elapsed.snapTo(0f)
        elapsed.animateTo(
            targetValue = FiiLDALogoWaveDurationMs,
            animationSpec = tween(FiiLDALogoWaveDurationMs.toInt(), easing = LinearEasing),
        )
    }
    val primary = accents.primary
    val secondary = accents.secondary
    Canvas(modifier = modifier) {
        // The logo's gap is about a sixth of a cell; the grid fills 22/24 of the icon box.
        val grid = size.minDimension * 22f / 24f
        val gap = grid / 21f
        val cell = (grid - 2 * gap) / 3f
        val origin = Offset((size.width - grid) / 2f, (size.height - grid) / 2f)
        val corner = CornerRadius(cell * cornerFraction)
        val stroke = cell * 0.14f
        val gridCenter = origin + Offset(grid / 2f, grid / 2f)
        val logoAssembly = assembly()
        FiiLDALogoTiles.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, tile ->
                if (tile == FiiLDALogoTile.EMPTY) return@forEachIndexed
                val topLeft = origin + Offset(column * (cell + gap), row * (cell + gap))
                val center = topLeft + Offset(cell / 2f, cell / 2f)
                val assembled = fiildaLogoTileAssembly(row, column, logoAssembly)
                if (assembled <= 0f) return@forEachIndexed
                // A gathering cell slides toward the centre and shrinks; assembling, it pops
                // slightly past full size on the way out.
                val shift = (gridCenter - center) * (1f - assembled)
                val pop = assembled * (1f + 0.2f * sin(PI * assembled).toFloat())
                val factor = fiildaLogoWaveScale(row, column, elapsed.value) * pop
                val alpha = (assembled * 2f).coerceAtMost(1f)
                translate(shift.x, shift.y) {
                    scale(factor, pivot = center) {
                        when (tile) {
                            FiiLDALogoTile.OUTLINE -> drawRoundRect(
                                color = secondary,
                                topLeft = topLeft + Offset(stroke / 2f, stroke / 2f),
                                size = Size(cell - stroke, cell - stroke),
                                cornerRadius = corner,
                                style = Stroke(width = stroke),
                                alpha = alpha,
                            )
                            else -> drawRoundRect(
                                color = when (tile) {
                                    FiiLDALogoTile.PRIMARY -> primary
                                    FiiLDALogoTile.SECONDARY -> secondary
                                    else -> tint
                                },
                                topLeft = topLeft,
                                size = Size(cell, cell),
                                cornerRadius = corner,
                                alpha = alpha,
                            )
                        }
                    }
                }
            }
        }
    }
}
