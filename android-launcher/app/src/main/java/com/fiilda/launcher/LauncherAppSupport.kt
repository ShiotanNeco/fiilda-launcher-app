package com.fiilda.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal enum class AppTileSize(
    val columnSpan: Int,
    val rowSpan: Int,
    val label: String,
) {
    SMALL(1, 1, "1×1"),
    // Labels use numeric row×column order; the action sheet supplies a footprint pictogram.
    WIDE(2, 1, "1×2"),
    TALL(1, 2, "2×1"),
    LARGE(2, 2, "2×2"),
    TALL_3X1(1, 3, "3×1"),
    TALL_3X2(2, 3, "3×2");

    /** Readable aliases for callers that describe these footprints as vertical sizes. */
    companion object {
        val VERTICAL_3X1: AppTileSize
            get() = TALL_3X1
        val VERTICAL_3X2: AppTileSize
            get() = TALL_3X2
    }
}

internal fun AppTileSize.supportsShortcuts(): Boolean = when (this) {
    AppTileSize.SMALL -> false
    AppTileSize.WIDE,
    AppTileSize.TALL,
    AppTileSize.LARGE,
    AppTileSize.TALL_3X1,
    AppTileSize.TALL_3X2 -> true
}

internal data class LaunchableApp(
    val packageName: String,
    val className: String,
    val label: String,
    val icon: Drawable,
    /** Computed once while querying the launcher catalog; Compose never re-rasterizes the icon. */
    val tileColorArgb: Int,
    val tileContentColorArgb: Int,
    /** Optional platform dynamic icon definition resolved while loading the app catalog. */
    val dynamicIcon: DynamicAppIconSpec? = null,
    /** Null denotes the launcher's own profile; other profiles have a stable storage identity. */
    val profile: LauncherAppProfile? = null,
)

internal data class LauncherAppProfile(val user: UserHandle, val serialNumber: Long)

internal fun launcherAppId(packageName: String, className: String, profileSerial: Long? = null): String =
    if (profileSerial == null) "$packageName/$className" else "$packageName@$profileSerial/$className"

internal fun LaunchableApp.launchUser(): UserHandle = profile?.user ?: Process.myUserHandle()

/**
 * A shortcut snapshot ready for rendering. The platform query and icon loading happen on the
 * producer dispatcher; Compose only receives this immutable per-refresh projection.
 */
internal data class ResolvedLauncherShortcut(
    val id: String,
    val label: String,
    val icon: Drawable?,
)

/** Package identity used to reuse icon colors across launcher catalog refreshes. */
internal data class LaunchableAppIconCacheKey(
    val componentKey: String,
    val packageVersionCode: Long,
    val packageLastUpdateTime: Long,
)

private data class LaunchableAppPackageVersion(
    val versionCode: Long,
    val lastUpdateTime: Long,
)

/** A query identity combines ordering (for prune races) with package invalidation state. */
internal data class LaunchableAppIconCacheQuery(
    val querySequence: Long,
    val invalidationGeneration: Long,
)

internal fun launchableAppIconCacheKey(
    packageName: String,
    className: String,
    packageVersionCode: Long,
    packageLastUpdateTime: Long,
): LaunchableAppIconCacheKey = LaunchableAppIconCacheKey(
    componentKey = "$packageName/$className",
    packageVersionCode = packageVersionCode,
    packageLastUpdateTime = packageLastUpdateTime,
)

internal fun launchableAppIconCacheKeyBelongsToPackage(
    key: LaunchableAppIconCacheKey,
    packageName: String,
): Boolean = key.componentKey.substringBefore('/') == packageName

internal data class CachedLaunchableAppIconColors(
    val tileColorArgb: Int,
    val tileContentColorArgb: Int,
)

/**
 * Generic versioned cache used by the launcher and by pure unit tests. Drawable rasterization is
 * deliberately supplied as a lambda and runs outside the synchronized sections. A result may be
 * installed only when both its query sequence and invalidation generation are still current.
 */
internal class LaunchableAppIconColorCache<Value> {
    private val entries = LinkedHashMap<LaunchableAppIconCacheKey, Value>()
    private var nextQuerySequence = 0L
    private var latestQuerySequence = 0L
    private var invalidationGeneration = 0L

    fun beginQuery(): LaunchableAppIconCacheQuery = synchronized(this) {
        nextQuerySequence += 1L
        latestQuerySequence = nextQuerySequence
        LaunchableAppIconCacheQuery(
            querySequence = latestQuerySequence,
            invalidationGeneration = invalidationGeneration,
        )
    }

