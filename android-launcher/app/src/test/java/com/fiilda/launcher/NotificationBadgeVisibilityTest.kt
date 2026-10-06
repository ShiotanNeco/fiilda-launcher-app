package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBadgeVisibilityTest {
    @Test
    fun missingPreferenceDefaultsToVisible() {
        assertEquals(DefaultShowNotificationBadges, showNotificationBadgesFromStoredValue(null))
    }

    @Test
    fun storedPreferenceIsPreserved() {
        assertTrue(showNotificationBadgesFromStoredValue(true))
        assertFalse(showNotificationBadgesFromStoredValue(false))
    }

    @Test
    fun renderingOnlyUsesBadgesForEnabledNonLiveTilesWithNotifications() {
        assertTrue(
            shouldRenderNotificationBadge(
                showNotificationBadges = true,
                notificationCount = 1,
                isLiveTile = false,
            ),
        )
        assertFalse(
            shouldRenderNotificationBadge(
                showNotificationBadges = false,
                notificationCount = 1,
                isLiveTile = false,
            ),
        )
        assertFalse(
            shouldRenderNotificationBadge(
                showNotificationBadges = true,
                notificationCount = 0,
                isLiveTile = false,
            ),
        )
        assertFalse(
            shouldRenderNotificationBadge(
                showNotificationBadges = true,
                notificationCount = 1,
                isLiveTile = true,
            ),
        )
    }

    @Test
    fun badgeTextSizeFitsFixedSquareAtLargeFontScales() {
        assertEquals(9f, notificationBadgeTextSizeSp("9", fontScale = 1f), 0f)
        assertEquals(8f, notificationBadgeTextSizeSp("99+", fontScale = 1f), 0f)
        assertEquals(9f, notificationBadgeTextSizeSp("9", fontScale = 1.7f) * 1.7f, 0.01f)
        assertEquals(8f, notificationBadgeTextSizeSp("99+", fontScale = 2f) * 2f, 0.01f)
        assertEquals(9f, notificationBadgeTextSizeSp("9", fontScale = 0f), 0f)
    }

    @Test
    fun preferenceKeyIsStableAndUsesExistingPreferenceStore() {
        assertEquals("show_notification_badges", ShowNotificationBadgesPreferenceKey)
        assertEquals("fiilda_preferences", LauncherThemePreferencesName)
    }
}
