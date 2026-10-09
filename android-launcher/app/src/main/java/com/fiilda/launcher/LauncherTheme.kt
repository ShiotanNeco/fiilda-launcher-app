package com.fiilda.launcher

import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import java.util.Locale

/**
 * Themes supported by the launcher. The token is deliberately small and stable because it is
 * persisted in SharedPreferences and may outlive a particular UI label.
 */
internal enum class LauncherTheme(
    val token: String,
    private val jaName: String,
    private val enName: String,
    private val jaDescription: String,
    private val enDescription: String,
) {
    DEFAULT("default", "デフォルト", "Default", "現在の暗い配色", "The original dark colors"),
    CLASSIC("classic", "Classic", "Classic", "白地、淡い青、赤のアクセント", "White, pale blue, and a red accent"),
    WINDOWS_8(
        "windows8",
        "窓",
        "Windows",
        "紺色の背景とアイコン連動のカラフルなタイル",
        "A navy background with colorful tiles matching each icon",
    ),
    MATERIAL(
        "material",
        "マテリアル",
        "Material",
        "端末の明暗設定に合わせ、Android 12以降は壁紙の動的カラーを使用（それ以前は標準配色）",
        "Follows the device's light or dark mode and uses wallpaper colors on Android 12 and later",
    ),
    GLASS(
        "glass",
        "ガラス",
        "Glass",
        "青灰色の壁紙を使った、読みやすい白文字のガラス風配色",
        "Glass-like surfaces with readable white text over a blue-grey wallpaper",
    ),
    DARK_GLASS(
        "dark_glass",
        "ダークガラス",
        "Dark Glass",
        "黒いガラスと白文字。ガラスの調整値は通常のガラスと共通",
        "Black glass with white text. Shares its glass settings with Glass",
    ),
    ;

    val isGlass: Boolean get() = this == GLASS || this == DARK_GLASS

    val displayName: String get() = tr(jaName, enName)
    val description: String get() = tr(jaDescription, enDescription)
}

/**
 * The three reference colors of the INFOBAR NISHIKIGOI-inspired Classic theme. Color itself is
 * not a compile-time Kotlin constant, so the ARGB values are also kept as named constants for
 * pure logic tests and for callers that need an unambiguous source value.
 */
internal const val NISHIKIGOI_RED_ARGB: Long = 0xFFC8102E
internal const val NISHIKIGOI_IVORY_ARGB: Long = 0xFFF2F0E6
internal const val NISHIKIGOI_BLUE_ARGB: Long = 0xFFC9DDEA

internal val NISHIKIGOI_RED = Color(NISHIKIGOI_RED_ARGB)
internal val NISHIKIGOI_IVORY = Color(NISHIKIGOI_IVORY_ARGB)
internal val NISHIKIGOI_BLUE = Color(NISHIKIGOI_BLUE_ARGB)

/**
 * Shared semantic roles used by the launcher and settings surfaces. Keeping roles independent of
 * Material's light/dark scheme lets the existing FiiLDA* call sites follow a theme without a large
 * mechanical rewrite of the launcher UI.
 */
internal data class LauncherPalette(
    val background: Color,
    val deep: Color,
    val surface: Color,
    val selectedSurface: Color,
    val enabledSurface: Color,
    val accentSurface: Color,
    val photoFrameSurface: Color,
    val photoPreviewInk: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val muted: Color,
    val quiet: Color,
    val accent: Color,
    val accentOn: Color,
    /** True for a flat tile system whose surfaces intentionally have no visible outlines. */
    val borderless: Boolean = false,
    /** True when the active color scheme is light and system bars need dark icons. */
    val isLight: Boolean = false,
)

private val DefaultLauncherPalette = LauncherPalette(
    background = Color(0xFF080909),
    deep = Color(0xFF101312),
    surface = Color(0xFF151918),
    // Existing selected/pressed surfaces are retained byte-for-byte for the default theme.
    selectedSurface = Color(0xFF192223),
    enabledSurface = Color(0xFF202725),
    accentSurface = Color(0xFF182629),
    photoFrameSurface = Color(0xFF0B0E0D),
    photoPreviewInk = Color(0xFFF4F6F5),
    line = Color(0xFF303634),
    lineStrong = Color(0xFF47504D),
    ink = Color(0xFFF4F6F5),
    muted = Color(0xFF9DA5A3),
    quiet = Color(0xFF69716F),
    accent = Color(0xFF52D7EF),
    accentOn = Color(0xFF080909),
    isLight = false,
)

