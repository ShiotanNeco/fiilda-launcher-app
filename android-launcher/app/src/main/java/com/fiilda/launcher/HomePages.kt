package com.fiilda.launcher

import java.nio.charset.StandardCharsets
import java.util.Base64

internal const val DefaultHomePageCount = 2

/** The ordered pages; an item belongs to at most one page. */
internal data class HomePages(
    val pages: List<List<String>> = List(DefaultHomePageCount) { emptyList() },
) {
    val count: Int get() = pages.size.coerceAtLeast(1)
    val allIds: Set<String> get() = pages.asSequence().flatten().toSet()

    operator fun get(page: Int): List<String> = pages.getOrNull(page.coerceIn(0, count - 1)).orEmpty()

    fun withPage(page: Int, order: List<String>): HomePages {
        val targetPage = page.coerceIn(0, count - 1)
        val target = order.filter { it.isNotBlank() }.distinct()
        return canonicalizeHomePages(HomePages(List(count) { index ->
            if (index == targetPage) target else this[index].filterNot { it in target }
        }))
    }

    companion object {
        fun empty(): HomePages = HomePages()
    }
}

internal fun addHomeItemToPage(pages: HomePages, page: Int, id: String): HomePages {
    if (id.isBlank() || id in pages.allIds) return pages
    val targetPage = page.coerceIn(0, pages.count - 1)
    return pages.withPage(targetPage, pages[targetPage] + id)
}

internal data class FavoriteHomePageAddition(
    val favoriteIds: List<String>,
    val homePages: HomePages,
)

internal fun computeFavoriteHomePageAddition(
    favoriteIds: List<String>,
    homePages: HomePages,
    id: String,
    targetHomePage: Int,
): FavoriteHomePageAddition? {
    if (id.isBlank() || id in favoriteIds) return null
    return FavoriteHomePageAddition(
        favoriteIds = (favoriteIds + id).distinct(),
        homePages = addHomeItemToPage(removeHomeItemFromPages(homePages, id), targetHomePage, id),
    )
}

internal fun removeHomeItemFromPages(pages: HomePages, id: String): HomePages =
    canonicalizeHomePages(HomePages(pages.pages.map { page -> page.filterNot { it == id } }))

internal fun updateHomePage(
    pages: HomePages,
    page: Int,
    transform: (List<String>) -> List<String>,
): HomePages = pages.withPage(page, transform(pages[page]))

internal fun homePageContaining(pages: HomePages, id: String): Int? =
    pages.pages.indexOfFirst { id in it }.takeIf { it >= 0 }

internal fun canonicalizeHomePages(pages: HomePages): HomePages {
    val seen = mutableSetOf<String>()
    return HomePages(List(pages.count) { index ->
        pages[index].filter { it.isNotBlank() && seen.add(it) }
    })
}

/** Retains stored assignments and appends newly discovered items to the first page. */
internal fun normalizeHomePages(
    storedPages: HomePages?,
    legacyOrder: List<String>?,
    favoriteIds: List<String>,
    externalWidgetIds: List<String> = emptyList(),
    folderIds: List<String> = emptyList(),
    folderMemberIds: Set<String> = emptySet(),
): HomePages {
    if (storedPages == null) {
        return HomePages(listOf(normalizeHomeOrder(
            stored = legacyOrder,
            favoriteIds = favoriteIds,
            externalWidgetIds = externalWidgetIds,
            folderIds = folderIds,
            hiddenIds = folderMemberIds,
        ), emptyList()))
    }
    val allowed = (HomeWidget.values().map { it.id } + favoriteIds + externalWidgetIds + folderIds).toSet()
    val retained = canonicalizeHomePages(HomePages(storedPages.pages.map { page ->
        page.filter { (it in allowed || isPhotoWidgetHomeId(it)) && it !in folderMemberIds }
    }))
    val additions = (favoriteIds + externalWidgetIds + folderIds).distinct()
        .filterNot { it in retained.allIds || it in folderMemberIds }
    return retained.withPage(0, retained[0] + additions)
}

internal const val HomePagesStoragePrefix = "v3:"

