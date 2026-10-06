package com.fiilda.launcher

import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarterAppsTest {
    private fun app(pkg: String, cls: String = "$pkg.Main") = LaunchableApp(pkg, cls, pkg, ColorDrawable(0), 0, 0)

    private val catalog = listOf(
        app("com.example.alpha"),
        app("com.google.android.dialer"),
        app("com.google.android.apps.messaging"),
        app("com.android.chrome"),
        app("com.android.chrome", "com.android.chrome.Incognito"),
        app("com.google.android.GoogleCamera"),
        app("com.example.zeta"),
    )

    @Test
    fun picksDefaultAppsInRoleOrderOncePerPackage() {
        val picked = pickStarterApps(
            catalog,
            listOf(
                "com.google.android.dialer",
                "com.google.android.apps.messaging",
                "com.android.chrome",
                "com.google.android.GoogleCamera",
                null,
                "com.android.chrome",
                "com.not.installed",
            ),
        )
        assertEquals(
            listOf("com.google.android.dialer", "com.google.android.apps.messaging", "com.android.chrome", "com.google.android.GoogleCamera"),
            picked.map { it.packageName },
        )
        assertEquals("com.android.chrome.Main", picked[2].className)
    }

    @Test
    fun topsUpFromTheCatalogWhenTooFewDefaultsExist() {
        val picked = pickStarterApps(catalog, listOf("com.android.chrome", null))
        assertEquals(StarterAppMinimum, picked.size)
        assertEquals("com.android.chrome", picked.first().packageName)
        assertEquals(listOf("com.example.alpha", "com.google.android.dialer", "com.google.android.apps.messaging"), picked.drop(1).map { it.packageName })
    }

    @Test
    fun neverExceedsTheLimit() {
        val many = (1..12).map { app("com.example.app$it") }
        assertEquals(StarterAppLimit, pickStarterApps(many, many.map { it.packageName }).size)
    }

    @Test
    fun freshHomeStartsWithLiveDataWidgets() {
        assertEquals(
            listOf("widget:clock", "widget:weather", "widget:agenda", "widget:calendar", "widget:battery", "widget:media"),
            DefaultHomeOrder,
        )
    }
}

class OnboardingDecisionTest {
    @Test
    fun freshInstallsSeeTheTutorialEvenAfterTheFirstSave() {
        assertEquals(true, onboardingIsForFreshInstall(neverUpdated = true, hasSavedLayout = true))
        assertEquals(true, onboardingIsForFreshInstall(neverUpdated = false, hasSavedLayout = false))
    }

    @Test
    fun updatesFromAnOlderVersionSkipIt() {
        assertEquals(false, onboardingIsForFreshInstall(neverUpdated = false, hasSavedLayout = true))
    }
}