private val ClassicLauncherPalette = LauncherPalette(
    background = NISHIKIGOI_IVORY,
    // Warm white keeps dialogs and secondary surfaces within the ivory/white canvas.
    deep = Color(0xFFFFFCF7),
    surface = Color(0xFFFFFFFF),
    selectedSurface = NISHIKIGOI_BLUE,
    enabledSurface = Color(0xFFE5F0F5),
    // Keep the exact reference blue visible in a selected/active color surface while using
    // darker blue derivatives for boundaries on the white/ivory canvas.
    accentSurface = NISHIKIGOI_BLUE,
    photoFrameSurface = NISHIKIGOI_IVORY,
    photoPreviewInk = Color(0xFFFFFFFF),
    line = Color(0xFF5B7B89),
    lineStrong = Color(0xFF3F5E6C),
    ink = Color(0xFF1D2528),
    muted = Color(0xFF4A5C63),
    quiet = Color(0xFF4E6066),
    accent = NISHIKIGOI_RED,
    accentOn = Color(0xFFFFFFFF),
    isLight = true,
)

/**
 * A restrained Metro palette for the Windows 8-inspired launcher. These are deliberately opaque,
 * flat colors: the navy canvas carries the system chrome while tile colors provide the content
 * hierarchy without gradients, shadows, or rounded cards.
 */
private val Windows8LauncherPalette = LauncherPalette(
    background = Color(0xFF001A33),
    deep = Color(0xFF002542),
    surface = Color(0xFF003653),
    selectedSurface = Color(0xFF004B6B),
    enabledSurface = Color(0xFF005B7A),
    accentSurface = Color(0xFF006E98),
    photoFrameSurface = Color(0xFF001426),
    photoPreviewInk = Color(0xFFFFFFFF),
    // Border roles stay transparent as a structural guarantee. launcherBorder() additionally
    // omits the draw operation so an accidental accent color cannot leave a one-pixel outline.
    line = Color.Transparent,
    lineStrong = Color.Transparent,
    ink = Color(0xFFFFFFFF),
    muted = Color(0xFFE1F2FA),
    // This near-white cyan remains visibly distinct from ink while clearing AA on #006E98.
    quiet = Color(0xFFD0ECF5),
    // Slightly brighter than the original cyan so the battery progress bar remains AA-readable
    // over the dark Metro green surface.
    accent = Color(0xFFBFF7FF),
    accentOn = Color(0xFF001A33),
    borderless = true,
    isLight = false,
)

/**
 * A neutral profile for the Glass surface. Interactive backgrounds stay transparent so the
 * renderer owns the optical material; these roles provide only neutral fallback/ink tokens.
 */
private val GlassLauncherPalette = LauncherPalette(
    background = Color.Transparent,
    // These low-alpha neutral roles are used only as renderer/native-content fallbacks. The
    // Compose launcher surfaces themselves remain transparent and are supplied by GlassSurface.
    deep = Color.White.copy(alpha = 0.08f),
    surface = Color.White.copy(alpha = 0.10f),
    selectedSurface = Color.White.copy(alpha = 0.14f),
    enabledSurface = Color.White.copy(alpha = 0.12f),
    accentSurface = Color.White.copy(alpha = 0.16f),
    photoFrameSurface = Color.White.copy(alpha = 0.08f),
    photoPreviewInk = Color.White,
    line = Color.White.copy(alpha = 0.36f),
    lineStrong = Color.White.copy(alpha = 0.62f),
    ink = Color.White,
    muted = Color.White.copy(alpha = 0.82f),
    quiet = Color.White.copy(alpha = 0.62f),
    accent = Color.White,
    accentOn = Color.Black,
    isLight = false,
)

private val DarkGlassLauncherPalette = GlassLauncherPalette.copy(
    deep = Color.Black.copy(alpha = 0.40f),
    surface = Color.Black.copy(alpha = 0.40f),
    selectedSurface = Color.Black.copy(alpha = 0.30f),
    enabledSurface = Color.Black.copy(alpha = 0.34f),
    accentSurface = Color.Black.copy(alpha = 0.28f),
    photoFrameSurface = Color.Black.copy(alpha = 0.40f),
)

