package com.fiilda.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap
import java.util.TimeZone
import java.util.concurrent.CancellationException

/** Platform metadata suffix used by AOSP and the Google Calendar icon. */
internal const val DynamicCalendarMetadataSuffix = ".dynamic_icons"

private const val CalendarDynamicDayCount = 31
private const val DynamicClockMetadataPrefix = "com.android.launcher3"
private const val DynamicClockRoundIconMetadataKey =
    "$DynamicClockMetadataPrefix.LEVEL_PER_TICK_ICON_ROUND"
private const val DynamicClockHourLayerMetadataKey =
    "$DynamicClockMetadataPrefix.HOUR_LAYER_INDEX"
private const val DynamicClockMinuteLayerMetadataKey =
    "$DynamicClockMetadataPrefix.MINUTE_LAYER_INDEX"
private const val DynamicClockSecondLayerMetadataKey =
    "$DynamicClockMetadataPrefix.SECOND_LAYER_INDEX"
private const val DynamicClockDefaultHourMetadataKey =
    "$DynamicClockMetadataPrefix.DEFAULT_HOUR"
private const val DynamicClockDefaultMinuteMetadataKey =
    "$DynamicClockMetadataPrefix.DEFAULT_MINUTE"
private const val DynamicClockDefaultSecondMetadataKey =
    "$DynamicClockMetadataPrefix.DEFAULT_SECOND"

internal const val DynamicIconSecondTickMillis = 1_000L
internal const val DynamicIconMinuteTickMillis = 60_000L

/**
 * A resolved dynamic icon definition. The definition contains resource metadata, while every
 * rendered surface asks it for its own Drawable instance. This is important because a
 * LayerDrawable's levels and callbacks are mutable state.
 */
internal abstract class DynamicAppIconSpec {
    abstract val updateIntervalMillis: Long
    abstract val hasSecondHand: Boolean

    /** Whether the first usable Drawable is loaded asynchronously from a provider. */
    open val supportsAsyncLoading: Boolean = false

    /** Identifies a visual state that requires replacing the current Drawable. */
    abstract fun stateKey(atMillis: Long): Long

    /** Creates and initializes a fresh Drawable for one rendered surface. */
    abstract fun newDrawable(context: Context, atMillis: Long): Drawable?

    /** Updates mutable state in [current], or returns a replacement Drawable when required. */
    abstract fun updateDrawable(
        context: Context,
        current: Drawable,
        atMillis: Long,
    ): Drawable?
}

internal class CalendarDynamicIconSpec internal constructor(
    private val resources: Resources,
    private val densityDpi: Int,
    dayResourceIds: IntArray,
) : DynamicAppIconSpec() {
    private val dayResourceIds = dayResourceIds.copyOf()

    init {
        require(this.dayResourceIds.size == CalendarDynamicDayCount)
    }

    override val updateIntervalMillis: Long = DynamicIconMinuteTickMillis
    override val hasSecondHand: Boolean = false

    override fun stateKey(atMillis: Long): Long =
        calendarDayIndexAt(atMillis, TimeZone.getDefault()).toLong()

    override fun newDrawable(context: Context, atMillis: Long): Drawable? =
        drawableForDay(calendarDayIndexAt(atMillis, TimeZone.getDefault()))

    override fun updateDrawable(
        context: Context,
        current: Drawable,
        atMillis: Long,
    ): Drawable? = null

    private fun drawableForDay(dayIndex: Int): Drawable? {
        val resourceId = dayResourceIds.getOrNull(dayIndex) ?: return null
        return dynamicDrawableForResource(resources, resourceId, densityDpi)
    }
}

internal data class ClockDynamicIconMetadata(
    val roundIconResourceId: Int,
    val hourLayerIndex: Int,
    val minuteLayerIndex: Int,
    val secondLayerIndex: Int,
    val defaultHour: Int,
    val defaultMinute: Int,
    val defaultSecond: Int,
)

internal data class ClockHandLevels(
    val hour: Int,
    val minute: Int,
    val second: Int,
)

