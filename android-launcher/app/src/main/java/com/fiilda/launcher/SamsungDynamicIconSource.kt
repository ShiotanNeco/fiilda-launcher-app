package com.fiilda.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

/**
 * Identifies the two Samsung system applications whose stock icons may be live on One UI.
 *
 * The package names are deliberately part of this closed set. Samsung does not publish a
 * general third-party live-icon contract, so this source must never become a generic icon
 * replacement path.
 */
internal class SamsungDynamicIconSource internal constructor(
    val target: Target,
    private val loader: suspend (Context, Target, Int) -> Drawable? =
        { context, target, density -> loadFromLauncherApps(context, target, density) },
) {
    internal enum class Kind(val packageName: String) {
        CLOCK("com.sec.android.app.clockpackage"),
        CALENDAR("com.samsung.android.calendar"),
    }

    /**
     * A fully resolved Samsung component. [packageName] is retained separately so the caller
     * cannot accidentally query all launchable activities when the component is known.
     */
    internal data class Target(
        val kind: Kind,
        val packageName: String,
        val component: ComponentName,
    )

    /**
     * Loads one vendor-provided icon snapshot on an IO dispatcher.
     *
     * Samsung's framework may add live-icon behavior behind the public LauncherActivityInfo
     * method. AOSP itself only promises an activity drawable, so a null result is a normal
     * unsupported/unavailable outcome and must leave the caller's ordinary icon untouched.
     */
    internal suspend fun load(context: Context, density: Int): Drawable? {
        if (density < 0 || !target.isValid()) return null
        return withContext(Dispatchers.IO) {
            try {
                loader(context, target, density)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        /**
         * Returns a target only for Samsung firmware and the two exact stock package names.
         * The activity class is supplied by the launcher catalog; this method never guesses it.
         */
        internal fun targetFor(
            manufacturer: String,
            component: ComponentName,
        ): Target? {
            if (!manufacturer.equals("samsung", ignoreCase = true)) return null
            val kind = Kind.entries.firstOrNull { it.packageName == component.packageName }
                ?: return null
            if (component.className.isBlank()) return null
            return Target(
                kind = kind,
                packageName = kind.packageName,
                component = component,
            )
        }
    }

    private fun Target.isValid(): Boolean =
        packageName == kind.packageName &&
            component.packageName == packageName &&
            component.className.isNotBlank()
}

private suspend fun loadFromLauncherApps(
    context: Context,
    target: SamsungDynamicIconSource.Target,
    density: Int,
): Drawable? {
    val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
    val activities = launcherApps.getActivityList(target.packageName, Process.myUserHandle())
    return activities.firstOrNull { activity ->
        activity.componentName == target.component
    }?.getIcon(density)
}
