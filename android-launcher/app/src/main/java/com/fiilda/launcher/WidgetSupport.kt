package com.fiilda.launcher

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Pending picker state restored by the Activity. The preference marker and captured page are
 * committed before a picker is launched, so both are authoritative over a potentially stale
 * saved-instance bundle.
 */
internal data class PendingWidgetRestore(
    val appWidgetId: Int?,
    val resultReady: Boolean,
    val savedStateWasStale: Boolean,
    /** The persisted page owns the result even when the saved-instance bundle is stale. */
    val targetHomePage: Int = 0,
)

internal fun resolvePendingWidgetRestore(
    savedAppWidgetId: Int?,
    savedResultReady: Boolean,
    persistedAppWidgetId: Int?,
    persistedResultReady: Boolean,
    savedHomePage: Int = 0,
    persistedHomePage: Int = 0,
): PendingWidgetRestore {
    val savedId = savedAppWidgetId?.takeIf { it > 0 }
    val persistedId = persistedAppWidgetId?.takeIf { it > 0 }
    val savedReady = savedId != null && savedResultReady
    val persistedReady = persistedId != null && persistedResultReady
    val savedPage = savedHomePage.coerceAtLeast(0)
    val persistedPage = persistedHomePage.coerceAtLeast(0)
    return PendingWidgetRestore(
        appWidgetId = persistedId,
        resultReady = persistedReady,
        savedStateWasStale = savedId != persistedId ||
            savedReady != persistedReady ||
            savedPage != persistedPage,
        targetHomePage = if (persistedId != null) persistedPage else 0,
    )
}

// Activity.RESULT_* values are stable platform constants. Keeping these aliases here lets the
// configuration-result decision stay pure and testable without coupling WidgetSupport to Activity.
internal const val WidgetActivityResultOk = -1
internal const val WidgetActivityResultCanceled = 0

/**
 * Accepts a widget configuration result only for the allocated ID. A canceled configuration is
 * valid for providers that declare CONFIGURATION_OPTIONAL: Samsung's settings activity can apply
 * its defaults, refresh the bound RemoteViews, and still finish with RESULT_CANCELED. The caller
 * supplies the provider-bound check from AppWidgetManager; all other cancellations remain false.
 */
internal fun shouldAcceptWidgetConfigureResult(
    resultCode: Int,
    returnedAppWidgetId: Int,
    allocatedAppWidgetId: Int?,
    providerBound: Boolean,
    configurationOptional: Boolean,
): Boolean {
    val allocatedId = allocatedAppWidgetId?.takeIf { it > 0 } ?: return false
    if (returnedAppWidgetId > 0 && returnedAppWidgetId != allocatedId) return false
    return resultCode == WidgetActivityResultOk ||
        resultCode == WidgetActivityResultCanceled && providerBound && configurationOptional
}

/**
 * Returns only IDs allocated by this launcher flow that are safe to reclaim after a rejected
 * activity result. A returned ID is untrusted result data: it is intentionally never a cleanup
 * target, whether it mismatches the marker or arrives while the marker is missing.
 */
internal fun widgetActivityResultCleanupIds(
    allocatedAppWidgetId: Int?,
    returnedAppWidgetId: Int,
): List<Int> = allocatedAppWidgetId
    ?.takeIf { it > 0 }
    ?.let(::listOf)
    .orEmpty()

/**
 * The provider dimensions used to choose a board footprint. The descriptor stores dimensions in
 * dp; the Activity converts the framework's px-adjusted provider fields before constructing this
 * value object. Keeping the calculation here makes it testable without an Android device or a
 * provider installed on the host.
 */
