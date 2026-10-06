package com.fiilda.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony

/** Number of apps placed on Home on a fresh install. */
internal const val StarterAppLimit = 8

/** Below this many default apps, the starter set is topped up from the sorted catalog. */
internal const val StarterAppMinimum = 4

/**
 * Picks the fresh-install Home apps: the device's own default apps in [rolePackages] order (each
 * at most once, first launcher activity of the package), then — only when too few were found —
 * the catalog order. Works the same in every country because it follows the device's settings.
 */
internal fun pickStarterApps(
    apps: List<LaunchableApp>,
    rolePackages: List<String?>,
    limit: Int = StarterAppLimit,
    minimum: Int = StarterAppMinimum,
): List<LaunchableApp> {
    val personalApps = apps.filter { it.profile == null }
    val byPackage = personalApps.groupBy { it.packageName }
    val picked = rolePackages
        .filterNotNull()
        .distinct()
        .mapNotNull { byPackage[it]?.firstOrNull() }
        .take(limit)
    if (picked.size >= minimum) return picked
    return (picked + personalApps.filterNot { it in picked }).take(minimum)
}

/** Default handler packages for phone, messages, browser, camera, gallery, email, maps, calendar. */
internal fun starterRolePackages(context: Context): List<String?> {
    val packageManager = context.packageManager
    fun defaultFor(intent: Intent): String? = runCatching {
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            // "android" is the chooser shown when no default is set; fall back to the first match.
            ?.takeUnless { it == "android" }
            ?: packageManager.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
    }.getOrNull()
    fun category(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
    return listOf(
        defaultFor(Intent(Intent.ACTION_DIAL)),
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            ?: defaultFor(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))),
        defaultFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
        defaultFor(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        defaultFor(category(Intent.CATEGORY_APP_GALLERY)),
        defaultFor(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))),
        defaultFor(category(Intent.CATEGORY_APP_MAPS)),
        defaultFor(category(Intent.CATEGORY_APP_CALENDAR)),
    )
}