/** Fixed built-in surfaces keep live tiles visually distinct while remaining recognizably Metro. */
internal fun windows8BuiltInTileColor(widgetId: String): Color = when (widgetId) {
    // These two blues are deliberately darker than the familiar brand swatches: all 8–12sp
    // white, muted, and quiet roles rendered by their tiles need dependable AA contrast.
    "widget:clock" -> Color(0xFF005A9E)
    "widget:weather" -> Color(0xFF006B7A)
    "widget:agenda" -> Color(0xFF7A3E9D)
    // Darkened just enough for the light semantic roles to remain AA-readable.
    "widget:calendar" -> Color(0xFFA72B00)
    "widget:battery" -> Color(0xFF107C10)
    "widget:reminder" -> Color(0xFFB4009E)
    "widget:media" -> Color(0xFF0063B1)
    "widget:forecast" -> Color(0xFF7A5E00)
    "widget:photo" -> Color(0xFF005A78)
    else -> Windows8LauncherPalette.surface
}

/**
 * Curated opaque Metro colors used when an icon has no useful chromatic pixels. Keep this list
 * private so callers cannot mutate the fallback sequence and make package-color assignment
 * process-dependent.
 */
private val MetroFallbackTileColors = intArrayOf(
    0xFFE51400.toInt(), // red
    0xFFFA6800.toInt(), // orange
    0xFFF0A30A.toInt(), // amber
    0xFF60A917.toInt(), // green
    0xFF00A4EF.toInt(), // cyan
    0xFF1BA1E2.toInt(), // blue
    0xFFAA00FF.toInt(), // purple
    0xFF8C2FB0.toInt(), // plum
    0xFF008A00.toInt(), // dark green
    0xFFB4009E.toInt(), // magenta
    0xFF0063B1.toInt(), // deep blue
    0xFFC19C00.toInt(), // ochre
)

private const val IconColorHueBins = 24

/** Below this share of colorful opaque pixels, an icon reads as black/white rather than colored. */
private const val ChromaticIconCoverage = 0.12f
private const val NeutralDarkTileArgb = 0xFF1E1E1E.toInt()
private const val NeutralLightTileArgb = 0xFFEDEDED.toInt()

/** Stable package/class based fallback that does not depend on process or list ordering. */
internal fun fallbackIconTileColor(packageName: String, className: String): Int {
    var hash = 2166136261L
    val identity = "$packageName/$className"
    identity.forEach { character ->
        hash = (hash xor character.code.toLong()) * 16777619L
        hash = hash and 0xFFFF_FFFFL
    }
    return MetroFallbackTileColors[(hash % MetroFallbackTileColors.size).toInt()]
}

/**
 * Selects the strongest chromatic hue from rendered drawable pixels. Near-white, near-black, and
 * low-saturation pixels do not vote for a hue, so a white adaptive-icon mask cannot wash out a
 * colorful logo; the hue histogram keeps the result stable across anti-aliased edges. Icons that
 * are almost entirely black/white/grey get a black or white tile matching their overall tone.
 */
internal fun selectIconTileColorFromPixels(
    pixels: IntArray,
    packageName: String,
    className: String,
): Int {
    val weights = FloatArray(IconColorHueBins)
    val redSums = FloatArray(IconColorHueBins)
    val greenSums = FloatArray(IconColorHueBins)
    val blueSums = FloatArray(IconColorHueBins)
    var opaqueCount = 0
    var chromaticCount = 0
    var neutralValueSum = 0f
    pixels.forEach { argb ->
        val alpha = argb ushr 24 and 0xFF
        if (alpha < 128) return@forEach
        opaqueCount++
        val red = argb ushr 16 and 0xFF
        val green = argb ushr 8 and 0xFF
        val blue = argb and 0xFF
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val hsv = rgbToHsv(red, green, blue)
        val saturation = hsv[1]
        val value = hsv[2]
        if (maximum - minimum < 24 || (red >= 232 && green >= 232 && blue >= 232) ||
            (red <= 24 && green <= 24 && blue <= 24) ||
            saturation < 0.25f || value < 0.18f || (value > 0.97f && saturation < 0.4f)
        ) {
            neutralValueSum += value
            return@forEach
        }
        chromaticCount++
        val bin = ((hsv[0] / 360f) * IconColorHueBins)
            .toInt()
            .coerceIn(0, IconColorHueBins - 1)
        val weight = (alpha / 255f) * saturation * (0.5f + value)
        weights[bin] += weight
        redSums[bin] += red * weight
        greenSums[bin] += green * weight
        blueSums[bin] += blue * weight
    }
    if (opaqueCount == 0) return fallbackIconTileColor(packageName, className)
    if (chromaticCount < opaqueCount * ChromaticIconCoverage) {
        val neutralCount = opaqueCount - chromaticCount
        return if (neutralValueSum / neutralCount >= 0.5f) NeutralLightTileArgb else NeutralDarkTileArgb
    }
    val winningBin = weights.indices.maxBy { weights[it] }
    val winningWeight = weights[winningBin]
    val representative = (
        (0xFF shl 24) or
            ((redSums[winningBin] / winningWeight).toInt().coerceIn(0, 255) shl 16) or
            ((greenSums[winningBin] / winningWeight).toInt().coerceIn(0, 255) shl 8) or
            (blueSums[winningBin] / winningWeight).toInt().coerceIn(0, 255)
        )
    return normalizeIconTileColor(representative)
}