internal class ClockDynamicIconSpec internal constructor(
    private val resources: Resources,
    private val densityDpi: Int,
    private val metadata: ClockDynamicIconMetadata,
) : DynamicAppIconSpec() {
    override val updateIntervalMillis: Long =
        if (metadata.secondLayerIndex >= 0) {
            DynamicIconSecondTickMillis
        } else {
            DynamicIconMinuteTickMillis
        }
    override val hasSecondHand: Boolean = metadata.secondLayerIndex >= 0

    // Clock levels are mutable on the existing Drawable, so no replacement is needed for a tick.
    override fun stateKey(atMillis: Long): Long = 0L

    override fun newDrawable(context: Context, atMillis: Long): Drawable? {
        val drawable = dynamicDrawableForResource(
            resources = resources,
            resourceId = metadata.roundIconResourceId,
            densityDpi = densityDpi,
        ) ?: return null
        val foreground = (drawable as? AdaptiveIconDrawable)?.foreground as? LayerDrawable
            ?: return null
        applyClockHandLevels(foreground, metadata, atMillis)
        return drawable
    }

    override fun updateDrawable(
        context: Context,
        current: Drawable,
        atMillis: Long,
    ): Drawable? {
        val foreground = (current as? AdaptiveIconDrawable)?.foreground as? LayerDrawable
            ?: return null
        applyClockHandLevels(foreground, metadata, atMillis)
        return null
    }
}

/**
 * Best-effort source for Samsung's two stock applications. Samsung does not publish a general
 * live-icon metadata contract, so the source is intentionally limited to the exact component
 * selected by the launcher catalog and keeps the normal ResolveInfo icon as its initial fallback.
 */
internal class SamsungDynamicIconSpec internal constructor(
    private val source: SamsungDynamicIconSource,
    private val densityDpi: Int,
) : DynamicAppIconSpec() {
    override val updateIntervalMillis: Long = DynamicIconMinuteTickMillis
    override val hasSecondHand: Boolean = false
    override val supportsAsyncLoading: Boolean = true

    // The source is reloaded for every visible minute and broadcast refresh. Include the wall
    // clock bucket for diagnostics and for callers that need to identify a changed snapshot.
    override fun stateKey(atMillis: Long): Long =
        atMillis.floorDiv(DynamicIconMinuteTickMillis)

    // Samsung's LauncherApps result is only safe to obtain off the main thread.
    override fun newDrawable(context: Context, atMillis: Long): Drawable? = null

    override fun updateDrawable(
        context: Context,
        current: Drawable,
        atMillis: Long,
    ): Drawable? = null

    internal suspend fun loadLatestDrawable(context: Context): Drawable? =
        source.load(context.applicationContext, densityDpi)
}

/** Zero-based day of month, matching IconProvider.getDay() in AOSP. */
internal fun calendarDayIndexAt(atMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
    val calendar = Calendar.getInstance(timeZone)
    calendar.timeInMillis = atMillis
    return (calendar.get(Calendar.DAY_OF_MONTH) - 1).coerceIn(0, CalendarDynamicDayCount - 1)
}

internal fun clockHandLevelsAt(
    hour12: Int,
    minute: Int,
    second: Int,
    defaultHour: Int,
    defaultMinute: Int,
    defaultSecond: Int,
): ClockHandLevels {
    val normalizedHour = positiveModulo(hour12, 12)
    val normalizedMinute = positiveModulo(minute, 60)
    val normalizedSecond = positiveModulo(second, 60)
    val convertedHour = positiveModulo(normalizedHour + 12 - defaultHour, 12)
    val convertedMinute = positiveModulo(normalizedMinute + 60 - defaultMinute, 60)
    val convertedSecond = positiveModulo(normalizedSecond + 60 - defaultSecond, 60)
    return ClockHandLevels(
        // Keep the minute component unshifted, as in AOSP ClockDrawableWrapper.
        hour = convertedHour * 60 + normalizedMinute,
        minute = normalizedHour * 60 + convertedMinute,
        second = convertedSecond * 10,
    )
}

internal fun clockHandLevelsAt(
    atMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    defaultHour: Int,
    defaultMinute: Int,
    defaultSecond: Int,
): ClockHandLevels {
    val calendar = Calendar.getInstance(timeZone)
    calendar.timeInMillis = atMillis
    return clockHandLevelsAt(
        hour12 = calendar.get(Calendar.HOUR),
        minute = calendar.get(Calendar.MINUTE),
        second = calendar.get(Calendar.SECOND),
        defaultHour = defaultHour,
        defaultMinute = defaultMinute,
        defaultSecond = defaultSecond,
    )
}

