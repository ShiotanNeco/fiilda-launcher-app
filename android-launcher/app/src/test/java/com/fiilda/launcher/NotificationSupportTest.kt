package com.fiilda.launcher

import android.app.ActivityOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSupportTest {
    @Test
    fun samePackageNotificationsStayInTheirOwnProfile() {
        val personal = ActiveNotificationSnapshot(NotificationMetadata("personal", "mail"))
        val work = ActiveNotificationSnapshot(NotificationMetadata("work", "mail"), profileUserId = 10)
        val projected = projectFavoriteNotifications(listOf(personal, work), setOf("mail", "mail@10"))
        assertEquals(listOf(personal), projected["mail"])
        assertEquals(listOf(work), projected["mail@10"])
        assertEquals(mapOf("mail@10" to listOf(work)), projectFavoriteNotifications(listOf(personal, work), setOf("mail@10")))
    }

    @Test
    fun filtersSummariesMediaAndOngoingNotifications() {
        val selected = selectProjectableNotifications(
            listOf(
                NotificationMetadata("normal", "mail", timestamp = 4),
                NotificationMetadata("summary", "mail", timestamp = 5, isGroupSummary = true),
                NotificationMetadata("media", "music", timestamp = 6, isMediaTransport = true),
                NotificationMetadata("ongoing", "sync", timestamp = 7, isOngoing = true),
                NotificationMetadata("", "mail", timestamp = 8),
            ),
        )

        assertEquals(listOf("normal"), selected.map { it.key })
    }

    @Test
    fun duplicateKeysKeepNewestUpdateAndSortNewestFirst() {
        val selected = selectProjectableNotifications(
            listOf(
                NotificationMetadata("old", "mail", body = "old", timestamp = 10),
                NotificationMetadata("newer", "mail", timestamp = 30),
                NotificationMetadata("old", "mail", body = "updated", timestamp = 20),
                NotificationMetadata("same-time", "mail", timestamp = 20),
            ),
        )

        assertEquals(listOf("newer", "old", "same-time"), selected.map { it.key })
        assertEquals("updated", selected[1].body)
    }

    @Test
    fun projectionKeepsOnlyFavoritePackagesAndPerPackageOrder() {
        val projected = projectFavoriteNotificationMetadata(
            notifications = listOf(
                NotificationMetadata("mail-old", "mail", timestamp = 1),
                NotificationMetadata("chat", "chat", timestamp = 4),
                NotificationMetadata("mail-new", "mail", timestamp = 8),
                NotificationMetadata("other", "other", timestamp = 20),
            ),
            favoritePackages = setOf("mail", "chat"),
        )

        assertEquals(setOf("mail", "chat"), projected.keys)
        assertEquals(listOf("mail-new", "mail-old"), projected.getValue("mail").map { it.key })
        assertEquals(listOf("chat"), projected.getValue("chat").map { it.key })
    }

    @Test
    fun snapshotProjectionAlsoDeduplicatesUpdatedKeys() {
        val projected = projectFavoriteNotifications(
            notifications = listOf(
                ActiveNotificationSnapshot(
                    NotificationMetadata("same", "mail", body = "old", timestamp = 1),
                ),
                ActiveNotificationSnapshot(
                    NotificationMetadata("same", "mail", body = "new", timestamp = 2),
                ),
            ),
            favoritePackages = setOf("mail"),
        )

        assertEquals(1, projected.getValue("mail").size)
        assertEquals("new", projected.getValue("mail").single().body)
    }

    @Test
    fun autoCancelIsRequestedOnlyAfterContentIntentSendSucceeds() {
        val snapshot = ActiveNotificationSnapshot(
            metadata = NotificationMetadata(
                key = "auto",
                packageName = "mail",
                isAutoCancel = true,
            ),
        )
        val events = mutableListOf<String>()

        assertTrue(
            deliverNotificationContentIntent(
                snapshot = snapshot,
                sendIntent = { events += "send" },
                cancelNotification = {
                    assertEquals(listOf("send"), events)
                    events += "cancel"
                },
            ),
        )
        assertTrue(snapshot.isAutoCancel)
        assertEquals(listOf("send", "cancel"), events)
    }

    @Test
    fun nonAutoCancelAndFailedContentIntentKeepNotification() {
        val normal = ActiveNotificationSnapshot(
            metadata = NotificationMetadata(
                key = "normal",
                packageName = "mail",
            ),
        )
        var normalCancelCount = 0
        assertTrue(
            deliverNotificationContentIntent(
                snapshot = normal,
                sendIntent = {},
                cancelNotification = { normalCancelCount++ },
            ),
        )
        assertEquals(0, normalCancelCount)

        val failed = ActiveNotificationSnapshot(
            metadata = NotificationMetadata(
                key = "failed",
                packageName = "mail",
                isAutoCancel = true,
            ),
        )
        var failedCancelCount = 0
        assertFalse(
            deliverNotificationContentIntent(
                snapshot = failed,
                sendIntent = { error("expired pending intent") },
                cancelNotification = { failedCancelCount++ },
            ),
        )
        assertEquals(0, failedCancelCount)
    }

    @Test
    fun cancellationFailureDoesNotMakeSuccessfulContentIntentLookFailed() {
        val snapshot = ActiveNotificationSnapshot(
            metadata = NotificationMetadata(
                key = "auto",
                packageName = "mail",
                isAutoCancel = true,
            ),
        )

        assertTrue(
            deliverNotificationContentIntent(
                snapshot = snapshot,
                sendIntent = {},
                cancelNotification = { error("listener disconnected") },
            ),
        )
    }

    @Test
    fun missingContentIntentDoesNotCancelNotification() {
        val snapshot = ActiveNotificationSnapshot(
            metadata = NotificationMetadata(
                key = "missing",
                packageName = "mail",
                isAutoCancel = true,
            ),
        )

        assertFalse(sendNotificationContentIntent(snapshot))
    }

    @Test
    @Suppress("DEPRECATION")
    fun activityPendingIntentBalOptInMatchesAndroidApiBoundary() {
        assertNull(
            notificationContentIntentBackgroundStartModeForSdk(
                sdkInt = 33,
                isActivity = true,
            ),
        )
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            notificationContentIntentBackgroundStartModeForSdk(
                sdkInt = 34,
                isActivity = true,
            ),
        )
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            notificationContentIntentBackgroundStartModeForSdk(
                sdkInt = 35,
                isActivity = true,
            ),
        )
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE,
            notificationContentIntentBackgroundStartModeForSdk(
                sdkInt = 36,
                isActivity = true,
            ),
        )
    }

    @Test
    fun nonActivityPendingIntentKeepsLegacySendPathAtEveryApiLevel() {
        listOf(29, 33, 34, 35, 36).forEach { sdkInt ->
            assertNull(
                notificationContentIntentBackgroundStartModeForSdk(
                    sdkInt = sdkInt,
                    isActivity = false,
                ),
            )
        }
    }

    @Test
    fun capacitiesAndOverflowMatchSupportedTileFootprints() {
        assertEquals(0, notificationTileCapacity(AppTileSize.SMALL))
        assertEquals(1, notificationTileCapacity(AppTileSize.WIDE))
        assertEquals(1, notificationTileCapacity(AppTileSize.TALL))
        assertEquals(2, notificationTileCapacity(AppTileSize.LARGE))
        assertEquals(2, notificationTileCapacity(AppTileSize.TALL_3X1))
        assertEquals(3, notificationTileCapacity(AppTileSize.TALL_3X2))
        assertEquals(3, notificationTileCapacityForFontScale(AppTileSize.TALL_3X2, 1f))
        assertEquals(2, notificationTileCapacityForFontScale(AppTileSize.TALL_3X2, 1.3f))
        assertEquals(1, notificationTileCapacityForFontScale(AppTileSize.TALL_3X2, 1.7f))
        assertNull(notificationOverflowLabel(totalCount = 2, capacity = 2))
        assertEquals("ほか2件", notificationOverflowLabel(totalCount = 5, capacity = 3))
        assertNull(notificationOverflowLabel(totalCount = 3, capacity = 0))
    }

    @Test
    fun nonDefaultContentModesRoundTripAndPruneTogether() {
        val modes = mapOf(
            "z/app" to AppTileContentMode.NOTIFICATIONS,
            "a/app" to AppTileContentMode.APP_ONLY,
            "default/app" to AppTileContentMode.SHORTCUTS,
        )

        val serialized = serializeAppTileContentModes(modes)

        assertEquals("a/app\tAPP_ONLY\nz/app\tNOTIFICATIONS", serialized)
        assertEquals(modes - "default/app", parseAppTileContentModes(serialized))
        assertEquals(
            modes - "default/app",
            pruneAppTileContentModes(modes, setOf("a/app", "z/app")),
        )
    }

    @Test
    fun malformedRowsDoNotCreateNotificationModes() {
        val parsed = parseAppTileContentModes(
            "kept/app\tNOTIFICATIONS\n" +
                "broken\n" +
                "default/app\tSHORTCUTS\n" +
                "bad/app\tUNKNOWN",
        )

        assertEquals(
            mapOf("kept/app" to AppTileContentMode.NOTIFICATIONS),
            parsed,
        )
        assertTrue(parsed.values.all { it != AppTileContentMode.SHORTCUTS })
    }
}
