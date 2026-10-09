package com.fiilda.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Provider metadata, preview assets, and persisted descriptor projection helpers. */

internal data class WidgetPickerGroup(
    val key: WidgetPickerGroupKey,
    val packageName: String,
    val profileKey: String,
    val profileLabel: String,
    val appLabel: String,
    val appIcon: Drawable?,
    val providers: List<WidgetPickerProvider>,
)

internal data class WidgetPickerProvider(
    val info: AppWidgetProviderInfo,
    val profile: UserHandle,
) {
    val componentKey: String
        get() = "${info.provider?.flattenToString().orEmpty()}@$profile"
}

internal data class WidgetPreviewAsset(
    val layout: View?,
    val image: Drawable?,
)

/**
 * Reads the framework's current host padding for a provider. This must stay dynamic: the
 * framework can return different padding after a posture/configuration change, while a remembered
 * AppWidgetHostView itself is retained by Compose.
 */
internal fun defaultWidgetPaddingForProvider(
    context: Context,
    provider: ComponentName,
): Rect = runCatching {
    AppWidgetHostView.getDefaultPaddingForWidget(context, provider, Rect())
}.getOrDefault(Rect())

// Some OEMs reserve extra bottom padding for launcher labels. Our tile has no label below
// the provider, so balance the vertical padding while preserving its negotiated content size.
internal fun centeredWidgetHostPadding(padding: Rect): Rect {
    val vertical = padding.top + padding.bottom
    val top = vertical / 2
    return Rect(padding.left, top, padding.right, vertical - top)
}

// These metadata fields were added in API 31. Keep every access behind one SDK-gated helper so
// Compose remember keys and picker code remain safe on the launcher's API 29/30 minimum devices.
internal fun widgetTargetCellWidth(info: AppWidgetProviderInfo): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellWidth else 0

internal fun widgetTargetCellHeight(info: AppWidgetProviderInfo): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.targetCellHeight else 0

internal fun widgetPreviewLayoutResource(info: AppWidgetProviderInfo): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.previewLayout else 0

internal fun widgetDescriptionResource(info: AppWidgetProviderInfo): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) info.descriptionRes else 0

internal fun isOptionalWidgetConfiguration(info: AppWidgetProviderInfo): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0

internal fun widgetPreviewImageResource(info: AppWidgetProviderInfo): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) info.previewImage else 0

private fun loadWidgetPreviewImage(
    context: Context,
    info: AppWidgetProviderInfo,
): Drawable? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
    runCatching {
        info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
    }.getOrNull()
} else {
    null
}

internal fun descriptorForWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int,
): LauncherWidgetDescriptor? {
    if (appWidgetId <= 0) return null
    val info = runCatching { appWidgetManager.getAppWidgetInfo(appWidgetId) }.getOrNull()
        ?: return null
    val provider = info.provider ?: return null
    val density = context.resources.displayMetrics.density
    val defaultPadding = defaultWidgetPaddingForProvider(context, provider)
    val targetCellWidth = widgetTargetCellWidth(info)
    val targetCellHeight = widgetTargetCellHeight(info)
    val label = runCatching {
        info.loadLabel(context.packageManager).toString()
    }.getOrDefault("")
    return LauncherWidgetDescriptor(
        appWidgetId = appWidgetId,
        provider = provider.flattenToString(),
        label = label,
        // AppWidgetProviderInfo dimensions arrive px-adjusted on the target framework. Keep
        // descriptors and provider options in dp so the same persisted widget scales correctly
        // when the device density changes.
        sizeSpec = widgetSizeSpecFromProviderPixels(
            minWidthPx = info.minWidth,
            minHeightPx = info.minHeight,
            minResizeWidthPx = info.minResizeWidth,
            minResizeHeightPx = info.minResizeHeight,
            resizeMode = info.resizeMode,
            density = density,
            defaultPaddingHorizontalPx = (defaultPadding.left + defaultPadding.right).coerceAtLeast(0),
            defaultPaddingVerticalPx = (defaultPadding.top + defaultPadding.bottom).coerceAtLeast(0),
            targetCellWidth = targetCellWidth,
            targetCellHeight = targetCellHeight,
        ),
    )
}

internal enum class StoredWidgetDisposition {
    /** The provider resolved to the stored component; use the freshly read metadata. */
    REFRESH,