/**
 * Canonical layout for all postures.
 *
 * [order] is the global order used by the wide Start canvas and [narrowOrder] is the global order
 * used to derive the narrow pages. [narrowPageById] is a presentation-only ownership map used
 * by those pages. The two order lists always contain the same IDs after [canonicalizeHomeLayout],
 * but their relative order is intentionally independent.
 *
 * The default for [narrowOrder] keeps source compatibility with the v3 two-argument constructor:
 * code that only knows a wide order still starts with one shared order and is upgraded by the
 * canonicalizer/migration path.
 */
internal data class HomeLayout(
    val order: List<String> = emptyList(),
    val narrowPageById: Map<String, Int> = emptyMap(),
    val narrowOrder: List<String> = order,
    val pageCount: Int = DefaultHomePageCount,
) {
    val allIds: Set<String>
        get() = order.toSet()

    fun pageOf(id: String): Int = narrowPageById[id]?.coerceIn(0, pageCount.coerceAtLeast(1) - 1) ?: 0

    fun orderForPage(page: Int): List<String> {
        val normalized = page.coerceIn(0, pageCount.coerceAtLeast(1) - 1)
        return narrowOrder.filter { pageOf(it) == normalized }
    }

    fun toHomePages(): HomePages = HomePages(List(pageCount.coerceAtLeast(1)) { orderForPage(it) })

    companion object {
        fun empty(): HomeLayout = HomeLayout()
    }
}

/** Current durable layout codec. Earlier layouts remain readable through [parseHomeLayout]. */
internal const val HomeLayoutStoragePrefix = "v5:"
internal const val LegacyHomeLayoutStoragePrefix = "v3:"

/** Creates a canonical shared layout from the narrow-page projection. */
internal fun homeLayoutFromPages(pages: HomePages): HomeLayout {
    val canonical = canonicalizeHomePages(pages)
    val order = canonical.pages.flatten().distinct()
    val ownership = buildMap {
        canonical.pages.forEachIndexed { page, ids -> ids.forEach { put(it, page) } }
    }
    return HomeLayout(
        order = order,
        narrowPageById = ownership,
        narrowOrder = order,
        pageCount = canonical.count,
    )
}

/**
 * Drops malformed entries and guarantees both orders contain the same unique IDs, with every
 * ordered ID having an explicit narrow owner. IDs missing from the narrow order are appended in
 * wide-order sequence so a damaged/older payload cannot make a tile disappear from one posture.
 */
internal fun canonicalizeHomeLayout(layout: HomeLayout): HomeLayout {
    val order = layout.order.filter { it.isNotBlank() }.distinct()
    val orderIds = order.toSet()
    val narrowPrefix = layout.narrowOrder
        .filter { it.isNotBlank() && it in orderIds }
        .distinct()
    val narrowOrder = narrowPrefix + order.filterNot { it in narrowPrefix }
    val ownership = order.associateWith { id ->
        layout.narrowPageById[id]?.coerceIn(0, layout.pageCount.coerceAtLeast(1) - 1) ?: 0
    }
    return HomeLayout(
        order = order,
        narrowPageById = ownership,
        narrowOrder = narrowOrder,
        pageCount = layout.pageCount.coerceAtLeast(1),
    )
}

/**
 * Updates only the wide order. The narrow ownership map is copied verbatim for all retained IDs.
 * Unknown IDs in a caller-provided order are ignored so a stale app/package callback cannot add a
 * tile that is not part of the durable layout. Omitted known IDs (for example a favorite whose app
 * is archived and therefore not rendered) keep their current slots; this is a reorder helper, not
 * a removal operation. Use [removeHomeItemFromLayout] for deletion.
 */
internal fun updateWideHomeOrder(layout: HomeLayout, order: List<String>): HomeLayout {
    val canonical = canonicalizeHomeLayout(layout)
    return canonicalizeHomeLayout(
        canonical.copy(order = reorderPreservingOmittedSlots(canonical.order, order)),
    )
}

/**
 * Reorders the IDs of [current] that appear in [requested] into the slots those IDs occupied,
 * leaving every omitted ID at its original index. Unknown requested IDs are ignored.
 */
internal fun reorderPreservingOmittedSlots(
    current: List<String>,
    requested: List<String>,
): List<String> {
    val currentIds = current.toSet()
    val reordered = requested.filter { it in currentIds }.distinct()
    val reorderedIds = reordered.toSet()
    var next = 0
    return current.map { id -> if (id in reorderedIds) reordered[next++] else id }
}