private fun positiveModulo(value: Int, modulus: Int): Int {
    val remainder = value % modulus
    return if (remainder < 0) remainder + modulus else remainder
}

internal fun parseClockDynamicIconMetadata(
    metadata: Bundle?,
    layerCount: Int,
): ClockDynamicIconMetadata? {
    if (metadata == null || layerCount <= 0) return null
    val roundIconResourceId = metadataIntOrNull(
        metadata,
        DynamicClockRoundIconMetadataKey,
        defaultValue = 0,
    ) ?: return null
    if (roundIconResourceId <= 0) return null
    val hourLayerIndex = validLayerIndex(
        metadataIntOrNull(metadata, DynamicClockHourLayerMetadataKey, defaultValue = -1),
        layerCount,
    )
    val minuteLayerIndex = validLayerIndex(
        metadataIntOrNull(metadata, DynamicClockMinuteLayerMetadataKey, defaultValue = -1),
        layerCount,
    )
    val secondLayerIndex = validLayerIndex(
        metadataIntOrNull(metadata, DynamicClockSecondLayerMetadataKey, defaultValue = -1),
        layerCount,
    )
    // A metadata record with no usable hand cannot produce a dynamic clock icon.
    if (hourLayerIndex < 0 && minuteLayerIndex < 0 && secondLayerIndex < 0) return null
    return ClockDynamicIconMetadata(
        roundIconResourceId = roundIconResourceId,
        hourLayerIndex = hourLayerIndex,
        minuteLayerIndex = minuteLayerIndex,
        secondLayerIndex = secondLayerIndex,
        defaultHour = metadataIntOrNull(
            metadata,
            DynamicClockDefaultHourMetadataKey,
            defaultValue = 0,
        ) ?: return null,
        defaultMinute = metadataIntOrNull(
            metadata,
            DynamicClockDefaultMinuteMetadataKey,
            defaultValue = 0,
        ) ?: return null,
        defaultSecond = metadataIntOrNull(
            metadata,
            DynamicClockDefaultSecondMetadataKey,
            defaultValue = 0,
        ) ?: return null,
    )
}

internal fun parseCalendarDynamicIconResourceIds(
    metadata: Bundle?,
    packageName: String,
    resources: Resources,
): IntArray? {
    if (metadata == null || packageName.isBlank()) return null
    val arrayResourceId = metadataIntOrNull(
        metadata,
        packageName + DynamicCalendarMetadataSuffix,
        defaultValue = 0,
    ) ?: return null
    if (arrayResourceId <= 0) return null
    val typedArray = try {
        resources.obtainTypedArray(arrayResourceId)
    } catch (exception: Exception) {
        rethrowCancellation(exception)
        return null
    }
    return try {
        if (typedArray.length() < CalendarDynamicDayCount) return null
        val ids = IntArray(CalendarDynamicDayCount) { index ->
            runCatching { typedArray.getResourceId(index, 0) }
                .getOrElse { exception ->
                    rethrowCancellation(exception)
                    0
                }
        }
        if (ids.any { it <= 0 }) return null
        // Check every date up front so a later date rollover cannot expose malformed metadata.
        if (ids.any { resourceId ->
                try {
                    resources.getDrawableForDensity(resourceId, resources.displayMetrics.densityDpi)
                    false
                } catch (exception: Exception) {
                    rethrowCancellation(exception)
                    true
                }
            }
        ) {
            return null
        }
        ids
    } finally {
        typedArray.recycle()
    }
}

/**
 * Resolves AOSP-style dynamic metadata for one launchable activity. Calendar metadata lives on
 * the activity; clock metadata lives on ApplicationInfo. The normal ResolveInfo icon remains the
 * fallback and is still used for the precomputed tile colors.
 */