/** Raises saturation/value into a vivid but readable range while preserving the selected hue. */
internal fun normalizeIconTileColor(argb: Int): Int {
    val red = argb ushr 16 and 0xFF
    val green = argb ushr 8 and 0xFF
    val blue = argb and 0xFF
    val hsv = rgbToHsv(red, green, blue)
    return hsvToArgb(
        hue = hsv[0],
        saturation = hsv[1].coerceIn(0.56f, 0.96f),
        value = hsv[2].coerceIn(0.62f, 0.92f),
    )
}

/** Returns whichever solid label color has the better WCAG-style contrast against the tile. */
internal fun accessibleTileForegroundArgb(tileColorArgb: Int): Int {
    val luminance = relativeLuminance(tileColorArgb)
    val whiteContrast = 1.05f / (luminance + 0.05f)
    val blackContrast = (luminance + 0.05f) / 0.05f
    return if (whiteContrast >= blackContrast) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
}

/** Pure contrast helper shared by tests and the small set of Windows 8 surface role checks. */
internal fun contrastRatioArgb(firstArgb: Int, secondArgb: Int): Float {
    val firstLuminance = relativeLuminance(firstArgb)
    val secondLuminance = relativeLuminance(secondArgb)
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
        (minOf(firstLuminance, secondLuminance) + 0.05f)
}

/** A subtle selected-state lift that retains the icon hue without reintroducing a border. */
internal fun selectedIconTileColor(tileColorArgb: Int): Int {
    val red = tileColorArgb ushr 16 and 0xFF
    val green = tileColorArgb ushr 8 and 0xFF
    val blue = tileColorArgb and 0xFF
    fun lift(channel: Int): Int = (channel + ((255 - channel) * 0.12f).toInt()).coerceIn(0, 255)
    return (0xFF shl 24) or (lift(red) shl 16) or (lift(green) shl 8) or lift(blue)
}

private fun rgbToHsv(red: Int, green: Int, blue: Int): FloatArray {
    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val maximum = maxOf(r, g, b)
    val minimum = minOf(r, g, b)
    val delta = maximum - minimum
    val hue = when {
        delta == 0f -> 0f
        maximum == r -> ((g - b) / delta).let { if (it < 0f) it + 6f else it } * 60f
        maximum == g -> (((b - r) / delta) + 2f) * 60f
        else -> (((r - g) / delta) + 4f) * 60f
    }
    val saturation = if (maximum == 0f) 0f else delta / maximum
    return floatArrayOf(hue, saturation, maximum)
}