/**
 * Replaces both presentation orders with one shared order. This is used when the user reorders
 * while the setting is off; the independent wide order is intentionally overwritten only by that
 * explicit reorder, not merely by toggling the setting.
 */
internal fun synchronizeHomeLayoutOrders(
    layout: HomeLayout,
    order: List<String> = canonicalizeHomeLayout(layout).narrowOrder,
): HomeLayout {
    val canonical = canonicalizeHomeLayout(layout)
    val requested = order.filter { it in canonical.allIds }.distinct()
    val complete = requested + canonical.order.filterNot { it in requested }
    return canonicalizeHomeLayout(
        canonical.copy(order = complete, narrowOrder = complete),
    )
}

/** Alias phrased for callers that treat the v4 records as two synchronized order fields. */
internal fun synchronizeHomeOrders(
    layout: HomeLayout,
    order: List<String> = canonicalizeHomeLayout(layout).narrowOrder,
): HomeLayout = synchronizeHomeLayoutOrders(layout, order)

/** Selects the order rendered by a wide Start canvas for the current setting. */
internal fun wideOrderForHomePresentation(
    layout: HomeLayout,
    separateWideOrder: Boolean,
): List<String> {
    val canonical = canonicalizeHomeLayout(layout)
    return if (separateWideOrder) canonical.order else canonical.narrowOrder
}

/**
 * Applies a committed wide reorder for the currently selected presentation mode. Keeping this
 * branch pure and at the model boundary prevents a delayed gesture callback from accidentally
 * applying the shared-mode rule that was captured when the drag started.
 */
internal fun homeLayoutAfterWideReorder(
    layout: HomeLayout,
    order: List<String>,
    separateWideOrder: Boolean,
): HomeLayout {
    val wideLayout = updateWideHomeOrder(layout, order)
    return if (separateWideOrder) {
        wideLayout
    } else {
        synchronizeHomeLayoutOrders(wideLayout, wideLayout.order)
    }
}

/**
 * Applies a committed narrow-page reorder for the currently selected presentation mode. Narrow
 * mode stays independent when enabled; shared mode copies the resulting narrow order to wide too.
 */
internal fun homeLayoutAfterNarrowReorder(
    layout: HomeLayout,
    page: Int,
    order: List<String>,
    separateWideOrder: Boolean,
): HomeLayout {
    val narrowLayout = updateNarrowHomePageOrder(layout, page, order)
    return if (separateWideOrder) {
        narrowLayout
    } else {
        synchronizeHomeLayoutOrders(narrowLayout, narrowLayout.narrowOrder)
    }
}

/** Removes an item from the canonical order and its narrow-page ownership. */
internal fun removeHomeItemFromLayout(layout: HomeLayout, id: String): HomeLayout {
    if (id.isBlank()) return canonicalizeHomeLayout(layout)
    val canonical = canonicalizeHomeLayout(layout)
    return canonicalizeHomeLayout(
        canonical.copy(
            order = canonical.order.filterNot { it == id },
            narrowOrder = canonical.narrowOrder.filterNot { it == id },
            narrowPageById = canonical.narrowPageById - id,
        ),
    )
}

/**
 * Reorders one narrow page by replacing only that page's subsequence in the narrow order. The wide
 * order and sibling-page subsequence remain untouched.
 */
internal fun updateNarrowHomePageOrder(
    layout: HomeLayout,
    page: Int,
    order: List<String>,
): HomeLayout {
    val canonical = canonicalizeHomeLayout(layout)
    val targetPage = page.coerceIn(0, canonical.pageCount - 1)
    val currentPageIds = canonical.orderForPage(targetPage)
    val replacement = reorderPreservingOmittedSlots(currentPageIds, order)
    var replacementIndex = 0
    val updatedNarrowOrder = canonical.narrowOrder.map { id ->
        if (canonical.pageOf(id) == targetPage) replacement[replacementIndex++] else id
    }
    return canonical.copy(narrowOrder = updatedNarrowOrder)
}

/**
 * Assigns an item to a narrow page while preserving its position in the wide order. This is used
 * by add flows and is deliberately separate from reorder so a page selector cannot reshuffle the
 * Start canvas.
 */
