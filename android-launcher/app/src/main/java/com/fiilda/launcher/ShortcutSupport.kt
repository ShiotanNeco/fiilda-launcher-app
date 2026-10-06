package com.fiilda.launcher

import java.util.Locale

/** Maximum number of public shortcuts that can share the three secondary cells of a large tile. */
internal const val MaxLargeTileShortcuts = 3

private const val ShortcutCellIconSizeDp = 63

/** Shared icon size for the app and shortcut cells in every populated split tile. */
internal fun shortcutCellIconSizeDp(): Int = ShortcutCellIconSizeDp

/** The label size used by a normal home app tile in the current Fold posture. */
internal fun homeAppTileLabelFontSizeSp(posture: Posture): Int =
    if (posture == Posture.COVER) 17 else 15

/**
 * Controls whether a favorite tile may replace its app surface with published launcher
 * shortcuts. The default is deliberately the current shortcut-aware presentation.
 */
internal enum class AppTileContentMode(private val jaLabel: String, private val enLabel: String) {
    SHORTCUTS("ショートカット付き", "With shortcuts"),
    APP_ONLY("アプリのみ", "App only"),
    NOTIFICATIONS("通知ライブ", "Live notifications"),
    ;

    val label: String get() = tr(jaLabel, enLabel)
}

internal fun appTileContentModeFor(
    appId: String,
    modes: Map<String, AppTileContentMode>,
): AppTileContentMode = modes[appId] ?: AppTileContentMode.SHORTCUTS

/**
 * Serializes only the non-default mode. Sorted keys keep preference writes stable across map
 * iteration order and make malformed/legacy records easy to canonicalize on the next load.
 */
internal fun serializeAppTileContentModes(
    modes: Map<String, AppTileContentMode>,
): String = modes
    .filter { (id, mode) ->
        id.isNotBlank() && mode != AppTileContentMode.SHORTCUTS
    }
    .toSortedMap()
    .entries
    .joinToString("\n") { (id, mode) -> "$id\t${mode.name}" }

/** Parses the independent favorite display-mode preference, retaining every non-default mode. */
internal fun parseAppTileContentModes(raw: String?): Map<String, AppTileContentMode> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw
        .split("\n")
        .mapNotNull { line ->
            val fields = line.split("\t", limit = 2)
            if (fields.size != 2) return@mapNotNull null
            val id = fields[0].trim()
            val mode = runCatching { AppTileContentMode.valueOf(fields[1].trim()) }
                .getOrNull()
            if (id.isBlank() || mode == null || mode == AppTileContentMode.SHORTCUTS) {
                null
            } else {
                id to mode
            }
        }
        .toMap()
        .toSortedMap()
}

/** Drops display-mode records for removed/uninstalled favorites and restores default-only rows. */
internal fun pruneAppTileContentModes(
    modes: Map<String, AppTileContentMode>,
    favoriteIds: Set<String>,
): Map<String, AppTileContentMode> = modes
    .filter { (id, mode) ->
        id in favoriteIds && id.isNotBlank() && mode != AppTileContentMode.SHORTCUTS
    }
    .toSortedMap()

/**
 * Platform-independent shortcut metadata used to make launcher ordering deterministic.
 *
 * [order] is the stable position from the platform query and is used only after the publisher's
 * rank. Android reports -1 for an unknown rank on some releases, so unknown values sort after
 * ranked entries. Keeping this value object free of [android.content.pm.ShortcutInfo] also makes
 * the selection policy straightforward to exercise with JVM tests.
 */
internal data class PublicLauncherShortcutCandidate(
    val id: String,
    val shortLabel: String = "",
    val longLabel: String = "",
    val rank: Int = -1,
    val order: Int = 0,
    val enabled: Boolean = true,
)

/**
 * Chooses at most [maxCount] usable public shortcuts for a large app tile.
 *
 * Static, dynamic, pinned, and cached records can overlap in a LauncherApps result. Candidates
 * are therefore grouped by ID first; an enabled, labelled copy wins for that ID, and the copy
 * with the strongest rank/order is retained. The final ID tie-breaker prevents query ordering
 * changes from making an otherwise identical result jump between recompositions.
 */
internal fun selectPublicLauncherShortcuts(
    candidates: List<PublicLauncherShortcutCandidate>,
    maxCount: Int = MaxLargeTileShortcuts,
): List<PublicLauncherShortcutCandidate> {
    if (maxCount <= 0 || candidates.isEmpty()) return emptyList()

    val candidateComparator = compareBy<PublicLauncherShortcutCandidate>(
        { normalizedShortcutRank(it.rank) },
        { normalizedShortcutOrder(it.order) },
        { it.id.lowercase(Locale.ROOT) },
    )

    return candidates
        .asSequence()
        .filter { candidate ->
            candidate.enabled &&
                candidate.id.isNotBlank() &&
                shortcutDisplayLabel(candidate).isNotBlank()
        }
        .groupBy { it.id }
        .values
        .mapNotNull { sameId -> sameId.minWithOrNull(candidateComparator) }
        .sortedWith(candidateComparator)
        .take(maxCount)
}

internal fun shortcutDisplayLabel(candidate: PublicLauncherShortcutCandidate): String =
    candidate.shortLabel.trim().ifBlank { candidate.longLabel.trim() }

private fun normalizedShortcutRank(rank: Int): Int = rank.takeIf { it >= 0 } ?: Int.MAX_VALUE

private fun normalizedShortcutOrder(order: Int): Int = order.takeIf { it >= 0 } ?: Int.MAX_VALUE

/** Smallest size an app name shrinks to before it is shortened with an ellipsis. */
internal const val AppLabelMinFontSizeSp = 10

/** Height of one label line relative to its font size, matching the default line height. */
internal const val AppLabelLineHeightRatio = 1.35f

/**
 * The app icon keeps its preferred size when the tile has room and otherwise shrinks to fit the
 * tile beside its label, so narrow phones (small cells) never clip the label.
 */
internal fun fittedAppIconSizeDp(
    preferredDp: Float,
    availableWidthDp: Float,
    availableHeightDp: Float,
    labelHeightDp: Float,
): Float {
    val byWidth = availableWidthDp * 0.78f
    val byHeight = (availableHeightDp - labelHeightDp) * 0.9f
    return minOf(preferredDp, byWidth, byHeight).coerceAtLeast(24f)
}