internal fun resolveDynamicAppIconSpec(
    context: Context,
    activityInfo: ActivityInfo,
    metadataActivityInfo: ActivityInfo? = null,
): DynamicAppIconSpec? {
    val packageManager = context.packageManager
    val packageName = activityInfo.packageName
    val packageResources = try {
        packageManager.getResourcesForApplication(packageName)
    } catch (exception: Exception) {
        rethrowCancellation(exception)
        return null
    }
    val densityDpi = context.resources.displayMetrics.densityDpi
    val samsungFallback = samsungDynamicIconSpec(activityInfo, densityDpi)
    val resolvedMetadataActivityInfo = metadataActivityInfo ?: activityInfoWithMetadata(
        packageManager,
        activityInfo,
    )
    val calendarIds = parseCalendarDynamicIconResourceIds(
        metadata = resolvedMetadataActivityInfo.metaData,
        packageName = packageName,
        resources = packageResources,
    )
    if (calendarIds != null) {
        return CalendarDynamicIconSpec(packageResources, densityDpi, calendarIds)
    }

    val applicationInfo = applicationInfoWithMetadata(packageManager, resolvedMetadataActivityInfo)
        ?: resolvedMetadataActivityInfo.applicationInfo
    val clockMetadata = applicationInfo?.metaData
    val roundIconResourceId = clockMetadata?.let {
        metadataIntOrNull(it, DynamicClockRoundIconMetadataKey, defaultValue = 0)
    } ?: return samsungFallback
    if (roundIconResourceId <= 0) return samsungFallback
    val roundDrawable = dynamicDrawableForResource(
        resources = packageResources,
        resourceId = roundIconResourceId,
        densityDpi = densityDpi,
    ) ?: return samsungFallback
    val adaptiveIcon = roundDrawable as? AdaptiveIconDrawable ?: return samsungFallback
    val foreground = adaptiveIcon.foreground as? LayerDrawable ?: return samsungFallback
    val metadata = parseClockDynamicIconMetadata(
        metadata = clockMetadata,
        layerCount = foreground.numberOfLayers,
    ) ?: return samsungFallback
    if (clockLayerDrawablesArePresent(foreground, metadata)) {
        return ClockDynamicIconSpec(packageResources, densityDpi, metadata)
    }

    return samsungDynamicIconSpec(activityInfo, densityDpi)
}

private fun samsungDynamicIconSpec(
    activityInfo: ActivityInfo,
    densityDpi: Int,
): DynamicAppIconSpec? {
    val target = SamsungDynamicIconSource.targetFor(
        manufacturer = Build.MANUFACTURER,
        component = ComponentName(activityInfo.packageName, activityInfo.name),
    ) ?: return null
    return SamsungDynamicIconSpec(
        source = SamsungDynamicIconSource(target),
        densityDpi = densityDpi,
    )
}

private fun clockLayerDrawablesArePresent(
    foreground: LayerDrawable,
    metadata: ClockDynamicIconMetadata,
): Boolean = listOf(
    metadata.hourLayerIndex,
    metadata.minuteLayerIndex,
    metadata.secondLayerIndex,
).filter { it >= 0 }.all { index ->
    try {
        foreground.getDrawable(index) != null
    } catch (exception: Exception) {
        rethrowCancellation(exception)
        false
    }
}

private fun dynamicDrawableForResource(
    resources: Resources,
    resourceId: Int,
    densityDpi: Int,
): Drawable? = try {
    resources.getDrawableForDensity(resourceId, densityDpi)?.mutate()
} catch (exception: Exception) {
    rethrowCancellation(exception)
    null
}

private fun applyClockHandLevels(
    foreground: LayerDrawable,
    metadata: ClockDynamicIconMetadata,
    atMillis: Long,
): Boolean {
    val levels = clockHandLevelsAt(
        atMillis = atMillis,
        defaultHour = metadata.defaultHour,
        defaultMinute = metadata.defaultMinute,
        defaultSecond = metadata.defaultSecond,
    )
    var changed = false
    fun setLevelIfPresent(index: Int, level: Int) {
        if (index < 0) return
        try {
            if (foreground.getDrawable(index).setLevel(level)) changed = true
        } catch (exception: Exception) {
            rethrowCancellation(exception)
        }
    }
    setLevelIfPresent(metadata.hourLayerIndex, levels.hour)
    setLevelIfPresent(metadata.minuteLayerIndex, levels.minute)
    setLevelIfPresent(metadata.secondLayerIndex, levels.second)
    return changed
}

private fun validLayerIndex(value: Int?, layerCount: Int): Int =
    value?.takeIf { it in 0 until layerCount } ?: -1