internal fun assignHomeItemToPage(layout: HomeLayout, id: String, page: Int): HomeLayout {
    if (id.isBlank() || id !in layout.allIds) return canonicalizeHomeLayout(layout)
    val targetPage = page.coerceIn(0, layout.pageCount.coerceAtLeast(1) - 1)
    return canonicalizeHomeLayout(layout.copy(
        narrowPageById = layout.narrowPageById + (id to targetPage),
    ))
}

/**
 * Computes a favorite app's page transfer without changing either persisted order.
 *
 * The favorite and layout membership checks deliberately happen against one canonical snapshot.
 * A stale action sheet therefore gets a null result instead of being able to recreate a removed
 * app or assign a non-favorite item. A non-null result may still be unchanged when the selected
 * page is already the current owner; callers can close that no-op without writing storage.
 */
internal data class HomeItemPageTransfer(
    val layout: HomeLayout,
    val sourcePage: Int,
    val targetPage: Int,
) {
    val changed: Boolean
        get() = sourcePage != targetPage
}

/** Returns true only for a currently installed favorite that is a direct home item. */
internal fun isHomeItemPageTransferEligible(
    layout: HomeLayout,
    favoriteIds: Collection<String>,
    installedAppIds: Collection<String>,
    id: String,
): Boolean {
    val canonical = canonicalizeHomeLayout(layout)
    return id.isNotBlank() &&
        id in favoriteIds &&
        id in installedAppIds &&
        id in canonical.allIds
}

internal fun computeHomeItemPageTransfer(
    layout: HomeLayout,
    favoriteIds: Collection<String>,
    id: String,
    targetPage: Int,
): HomeItemPageTransfer? {
    val canonical = canonicalizeHomeLayout(layout)
    if (id.isBlank() || id !in favoriteIds || id !in canonical.allIds) return null
    val sourcePage = canonical.pageOf(id)
    val normalizedTargetPage = targetPage.coerceIn(0, canonical.pageCount - 1)
    return HomeItemPageTransfer(
        layout = if (sourcePage == normalizedTargetPage) {
            canonical
        } else {
            assignHomeItemToPage(canonical, id, normalizedTargetPage)
        },
        sourcePage = sourcePage,
        targetPage = normalizedTargetPage,
    )
}

/** Adds a new item at the end of the wide order and assigns it to the requested narrow page. */
internal fun addHomeItemToLayout(layout: HomeLayout, id: String, page: Int): HomeLayout {
    if (id.isBlank()) return canonicalizeHomeLayout(layout)
    val canonical = canonicalizeHomeLayout(layout)
    val withId = if (id in canonical.allIds) {
        canonical
    } else {
        canonical.copy(
            order = canonical.order + id,
            narrowOrder = canonical.narrowOrder + id,
        )
    }
    return assignHomeItemToPage(withId, id, page)
}

/** The canonical result of a successful PHOTO selection, including a collision-safe home ID. */
internal data class PhotoHomeLayoutSelection(
    val widgetId: String,
    val layout: HomeLayout,
)

/**
 * Adds a PHOTO item to a canonical layout without using a possibly empty in-memory page snapshot.
 *
 * The activity can receive a restored OpenDocument result before its asynchronous home load has
 * completed. In that window the durable layout is authoritative. [existingIds] also covers URI
 * records that have not yet appeared in a layout mirror, so a restored add cannot overwrite an
 * existing photo by reusing its generated ID.
 */
internal fun homeLayoutAfterPhotoSelection(
    layout: HomeLayout,
    widgetId: String,
    targetHomePage: Int,
    addToHome: Boolean,
    existingIds: Set<String> = emptySet(),
): PhotoHomeLayoutSelection {
    val canonical = canonicalizeHomeLayout(layout)
    if (!addToHome) {
        return PhotoHomeLayoutSelection(widgetId = widgetId, layout = canonical)
    }

    val usedIds = canonical.allIds + existingIds
    val resolvedWidgetId = widgetId
        .takeIf { it.isNotBlank() && it !in usedIds }
        ?: newPhotoWidgetHomeId(usedIds)
    return PhotoHomeLayoutSelection(
        widgetId = resolvedWidgetId,
        layout = addHomeItemToLayout(canonical, resolvedWidgetId, targetHomePage),
    )
}

