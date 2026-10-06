package com.fiilda.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Result of matching the stored favorites against the current launcher catalog at startup.
 *
 * A favorite that is missing from the catalog is not proof that the user lost the app: an archived,
 * disabled, or temporarily unavailable package disappears from the query while its home position,
 * size, and folder membership must survive. Only a package that is no longer installed is removed.
 */
internal data class FavoriteReconciliation(
    val favoriteIds: List<String>,
    /** Favorites whose package now exposes exactly one different launcher activity (old to new). */
    val renamedIds: Map<String, String>,
    val removedIds: Set<String>,
)

internal fun reconcileStoredFavorites(
    storedFavorites: List<String>,
    installedAppIds: List<String>,
    isPackageInstalled: (String) -> Boolean,
): FavoriteReconciliation {
    val stored = storedFavorites.filter { it.isNotBlank() }.distinct()
    val storedSet = stored.toSet()
    val installedSet = installedAppIds.toSet()
    val claimed = mutableSetOf<String>()
    val renamed = linkedMapOf<String, String>()
    val removed = mutableSetOf<String>()
    val retained = mutableListOf<String>()
    stored.forEach { id ->
        if (id in installedSet) {
            retained += id
            return@forEach
        }
        val packageName = id.substringBefore('/', missingDelimiterValue = "")
        if (packageName.isBlank()) {
            removed += id
            return@forEach
        }
        // An update that renames the launcher activity keeps the package. Follow it only when the
        // package now has a single unclaimed launcher entry, so the mapping cannot be ambiguous.
        val candidates = installedAppIds.filter { candidate ->
            candidate.substringBefore('/', missingDelimiterValue = "") == packageName &&
                candidate !in storedSet &&
                candidate !in claimed
        }.distinct()
        when {
            candidates.size == 1 -> {
                val replacement = candidates.single()
                claimed += replacement
                renamed[id] = replacement
                retained += replacement
            }
            isPackageInstalled(packageName) -> retained += id
            else -> removed += id
        }
    }
    return FavoriteReconciliation(
        favoriteIds = retained.distinct(),
        renamedIds = renamed,
        removedIds = removed,
    )
}

internal fun List<String>.renameHomeIds(renames: Map<String, String>): List<String> =
    if (renames.isEmpty()) this else map { renames[it] ?: it }.distinct()

internal fun <V> Map<String, V>.renameHomeIdKeys(renames: Map<String, String>): Map<String, V> =
    if (renames.isEmpty()) this else entries.associate { (id, value) -> (renames[id] ?: id) to value }

internal fun HomeLayout.renameHomeIds(renames: Map<String, String>): HomeLayout =
    if (renames.isEmpty()) {
        this
    } else {
        HomeLayout(
            order = order.renameHomeIds(renames),
            narrowPageById = narrowPageById.renameHomeIdKeys(renames),
            narrowOrder = narrowOrder.renameHomeIds(renames),
            pageCount = pageCount,
        )
    }

internal fun HomePages.renameHomeIds(renames: Map<String, String>): HomePages =
    if (renames.isEmpty()) {
        this
    } else {
        HomePages(pages.map { it.renameHomeIds(renames) })
    }

internal fun List<HomeFolder>.renameHomeFolderMembers(renames: Map<String, String>): List<HomeFolder> =
    if (renames.isEmpty()) this else map { it.copy(memberIds = it.memberIds.renameHomeIds(renames)) }

/**
 * True unless the package is confirmed absent. Archived and disabled packages still count as
 * installed, and an unexpected platform failure is not treated as proof of removal.
 */
internal fun isFavoritePackageInstalled(context: Context, packageName: String): Boolean {
    if (packageName.isBlank()) return false
    val packageManager = context.packageManager
    return try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.MATCH_ARCHIVED_PACKAGES or
                            PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                    ),
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(
                        PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                    ),
                )
            else -> packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        true
    }
}