private fun metadataIntOrNull(metadata: Bundle, key: String, defaultValue: Int): Int? = try {
    metadata.getInt(key, defaultValue)
} catch (exception: Exception) {
    rethrowCancellation(exception)
    null
}

private fun rethrowCancellation(exception: Throwable) {
    if (exception is CancellationException) throw exception
}

/**
 * Returns an independent Drawable instance for a rendered surface. ConstantState is the normal
 * path. A few provider drawables do not expose one, so take a bounded bitmap snapshot instead of
 * handing the mutable provider object to multiple ImageViews.
 */
internal fun independentLauncherDrawable(
    source: Drawable,
    resources: Resources,
): Drawable {
    val copy = try {
        source.constantState?.newDrawable(resources)?.mutate()
    } catch (exception: Exception) {
        rethrowCancellation(exception)
        null
    }
    if (copy != null) return copy
    val originalBounds = Rect(source.bounds)
    val width = source.intrinsicWidth.coerceAtLeast(1).coerceAtMost(128)
    val height = source.intrinsicHeight.coerceAtLeast(1).coerceAtMost(128)
    val bitmap = runCatching {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }.getOrNull()
    if (bitmap == null) {
        return resources.getDrawable(android.R.drawable.sym_def_app_icon).mutate()
    }
    return try {
        source.setBounds(0, 0, width, height)
        source.draw(Canvas(bitmap))
        BitmapDrawable(resources, bitmap)
    } catch (exception: Exception) {
        bitmap.recycle()
        rethrowCancellation(exception)
        resources.getDrawable(android.R.drawable.sym_def_app_icon).mutate()
    } finally {
        runCatching { source.bounds = originalBounds }
    }
}

/**
 * ImageView used by every app icon surface. It owns its Drawable, and registers only while its
 * attached/lifecycle-visible View can actually be shown.
 */