/** One-time migration entry point: v4/v3 wins, then v2, then the legacy single-page order. */
internal fun normalizeHomeLayout(
    storedLayout: HomeLayout?,
    storedPages: HomePages?,
    legacyOrder: List<String>?,
    favoriteIds: List<String>,
    externalWidgetIds: List<String> = emptyList(),
    homeFolders: List<HomeFolder> = emptyList(),
): HomeLayout {
    val folderIds = homeFolders.map { it.id }
    val folderMemberIds = homeFolders.asSequence().flatMap { it.memberIds.asSequence() }.toSet()
    val normalizedPages = normalizeHomePages(
        storedPages = storedPages,
        legacyOrder = legacyOrder,
        favoriteIds = favoriteIds,
        externalWidgetIds = externalWidgetIds,
        folderIds = folderIds,
        folderMemberIds = folderMemberIds,
    )
    val allowed = (HomeWidget.values().map { it.id } + favoriteIds + externalWidgetIds + folderIds)
        .toSet()
    val fallback = homeLayoutFromPages(normalizedPages)
    if (storedLayout == null && storedPages == null && legacyOrder == null) {
        // A fresh install starts with a single page so no empty second page looks broken; more
        // pages can be added from the Home menu.
        return canonicalizeHomeLayout(
            fallback.copy(pageCount = 1, narrowPageById = fallback.narrowPageById.mapValues { 0 }),
        )
    }
    val candidate = storedLayout ?: return fallback
    val canonical = canonicalizeHomeLayout(candidate)
    val retained = canonical.order.filter {
        (it in allowed || isPhotoWidgetHomeId(it)) && it !in folderMemberIds
    }
    val retainedIds = retained.toSet()
    val retainedNarrow = canonical.narrowOrder.filter { it in retainedIds }
    // Once v3/v4 exists, the v2/legacy projection may contain items the user deliberately removed
    // after the canonical snapshot was written. Only currently discovered dynamic IDs may be
    // recovered; replaying all normalized v2 pages would resurrect deleted built-ins (for example
    // WEATHER).
    val newlyDiscoveredIds = (favoriteIds + externalWidgetIds + folderIds).distinct()
    val additions = newlyDiscoveredIds.filterNot { it in retained || it in folderMemberIds }
    val order = retained + additions
    val narrowOrder = retainedNarrow + additions
    val fallbackOwnership = homeLayoutFromPages(normalizedPages).narrowPageById
    // A valid v3/v4 assignment is authoritative. Only IDs introduced during recovery may borrow the
    // v2/legacy projection's page, otherwise a stale mirror would undo a deliberate page choice.
    val ownership = (canonical.narrowPageById + fallbackOwnership.filterKeys {
        it !in canonical.narrowPageById
    }).filterKeys { it in order }
    return canonicalizeHomeLayout(
        HomeLayout(
            order = order,
            narrowPageById = ownership,
            narrowOrder = narrowOrder,
            pageCount = canonical.pageCount,
        ),
    )
}

private const val HomeLayoutAssignmentSeparator = "~"

internal fun addHomePage(layout: HomeLayout): HomeLayout =
    canonicalizeHomeLayout(layout).let { it.copy(pageCount = it.pageCount + 1) }

/** A deleted page's items move to its previous neighbor (or the next for the first page). */
internal fun removeHomePage(layout: HomeLayout, page: Int): HomeLayout? {
    val canonical = canonicalizeHomeLayout(layout)
    if (canonical.pageCount <= 1 || page !in 0 until canonical.pageCount) return null
    val destination = (page - 1).coerceAtLeast(0)
    return canonical.copy(
        pageCount = canonical.pageCount - 1,
        narrowPageById = canonical.narrowPageById.mapValues { (_, owner) ->
            when {
                owner == page -> destination
                owner > page -> owner - 1
                else -> owner
            }
        },
    )
}

internal fun homePageAfterRemoval(selectedPage: Int, removedPage: Int, remainingCount: Int): Int =
    (if (selectedPage >= removedPage) selectedPage - 1 else selectedPage)
        .coerceIn(0, remainingCount.coerceAtLeast(1) - 1)