    fun getOrCompute(
        key: LaunchableAppIconCacheKey,
        query: LaunchableAppIconCacheQuery,
        compute: () -> Value,
    ): LaunchableAppIconCacheLookup<Value> {
        synchronized(this) {
            if (!isCurrentLocked(query)) {
                return LaunchableAppIconCacheLookup(value = null, isCurrent = false)
            }
            entries[key]?.let { cached ->
                return LaunchableAppIconCacheLookup(value = cached, isCurrent = true)
            }
        }

        // The expensive work intentionally happens without holding the cache monitor. A package
        // callback can therefore invalidate promptly even if a custom Drawable blocks in draw().
        val computed = compute()
        return synchronized(this) {
            if (!isCurrentLocked(query)) {
                LaunchableAppIconCacheLookup(value = computed, isCurrent = false)
            } else {
                val value = entries[key] ?: computed.also { entries[key] = it }
                LaunchableAppIconCacheLookup(value = value, isCurrent = true)
            }
        }
    }

    fun invalidatePackage(packageName: String) {
        synchronized(this) {
            invalidationGeneration += 1L
            val iterator = entries.keys.iterator()
            while (iterator.hasNext()) {
                if (launchableAppIconCacheKeyBelongsToPackage(iterator.next(), packageName)) {
                    iterator.remove()
                }
            }
        }
    }

    fun retainKeys(
        activeKeys: Set<LaunchableAppIconCacheKey>,
        query: LaunchableAppIconCacheQuery,
    ): Boolean = synchronized(this) {
        if (!isCurrentLocked(query)) return@synchronized false
        val iterator = entries.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in activeKeys) iterator.remove()
        }
        true
    }

    fun getIfPresent(key: LaunchableAppIconCacheKey): Value? = synchronized(this) {
        entries[key]
    }

    fun isCurrent(query: LaunchableAppIconCacheQuery): Boolean = synchronized(this) {
        isCurrentLocked(query)
    }

    private fun isCurrentLocked(query: LaunchableAppIconCacheQuery): Boolean =
        query.querySequence == latestQuerySequence &&
            query.invalidationGeneration == invalidationGeneration
}

internal data class LaunchableAppIconCacheLookup<Value>(
    val value: Value?,
    val isCurrent: Boolean,
)

internal val launchableAppIconColorCache =
    LaunchableAppIconColorCache<CachedLaunchableAppIconColors>()

private val PreferredPackages = listOf(
    "com.android.dialer",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.android.mms",
    "com.samsung.android.messaging",
    "jp.naver.line.android",
    "com.google.android.youtube",
    "com.android.chrome",
    "com.sec.android.app.camera",
    "com.android.camera2",
    "com.spotify.music",
    "com.twitter.android",
    "jp.paypay.android.app",
    "com.instagram.android",
    "com.discord",
    "com.google.android.calendar",
    "com.google.android.gm",
    "com.openai.chatgpt",
    "com.samsung.android.spay",
    "com.samsung.android.wallet",
)

internal fun LaunchableApp.packageIdentity(): String = favoriteId(this).substringBefore('/')

internal fun favoriteId(app: LaunchableApp): String =
    launcherAppId(app.packageName, app.className, app.profile?.serialNumber)

private const val IconTileColorBitmapSize = 32

/**
 * Renders one launcher drawable into a tiny ARGB bitmap for color extraction. Drawable bounds
 * are restored in every path because the same instance is subsequently handed to ImageView for
 * the visible icon. Adaptive, bitmap, and vector drawables all use the ordinary Drawable.draw()
 * contract; malformed provider drawables simply use the deterministic Metro fallback.
 */
private fun iconTileColorForDrawable(
    icon: Drawable,
    packageName: String,
    className: String,
): Int {
    val fallback = fallbackIconTileColor(packageName, className)
    val originalBounds = Rect(icon.bounds)
    val bitmap = runCatching {
        Bitmap.createBitmap(
            IconTileColorBitmapSize,
            IconTileColorBitmapSize,
            Bitmap.Config.ARGB_8888,
        )
    }.getOrNull() ?: return fallback
    return try {
        runCatching {
            icon.setBounds(0, 0, IconTileColorBitmapSize, IconTileColorBitmapSize)
            icon.draw(Canvas(bitmap))
            IntArray(IconTileColorBitmapSize * IconTileColorBitmapSize).also { pixels ->
                bitmap.getPixels(
                    pixels,
                    0,
                    IconTileColorBitmapSize,
                    0,
                    0,
                    IconTileColorBitmapSize,
                    IconTileColorBitmapSize,
                )
            }
        }.getOrNull()?.let { pixels ->
            selectIconTileColorFromPixels(pixels, packageName, className)
        } ?: fallback
    } finally {
        // Always restore the caller's bounds, even if a custom Drawable throws from draw().
        runCatching { icon.bounds = originalBounds }
        bitmap.recycle()
    }
}