internal class DynamicIconImageView(context: Context) : ImageView(context) {
    private var boundAppId: String? = null
    private var boundSource: Drawable? = null
    private var boundSpec: DynamicAppIconSpec? = null
    private var dynamicDrawable: Drawable? = null
    private var dynamicStateKey: Long = Long.MIN_VALUE
    private var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null
    private var loadScope: CoroutineScope? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var requestedStateKey: Long? = null

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        syncTickerRegistration()
    }
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
        syncTickerRegistration()
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> syncTickerRegistration()
            Lifecycle.Event.ON_STOP,
            Lifecycle.Event.ON_DESTROY -> DynamicIconTicker.unregister(this)
            else -> Unit
        }
    }

    fun bindApp(app: LaunchableApp, overrideIcon: Drawable? = null) {
        val appId = favoriteId(app)
        val spec = if (overrideIcon == null) app.dynamicIcon else null
        val source = overrideIcon ?: app.icon
        if (boundAppId == appId && boundSource === source && boundSpec === spec) return
        DynamicIconTicker.unregister(this)
        boundAppId = appId
        boundSource = source
        boundSpec = spec
        dynamicStateKey = Long.MIN_VALUE
        val now = System.currentTimeMillis()
        val resolvedDynamicDrawable = if (overrideIcon == null) {
            spec?.newDrawable(this.context, now)
        } else {
            null
        }
        val nextDrawable = resolvedDynamicDrawable
            ?: independentLauncherDrawable(source, resources)
        // A provider can disappear during a catalog refresh. Keep this View on the normal icon
        // and allow a later catalog refresh to retry the dynamic definition.
        boundSpec = spec?.takeIf { resolvedDynamicDrawable != null || it.supportsAsyncLoading }
        dynamicDrawable = resolvedDynamicDrawable
        if (boundSpec != null && dynamicDrawable != null) {
            dynamicStateKey = boundSpec!!.stateKey(now)
        }
        setImageDrawable(nextDrawable)
        syncTickerRegistration()
    }

    internal fun refreshDynamicIcon(atMillis: Long, forceRefresh: Boolean = false) {
        val spec = boundSpec ?: return
        if (spec is SamsungDynamicIconSpec) {
            refreshSamsungIcon(spec, atMillis, forceRefresh)
            return
        }
        val current = dynamicDrawable ?: return
        val nextStateKey = spec.stateKey(atMillis)
        if (nextStateKey != dynamicStateKey) {
            val replacement = spec.newDrawable(context, atMillis)
            if (replacement != null) {
                dynamicDrawable = replacement
                dynamicStateKey = nextStateKey
                setImageDrawable(replacement)
                return
            }
            // Keep the last known good icon if a provider becomes unavailable mid-session. Leave
            // the old key in place so the next tick retries the replacement.
        }
        spec.updateDrawable(context, current, atMillis)
        invalidate()
    }

    private fun refreshSamsungIcon(
        spec: SamsungDynamicIconSpec,
        atMillis: Long,
        forceRefresh: Boolean,
    ) {
        if (!isTickerEligible()) return
        val key = spec.stateKey(atMillis)
        if (!forceRefresh && (dynamicStateKey == key || requestedStateKey == key)) return
        cancelPendingIconLoad()
        val generation = loadGeneration
        requestedStateKey = key
        val scope = loadScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            .also { loadScope = it }
        loadJob = scope.launch {
            try {
                val loaded = spec.loadLatestDrawable(context.applicationContext)
                if (!isActive || generation != loadGeneration || boundSpec !== spec ||
                    !isTickerEligible()
                ) return@launch
                // Even a null result is attempted only once per minute. Keep the normal/last
                // known icon, and retry on the next minute or explicit lifecycle/time refresh.
                dynamicStateKey = key
                if (loaded != null) {
                    val independent = independentLauncherDrawable(loaded, resources)
                    dynamicDrawable = independent
                    setImageDrawable(independent)
                }
            } finally {
                if (generation == loadGeneration) {
                    requestedStateKey = null
                    loadJob = null
                }
            }
        }
    }

    internal fun cancelPendingIconLoad() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        requestedStateKey = null
    }

    internal fun hasSecondHandForTicker(): Boolean = boundSpec?.hasSecondHand == true

    internal fun isTickerEligible(): Boolean {
        if (boundSpec == null || !isAttachedToWindow || visibility != VISIBLE ||
            windowVisibility != VISIBLE
        ) {
            return false
        }
        if (!isShown) return false
        val owner = lifecycleOwner
        if (owner != null && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return false
        }
        if (width <= 0 || height <= 0) return true
        val visibleRect = Rect()
        return getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty
    }

    private fun syncTickerRegistration() {
        if (isTickerEligible()) {
            DynamicIconTicker.register(this)
        } else {
            DynamicIconTicker.unregister(this)
        }
    }

    private fun attachLifecycleOwner() {
        val owner = findViewTreeLifecycleOwner()
        if (owner === lifecycleOwner) return
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = owner
        owner?.lifecycle?.addObserver(lifecycleObserver)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        viewTreeObserver.addOnScrollChangedListener(scrollChangedListener)
        attachLifecycleOwner()
        syncTickerRegistration()
    }

    override fun onDetachedFromWindow() {
        DynamicIconTicker.unregister(this)
        loadScope?.cancel()
        loadScope = null
        runCatching { viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener) }
        runCatching { viewTreeObserver.removeOnScrollChangedListener(scrollChangedListener) }
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Ancestor visibility changes also arrive here, which lets a folder/grid re-register an
        // icon when it becomes visible again after a clipped scroll or transition.
        syncTickerRegistration()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncTickerRegistration()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        syncTickerRegistration()
    }
}

internal fun dynamicIconTickIntervalMillis(
    hasVisibleDynamicIcons: Boolean,
    hasVisibleSecondHand: Boolean,
): Long? = when {
    !hasVisibleDynamicIcons -> null
    hasVisibleSecondHand -> DynamicIconSecondTickMillis
    else -> DynamicIconMinuteTickMillis
}