/** Encodes the page count and independent orders; Base64 keeps provider/class IDs punctuation-safe. */
internal fun serializeHomeLayout(layout: HomeLayout): String {
    val canonical = canonicalizeHomeLayout(layout)
    val encodedOrder = canonical.order.joinToString(HomeItemSeparator, transform = ::encodeHomeId)
    val encodedNarrowOrder = canonical.narrowOrder.joinToString(
        HomeItemSeparator,
        transform = ::encodeHomeId,
    )
    val encodedOwners = canonical.order.joinToString(HomeItemSeparator) { id ->
        "${encodeHomeId(id)}$HomeLayoutAssignmentSeparator${canonical.pageOf(id)}"
    }
    return "$HomeLayoutStoragePrefix${canonical.pageCount}$HomePageSeparator$encodedOrder$HomePageSeparator$encodedNarrowOrder$HomePageSeparator$encodedOwners"
}

/**
 * Compares the canonical v4 layout with both compatibility mirrors before a migration write.
 * Keeping this comparison pure makes it impossible for startup code to update only one mirror
 * after a v3 reorder.
 */
internal fun homeLayoutMirrorsNeedMigration(
    storedLayoutRaw: String?,
    storedPagesRaw: String?,
    storedLegacyOrder: List<String>?,
    canonicalLayout: HomeLayout,
): Boolean {
    val canonical = canonicalizeHomeLayout(canonicalLayout)
    val pages = canonical.toHomePages()
    return storedLayoutRaw != serializeHomeLayout(canonical) ||
        storedPagesRaw != serializeHomePages(pages) ||
        storedLegacyOrder != pages[0]
}

/**
 * Parses v5 page counts and migrates previous v4/v3 layouts. A v3 payload has no independent
 * narrow order, so its wide order is copied into [HomeLayout.narrowOrder] during migration.
 */
internal fun parseHomeLayout(raw: String?): HomeLayout? {
    if (raw == null) return null
    return when {
        raw.startsWith(HomeLayoutStoragePrefix) -> {
            val payload = raw.removePrefix(HomeLayoutStoragePrefix)
            val count = payload.substringBefore(HomePageSeparator).toIntOrNull() ?: return null
            if (count < 1 || !payload.contains(HomePageSeparator)) return null
            parseHomeLayoutV4Payload(payload.substringAfter(HomePageSeparator), count)
        }
        raw.startsWith("v4:") -> parseHomeLayoutV4Payload(raw.removePrefix("v4:"))
        raw.startsWith(LegacyHomeLayoutStoragePrefix) -> parseHomeLayoutV3Payload(
            raw.removePrefix(LegacyHomeLayoutStoragePrefix),
        )
        else -> null
    }
}

private fun parseHomeLayoutV4Payload(payload: String, pageCount: Int = DefaultHomePageCount): HomeLayout? {
    val firstSeparator = payload.indexOf(HomePageSeparator)
    val secondSeparator = if (firstSeparator < 0) -1 else payload.indexOf(
        HomePageSeparator,
        firstSeparator + HomePageSeparator.length,
    )
    if (firstSeparator < 0 || secondSeparator < 0 ||
        payload.indexOf(HomePageSeparator, secondSeparator + HomePageSeparator.length) >= 0
    ) {
        return null
    }
    val order = decodeLayoutOrderField(payload.substring(0, firstSeparator)) ?: return null
    val narrowOrder = decodeLayoutOrderField(
        payload.substring(firstSeparator + HomePageSeparator.length, secondSeparator),
    ) ?: return null
    val owners = decodeHomeLayoutOwners(payload.substring(secondSeparator + HomePageSeparator.length), pageCount)
        ?: return null
    if (order.toSet().size != order.size ||
        narrowOrder.toSet().size != narrowOrder.size ||
        order.toSet() != narrowOrder.toSet() ||
        owners.keys != order.toSet() ||
        owners.size != order.size
    ) {
        return null
    }
    return canonicalizeHomeLayout(
        HomeLayout(
            order = order,
            narrowPageById = owners,
            narrowOrder = narrowOrder,
            pageCount = pageCount,
        ),
    )
}

