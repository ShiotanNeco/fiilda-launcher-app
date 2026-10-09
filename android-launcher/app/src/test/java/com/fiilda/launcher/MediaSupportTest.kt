package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSupportTest {
    @Test
    fun mediaPerimeterProgressStartsAtTopCenterAndTravelsClockwise() {
        val points = mediaPerimeterProgressPoints(
            width = 200f,
            height = 100f,
            inset = 3f,
            progress = 0.5f,
        )

        assertEquals(
            listOf(
                MediaPerimeterPoint(100f, 3f),
                MediaPerimeterPoint(197f, 3f),
                MediaPerimeterPoint(197f, 97f),
                MediaPerimeterPoint(100f, 97f),
            ),
            points,
        )
    }

    @Test
    fun mediaPerimeterProgressHasContinuousEndpointsAtZeroAndFullProgress() {
        assertTrue(mediaPerimeterProgressPoints(100f, 100f, 3f, 0f).isEmpty())

        val full = mediaPerimeterProgressPoints(100f, 100f, 3f, 1f)
        assertEquals(
            listOf(
                MediaPerimeterPoint(50f, 3f),
                MediaPerimeterPoint(97f, 3f),
                MediaPerimeterPoint(97f, 97f),
                MediaPerimeterPoint(3f, 97f),
                MediaPerimeterPoint(3f, 3f),
            ),
            full,
        )
        assertEquals(full, mediaPerimeterProgressPoints(100f, 100f, 3f, 1.5f))
    }

    @Test
    fun glassMediaPerimeterRoundsCornersWithoutChangingZeroRadiusFallback() {
        val rounded = mediaRoundedPerimeterProgressPoints(
            width = 100f,
            height = 100f,
            inset = 3f,
            progress = 1f,
            cornerRadius = 20f,
        )

        // Start at 12 o'clock while keeping the rounded GLASS stroke off the sharp tile corner.
        assertEquals(MediaPerimeterPoint(50f, 3f), rounded.first())
        assertFalse(rounded.contains(MediaPerimeterPoint(3f, 3f)))
        assertEquals(
            mediaPerimeterProgressPoints(100f, 100f, 3f, 0.5f),
            mediaRoundedPerimeterProgressPoints(
                width = 100f,
                height = 100f,
                inset = 3f,
                progress = 0.5f,
                cornerRadius = 0f,
            ),
        )
    }

    @Test
    fun semanticArtworkCacheRetriesAfterInitialMissForSameKey() {
        var reads = 0

        val firstRead = readCachedMediaArtwork(
            key = "track-1",
            previousKey = null,
            previousValue = null,
        ) {
            reads += 1
            null
        }
        val laterRead = readCachedMediaArtwork(
            key = "track-1",
            previousKey = "track-1",
            previousValue = firstRead,
        ) {
            reads += 1
            "artwork-1"
        }

        assertEquals(null, firstRead)
        assertEquals("artwork-1", laterRead)
        assertEquals(2, reads)
    }

    @Test
    fun semanticArtworkCacheHitSkipsRead() {
        var reads = 0

        val result = readCachedMediaArtwork(
            key = "track-1",
            previousKey = "track-1",
            previousValue = "cached-artwork",
        ) {
            reads += 1
            "fresh-artwork"
        }

        assertEquals("cached-artwork", result)
        assertEquals(0, reads)
    }

    @Test
    fun semanticArtworkCacheReadsAgainWhenTrackChanges() {
        var reads = 0

        val result = readCachedMediaArtwork(
            key = "track-2",
            previousKey = "track-1",
            previousValue = "artwork-1",
        ) {
            reads += 1
            null
        }

        assertEquals(null, result)
        assertEquals(1, reads)
    }

    @Test
    fun semanticArtworkCacheClearsCachedArtWhenChangedTrackHasNoImage() {
        var reads = 0
        val cachedTrack = readCachedMediaArtwork(
            key = "track-1",
            previousKey = "track-1",
            previousValue = "artwork-1",
        ) {
            reads += 1
            "unexpected-read"
        }

        val changedTrack = readCachedMediaArtwork(
            key = "track-2",
            previousKey = "track-1",
            previousValue = cachedTrack,
        ) {
            reads += 1
            null
        }

        assertEquals("artwork-1", cachedTrack)
        assertEquals(null, changedTrack)
        assertEquals(1, reads)
    }

    @Test
    fun semanticArtworkCacheDoesNotReuseValueWhenKeyIsUnknown() {
        var reads = 0

        val result = readCachedMediaArtwork(
            key = null,
            previousKey = null,
            previousValue = "stale-artwork",
        ) {
            reads += 1
            "new-artwork"
        }

        assertEquals("new-artwork", result)
        assertEquals(1, reads)
    }

    @Test
    fun mediaControllerReconciliationReusesTokenAndDropsDuplicateWrappers() {
        val existing = mapOf("session-a" to "old-wrapper-a")

        val reconciled = reconcileMediaControllersBySessionToken(
            incoming = listOf(
                "session-a" to "new-wrapper-a",
                "session-b" to "new-wrapper-b",
                "session-a" to "duplicate-wrapper-a",
            ),
            existing = existing,
        )

        assertEquals(
            listOf(
                "session-a" to "old-wrapper-a",
                "session-b" to "new-wrapper-b",
            ),
            reconciled,
        )
    }

    @Test
    fun mediaPositionAdvancesFromPlaybackStateSample() {
        assertEquals(
            1_500L,
            mediaPositionAt(
                position = 1_000L,
                duration = 10_000L,
                positionUpdateTime = 5_000L,
                playbackSpeed = 1f,
                isPlaying = true,
                nowElapsedRealtime = 5_500L,
            ),
        )
        assertEquals(
            2_000L,
            mediaPositionAt(
                position = 1_000L,
                duration = 10_000L,
                positionUpdateTime = 5_000L,
                playbackSpeed = 2f,
                isPlaying = true,
                nowElapsedRealtime = 5_500L,
            ),
        )
    }

    @Test
    fun mediaPositionDoesNotAdvanceWhenPausedAndClampsToDuration() {
        assertEquals(
            9_500L,
            mediaPositionAt(
                position = 9_500L,
                duration = 10_000L,
                positionUpdateTime = 5_000L,
                playbackSpeed = 1f,
                isPlaying = false,
                nowElapsedRealtime = 8_000L,
            ),
        )
        assertEquals(
            10_000L,
            mediaPositionAt(
                position = 9_500L,
                duration = 10_000L,
                positionUpdateTime = 5_000L,
                playbackSpeed = 1f,
                isPlaying = true,
                nowElapsedRealtime = 6_000L,
            ),
        )
    }

    @Test
    fun artworkSemanticKeyChangesOnlyWhenArtworkMetadataChanges() {
        val original = mediaArtworkSemanticKey(
            mediaId = "track-1",
            artUri = "content://art/1",
            albumArtUri = null,
            displayIconUri = null,
        )
        assertEquals(
            original,
            mediaArtworkSemanticKey(
                mediaId = "track-1",
                artUri = "content://art/1",
                albumArtUri = null,
                displayIconUri = null,
            ),
        )
        assertFalse(
            original == mediaArtworkSemanticKey(
                mediaId = "track-2",
                artUri = "content://art/1",
                albumArtUri = null,
                displayIconUri = null,
            ),
        )
        assertEquals(
            null,
            mediaArtworkSemanticKey(
                mediaId = null,
                artUri = null,
                albumArtUri = null,
                displayIconUri = null,
            ),
        )
    }

    @Test
    fun artworkSemanticKeyUsesSongFieldsWhenProviderHasNoArtIdentifier() {
        val original = mediaArtworkSemanticKey(
            mediaId = null,
            artUri = null,
            albumArtUri = null,
            displayIconUri = null,
            titleFallback = "Song",
            artistFallback = "Artist",
            albumFallback = "Album",
        )
        assertEquals(
            original,
            mediaArtworkSemanticKey(
                mediaId = null,
                artUri = null,
                albumArtUri = null,
                displayIconUri = null,
                titleFallback = "Song",
                artistFallback = "Artist",
                albumFallback = "Album",
            ),
        )
        assertFalse(
            original == mediaArtworkSemanticKey(
                mediaId = null,
                artUri = null,
                albumArtUri = null,
                displayIconUri = null,
                titleFallback = "Different Song",
                artistFallback = "Artist",
                albumFallback = "Album",
            ),
        )
    }

    @Test
    fun mediaSnapshotComparisonIgnoresBitmapIdentityWhenSemanticArtworkIsSame() {
        val left = MediaSnapshot(
            title = "Song",
            artist = "Artist",
            artworkKey = "track-1\u001fcontent://art/1\u001f\u001f",
            position = 100L,
            duration = 1_000L,
            positionUpdateTime = 5_000L,
            playbackSpeed = 1f,
            isPlaying = true,
        )
        val right = left.copy()
        assertTrue(mediaSnapshotsSemanticallyEqual(left, right))
        assertFalse(
            mediaSnapshotsSemanticallyEqual(
                left,
                right.copy(artworkKey = "track-1\u001fcontent://art/2\u001f\u001f"),
            ),
        )
    }

    @Test
    fun mediaAppLaunchPrefersTheSelectedControllersSessionActivity() {
        var sessionLaunches = 0
        var packageLaunches = 0

        assertEquals(
            MediaAppLaunchPath.SESSION_ACTIVITY,
            mediaAppLaunchPath(
                controllerAvailable = true,
                sessionActivity = {
                    sessionLaunches += 1
                    true
                },
                packageLaunch = {
                    packageLaunches += 1
                    true
                },
            ),
        )
        assertEquals(1, sessionLaunches)
        assertEquals(0, packageLaunches)
    }

    @Test
    fun cancelledSessionActivityFallsBackToTheSameControllersPackage() {
        var packageLaunches = 0

        assertEquals(
            MediaAppLaunchPath.PACKAGE_LAUNCH,
            mediaAppLaunchPath(
                controllerAvailable = true,
                sessionActivity = { error("cancelled session activity") },
                packageLaunch = {
                    packageLaunches += 1
                    true
                },
            ),
        )
        assertEquals(1, packageLaunches)
    }

    @Test
    fun missingControllerOrFailedLaunchesKeepTheExistingContextPanelFallback() {
        var missingControllerLaunches = 0
        assertEquals(
            MediaAppLaunchPath.CONTEXT_PANEL,
            mediaAppLaunchPath(
                controllerAvailable = false,
                sessionActivity = {
                    missingControllerLaunches += 1
                    true
                },
                packageLaunch = {
                    missingControllerLaunches += 1
                    true
                },
            ),
        )
        assertEquals(0, missingControllerLaunches)

        assertEquals(
            MediaAppLaunchPath.CONTEXT_PANEL,
            mediaAppLaunchPath(
                controllerAvailable = true,
                sessionActivity = { false },
                packageLaunch = { error("launcher activity unavailable") },
            ),
        )
    }
}