    /** The provider is temporarily unreadable (locked/paused profile, zombie provider). */
    KEEP_STORED,

    /** The framework already released the ID, e.g. because the provider was uninstalled. */
    DROP,

    /** The ID now resolves to a different provider; the stored record is stale. */
    DELETE,
}

/**
 * Decides what to do with a persisted widget during validation. A missing provider is not proof
 * of removal: AppWidgetService keeps a bound ID while a work profile or private space is locked,
 * and deleting it there would be irreversible. Only a framework-confirmed release drops the record.
 * [hostStillOwnsId] is null when the host ID list could not be read, which is treated as owned.
 */
internal fun storedWidgetDescriptorDisposition(
    storedProvider: String,
    currentProvider: String?,
    hostStillOwnsId: Boolean?,
): StoredWidgetDisposition = when {
    currentProvider == storedProvider -> StoredWidgetDisposition.REFRESH
    currentProvider != null -> StoredWidgetDisposition.DELETE
    hostStillOwnsId == false -> StoredWidgetDisposition.DROP
    else -> StoredWidgetDisposition.KEEP_STORED
}

internal fun loadValidWidgetDescriptors(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetHost: AppWidgetHost,
    storedDescriptors: List<LauncherWidgetDescriptor>? = null,
): List<LauncherWidgetDescriptor> {
    val stored = storedDescriptors ?: readWidgetDescriptors(context)
    // Read lazily and at most once: it is only needed when a provider cannot be resolved.
    val hostIds by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { appWidgetHost.appWidgetIds.toSet() }.getOrNull()
    }
    val valid = stored.mapNotNull { descriptor ->
        val current = descriptorForWidget(context, appWidgetManager, descriptor.appWidgetId)
        val disposition = storedWidgetDescriptorDisposition(
            storedProvider = descriptor.provider,
            currentProvider = current?.provider,
            hostStillOwnsId = if (current == null) {
                hostIds?.let { descriptor.appWidgetId in it }
            } else {
                true
            },
        )
        when (disposition) {
            StoredWidgetDisposition.REFRESH -> current
            StoredWidgetDisposition.KEEP_STORED -> descriptor
            StoredWidgetDisposition.DROP -> null
            StoredWidgetDisposition.DELETE -> {
                runCatching { appWidgetHost.deleteAppWidgetId(descriptor.appWidgetId) }
                null
            }
        }
    }
    if (storedDescriptors == null && valid != stored) saveWidgetDescriptors(context, valid)
    return valid
}

private fun installedWidgetProviders(
    context: Context,
    appWidgetManager: AppWidgetManager,
): List<WidgetPickerProvider> {
    val userManager = context.getSystemService(UserManager::class.java)
    val profiles = runCatching { userManager?.getUserProfiles().orEmpty() }
        .getOrDefault(emptyList())
        .ifEmpty { listOf(Process.myUserHandle()) }
    return profiles.asSequence()
        .flatMap { profile ->
            runCatching {
                appWidgetManager.getInstalledProvidersForProfile(profile)
            }.getOrDefault(emptyList())
                .asSequence()
                .map { info -> WidgetPickerProvider(info = info, profile = profile) }
        }
        .filter { provider ->
            val info = provider.info
            shouldIncludeWidgetProviderInPicker(
                providerAvailable = info.provider != null,
                widgetCategory = info.widgetCategory,
                hideFromPicker = info.widgetFeatures and
                    AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER != 0,
            )
        }
        .distinctBy { provider -> provider.componentKey }
        .toList()
}

/** Resolves the application from the provider's profile rather than the launcher's current user. */
private fun providerApplicationInfo(
    context: Context,
    provider: WidgetPickerProvider,
): android.content.pm.ApplicationInfo? {
    val component = provider.info.provider ?: return null
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
    return runCatching {
        launcherApps.getApplicationInfo(
            component.packageName,
            PackageManager.MATCH_ALL,
            provider.profile,
        )
    }.getOrNull()
}

private fun widgetProviderProfileKey(provider: WidgetPickerProvider): String {
    val profile = provider.profile
    // UserHandle.toString() includes the stable profile identifier on supported Android builds,
    // while avoiding hidden/SDK-version-specific identifier accessors.
    return "user:$profile"
}