private fun parseHomeLayoutV3Payload(payload: String): HomeLayout? {
    val separatorIndex = payload.indexOf(HomePageSeparator)
    if (separatorIndex < 0 || payload.indexOf(HomePageSeparator, separatorIndex + 1) >= 0) return null
    val orderField = payload.substring(0, separatorIndex)
    val ownerField = payload.substring(separatorIndex + 1)
    val order = decodeLayoutOrderField(orderField) ?: return null
    val owners = decodeHomeLayoutOwners(ownerField) ?: return null
    if (order.toSet().size != order.size ||
        owners.keys.any { it !in order } ||
        owners.size != order.size
    ) return null
    return canonicalizeHomeLayout(
        HomeLayout(
            order = order,
            narrowPageById = owners,
            narrowOrder = order,
        ),
    )
}

private fun decodeLayoutOrderField(field: String): List<String>? = if (field.isEmpty()) {
    emptyList()
} else {
    decodeLayoutTokens(field)
}

private fun decodeHomeLayoutOwners(field: String, pageCount: Int = DefaultHomePageCount): Map<String, Int>? {
    if (field.isEmpty()) return emptyMap()
    if (field.startsWith(HomeItemSeparator) || field.endsWith(HomeItemSeparator) ||
        field.contains(HomeItemSeparator + HomeItemSeparator)
    ) return null
    val owners = linkedMapOf<String, Int>()
    field.split(HomeItemSeparator).forEach { token ->
        val split = token.split(HomeLayoutAssignmentSeparator)
        if (split.size != 2) return null
        val id = decodeHomeId(split[0]) ?: return null
        val page = split[1].toIntOrNull() ?: return null
        if (page !in 0 until pageCount || owners.put(id, page) != null) return null
    }
    return owners
}

private fun decodeLayoutTokens(field: String): List<String>? {
    if (field.startsWith(HomeItemSeparator) || field.endsWith(HomeItemSeparator) ||
        field.contains(HomeItemSeparator + HomeItemSeparator)
    ) return null
    return field.split(HomeItemSeparator).map { decodeHomeId(it) ?: return null }
}

private const val HomePageSeparator = ";"
private const val HomeItemSeparator = ","

/**
 * Encodes each ID independently so a future provider/class name containing punctuation cannot
 * corrupt the neighboring page. The version prefix lets us distinguish malformed v2 storage
 * from the legacy newline-delimited key and fall back safely.
 */
internal fun serializeHomePages(pages: HomePages): String = HomePagesStoragePrefix + canonicalizeHomePages(pages).pages
    .map { page -> page.filter { it.isNotBlank() }.distinct().joinToString(HomeItemSeparator, transform = ::encodeHomeId) }
    .joinToString(HomePageSeparator)

/**
 * Returns null for missing/version-mismatched/truncated storage or any malformed token; an empty
 * v2 board is valid. Parsing is all-or-nothing so a damaged row cannot silently delete one item
 * while preserving its neighbors.
 */
internal fun parseHomePages(raw: String?): HomePages? {
    val legacy = raw?.startsWith("v2:") == true
    val payload = when {
        legacy -> raw!!.removePrefix("v2:")
        raw?.startsWith(HomePagesStoragePrefix) == true -> raw.removePrefix(HomePagesStoragePrefix)
        else -> return null
    }
    val fields = payload.split(HomePageSeparator)
    if (legacy && fields.size != DefaultHomePageCount) return null
    return HomePages(fields.map { decodeHomePageField(it) ?: return null })
}

private fun decodeHomePageField(field: String): List<String>? {
    if (field.isEmpty()) return emptyList()
    if (field.startsWith(HomeItemSeparator) ||
        field.endsWith(HomeItemSeparator) ||
        field.contains(HomeItemSeparator + HomeItemSeparator)
    ) {
        return null
    }
    val tokens = field.split(HomeItemSeparator)
    if (tokens.any { it.isEmpty() }) return null
    return tokens.map { token -> decodeHomeId(token) ?: return null }
}

private fun encodeHomeId(id: String): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString(id.toByteArray(StandardCharsets.UTF_8))

private fun decodeHomeId(encoded: String): String? = runCatching {
    val decoded = Base64.getUrlDecoder().decode(encoded)
    val value = decoded.toString(StandardCharsets.UTF_8)
    // Serializer output is unpadded URL-safe Base64. Re-encoding rejects otherwise decodable but
    // malformed tokens (padding, alternate spellings, or invalid UTF-8 replacement bytes).
    value.takeIf {
        it.isNotBlank() &&
            '\n' !in it &&
            '\r' !in it &&
            encodeHomeId(it) == encoded
    }
}.getOrNull()
