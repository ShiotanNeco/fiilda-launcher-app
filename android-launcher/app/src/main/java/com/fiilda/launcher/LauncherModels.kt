package com.fiilda.launcher

/**
 * Stable launcher models shared by the coordinator, home board, drawer, dialogs, and storage.
 * Keeping these declarations together makes the cross-surface data contract discoverable without
 * moving state ownership out of [FiiLDALauncher].
 */

internal enum class ContextMode { GLANCE, WEATHER, CALENDAR, MEDIA }
internal enum class Posture { COVER, INNER_PORTRAIT, INNER_LANDSCAPE }

/**
 * Classifies the whole activity window, not the content left after the header and bottom bar.
 * Keeping this pure makes the fold thresholds explicit and prevents a tall portrait window from
 * being mistaken for landscape merely because the content column has consumed vertical space.
 */
internal fun launcherPostureForWindow(
    widthDp: Int,
    heightDp: Int,
    coverWidthDp: Int = 600,
): Posture {
    val normalizedWidth = widthDp.coerceAtLeast(0)
    val normalizedHeight = heightDp.coerceAtLeast(0)
    val normalizedCoverWidth = coverWidthDp.coerceAtLeast(0)
    return when {
        normalizedWidth < normalizedCoverWidth -> Posture.COVER
        normalizedWidth > normalizedHeight -> Posture.INNER_LANDSCAPE
        else -> Posture.INNER_PORTRAIT
    }
}

/** A failed canonical migration must remain retryable on the next lifecycle refresh. */
internal fun launcherStateLoadCompletedAfterMigration(
    needsCanonicalMigration: Boolean,
    migrationCommitted: Boolean,
): Boolean = !needsCanonicalMigration || migrationCommitted

internal enum class HomeWidget(val id: String, private val jaLabel: String, private val enLabel: String) {
    CLOCK("widget:clock", "日付と時刻", "Date and time"),
    WEATHER("widget:weather", "天気", "Weather"),
    AGENDA("widget:agenda", "今日の予定", "Today's events"),
    CALENDAR("widget:calendar", "カレンダー", "Calendar"),
    BATTERY("widget:battery", "端末", "Battery"),
    REMINDER("widget:reminder", "リマインダー", "Reminders"),
    MEDIA("widget:media", "メディア", "Media"),
    FORECAST("widget:forecast", "週間天気予報", "Weekly forecast"),
    PHOTO(PhotoWidgetHomeId, "フォトフレーム", "Photo frame"),
    ;

    val label: String get() = tr(jaLabel, enLabel)
}

internal sealed interface HomeItem {
    val id: String

    data class App(val app: LaunchableApp) : HomeItem {
        override val id: String = favoriteId(app)
    }

    data class Widget(
        val widget: HomeWidget,
        override val id: String = widget.id,
    ) : HomeItem {
    }

    data class ExternalWidget(val descriptor: LauncherWidgetDescriptor) : HomeItem {
        override val id: String = descriptor.homeId
    }

    data class PinnedShortcut(val shortcut: ResolvedPinnedShortcut) : HomeItem {
        override val id: String = shortcut.homeId
    }

    data class Folder(val folder: HomeFolder) : HomeItem {
        override val id: String = folder.id
    }
}

/** A compact group of home apps. Members remain favorites but are represented only by the folder. */
internal data class HomeFolder(
    val id: String,
    val name: String = tr("フォルダ", "Folder"),
    val memberIds: List<String> = emptyList(),
    val size: HomeFolderSize = HomeFolderSize.SMALL,
)

internal enum class HomeFolderSize(
    val columnSpan: Int,
    val rowSpan: Int,
    val label: String,
) {
    SMALL(1, 1, "1×1"),
    LARGE(2, 2, "2×2"),
}

internal const val HomeFolderIdPrefix = "folder:"

internal data class GridItemSize(
    val columnSpan: Int,
    val rowSpan: Int,
)

internal fun buildHomeItems(
    order: List<String>,
    apps: List<LaunchableApp>,
    externalWidgets: List<LauncherWidgetDescriptor> = emptyList(),
    pinnedShortcuts: List<ResolvedPinnedShortcut> = emptyList(),
    folders: List<HomeFolder> = emptyList(),
): List<HomeItem> {
    val appsById = apps.associateBy(::favoriteId)
    val externalById = externalWidgets.associateBy { it.homeId }
    val pinnedById = pinnedShortcuts.associateBy { it.homeId }
    val foldersById = folders.associateBy { it.id }
    return order.mapNotNull { id ->
        HomeWidget.values().firstOrNull { it.id == id }?.let { HomeItem.Widget(it, id) }
            ?: id.takeIf(::isPhotoWidgetHomeId)?.let { HomeItem.Widget(HomeWidget.PHOTO, it) }
            ?: externalById[id]?.let { HomeItem.ExternalWidget(it) }
            ?: pinnedById[id]?.let { HomeItem.PinnedShortcut(it) }
            ?: foldersById[id]?.let { HomeItem.Folder(it) }
            ?: appsById[id]?.let { HomeItem.App(it) }
    }
}

// A fresh install starts with the widgets that show live data. Reminders (no data source yet) and the
// weekly forecast (overlaps Weather) can be added from the widget picker. PHOTO is opt-in: each
// image or video selection adds one frame, so a fresh install never shows an empty frame.
internal val DefaultHomeOrder = listOf(
    HomeWidget.CLOCK,
    HomeWidget.WEATHER,
    HomeWidget.AGENDA,
    HomeWidget.CALENDAR,
    HomeWidget.BATTERY,
    HomeWidget.MEDIA,
).map { it.id }

internal typealias WidgetResultListener = (
    appWidgetId: Int,
    targetHomePage: Int,
    acknowledgePersistence: () -> Unit,
) -> Unit
