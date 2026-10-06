package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinShortcutSupportTest {
    @Test
    fun recordsRoundTripOpaqueShortcutIdsAndLabels() {
        val original = listOf(
            PinnedShortcutRecord(
                instanceId = "pin-1",
                packageName = "com.android.chrome",
                shortcutId = "site/id with spaces\tand punctuation",
                userSerial = 12L,
                label = "ニュース\n朝刊",
                longLabel = "ニュースサイトの朝刊",
            ),
        )

        assertEquals(original, parsePinnedShortcutRecords(serializePinnedShortcutRecords(original)))
    }

    @Test
    fun samePlatformShortcutCanHaveTwoIndependentHomeInstances() {
        val records = listOf(
            PinnedShortcutRecord("first", "com.example", "site", 0L, "Site"),
            PinnedShortcutRecord("second", "com.example", "site", 0L, "Site"),
        )

        val parsed = parsePinnedShortcutRecords(serializePinnedShortcutRecords(records))

        assertEquals(listOf("shortcut:pin:first", "shortcut:pin:second"), parsed.map { it.homeId })
        assertEquals(2, parsed.size)
    }

    @Test
    fun acceptedRecordRetryIsIdempotentButMismatchedInstanceIsRejected() {
        val record = PinnedShortcutRecord("same-instance", "com.example", "site", 0L, "Site")
        val changed = record.copy(label = "Changed")

        assertEquals(
            PinnedShortcutRecordMergeDecision.IDEMPOTENT_REPLAY,
            pinnedShortcutRecordMergeDecision(listOf(record), record),
        )
        assertEquals(
            PinnedShortcutRecordMergeDecision.INSTANCE_COLLISION,
            pinnedShortcutRecordMergeDecision(listOf(record), changed),
        )
        assertEquals(
            PinnedShortcutRecordMergeDecision.ADD,
            pinnedShortcutRecordMergeDecision(emptyList(), record),
        )
    }

    @Test
    fun canceledOrFailedAcceptanceNeverPersistsLocally() {
        assertFalse(shouldPersistAcceptedPin(PinShortcutRequestState.READY, acceptResult = false))
        assertFalse(shouldPersistAcceptedPin(PinShortcutRequestState.INVALID, acceptResult = true))
        assertTrue(shouldPersistAcceptedPin(PinShortcutRequestState.READY, acceptResult = true))
    }

    @Test
    fun requestValidationRequiresShortcutTypeIdentityProfileAndLabel() {
        assertEquals(
            PinShortcutRequestState.READY,
            pinShortcutRequestState(1, true, "com.example", "site", 0L, "Site"),
        )
        assertEquals(
            PinShortcutRequestState.READY,
            pinShortcutRequestState(1, true, "com.example", "   ", 0L, "Site"),
        )
        assertEquals(
            PinShortcutRequestState.INVALID,
            pinShortcutRequestState(2, true, "com.example", "site", 0L, "Site"),
        )
        assertEquals(
            PinShortcutRequestState.INVALID,
            pinShortcutRequestState(1, true, "com.example", "site", null, "Site"),
        )
        assertEquals(
            PinShortcutRequestState.INVALID,
            pinShortcutRequestState(1, true, "com.example", "site", 0L, " "),
        )
        assertTrue(
            pinnedShortcutRecordIsValid(
                PinnedShortcutRecord("pin-1", "com.example", "   ", 0L, "Site"),
            ),
        )
    }

    @Test
    fun acceptedPinPlacementUsesStableIdAndRequestedPage() {
        val record = PinnedShortcutRecord("pin-1", "com.example", "site", 0L, "Site")
        val layout = addHomeItemToLayout(
            HomeLayout(
                order = listOf("widget:clock"),
                narrowPageById = mapOf("widget:clock" to 0),
            ),
            id = record.homeId,
            page = 1,
        )

        assertEquals(listOf("widget:clock", record.homeId), layout.order)
        assertEquals(1, layout.pageOf(record.homeId))
        val moved = addHomeItemToLayout(layout, record.homeId, page = 0)
        assertEquals(layout.order, moved.order)
        assertEquals(0, moved.pageOf(record.homeId))
        assertEquals(1, moved.allIds.count { it == record.homeId })
    }

    @Test
    fun validV4RecoveryIgnoresStaleCompatibilityMirror() {
        val current = HomeLayout(
            order = listOf("widget:clock", "shortcut:pin:current"),
            narrowPageById = mapOf("widget:clock" to 0, "shortcut:pin:current" to 1),
        )
        val stalePages = HomePages(listOf(listOf("removed-from-home"), emptyList()))

        assertEquals(
            listOf("widget:clock", "shortcut:pin:current"),
            pinnedShortcutRecoveryIds(current, stalePages, listOf("removed-from-home")),
        )
    }

    @Test
    fun removingDuplicateKeepsPlatformCleanupQueueEmptyUntilLastLocalCopy() {
        val first = PinnedShortcutRecord("first", "com.example", "site", 7L, "Site")
        val second = first.copy(instanceId = "second")
        val identity = pendingPinnedShortcutUnpinFor(first)

        assertEquals(
            emptyList<PendingPinnedShortcutUnpin>(),
            pendingPinnedShortcutUnpinsAfterRemoval(
                currentRecords = listOf(first, second),
                currentPending = emptyList(),
                removedRecord = first,
            ),
        )
        assertEquals(
            listOf(identity),
            pendingPinnedShortcutUnpinsAfterRemoval(
                currentRecords = listOf(second),
                currentPending = emptyList(),
                removedRecord = second,
            ),
        )
    }

    @Test
    fun platformCleanupPreservesOtherPinnedIdsAndDuplicateCopy() {
        val plan = pinnedShortcutPlatformCleanupPlan(
            platformPinnedShortcutIds = listOf("site", "other", "site"),
            targetShortcutId = "site",
            hasRemainingLocalCopy = false,
        )
        assertTrue(plan.shouldUpdatePlatform)
        assertEquals(listOf("other"), plan.retainedShortcutIds)

        val duplicatePlan = pinnedShortcutPlatformCleanupPlan(
            platformPinnedShortcutIds = listOf("site", "other"),
            targetShortcutId = "site",
            hasRemainingLocalCopy = true,
        )
        assertFalse(duplicatePlan.shouldUpdatePlatform)
        assertEquals(listOf("site", "other"), duplicatePlan.retainedShortcutIds)
    }

    @Test
    fun cleanupIdentityIsScopedToPackageAndProfile() {
        val chromePersonal = PendingPinnedShortcutUnpin("com.android.chrome", "site", 0L)
        val chromeWork = chromePersonal.copy(userSerial = 10L)
        val otherPackage = chromePersonal.copy(packageName = "com.example.other")

        assertFalse(samePinnedShortcutPlatformIdentity(chromePersonal, chromeWork))
        assertFalse(samePinnedShortcutPlatformIdentity(chromePersonal, otherPackage))
    }

    @Test
    fun failedPlatformCleanupRetainsQueueForRetry() {
        val pending = PendingPinnedShortcutUnpin("com.example", "site", 0L)
        val queue = listOf(pending)

        assertEquals(
            queue,
            pendingPinnedShortcutUnpinsAfterAttempt(
                pending = queue,
                completed = pending,
                attempt = PinnedShortcutCleanupAttempt.RETRY,
            ),
        )
        assertEquals(
            emptyList<PendingPinnedShortcutUnpin>(),
            pendingPinnedShortcutUnpinsAfterAttempt(
                pending = queue,
                completed = pending,
                attempt = PinnedShortcutCleanupAttempt.COMPLETE,
            ),
        )
    }

    @Test
    fun disabledPinnedShortcutRemainsButIsUnavailable() {
        assertFalse(resolvedPinnedShortcutIsAvailable(true, true, false))
        assertFalse(resolvedPinnedShortcutIsAvailable(false, true, true))
        assertTrue(resolvedPinnedShortcutIsAvailable(true, true, true))
    }
}
