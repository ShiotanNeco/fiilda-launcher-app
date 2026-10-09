package com.fiilda.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowUserManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ProfileLauncherAppsShadow::class, ProfileActivityShadow::class])
class WorkProfileAppsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val userManager get() = context.getSystemService(UserManager::class.java)
    private val launcherApps get() = context.getSystemService(LauncherApps::class.java)
    private val workUser = UserHandle.getUserHandleForUid(1_000_000)
    private val component = ComponentName("com.example.mail", "com.example.mail.Main")

    @Before
    fun setup() {
        ProfileLauncherAppsShadow.profiles = listOf(Process.myUserHandle(), workUser)
        ProfileLauncherAppsShadow.launch = null
        ProfileLauncherAppsShadow.details = null
        ProfileLauncherAppsShadow.shortcutUser = null
        ProfileLauncherAppsShadow.rejectLaunch = false
        shadowOf(userManager).addProfile(0, 10, "Work", ShadowUserManager.FLAG_MANAGED_PROFILE)
        shadowOf(userManager).setSerialNumberForUser(workUser, 42L)
        shadowOf(userManager).setUserState(workUser, ShadowUserManager.UserState.STATE_RUNNING_UNLOCKED)
    }

    @Test
    fun catalogKeepsPersonalAndWorkCopiesOfTheSameActivity() = runTest {
        val appInfo = ApplicationInfo().apply { packageName = component.packageName }
        val resolved = ResolveInfo().apply {
            nonLocalizedLabel = "Mail"
            activityInfo = ActivityInfo().apply {
                packageName = component.packageName
                name = component.className
                applicationInfo = appInfo
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), resolved,
        )
        val activity = ReflectionHelpers.newInstance(LauncherActivityInfo::class.java)
        Shadow.extract<ProfileActivityShadow>(activity).component = component
        shadowOf(launcherApps).addActivity(workUser, activity)

        val copies = queryLaunchableApps(context).filter { it.packageName == component.packageName }

        assertEquals(2, copies.size)
        assertEquals(setOf("com.example.mail/com.example.mail.Main", "com.example.mail@42/com.example.mail.Main"), copies.map(::favoriteId).toSet())
        val work = copies.single { it.profile != null }
        assertEquals(workUser, work.launchUser())
        assertEquals(42L, work.profile!!.serialNumber)
        assertEquals(ProfileActivityShadow.badgedColor, (work.icon as ColorDrawable).color)
        assertEquals(favoriteId(work), HomeItem.App(work).id)
    }

    @Test
    fun workLaunchAndAppInfoTargetWorkUserWithoutStartingPersonalActivity() {
        val work = app(workUser)
        assertEquals(AppLaunchResult.STARTED, launchApp(context, work))
        assertEquals(component to workUser, ProfileLauncherAppsShadow.launch)
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(AppLaunchResult.STARTED, openAppDetails(context, work))
        assertEquals(component to workUser, ProfileLauncherAppsShadow.details)
        assertFalse(canUninstallApp(context, work))
    }

    @Test
    fun deniedWorkLaunchDoesNotFallBackToPersonalCopy() {
        ProfileLauncherAppsShadow.rejectLaunch = true
        assertEquals(AppLaunchResult.SECURITY_DENIED, launchApp(context, app(workUser)))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun personalLaunchKeepsExistingIntentPathAndFavoriteId() {
        val personal = app(null)
        assertEquals(AppLaunchResult.STARTED, launchApp(context, personal))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(component, started.component)
        // Apps with strict intent matching only accept the launcher's MAIN/LAUNCHER intent.
        assertEquals(Intent.ACTION_MAIN, started.action)
        assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertNull(ProfileLauncherAppsShadow.launch)
        assertEquals("com.example.mail/com.example.mail.Main", favoriteId(personal))
    }

    @Test
    fun shortcutsUseTheAppProfileForQueryAndLaunch() = runTest {
        val work = app(workUser)
        shadowOf(launcherApps).setHasShortcutHostPermission(true)
        queryAppShortcuts(context, listOf(work))
        assertEquals(workUser, ProfileLauncherAppsShadow.shortcutUser)
        ProfileLauncherAppsShadow.shortcutUser = null
        launchShortcut(context, work, ResolvedLauncherShortcut("compose", "Compose", null))
        assertEquals(workUser, ProfileLauncherAppsShadow.shortcutUser)
    }

    @Test
    fun pausedLockedAndUnavailableProfilesKeepStoredFavorites() {
        userManager.requestQuietModeEnabled(true, workUser)
        assertTrue(isFavoritePackageInstalled(context, "com.example.mail@42"))
        userManager.requestQuietModeEnabled(false, workUser)
        shadowOf(userManager).setUserState(workUser, ShadowUserManager.UserState.STATE_RUNNING_LOCKED)
        assertTrue(isFavoritePackageInstalled(context, "com.example.mail@42"))
    }

    @Test
    fun removedProfileDropsItsFavorites() {
        // Serial 999 belongs to no profile on the device, as after the work profile is deleted.
        assertFalse(isFavoritePackageInstalled(context, "com.example.mail@999"))
        val result = reconcileStoredFavorites(
            storedFavorites = listOf("com.example.mail@999/Main", "com.example.mail@42/Main"),
            installedAppIds = emptyList(),
            isPackageInstalled = { isFavoritePackageInstalled(context, it) },
        )
        assertEquals(listOf("com.example.mail@42/Main"), result.favoriteIds)
        assertEquals(setOf("com.example.mail@999/Main"), result.removedIds)
    }

    @Test
    fun unavailableWorkPackageLookupDoesNotDiscardItsFavorite() {
        shadowOf(launcherApps).addApplicationInfo(workUser, component.packageName, ApplicationInfo())
        assertTrue(isFavoritePackageInstalled(context, "com.example.mail@42"))
        assertTrue(isFavoritePackageInstalled(context, "com.example.absent@42"))
    }

    private fun app(user: UserHandle?) = LaunchableApp(
        packageName = component.packageName,
        className = component.className,
        label = "Mail",
        icon = ColorDrawable(0),
        tileColorArgb = 0,
        tileContentColorArgb = 0,
        profile = user?.let { LauncherAppProfile(it, 42L) },
    )
}