internal suspend fun queryLaunchableApps(context: Context): List<LaunchableApp> {
    val queryGeneration = launchableAppIconColorCache.beginQuery()
    val packageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }
    val packageVersions = mutableMapOf<String, LaunchableAppPackageVersion?>()
    val activeCacheKeys = mutableSetOf<LaunchableAppIconCacheKey>()

    val apps = buildList {
        activities.forEach { resolveInfo ->
            currentCoroutineContext().ensureActive()
            val activityInfo: ActivityInfo = resolveInfo.activityInfo ?: return@forEach
            val icon = resolveInfo.loadIcon(packageManager)
            // queryIntentActivities() does not request component metadata. Fetch it separately so
            // Compatible calendar activities can expose their date icon array while the
            // ordinary ResolveInfo icon remains the color-cache fallback.
            val metadataActivityInfo = activityInfoWithMetadata(packageManager, activityInfo)
            val dynamicIcon = resolveDynamicAppIconSpec(
                context = context,
                activityInfo = activityInfo,
                metadataActivityInfo = metadataActivityInfo,
            )
            currentCoroutineContext().ensureActive()
            val packageVersion = if (packageVersions.containsKey(activityInfo.packageName)) {
                packageVersions[activityInfo.packageName]
            } else {
                runCatching {
                    packageManager.getPackageInfo(activityInfo.packageName, 0)
                }.getOrNull()?.let { packageInfo ->
                    LaunchableAppPackageVersion(
                        versionCode = packageInfo.longVersionCode,
                        lastUpdateTime = packageInfo.lastUpdateTime,
                    )
                }.also { packageVersion ->
                    packageVersions[activityInfo.packageName] = packageVersion
                }
            }
            val cacheKey = packageVersion?.let {
                launchableAppIconCacheKey(
                    packageName = activityInfo.packageName,
                    className = activityInfo.name,
                    packageVersionCode = it.versionCode,
                    packageLastUpdateTime = it.lastUpdateTime,
                )
            }
            val cachedColors: LaunchableAppIconCacheLookup<CachedLaunchableAppIconColors> =
                cacheKey?.let { key ->
                    activeCacheKeys += key
                    launchableAppIconColorCache.getOrCompute(key, queryGeneration) {
                        val tileColorArgb = iconTileColorForDrawable(
                            icon = icon,
                            packageName = activityInfo.packageName,
                            className = activityInfo.name,
                        )
                        CachedLaunchableAppIconColors(
                            tileColorArgb = tileColorArgb,
                            tileContentColorArgb = accessibleTileForegroundArgb(tileColorArgb),
                        )
                    }
                } ?: run {
                    // A package that disappears between the activity query and getPackageInfo
                    // cannot be safely versioned. Compute a color for this snapshot but do not
                    // cache it, so a later refresh cannot reuse an entry with an unknown version.
                    val tileColorArgb = iconTileColorForDrawable(
                        icon = icon,
                        packageName = activityInfo.packageName,
                        className = activityInfo.name,
                    )
                    LaunchableAppIconCacheLookup(
                        value = CachedLaunchableAppIconColors(
                            tileColorArgb = tileColorArgb,
                            tileContentColorArgb = accessibleTileForegroundArgb(tileColorArgb),
                        ),
                        isCurrent = launchableAppIconColorCache.isCurrent(queryGeneration),
                    )
                }
            if (!cachedColors.isCurrent) {
                throw CancellationException("Launcher app icon query was invalidated")
            }
            val colors = cachedColors.value
                ?: throw CancellationException("Launcher app icon color was unavailable")
            val label = resolveInfo.loadLabel(packageManager).toString()
            currentCoroutineContext().ensureActive()
            add(
                LaunchableApp(
                    packageName = activityInfo.packageName,
                    className = activityInfo.name,
                    label = label,
                    icon = icon,
                    tileColorArgb = colors.tileColorArgb,
                    tileContentColorArgb = colors.tileContentColorArgb,
                    dynamicIcon = dynamicIcon,
                ),
            )
        }
    }
    val profileApps = queryAssociatedProfileApps(context)
    val sortedApps = (apps + profileApps).distinctBy(::favoriteId)
        .sortedWith(
            compareBy<LaunchableApp> { preferredIndex(it.packageName) }
                .thenBy { it.label.lowercase(Locale.getDefault()) },
        )
    currentCoroutineContext().ensureActive()
    if (!launchableAppIconColorCache.retainKeys(activeCacheKeys, queryGeneration)) {
        throw CancellationException("Launcher app icon query was superseded")
    }
    return sortedApps
}