private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
    val chroma = value * saturation
    val hueSector = (hue / 60f).coerceIn(0f, 5.99999f)
    val secondary = chroma * (1f - kotlin.math.abs((hueSector % 2f) - 1f))
    val match = value - chroma
    val (red, green, blue) = when (hueSector.toInt()) {
        0 -> Triple(chroma, secondary, 0f)
        1 -> Triple(secondary, chroma, 0f)
        2 -> Triple(0f, chroma, secondary)
        3 -> Triple(0f, secondary, chroma)
        4 -> Triple(secondary, 0f, chroma)
        else -> Triple(chroma, 0f, secondary)
    }
    fun channel(value: Float): Int = ((value + match) * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

private fun relativeLuminance(argb: Int): Float {
    fun linear(channel: Int): Float {
        val normalized = channel / 255f
        return if (normalized <= 0.04045f) normalized / 12.92f
        else (((normalized + 0.055f) / 1.055f).toDouble().pow(2.4)).toFloat()
    }
    val red = linear(argb ushr 16 and 0xFF)
    val green = linear(argb ushr 8 and 0xFF)
    val blue = linear(argb and 0xFF)
    return red * 0.2126f + green * 0.7152f + blue * 0.0722f
}

/**
 * Converts a resolved Material 3 scheme into the launcher's semantic roles. All custom surfaces
 * stay in Material's neutral surface family so [LauncherPalette.ink] remains the corresponding
 * on-surface color; primary is reserved for accents instead of per-app Metro icon colors.
 *
 * [isLight] is supplied by the system theme rather than inferred from an arbitrary dynamic color,
 * because Android's system-bar icon appearance follows the selected light/dark mode.
 */
internal fun launcherPaletteFromMaterialColorScheme(
    colorScheme: ColorScheme,
    isLight: Boolean,
): LauncherPalette = LauncherPalette(
    background = colorScheme.background,
    // surfaceVariant is the established Material 3 secondary surface role and is a useful
    // recessed shell for dialogs and the context bar. The newer container roles provide the
    // additional interaction-state layers while retaining the onSurface pairing.
    deep = colorScheme.surfaceVariant,
    surface = colorScheme.surface,
    selectedSurface = colorScheme.surfaceContainerHigh,
    enabledSurface = colorScheme.surfaceContainer,
    accentSurface = colorScheme.surfaceContainerHighest,
    photoFrameSurface = colorScheme.surfaceContainerLowest,
    // PhotoPreviewDialog deliberately uses a fixed black media canvas. Neither onSurface nor
    // inverseOnSurface is guaranteed to contrast with black in both Material light and dark
    // schemes, so keep this dedicated media fallback explicitly light.
    photoPreviewInk = Color.White,
    line = colorScheme.outlineVariant,
    lineStrong = colorScheme.outline,
    ink = colorScheme.onSurface,
    muted = colorScheme.onSurfaceVariant,
    quiet = colorScheme.onSurfaceVariant,
    accent = colorScheme.primary,
    accentOn = colorScheme.onPrimary,
    isLight = isLight,
)

internal fun launcherPaletteFor(theme: LauncherTheme): LauncherPalette = when (theme) {
    LauncherTheme.DEFAULT -> DefaultLauncherPalette
    LauncherTheme.CLASSIC -> ClassicLauncherPalette
    LauncherTheme.WINDOWS_8 -> Windows8LauncherPalette
    LauncherTheme.GLASS -> GlassLauncherPalette
    LauncherTheme.DARK_GLASS -> DarkGlassLauncherPalette
    // A resolved Material scheme is selected at the composition boundary. Keep this overload
    // total for persistence/tests and provide a deterministic Material fallback for callers that
    // do not have a Context (the runtime path never uses this branch).
    LauncherTheme.MATERIAL -> launcherPaletteFromMaterialColorScheme(
        darkColorScheme(),
        isLight = false,
    )
}

internal fun parseLauncherThemeToken(raw: String?): LauncherTheme = when (
    raw?.trim()?.lowercase(Locale.ROOT)
) {
    LauncherTheme.CLASSIC.token -> LauncherTheme.CLASSIC
    LauncherTheme.DEFAULT.token -> LauncherTheme.DEFAULT
    LauncherTheme.WINDOWS_8.token -> LauncherTheme.WINDOWS_8
    LauncherTheme.MATERIAL.token -> LauncherTheme.MATERIAL
    LauncherTheme.GLASS.token -> LauncherTheme.GLASS
    LauncherTheme.DARK_GLASS.token -> LauncherTheme.DARK_GLASS
    else -> LauncherTheme.DEFAULT
}

internal fun serializeLauncherThemeToken(theme: LauncherTheme): String = theme.token

// Friendly aliases keep the token logic easy to discover from tests and future migrations.
internal fun launcherThemeFromToken(raw: String?): LauncherTheme = parseLauncherThemeToken(raw)

internal fun launcherThemeToken(theme: LauncherTheme): String = serializeLauncherThemeToken(theme)

internal const val LauncherThemePreferencesName = "fiilda_preferences"
internal const val LauncherThemePreferenceKey = "launcher_theme"

internal fun readLauncherTheme(context: Context): LauncherTheme = runCatching {
    parseLauncherThemeToken(
        context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
            .getString(LauncherThemePreferenceKey, LauncherTheme.DEFAULT.token),
    )
}.getOrDefault(LauncherTheme.DEFAULT)

/**
 * Uses commit rather than apply so a failed write is observable and the UI can keep its previous
 * selection. This also makes a process death immediately after a tap deterministic.
 */
internal fun saveLauncherTheme(context: Context, theme: LauncherTheme): Boolean = runCatching {
    context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
        .edit()
        .putString(LauncherThemePreferenceKey, serializeLauncherThemeToken(theme))
        .commit()
}.getOrDefault(false)

internal val LocalLauncherTheme = staticCompositionLocalOf { LauncherTheme.DEFAULT }
internal val LocalLauncherPalette = staticCompositionLocalOf { DefaultLauncherPalette }
internal val LocalLauncherThemeChanger = staticCompositionLocalOf<(LauncherTheme) -> Boolean> {
    { false }
}

/** App-owned accessibility preference consumed by the Glass UI layer. */
internal val LocalGlassReduceTransparency = staticCompositionLocalOf { false }

/**
 * Applies one of the launcher's fixed outline roles while preserving the exact legacy border
 * widths and colors for Default and Classic. Windows 8 omits the draw operation altogether so
 * transparent borders cannot still affect layout or leave an anti-aliased edge.
 */
@androidx.compose.runtime.Composable
internal fun Modifier.launcherBorder(
    width: Dp = 1.dp,
    color: Color = LocalLauncherPalette.current.line,
    shape: Shape = RectangleShape,
): Modifier = if (LocalLauncherPalette.current.borderless) {
    this
} else {
    border(width = width, color = color, shape = shape)
}

/** Corner radii shared by every theme. Glass defined them first; the other themes follow it. */
internal val LauncherTileCornerRadius = 20.dp
internal val LauncherNavigationCornerRadius = 28.dp
internal val LauncherFolderSheetCornerRadius = 28.dp
internal val LauncherSearchControlCornerRadius = 20.dp

internal val LauncherTileShape = RoundedCornerShape(LauncherTileCornerRadius)
internal val LauncherNavigationShape = RoundedCornerShape(LauncherNavigationCornerRadius)
internal val LauncherFolderSheetShape = RoundedCornerShape(LauncherFolderSheetCornerRadius)
internal val LauncherSearchControlShape = RoundedCornerShape(LauncherSearchControlCornerRadius)

/**
 * The non-Glass counterpart of a glass surface: clips content to [shape], then draws the themed
 * outline and an optional opaque background with the same corners.
 */
@androidx.compose.runtime.Composable
internal fun Modifier.launcherShapedSurface(
    shape: Shape,
    borderColor: Color,
    background: Color?,
    borderWidth: Dp = 1.dp,
): Modifier = clip(shape)
    .launcherBorder(borderWidth, borderColor, shape)
    .then(if (background != null) Modifier.background(background, shape) else Modifier)

/** The launcher's side margin between the screen edge and Home/Drawer content. */
internal val LauncherHorizontalMargin = 12.dp

private class HorizontallyExpandedClip(private val margin: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val marginPx = with(density) { margin.toPx() }
        return Outline.Rectangle(Rect(-marginPx, 0f, size.width + marginPx, size.height))
    }
}

/**
 * Clips like clipToBounds vertically but lets content reach the screen edge through the side
 * margin, so floating tiles near the edge are not cut off while they sway.
 */
internal fun Modifier.clipToBoundsWithSideMargin(expand: Boolean = true): Modifier =
    if (expand) clip(HorizontallyExpandedClip(LauncherHorizontalMargin)) else clipToBounds()

/** Widens this element by the side margin on both sides (layout only; position stays centred). */
internal fun Modifier.extendIntoSideMargin(): Modifier = layout { measurable, constraints ->
    val extra = LauncherHorizontalMargin.roundToPx() * 2
    val widened = if (constraints.hasBoundedWidth) {
        constraints.copy(
            minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra),
            maxWidth = constraints.maxWidth + extra,
        )
    } else {
        constraints
    }
    val placeable = measurable.measure(widened)
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-extra / 2, 0) }
}