@Implements(LauncherApps::class)
class ProfileLauncherAppsShadow : ShadowLauncherApps() {
    companion object {
        var profiles = emptyList<UserHandle>()
        var launch: Pair<ComponentName, UserHandle>? = null
        var details: Pair<ComponentName, UserHandle>? = null
        var shortcutUser: UserHandle? = null
        var rejectLaunch = false
    }

    @Implementation
    fun getProfiles(): List<UserHandle> = profiles

    @Implementation
    fun startMainActivity(component: ComponentName, user: UserHandle, bounds: Rect?, options: Bundle?) {
        if (rejectLaunch) throw SecurityException("work profile denied")
        launch = component to user
    }

    @Implementation
    override fun startAppDetailsActivity(component: ComponentName, user: UserHandle, bounds: Rect?, options: Bundle?) {
        details = component to user
    }

    @Implementation
    override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo> {
        shortcutUser = user
        return emptyList()
    }

    @Implementation
    override fun startShortcut(packageName: String, id: String, bounds: Rect?, options: Bundle?, user: UserHandle) {
        shortcutUser = user
    }
}

@Implements(LauncherActivityInfo::class)
class ProfileActivityShadow {
    companion object { const val badgedColor = -0xff0100 }
    lateinit var component: ComponentName
    @Implementation fun getComponentName(): ComponentName = component
    @Implementation fun getLabel(): CharSequence = "Mail"
    @Implementation fun getBadgedIcon(density: Int): Drawable = ColorDrawable(badgedColor)
}