/** Main-thread registry for visible dynamic icon Views. */
internal object DynamicIconTicker {
    private val handler = Handler(Looper.getMainLooper())
    private val views = Collections.newSetFromMap(
        IdentityHashMap<DynamicIconImageView, Boolean>(),
    )
    private var scheduledUptime = 0L
    private var context: Context? = null
    private var receiverRegistered = false
    private var screenInteractive = true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    views.forEach(DynamicIconImageView::cancelPendingIconLoad)
                    cancelTick()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = isScreenInteractive(context)
                    refreshVisible(System.currentTimeMillis(), forceRefresh = true)
                    scheduleTick()
                }
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED -> {
                    refreshVisible(System.currentTimeMillis(), forceRefresh = true)
                    scheduleTick()
                }
            }
        }
    }

    fun register(view: DynamicIconImageView) {
        if (!view.isTickerEligible()) return
        val added = views.add(view)
        if (context == null) {
            context = view.context.applicationContext
            screenInteractive = isScreenInteractive(context)
            registerReceiver(context!!)
        }
        // Re-attachment, lifecycle resume, and scrolling back from a clipped region should show
        // the current date/time immediately instead of waiting for the next wall-clock boundary.
        if (screenInteractive && (added || scheduledUptime == 0L)) {
            view.refreshDynamicIcon(System.currentTimeMillis(), forceRefresh = true)
        }
        scheduleTick()
    }

    fun unregister(view: DynamicIconImageView) {
        view.cancelPendingIconLoad()
        if (!views.remove(view)) return
        if (views.isEmpty()) stop()
        else scheduleTick()
    }

    private fun refreshVisible(atMillis: Long, forceRefresh: Boolean = false) {
        if (!screenInteractive) return
        views.toList().forEach { view ->
            // Keep clipped views in the registry. A scroll callback can then re-register them
            // when they become visible again after the ticker has gone idle.
            if (view.isTickerEligible()) view.refreshDynamicIcon(atMillis, forceRefresh)
            else view.cancelPendingIconLoad()
        }
        if (views.isEmpty()) stop()
    }

    private fun scheduleTick() {
        if (!screenInteractive || views.isEmpty()) return
        val hasSecondHand = views.any {
            it.isTickerEligible() && it.hasSecondHandForTicker()
        }
        val interval = dynamicIconTickIntervalMillis(
            hasVisibleDynamicIcons = views.any(DynamicIconImageView::isTickerEligible),
            hasVisibleSecondHand = hasSecondHand,
        ) ?: run {
            stop()
            return
        }
        val nowWall = System.currentTimeMillis()
        val remainder = nowWall % interval
        val delay = (interval - remainder).coerceAtLeast(1L)
        val target = SystemClock.uptimeMillis() + delay
        if (scheduledUptime == target) return
        cancelTick()
        scheduledUptime = target
        handler.postAtTime(tickRunnable, target)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            scheduledUptime = 0L
            if (!screenInteractive) return
            refreshVisible(System.currentTimeMillis())
            scheduleTick()
        }
    }

    private fun cancelTick() {
        if (scheduledUptime != 0L) {
            handler.removeCallbacks(tickRunnable)
            scheduledUptime = 0L
        }
    }

    private fun stop() {
        cancelTick()
        if (receiverRegistered) {
            context?.let { appContext ->
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            receiverRegistered = false
        }
        context = null
        screenInteractive = true
    }

    private fun registerReceiver(appContext: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (exception: Exception) {
            rethrowCancellation(exception)
        }
    }

    private fun isScreenInteractive(appContext: Context?): Boolean =
        appContext?.getSystemService(PowerManager::class.java)?.isInteractive ?: true
}

/** Fetches activity metadata and follows an activity-alias target when needed. */
internal fun activityInfoWithMetadata(
    packageManager: PackageManager,
    activityInfo: ActivityInfo,
): ActivityInfo {
    val direct = getActivityInfoWithMetadata(
        packageManager,
        ComponentName(activityInfo.packageName, activityInfo.name),
    ) ?: activityInfo
    val hasCalendarMetadata = direct.metaData?.containsKey(
        direct.packageName + DynamicCalendarMetadataSuffix,
    ) == true
    if (hasCalendarMetadata || direct.targetActivity.isNullOrBlank()) return direct
    return getActivityInfoWithMetadata(
        packageManager,
        ComponentName(direct.packageName, direct.targetActivity),
    ) ?: direct
}

private fun getActivityInfoWithMetadata(
    packageManager: PackageManager,
    componentName: ComponentName,
): ActivityInfo? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getActivityInfo(
            componentName,
            PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA)
    }
} catch (exception: Exception) {
    rethrowCancellation(exception)
    null
}

private fun applicationInfoWithMetadata(
    packageManager: PackageManager,
    activityInfo: ActivityInfo,
): ApplicationInfo? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(
            activityInfo.packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(activityInfo.packageName, PackageManager.GET_META_DATA)
    }
} catch (exception: Exception) {
    rethrowCancellation(exception)
    null
}