private suspend fun queryAssociatedProfileApps(
    context: Context,
): List<LaunchableApp> {
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyList()
    val userManager = context.getSystemService(UserManager::class.java) ?: return emptyList()
    val profiles = runCatching { launcherApps.profiles }.getOrDefault(emptyList())
    val density = context.resources.displayMetrics.densityDpi
    return buildList {
        profiles.filterNot { it == Process.myUserHandle() }.forEach { user ->
            currentCoroutineContext().ensureActive()
            val serial = runCatching { userManager.getSerialNumberForUser(user) }.getOrDefault(-1L)
            if (serial < 0L) return@forEach
            val activities = runCatching { launcherApps.getActivityList(null, user) }
                .getOrDefault(emptyList())
            for (activity in activities) {
                currentCoroutineContext().ensureActive()
                // Another profile can lock or disappear during a query. Omit that entry from this
                // snapshot without treating the temporary failure as an uninstall.
                val icon = runCatching { activity.getBadgedIcon(density) }.getOrNull() ?: continue
                val label = runCatching { activity.label.toString() }.getOrNull() ?: continue
                val component = activity.componentName
                // Public cross-profile APIs do not expose the package version used by our icon
                // cache. Compute colors off-thread for each catalog refresh instead.
                val color = iconTileColorForDrawable(icon, component.packageName, component.className)
                add(LaunchableApp(
                    packageName = component.packageName,
                    className = component.className,
                    label = label,
                    icon = icon,
                    tileColorArgb = color,
                    tileContentColorArgb = accessibleTileForegroundArgb(color),
                    profile = LauncherAppProfile(user, serial),
                ))
            }
        }
    }
}

/**
 * Reads only the shortcuts needed by currently shortcut-capable favorite tiles. LauncherApps owns
 * the visibility/host-permission checks; when this launcher is not an allowed shortcut host, an
 * empty result deliberately leaves every tile on its legacy presentation.
 */