private fun widgetProviderProfileLabel(
    context: Context,
    provider: WidgetPickerProvider,
    applicationInfo: android.content.pm.ApplicationInfo?,
): String {
    val profile = provider.profile
    if (profile == Process.myUserHandle()) return tr("個人用", "Personal")
    val packageManager = context.packageManager
    val applicationLabel = runCatching {
        applicationInfo?.loadLabel(packageManager)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
    val badgedLabel = applicationLabel?.let { label ->
        runCatching { packageManager.getUserBadgedLabel(label, profile).toString() }.getOrNull()
    }
    val badge = if (!applicationLabel.isNullOrBlank() && !badgedLabel.isNullOrBlank()) {
        badgedLabel.removePrefix(applicationLabel)
            .trim()
            .trim('(', ')', '（', '）', '[', ']', '【', '】', ' ', '・')
            .takeIf { it.isNotBlank() }
    } else {
        null
    }
    return badge ?: tr("別プロフィール", "Other profile")
}

internal fun widgetPickerGroups(
    context: Context,
    appWidgetManager: AppWidgetManager,
): List<WidgetPickerGroup> {
    val packageManager = context.packageManager
    return installedWidgetProviders(context, appWidgetManager)
        .mapNotNull { pickerProvider ->
            val provider = pickerProvider.info.provider ?: return@mapNotNull null
            val info = pickerProvider.info
            val profileKey = widgetProviderProfileKey(pickerProvider)
            val key = widgetPickerGroupKey(provider.packageName, profileKey)
            val applicationInfo = providerApplicationInfo(context, pickerProvider)
            val appLabel = runCatching {
                applicationInfo?.loadLabel(packageManager)?.toString()
            }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: provider.packageName
            val appIcon = runCatching {
                applicationInfo?.loadIcon(packageManager)
            }.getOrNull() ?: runCatching {
                info.loadIcon(context, context.resources.displayMetrics.densityDpi)
            }.getOrNull()
            Triple(
                key,
                pickerProvider,
                WidgetPickerGroupSeed(
                    appLabel = appLabel,
                    appIcon = appIcon,
                    applicationInfo = applicationInfo,
                ),
            )
        }
        .groupBy { it.first }
        .map { (key, entries) ->
            val first = entries.first()
            WidgetPickerGroup(
                key = key,
                packageName = key.packageName,
                profileKey = key.profileKey,
                profileLabel = widgetProviderProfileLabel(
                    context = context,
                    provider = first.second,
                    applicationInfo = first.third.applicationInfo,
                ),
                appLabel = entries.map { it.third.appLabel }
                    .firstOrNull { it.isNotBlank() }
                    ?: key.packageName,
                appIcon = entries.mapNotNull { it.third.appIcon }.firstOrNull(),
                providers = entries
                    .map { it.second }
                    .distinctBy { it.componentKey }
                    .sortedWith(
                        compareBy<WidgetPickerProvider> {
                            runCatching { it.info.loadLabel(packageManager) }
                                .getOrDefault("")
                                .lowercase(Locale.getDefault())
                        }.thenBy { it.componentKey },
                    ),
            )
        }
        .sortedWith(
            compareBy<WidgetPickerGroup> { it.appLabel.lowercase(Locale.getDefault()) }
                .thenBy { it.profileKey },
        )
}

private data class WidgetPickerGroupSeed(
    val appLabel: String,
    val appIcon: Drawable?,
    val applicationInfo: android.content.pm.ApplicationInfo?,
)

internal fun widgetDescription(
    context: Context,
    provider: WidgetPickerProvider,
): String? {
    val info = provider.info
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { info.loadDescription(context)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    val resourceId = widgetDescriptionResource(info)
    if (resourceId == 0) return null
    return runCatching {
        val applicationInfo = providerApplicationInfo(context, provider) ?: return null
        context.packageManager
            .getResourcesForApplication(applicationInfo)
            .getText(resourceId)
            .toString()
    }.getOrNull()
}

internal fun widgetPickerGridSize(
    context: Context,
    info: AppWidgetProviderInfo,
): WidgetGridSize {
    val provider = info.provider ?: return WidgetGridSize(1, 1)
    val density = context.resources.displayMetrics.density
    val padding = defaultWidgetPaddingForProvider(context, provider)
    val targetCellWidth = widgetTargetCellWidth(info)
    val targetCellHeight = widgetTargetCellHeight(info)
    val spec = widgetSizeSpecFromProviderPixels(
        minWidthPx = info.minWidth,
        minHeightPx = info.minHeight,
        minResizeWidthPx = info.minResizeWidth,
        minResizeHeightPx = info.minResizeHeight,
        resizeMode = info.resizeMode,
        density = density,
        defaultPaddingHorizontalPx = (padding.left + padding.right).coerceAtLeast(0),
        defaultPaddingVerticalPx = (padding.top + padding.bottom).coerceAtLeast(0),
        targetCellWidth = targetCellWidth,
        targetCellHeight = targetCellHeight,
    )
    // 72dp is the platform launcher's conventional baseline cell. The actual home board
    // recalculates AUTO sizing against the measured posture-specific cell width.
    return calculateWidgetGridSpans(spec, cellWidthDp = 72f, gapDp = 3f, columns = 6)
}

/**
 * A tiny provider-profile context for previewLayout inflation. LauncherApps supplies the
 * ApplicationInfo for the requested profile; PackageManager then supplies matching Resources,
 * avoiding a current-user createPackageContext for work-profile providers.
 */
private class WidgetProviderPreviewContext(
    base: Context,
    private val providerApplicationInfo: android.content.pm.ApplicationInfo,
    private val providerResources: Resources,
) : ContextWrapper(base) {
    private var providerTheme: Resources.Theme? = null

    override fun getResources(): Resources = providerResources

    override fun getAssets(): AssetManager = providerResources.assets

    override fun getPackageName(): String = providerApplicationInfo.packageName

    override fun getApplicationInfo(): android.content.pm.ApplicationInfo = providerApplicationInfo

    override fun getTheme(): Resources.Theme {
        providerTheme?.let { return it }
        return providerResources.newTheme().also { theme ->
            if (providerApplicationInfo.theme != 0) {
                theme.applyStyle(providerApplicationInfo.theme, true)
            }
            providerTheme = theme
        }
    }
}

internal fun loadWidgetPreviewAsset(
    context: Context,
    provider: WidgetPickerProvider,
): WidgetPreviewAsset {
    val info = provider.info
    val layoutResource = widgetPreviewLayoutResource(info)
    val providerContext = if (layoutResource != 0) {
        runCatching {
            val applicationInfo = providerApplicationInfo(context, provider) ?: return@runCatching null
            val resources = context.packageManager.getResourcesForApplication(applicationInfo)
            WidgetProviderPreviewContext(context, applicationInfo, resources)
        }.getOrNull()
    } else {
        null
    }
    val layout = if (layoutResource != 0 && providerContext != null) {
        runCatching {
            LayoutInflater.from(providerContext)
                .cloneInContext(providerContext)
                .inflate(
                    layoutResource,
                    FrameLayout(providerContext),
                    false,
                )
        }.getOrNull()
    } else {
        null
    }
    if (layout != null) return WidgetPreviewAsset(layout = layout, image = null)
    val image = loadWidgetPreviewImage(context, info)
    return WidgetPreviewAsset(layout = null, image = image)
}

internal fun appWidgetOptionsForProvider(
    context: Context,
    info: AppWidgetProviderInfo,
): Bundle {
    val provider = info.provider
    val density = context.resources.displayMetrics.density
    val padding = provider?.let { defaultWidgetPaddingForProvider(context, it) } ?: Rect()
    val spec = widgetSizeSpecFromProviderPixels(
        minWidthPx = info.minWidth,
        minHeightPx = info.minHeight,
        minResizeWidthPx = info.minResizeWidth,
        minResizeHeightPx = info.minResizeHeight,
        resizeMode = info.resizeMode,
        density = density,
        defaultPaddingHorizontalPx = (padding.left + padding.right).coerceAtLeast(0),
        defaultPaddingVerticalPx = (padding.top + padding.bottom).coerceAtLeast(0),
        targetCellWidth = widgetTargetCellWidth(info),
        targetCellHeight = widgetTargetCellHeight(info),
    )
    return Bundle().apply {
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        )
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, spec.effectiveMinWidthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, spec.effectiveMinWidthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, spec.effectiveMinHeightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, spec.effectiveMinHeightDp)
    }
}