internal data class WidgetSizeSpec(
    val minWidthDp: Int,
    val minHeightDp: Int,
    val minResizeWidthDp: Int = 0,
    val minResizeHeightDp: Int = 0,
    val resizeMode: Int = WidgetResizeBoth,
    /** Horizontal/vertical AppWidgetHostView default padding, in dp. */
    val defaultPaddingHorizontalDp: Int = 0,
    val defaultPaddingVerticalDp: Int = 0,
    /** AppWidgetProviderInfo.targetCellWidth/targetCellHeight, in grid cells. */
    val targetCellWidth: Int = 0,
    val targetCellHeight: Int = 0,
) {
    // The native/default footprint is declared by minWidth/minHeight. minResize* describes a
    // resize floor, not the initial size, so it must never enlarge the provider's default even
    // when a provider advertises an unusually large resize minimum.
    val effectiveMinWidthDp: Int
        get() = withHostPadding(
            base = maxOf(
                1,
                minWidthDp,
            ),
            padding = defaultPaddingHorizontalDp,
        )

    val effectiveMinHeightDp: Int
        get() = withHostPadding(
            base = maxOf(
                1,
                minHeightDp,
            ),
            padding = defaultPaddingVerticalDp,
        )
}

private fun withHostPadding(base: Int, padding: Int): Int =
    (base.toLong() + padding.coerceAtLeast(0).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

// Values mirror AppWidgetProviderInfo.RESIZE_* without pulling Android classes into pure tests.
internal const val WidgetResizeNone = 0
internal const val WidgetResizeHorizontal = 1
internal const val WidgetResizeVertical = 2
internal const val WidgetResizeBoth = WidgetResizeHorizontal or WidgetResizeVertical

/** AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, kept pure for picker filtering tests. */
internal const val WidgetCategoryHomeScreen = 1

/**
 * A provider group is keyed by both package and user/profile. The same package can expose a
 * personal and a work-profile provider; merging those rows would make the picker bind the wrong
 * user, so profileKey is deliberately part of the identity even when it is not human-readable.
 */
internal data class WidgetPickerGroupKey(
    val packageName: String,
    val profileKey: String,
)

internal fun isHomeScreenWidgetCategory(widgetCategory: Int): Boolean =
    widgetCategory == 0 || widgetCategory and WidgetCategoryHomeScreen != 0

/**
 * Whether a provider belongs in the launcher's own widget picker.
 *
 * AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER is only a hint to picker UIs. The
 * launcher owns this picker, so a provider that supports the home-screen category remains
 * selectable even when it declares that hint (for example, Samsung Now Brief).
 */
@Suppress("UNUSED_PARAMETER")
internal fun shouldIncludeWidgetProviderInPicker(
    providerAvailable: Boolean,
    widgetCategory: Int,
    hideFromPicker: Boolean,
): Boolean = providerAvailable && isHomeScreenWidgetCategory(widgetCategory)

internal fun widgetPickerGroupKey(packageName: String, profileKey: String): WidgetPickerGroupKey =
    WidgetPickerGroupKey(
        packageName = packageName.trim(),
        profileKey = profileKey.trim().ifBlank { "current" },
    )

/** Expands every profile-specific group belonging to the preferred app package. */
internal fun preferredWidgetPickerGroupKeys(
    groups: Collection<WidgetPickerGroupKey>,
    preferredPackage: String?,
): Set<WidgetPickerGroupKey> = preferredPackage
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.let { packageName -> groups.filter { it.packageName == packageName }.toSet() }
    .orEmpty()

/** The physical row × column footprint shown in the launcher-owned picker. */
internal fun widgetPickerFootprintLabel(size: WidgetGridSize): String =
    tr("標準 ${size.rowSpan.coerceAtLeast(1)}×${size.columnSpan.coerceAtLeast(1)}", "Default ${size.rowSpan.coerceAtLeast(1)}×${size.columnSpan.coerceAtLeast(1)}")

/** Converts a provider dimension exposed in px-adjusted form into the dp used by widget options. */
internal fun providerDimensionPxToDp(valuePx: Int, density: Float): Int {
    if (valuePx <= 0) return 0
    return (valuePx / density.coerceAtLeast(0.01f)).roundToInt().coerceAtLeast(1)
}

/** Converts optional host padding without turning a sub-dp padding edge into a whole dp. */
internal fun providerPaddingPxToDp(valuePx: Int, density: Float): Int {
    if (valuePx <= 0) return 0
    return (valuePx / density.coerceAtLeast(0.01f)).roundToInt().coerceAtLeast(0)
}

internal fun widgetSizeSpecFromProviderPixels(
    minWidthPx: Int,
    minHeightPx: Int,
    minResizeWidthPx: Int,
    minResizeHeightPx: Int,
    resizeMode: Int,
    density: Float,
    defaultPaddingHorizontalPx: Int = 0,
    defaultPaddingVerticalPx: Int = 0,
    targetCellWidth: Int = 0,
    targetCellHeight: Int = 0,
): WidgetSizeSpec = WidgetSizeSpec(
    minWidthDp = providerDimensionPxToDp(minWidthPx, density),
    minHeightDp = providerDimensionPxToDp(minHeightPx, density),
    minResizeWidthDp = providerDimensionPxToDp(minResizeWidthPx, density),
    minResizeHeightDp = providerDimensionPxToDp(minResizeHeightPx, density),
    resizeMode = resizeMode.coerceIn(WidgetResizeNone, WidgetResizeBoth),
    defaultPaddingHorizontalDp = providerPaddingPxToDp(defaultPaddingHorizontalPx, density),
    defaultPaddingVerticalDp = providerPaddingPxToDp(defaultPaddingVerticalPx, density),
    targetCellWidth = targetCellWidth.coerceAtLeast(0),
    targetCellHeight = targetCellHeight.coerceAtLeast(0),
)

/** A widget's requested footprint in the current square home-grid cells. */
internal data class WidgetGridSize(
    val columnSpan: Int,
    val rowSpan: Int,
)

/**
 * The launcher-owned widget footprints exposed by the long-press action menu.
 *
 * Labels use numeric row×column order; the action sheet pairs them with a footprint pictogram so
 * the orientation remains clear without repeating 縦/横 in every option. AUTO is only meaningful
 * for an external AppWidget; it is persisted as a token so choosing it explicitly remains stable
 * across an Activity recreation.
 */
/**
 * A widget footprint choice: AUTO keeps the provider's own size; any other value is a free size
 * from 1×1 to [MaxTileRowSpan]×[MaxTileColumnSpan]. Named presets keep their historical tokens.
 */
@androidx.compose.runtime.Immutable
internal class WidgetSizeChoice private constructor(
    val rowSpan: Int,
    val columnSpan: Int,
    private val presetName: String?,
    val isAuto: Boolean = false,
) {
    val label: String get() = if (isAuto) tr("自動（元のサイズ）", "Automatic (original size)") else "$rowSpan×$columnSpan"

    /** Stable persistence token: the preset name, or `R<rows>C<columns>` for a free size. */
    val name: String get() = presetName ?: "R${rowSpan}C$columnSpan"

    override fun equals(other: Any?): Boolean = other is WidgetSizeChoice &&
        other.isAuto == isAuto && other.rowSpan == rowSpan && other.columnSpan == columnSpan

    override fun hashCode(): Int = if (isAuto) -1 else rowSpan * 31 + columnSpan

    override fun toString(): String = name

    companion object {
        val AUTO = WidgetSizeChoice(0, 0, "AUTO", isAuto = true)
        val ROW_1_COLUMN_1 = WidgetSizeChoice(1, 1, "ROW_1_COLUMN_1")
        val ROW_1_COLUMN_2 = WidgetSizeChoice(1, 2, "ROW_1_COLUMN_2")
        val ROW_2_COLUMN_1 = WidgetSizeChoice(2, 1, "ROW_2_COLUMN_1")
        val ROW_2_COLUMN_2 = WidgetSizeChoice(2, 2, "ROW_2_COLUMN_2")
        val ROW_1_COLUMN_4 = WidgetSizeChoice(1, 4, "ROW_1_COLUMN_4")
        val ROW_2_COLUMN_4 = WidgetSizeChoice(2, 4, "ROW_2_COLUMN_4")
        val ROW_4_COLUMN_4 = WidgetSizeChoice(4, 4, "ROW_4_COLUMN_4")

        private val presets = listOf(
            ROW_1_COLUMN_1, ROW_1_COLUMN_2, ROW_2_COLUMN_1, ROW_2_COLUMN_2,
            ROW_1_COLUMN_4, ROW_2_COLUMN_4, ROW_4_COLUMN_4,
        )

        /** AUTO followed by every preset. */
        fun values(): List<WidgetSizeChoice> = listOf(AUTO) + presets

        /** A fixed footprint clamped to the supported range; a preset is returned for its own size. */
        fun of(rows: Int, columns: Int): WidgetSizeChoice {
            val safeRows = rows.coerceIn(1, MaxTileRowSpan)
            val safeColumns = columns.coerceIn(1, MaxTileColumnSpan)
            return presets.firstOrNull { it.rowSpan == safeRows && it.columnSpan == safeColumns }
                ?: WidgetSizeChoice(safeRows, safeColumns, null)
        }

        /** Parses a token written by [name]; unknown tokens throw like an enum lookup. */
        fun valueOf(token: String): WidgetSizeChoice {
            if (token == AUTO.name) return AUTO
            presets.firstOrNull { it.presetName == token }?.let { return it }
            val match = FreeSizeToken.matchEntire(token)
                ?: throw IllegalArgumentException("Unknown widget size: $token")
            val rows = match.groupValues[1].toInt()
            val columns = match.groupValues[2].toInt()
            require(rows in 1..MaxTileRowSpan && columns in 1..MaxTileColumnSpan) {
                "Widget size out of range: $token"
            }
            return of(rows, columns)
        }

        private val FreeSizeToken = Regex("R(\\d)C(\\d)")
    }
}

internal val FixedWidgetSizeChoices: List<WidgetSizeChoice> = listOf(
    WidgetSizeChoice.ROW_1_COLUMN_2,
    WidgetSizeChoice.ROW_2_COLUMN_2,
    WidgetSizeChoice.ROW_1_COLUMN_4,
    WidgetSizeChoice.ROW_2_COLUMN_4,
)

/**
 * PHOTO is intentionally the only built-in that exposes portrait, square, and poster-sized
 * footprints. Keeping this separate from [FixedWidgetSizeChoices] prevents existing widgets
 * from advertising a footprint that their content cannot render reliably.
 */
internal val PhotoWidgetSizeChoices: List<WidgetSizeChoice> = listOf(
    WidgetSizeChoice.ROW_1_COLUMN_1,
    WidgetSizeChoice.ROW_1_COLUMN_2,
    WidgetSizeChoice.ROW_2_COLUMN_1,
    WidgetSizeChoice.ROW_2_COLUMN_2,
    WidgetSizeChoice.ROW_2_COLUMN_4,
    WidgetSizeChoice.ROW_4_COLUMN_4,
)

internal const val WidgetSizeAutoToken = "AUTO"

internal fun widgetSizeChoiceForToken(token: String): WidgetSizeChoice? = when (token) {
    WidgetSizeAutoToken -> WidgetSizeChoice.AUTO
    else -> runCatching { WidgetSizeChoice.valueOf(token) }.getOrNull()
}

internal fun widgetSizeChoiceToken(choice: WidgetSizeChoice): String =
    if (choice.isAuto) WidgetSizeAutoToken else choice.name

/** Resolves a persisted choice, retaining provider-native sizing for AUTO or an absent choice. */
internal fun resolveWidgetGridSize(
    choice: WidgetSizeChoice?,
    providerSize: WidgetGridSize,
    columns: Int,
    builtIn: Boolean,
): WidgetGridSize {
    val resolvedChoice = if (builtIn) {
        choice?.takeUnless { it.isAuto } ?: WidgetSizeChoice.ROW_2_COLUMN_2
    } else {
        choice?.takeUnless { it.isAuto }
    }
    return resolvedChoice?.let {
        WidgetGridSize(
            columnSpan = it.columnSpan.coerceIn(1, columns.coerceAtLeast(1)),
            rowSpan = it.rowSpan.coerceAtLeast(1),
        )
    } ?: WidgetGridSize(
        columnSpan = providerSize.columnSpan.coerceIn(1, columns.coerceAtLeast(1)),
        rowSpan = providerSize.rowSpan.coerceAtLeast(1),
    )
}

/**
 * Encodes widget size overrides as one id/token pair per line.  IDs are intentionally limited to
 * the widget namespace; malformed rows and unknown tokens are ignored by the decoder.
 */
internal fun serializeWidgetSizeOverrides(
    overrides: Map<String, WidgetSizeChoice>,
): String = overrides
    .asSequence()
    .filter { (id, choice) ->
        isWidgetHomeId(id) && (!choice.isAuto || id.startsWith(ExternalWidgetIdPrefix))
    }
    .sortedBy { (id, _) -> id }
    .joinToString("\n") { (id, choice) -> "$id\t${widgetSizeChoiceToken(choice)}" }

internal fun parseWidgetSizeOverrides(
    raw: String?,
    knownHomeIds: Set<String> = emptySet(),
): Map<String, WidgetSizeChoice> {
    if (raw.isNullOrBlank()) return emptyMap()
    val seen = mutableSetOf<String>()
    return raw.lineSequence().mapNotNull { line ->
        val fields = line.split('\t', limit = 2)
        if (fields.size != 2) return@mapNotNull null
        val id = fields[0]
        if (!isWidgetHomeId(id) || (knownHomeIds.isNotEmpty() && id !in knownHomeIds)) {
            return@mapNotNull null
        }
        val choice = widgetSizeChoiceForToken(fields[1]) ?: return@mapNotNull null
        if (choice.isAuto && !id.startsWith(ExternalWidgetIdPrefix)) {
            return@mapNotNull null
        }
        if (!seen.add(id)) return@mapNotNull null
        id to choice
    }.toMap()
}

internal fun pruneWidgetSizeOverrides(
    overrides: Map<String, WidgetSizeChoice>,
    presentHomeIds: Set<String>,
): Map<String, WidgetSizeChoice> = overrides
    .filterKeys { it in presentHomeIds }
    .filterKeys(::isWidgetHomeId)
    .filter { (id, choice) -> !choice.isAuto || id.startsWith(ExternalWidgetIdPrefix) }

private fun isWidgetHomeId(id: String): Boolean {
    if (id.isBlank() || id.contains('\t') || id.contains('\r') || id.contains('\n')) {
        return false
    }
    if (id in BuiltInWidgetHomeIds || isPhotoWidgetHomeId(id) || isWebLinkHomeId(id)) return true
    return id.removePrefix(ExternalWidgetIdPrefix)
        .takeIf { id.startsWith(ExternalWidgetIdPrefix) }
        ?.toIntOrNull()
        ?.let { it > 0 }
        ?: false
}

private val BuiltInWidgetHomeIds = setOf(
    "widget:clock",
    "widget:weather",
    "widget:agenda",
    "widget:calendar",
    "widget:battery",
    "widget:reminder",
    "widget:media",
    "widget:forecast",
    PhotoWidgetHomeId,
)

/**
 * Computes a widget footprint from provider minimum dimensions and the actual current cell size.
 *
 * The gap is included in the numerator/denominator so that a widget spanning two cells also
 * accounts for the gap between those cells.  Width is clamped to the current posture's column
 * count; height is deliberately not clamped, preserving a provider's requested aspect footprint
 * when a narrow cover posture is used.
 */
internal fun calculateWidgetGridSpans(
    spec: WidgetSizeSpec,
    cellWidthDp: Float,
    gapDp: Float,
    columns: Int,
): WidgetGridSize {
    val safeCell = cellWidthDp.coerceAtLeast(1f)
    val safeGap = gapDp.coerceAtLeast(0f)
    val safeColumns = columns.coerceAtLeast(1)
    fun spanFor(sizeDp: Int): Int = ceil(
        (sizeDp.toFloat() + safeGap) / (safeCell + safeGap),
    ).toInt().coerceAtLeast(1)

    return WidgetGridSize(
        // API 31 providers may declare a native cell footprint. It is authoritative for AUTO;
        // only the horizontal span is clamped because the current posture has a finite column
        // count while a provider may legitimately request a taller board footprint.
        columnSpan = (spec.targetCellWidth.takeIf { it > 0 }
            ?: spanFor(spec.effectiveMinWidthDp)).coerceAtMost(safeColumns),
        rowSpan = spec.targetCellHeight.takeIf { it > 0 }
            ?: spanFor(spec.effectiveMinHeightDp),
    )
}

/** Height of one board row, including the gaps inside a multi-row widget footprint. */
internal fun calculateGridRowHeightDp(cellWidthDp: Float, rowSpan: Int, gapDp: Float): Float {
    val safeRows = rowSpan.coerceAtLeast(1)
    return cellWidthDp.coerceAtLeast(0f) * safeRows + gapDp.coerceAtLeast(0f) * (safeRows - 1)
}

/** Total board height for measured row spans, including gaps between rows. */
internal fun calculateGridBoardHeightDp(
    cellWidthDp: Float,
    rowSpans: List<Int>,
    gapDp: Float,
): Float {
    if (rowSpans.isEmpty()) return 0f
    val safeGap = gapDp.coerceAtLeast(0f)
    return rowSpans.sumOf { calculateGridRowHeightDp(cellWidthDp, it, safeGap).toDouble() }
        .toFloat() + safeGap * (rowSpans.size - 1)
}

/** A persisted third-party app-widget descriptor. */
internal data class LauncherWidgetDescriptor(
    val appWidgetId: Int,
    val provider: String,
    val label: String,
    val sizeSpec: WidgetSizeSpec,
) {
    val homeId: String
        get() = "$ExternalWidgetIdPrefix$appWidgetId"
}

internal const val ExternalWidgetIdPrefix = "widget:external:"
internal const val PhotoWidgetHomeId = "widget:photo"
internal const val PhotoWidgetHomeIdPrefix = "widget:photo:"

internal fun isPhotoWidgetHomeId(id: String): Boolean =
    id == PhotoWidgetHomeId ||
        (id.startsWith(PhotoWidgetHomeIdPrefix) &&
            id.removePrefix(PhotoWidgetHomeIdPrefix).isNotBlank())

internal fun newPhotoWidgetHomeId(existingIds: Set<String>): String {
    var serial = 1
    while ("$PhotoWidgetHomeIdPrefix$serial" in existingIds) serial++
    return "$PhotoWidgetHomeIdPrefix$serial"
}

/**
 * The EXIF orientation transform used by the photo frame. Android's ExifInterface constants are
 * intentionally not referenced here so this mapping remains a JVM-testable pure function.
 * Mirrored orientations are represented as a horizontal mirror after the clockwise rotation.
 */
internal data class PhotoFrameExifTransform(
    val rotationDegrees: Int,
    val mirrorHorizontal: Boolean = false,
)

internal fun photoFrameExifTransform(orientation: Int): PhotoFrameExifTransform = when (orientation) {
    2 -> PhotoFrameExifTransform(rotationDegrees = 0, mirrorHorizontal = true)
    3 -> PhotoFrameExifTransform(rotationDegrees = 180)
    4 -> PhotoFrameExifTransform(rotationDegrees = 180, mirrorHorizontal = true)
    5 -> PhotoFrameExifTransform(rotationDegrees = 90, mirrorHorizontal = true)
    6 -> PhotoFrameExifTransform(rotationDegrees = 90)
    7 -> PhotoFrameExifTransform(rotationDegrees = 270, mirrorHorizontal = true)
    8 -> PhotoFrameExifTransform(rotationDegrees = 270)
    else -> PhotoFrameExifTransform(rotationDegrees = 0)
}

internal fun photoHomeOrderAfterSelection(
    current: List<String>,
    addToHome: Boolean,
    widgetId: String = PhotoWidgetHomeId,
): List<String> = if (addToHome && widgetId !in current) {
    current + widgetId
} else {
    current
}

internal fun photoHomeOrderAfterRemoval(
    current: List<String>,
    widgetId: String = PhotoWidgetHomeId,
): List<String> = current.filterNot { it == widgetId }

/**
 * Shared-preference codec for widget descriptors. Provider names and labels are base64 encoded so
 * tabs/newlines in an app label cannot corrupt a neighbouring descriptor. Malformed rows are
 * ignored, which lets a bad legacy entry coexist with healthy widgets.
 */
internal fun serializeWidgetDescriptors(descriptors: List<LauncherWidgetDescriptor>): String =
    descriptors
        .asSequence()
        .filter { it.appWidgetId > 0 && it.provider.isNotBlank() }
        .distinctBy { it.appWidgetId }
        .joinToString("\n") { descriptor ->
            listOf(
                descriptor.appWidgetId.toString(),
                encodeWidgetPart(descriptor.provider),
                encodeWidgetPart(descriptor.label),
                descriptor.sizeSpec.minWidthDp.toString(),
                descriptor.sizeSpec.minHeightDp.toString(),
                descriptor.sizeSpec.minResizeWidthDp.toString(),
                descriptor.sizeSpec.minResizeHeightDp.toString(),
                descriptor.sizeSpec.resizeMode.toString(),
                descriptor.sizeSpec.defaultPaddingHorizontalDp.toString(),
                descriptor.sizeSpec.defaultPaddingVerticalDp.toString(),
                descriptor.sizeSpec.targetCellWidth.toString(),
                descriptor.sizeSpec.targetCellHeight.toString(),
            ).joinToString("\t")
        }

internal fun parseWidgetDescriptors(raw: String?): List<LauncherWidgetDescriptor> {
    if (raw.isNullOrBlank()) return emptyList()
    val seen = mutableSetOf<Int>()
    return raw.lineSequence().mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size !in setOf(7, 8, 9, 10, 11, 12)) {
            return@mapNotNull null
        }
        runCatching {
            val id = fields[0].toInt()
            val provider = decodeWidgetPart(fields[1])
            val label = decodeWidgetPart(fields[2])
            val dimensions = fields.slice(3..6).map(String::toInt)
            val resizeMode = fields.getOrNull(7)?.toInt() ?: run {
                // Seven-field rows predate resizeMode. Treat them as both-axis resizable so
                // their previously persisted footprint remains unchanged.
                WidgetResizeBoth
            }
            // Ten-field rows are accepted as an intermediate target-cell migration (resize mode
            // plus target width/height). The current format adds both padding dimensions first,
            // yielding twelve fields. Nine/eleven-field rows are tolerated as a partially-written
            // migration and default the missing trailing values to zero.
            val paddingHorizontalDp = if (fields.size >= 11) fields[8].toInt() else 0
            val paddingVerticalDp = if (fields.size >= 11) fields[9].toInt() else 0
            val targetCellWidth = when (fields.size) {
                9, 10 -> fields[8].toInt()
                11, 12 -> fields[10].toInt()
                else -> 0
            }
            val targetCellHeight = when (fields.size) {
                10 -> fields[9].toInt()
                12 -> fields[11].toInt()
                else -> 0
            }
            if (id <= 0 || provider.isBlank() || dimensions.any { it < 0 } ||
                resizeMode !in WidgetResizeNone..WidgetResizeBoth ||
                paddingHorizontalDp < 0 || paddingVerticalDp < 0 ||
                targetCellWidth < 0 || targetCellHeight < 0 || !seen.add(id)
            ) {
                null
            } else {
                LauncherWidgetDescriptor(
                    appWidgetId = id,
                    provider = provider,
                    label = label,
                    sizeSpec = WidgetSizeSpec(
                        minWidthDp = dimensions[0],
                        minHeightDp = dimensions[1],
                        minResizeWidthDp = dimensions[2],
                        minResizeHeightDp = dimensions[3],
                        resizeMode = resizeMode,
                        defaultPaddingHorizontalDp = paddingHorizontalDp,
                        defaultPaddingVerticalDp = paddingVerticalDp,
                        targetCellWidth = targetCellWidth,
                        targetCellHeight = targetCellHeight,
                    ),
                )
            }
        }.getOrNull()
    }.toList()
}

private fun encodeWidgetPart(value: String): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeWidgetPart(value: String): String = Base64.getUrlDecoder()
    .decode(value)
    .toString(StandardCharsets.UTF_8)