internal suspend fun queryAppShortcuts(
    context: Context,
    shortcutApps: List<LaunchableApp>,
): Map<String, List<ResolvedLauncherShortcut>> {
    if (shortcutApps.isEmpty()) return emptyMap()
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return emptyMap()
    val hasHostPermission = try {
        launcherApps.hasShortcutHostPermission()
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
    if (!hasHostPermission) return emptyMap()

    var queryFlags = LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
    // Cached shortcuts are available from Android 11. Do not reference this flag on older
    // releases at runtime, while still including it on devices that can safely expose cached
    // public records to a launcher host.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        queryFlags = queryFlags or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
    }
    val densityDpi = context.resources.displayMetrics.densityDpi
    val result = linkedMapOf<String, List<ResolvedLauncherShortcut>>()
    shortcutApps.forEach { app ->
        currentCoroutineContext().ensureActive()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(app.packageName)
            .setActivity(ComponentName(app.packageName, app.className))
            .setQueryFlags(queryFlags)
        val platformShortcuts = try {
            launcherApps.getShortcuts(query, app.launchUser())
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }.orEmpty()
        val candidates = platformShortcuts.mapIndexed { index, shortcut ->
            PublicLauncherShortcutCandidate(
                id = shortcut.id,
                shortLabel = shortcut.shortLabel?.toString().orEmpty(),
                longLabel = shortcut.longLabel?.toString().orEmpty(),
                rank = shortcut.rank,
                order = index,
                enabled = shortcut.isEnabled,
            )
        }
        val selected = selectPublicLauncherShortcuts(candidates)
        val resolved = selected.mapNotNull { candidate ->
            // Selection retains the source index, so resolve the exact record used for its
            // rank/label rather than letting associateBy silently choose the last duplicate.
            val shortcut = platformShortcuts.getOrNull(candidate.order)
                ?.takeIf { it.id == candidate.id }
                ?: return@mapNotNull null
            val icon = try {
                launcherApps.getShortcutIconDrawable(shortcut, densityDpi)
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            ResolvedLauncherShortcut(
                id = candidate.id,
                label = shortcutDisplayLabel(candidate),
                icon = icon,
            )
        }
        if (resolved.isNotEmpty()) {
            result[favoriteId(app)] = resolved
        }
    }
    return result
}

private fun preferredIndex(packageName: String): Int {
    val index = PreferredPackages.indexOf(packageName)
    return if (index == -1) 1000 else index
}

internal enum class AppLaunchResult {
    STARTED,
    ACTIVITY_NOT_FOUND,
    SECURITY_DENIED,
}

/** Converts the platform launch boundary into a result that callers can safely recover from. */
internal fun appLaunchResult(start: () -> Unit): AppLaunchResult = try {
    start()
    AppLaunchResult.STARTED
} catch (_: ActivityNotFoundException) {
    AppLaunchResult.ACTIVITY_NOT_FOUND
} catch (_: SecurityException) {
    AppLaunchResult.SECURITY_DENIED
}

internal fun launchApp(context: Context, app: LaunchableApp): AppLaunchResult {
    val result = appLaunchResult {
        if (app.profile != null) {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
                ?: throw ActivityNotFoundException("LauncherApps unavailable")
            launcherApps.startMainActivity(
                ComponentName(app.packageName, app.className), app.launchUser(), null, null,
            )
        } else context.startActivity(
            Intent().setComponent(ComponentName(app.packageName, app.className)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
    if (result != AppLaunchResult.STARTED) {
        Toast.makeText(context, tr("アプリを開けませんでした", "Couldn't open the app"), Toast.LENGTH_SHORT).show()
    }
    return result
}

/** Whether the platform should expose an uninstall action for this launcher entry. */
internal fun canUninstallApp(context: Context, app: LaunchableApp): Boolean {
    // The package-delete intent runs in our profile. Other profiles use their own app info UI.
    if (app.profile != null) return false
    if (app.packageName == context.packageName) return false
    val applicationInfo = runCatching {
        context.packageManager.getApplicationInfo(app.packageName, 0)
    }.getOrNull() ?: return false
    return applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
}

internal fun uninstallPackageUri(packageName: String): String = "package:$packageName"

/** Builds the platform package-removal confirmation intent without performing the removal. */
internal fun uninstallIntentForPackage(packageName: String): Intent = Intent(Intent.ACTION_DELETE).apply {
    data = Uri.parse(uninstallPackageUri(packageName))
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens Android's confirmation UI; the user must confirm there before anything is removed. */
internal fun uninstallApp(context: Context, app: LaunchableApp): AppLaunchResult {
    if (app.profile != null) return openAppDetails(context, app)
    val result = appLaunchResult {
        context.startActivity(uninstallIntentForPackage(app.packageName))
    }
    if (result != AppLaunchResult.STARTED) {
        Toast.makeText(context, tr("アンインストール確認を開けませんでした", "Couldn't open the uninstall prompt"), Toast.LENGTH_SHORT).show()
    }
    return result
}

/** Opens Android's app info screen for the app's package. */
internal fun openAppDetails(context: Context, app: LaunchableApp): AppLaunchResult {
    val result = appLaunchResult {
        if (app.profile != null) {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
                ?: throw ActivityNotFoundException("LauncherApps unavailable")
            launcherApps.startAppDetailsActivity(
                ComponentName(app.packageName, app.className), app.launchUser(), null, null,
            )
        } else context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    if (result != AppLaunchResult.STARTED) {
        Toast.makeText(context, tr("アプリ情報を開けませんでした", "Couldn't open app info"), Toast.LENGTH_SHORT).show()
    }
    return result
}

internal fun launchShortcut(
    context: Context,
    app: LaunchableApp,
    shortcut: ResolvedLauncherShortcut,
) {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    if (launcherApps == null) {
        Toast.makeText(context, tr("ショートカットを開けませんでした", "Couldn't open the shortcut"), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        launcherApps.startShortcut(
            app.packageName,
            shortcut.id,
            null,
            null,
            app.launchUser(),
        )
    } catch (_: SecurityException) {
        Toast.makeText(context, tr("ショートカットを開けませんでした", "Couldn't open the shortcut"), Toast.LENGTH_SHORT).show()
    } catch (_: RuntimeException) {
        Toast.makeText(context, tr("ショートカットを開けませんでした", "Couldn't open the shortcut"), Toast.LENGTH_SHORT).show()
    }
}
