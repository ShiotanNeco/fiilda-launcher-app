package com.fiilda.launcher

/** The presentation whose tile-size overrides are being edited or rendered. */
internal enum class HomeSizePresentation {
    NARROW,
    WIDE,
}

/** The two persisted size maps that make up one presentation's home-board sizing state. */
internal data class HomeSizeMaps(
    val appTileSizes: Map<String, AppTileSize> = emptyMap(),
    val widgetSizeOverrides: Map<String, WidgetSizeChoice> = emptyMap(),
)

/**
 * Resolves the maps used by a presentation. Shared mode always renders the narrow/shared maps;
 * the wide snapshot is consulted only by the inner-landscape canvas while separation is enabled.
 */
internal fun homeSizeMapsForPresentation(
    narrow: HomeSizeMaps,
    wide: HomeSizeMaps,
    separateWideOrder: Boolean,
    presentation: HomeSizePresentation,
): HomeSizeMaps = if (
    separateWideOrder && presentation == HomeSizePresentation.WIDE
) {
    wide
} else {
    narrow
}

/** A missing wide record is a migration of the current shared map, preserving the old appearance. */
internal fun migrateWideHomeSizeMaps(
    narrow: HomeSizeMaps,
    storedWide: HomeSizeMaps?,
): HomeSizeMaps = storedWide ?: narrow

private fun updateAppTileSize(
    maps: HomeSizeMaps,
    id: String,
    size: AppTileSize,
): HomeSizeMaps {
    if (id.isBlank()) return maps
    val updated = if (size == AppTileSize.SMALL) {
        maps.appTileSizes - id
    } else {
        maps.appTileSizes + (id to size)
    }
    return maps.copy(appTileSizes = updated)
}

/**
 * Applies an app-size edit to the selected presentation. In shared mode the resulting narrow app
 * map is copied to wide as one logical update, including when the preserved wide snapshot differs.
 */
internal fun updateHomeAppTileSize(
    narrow: HomeSizeMaps,
    wide: HomeSizeMaps,
    id: String,
    size: AppTileSize,
    presentation: HomeSizePresentation,
    separateWideOrder: Boolean,
): Pair<HomeSizeMaps, HomeSizeMaps> {
    if (!separateWideOrder) {
        val updatedNarrow = updateAppTileSize(narrow, id, size)
        // Shared mode synchronizes the edited map only. This keeps an unrelated wide snapshot
        // intact until that item is explicitly edited while sharing is off.
        return updatedNarrow to wide.copy(appTileSizes = updatedNarrow.appTileSizes)
    }
    return when (presentation) {
        HomeSizePresentation.NARROW -> updateAppTileSize(narrow, id, size) to wide
        HomeSizePresentation.WIDE -> narrow to updateAppTileSize(wide, id, size)
    }
}

private fun updateWidgetSize(
    maps: HomeSizeMaps,
    id: String,
    choice: WidgetSizeChoice,
    builtIn: Boolean,
): HomeSizeMaps {
    if (id.isBlank()) return maps
    val updated = if (
        (builtIn && (choice == WidgetSizeChoice.ROW_2_COLUMN_2 || choice.isAuto)) ||
        (!builtIn && choice == WidgetSizeChoice.AUTO)
    ) {
        if (builtIn) maps.widgetSizeOverrides - id
        else maps.widgetSizeOverrides + (id to choice)
    } else {
        maps.widgetSizeOverrides + (id to choice)
    }
    return maps.copy(widgetSizeOverrides = updated)
}

/** Applies a built-in/external widget-size edit using the same shared-mode rule as app sizes. */
internal fun updateHomeWidgetSize(
    narrow: HomeSizeMaps,
    wide: HomeSizeMaps,
    id: String,
    choice: WidgetSizeChoice,
    builtIn: Boolean,
    presentation: HomeSizePresentation,
    separateWideOrder: Boolean,
): Pair<HomeSizeMaps, HomeSizeMaps> {
    if (!separateWideOrder) {
        val updatedNarrow = updateWidgetSize(narrow, id, choice, builtIn)
        return updatedNarrow to wide.copy(widgetSizeOverrides = updatedNarrow.widgetSizeOverrides)
    }
    return when (presentation) {
        HomeSizePresentation.NARROW -> updateWidgetSize(narrow, id, choice, builtIn) to wide
        HomeSizePresentation.WIDE -> narrow to updateWidgetSize(wide, id, choice, builtIn)
    }
}

/** Removes a home ID from both durable maps, regardless of the current presentation mode. */
internal fun removeHomeSizeIdFromMaps(
    narrow: HomeSizeMaps,
    wide: HomeSizeMaps,
    id: String,
): Pair<HomeSizeMaps, HomeSizeMaps> =
    narrow.copy(
        appTileSizes = narrow.appTileSizes - id,
        widgetSizeOverrides = narrow.widgetSizeOverrides - id,
    ) to wide.copy(
        appTileSizes = wide.appTileSizes - id,
        widgetSizeOverrides = wide.widgetSizeOverrides - id,
    )

/** Removes invalid/default app size rows while retaining only current favorites. */
internal fun pruneAppTileSizes(
    sizes: Map<String, AppTileSize>,
    favoriteIds: Set<String>,
): Map<String, AppTileSize> = sizes
    .filter { (id, size) -> id in favoriteIds && id.isNotBlank() && size != AppTileSize.SMALL }
    .toSortedMap()

/** Encodes app tile-size overrides in the same stable line format as the existing preference. */
internal fun serializeAppTileSizes(
    sizes: Map<String, AppTileSize>,
): String = sizes
    .filter { (id, size) -> id.isNotBlank() && size != AppTileSize.SMALL }
    .toSortedMap()
    .entries
    .joinToString("\n") { (id, size) -> "$id\t${size.name}" }

/** Parses app tile-size rows defensively; malformed or duplicate rows are ignored. */
internal fun parseAppTileSizes(raw: String?): Map<String, AppTileSize> {
    if (raw.isNullOrBlank()) return emptyMap()
    val seen = mutableSetOf<String>()
    return raw.lineSequence().mapNotNull { line ->
        val fields = line.split('\t', limit = 2)
        if (fields.size != 2) return@mapNotNull null
        val id = fields[0]
        val size = runCatching { AppTileSize.valueOf(fields[1]) }.getOrNull()
            ?: return@mapNotNull null
        if (id.isBlank() || !seen.add(id)) return@mapNotNull null
        id to size
    }.toMap()
}
