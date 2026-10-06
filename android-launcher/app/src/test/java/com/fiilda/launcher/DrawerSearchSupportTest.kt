package com.fiilda.launcher

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DrawerSearchSupportTest {
    @Test
    fun normalizeSearchHandlesNfkcFullWidthHalfWidthDakutenAndKana() {
        assertEquals(
            "ぱーてぃー",
            normalizeDrawerSearchText("  ﾊﾟｰﾃｨｰ  "),
        )
        assertEquals(
            normalizeDrawerSearchText("ぱーてぃー"),
            normalizeDrawerSearchText("パーティー"),
        )
    }

    @Test
    fun normalizeSearchUsesRootLocaleForTurkishI() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("istanbul", normalizeDrawerSearchText("ISTANBUL"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun rankSearchPutsExactThenPrefixThenContainsAndKeepsStableTies() {
        val labels = listOf("beta alpha", "alphabet", "ALPHA", "alpha")
        assertEquals(
            listOf("ALPHA", "alpha", "alphabet", "beta alpha"),
            rankDrawerSearchValues("alpha", labels) { it },
        )
    }

    @Test
    fun externalSearchUrlsEncodeQueryComponents() {
        assertEquals(
            "https://www.google.com/search?q=cat%20%26%20dog",
            externalSearchUrl(ExternalSearchTarget.GOOGLE, "cat & dog"),
        )
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=%E6%9D%B1%E4%BA%AC%20%E9%A7%85",
            externalSearchUrl(ExternalSearchTarget.MAPS, "東京 駅"),
        )
        assertEquals(
            "https://www.youtube.com/results?search_query=music%2Fvideo",
            externalSearchUrl(ExternalSearchTarget.YOUTUBE, "music/video"),
        )
    }

    @Test
    fun permissionMatrixUsesExternalStorageOnlyThroughApi32() {
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            drawerSearchPermissionsFor(DeviceSearchSource.VISUAL_MEDIA, sdkInt = 32),
        )
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            drawerSearchPermissionsFor(DeviceSearchSource.AUDIO, sdkInt = 32),
        )
        assertTrue(
            Manifest.permission.READ_MEDIA_IMAGES in
                drawerSearchPermissionsFor(DeviceSearchSource.VISUAL_MEDIA, sdkInt = 33),
        )
        assertFalse(
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in
                drawerSearchPermissionsFor(DeviceSearchSource.VISUAL_MEDIA, sdkInt = 33),
        )
        assertTrue(
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in
                drawerSearchPermissionsFor(DeviceSearchSource.VISUAL_MEDIA, sdkInt = 34),
        )
    }

    @Test
    fun permissionMatrixDistinguishesFullAndPartialVisualAccess() {
        val full = resolveDrawerSearchPermissionState(
            source = DeviceSearchSource.VISUAL_MEDIA,
            grantedPermissions = setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            ),
            sdkInt = 34,
        )
        val partial = resolveDrawerSearchPermissionState(
            source = DeviceSearchSource.VISUAL_MEDIA,
            grantedPermissions = setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            sdkInt = 34,
        )
        val denied = resolveDrawerSearchPermissionState(
            source = DeviceSearchSource.VISUAL_MEDIA,
            grantedPermissions = emptySet(),
            sdkInt = 34,
        )
        assertEquals(DrawerSearchPermissionAccess.FULL, full.access)
        assertEquals(DrawerSearchPermissionAccess.PARTIAL, partial.access)
        assertEquals(DrawerSearchPermissionAccess.NONE, denied.access)
    }

    @Test
    fun restoredPermissionCallbackInfersDistinctApi33SourcesAndLeavesApi32Ambiguous() {
        assertEquals(
            DeviceSearchSource.CONTACTS,
            inferDrawerSearchPermissionSource(
                setOf(Manifest.permission.READ_CONTACTS),
                sdkInt = 37,
            ),
        )
        assertEquals(
            DeviceSearchSource.AUDIO,
            inferDrawerSearchPermissionSource(
                setOf(Manifest.permission.READ_MEDIA_AUDIO),
                sdkInt = 37,
            ),
        )
        assertEquals(
            DeviceSearchSource.VISUAL_MEDIA,
            inferDrawerSearchPermissionSource(
                setOf(Manifest.permission.READ_MEDIA_IMAGES),
                sdkInt = 37,
            ),
        )
        assertEquals(
            null,
            inferDrawerSearchPermissionSource(
                setOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                sdkInt = 32,
            ),
        )
    }

    @Test
    fun documentScannerContinuesBoundedBfsAndUsesProviderIdentity() {
        val root = SearchDocumentTarget(
            id = "tree:root",
            label = "root",
            uri = "content://provider/tree/root",
            isTree = true,
        )
        val reader = object : SearchDocumentReader {
            override fun children(
                parentUri: String,
                cancellation: SearchCancellation,
            ): List<SearchDocumentEntry> = when (parentUri) {
                root.uri -> listOf(
                    SearchDocumentEntry("provider:file-1", "content://provider/document/file-1", "needle-one"),
                    SearchDocumentEntry("provider:dir-a", "content://provider/document/dir-a", "dir-a", isDirectory = true),
                    SearchDocumentEntry("provider:file-2", "content://provider/document/file-2", "needle-two"),
                )
                "content://provider/document/dir-a" -> listOf(
                    SearchDocumentEntry("provider:nested", "content://provider/document/nested", "needle-nested"),
                )
                else -> emptyList()
            }

            override fun metadata(uri: String, cancellation: SearchCancellation): SearchDocumentEntry? = null
        }
        val session = SearchDocumentScanSession(listOf(root), reader, maxEntries = 2)

        val first = session.scanRound("needle")
        assertTrue(first.hasMore)
        assertEquals(2, first.entriesVisited)
        assertEquals(listOf("needle-one"), first.results.map { it.label })

        val second = session.scanRound("needle")
        assertTrue(second.hasMore)
        assertEquals(listOf("needle-one", "needle-two"), second.results.map { it.label })

        val third = session.scanRound("needle")
        assertFalse(third.hasMore)
        assertEquals(
            listOf("needle-one", "needle-two", "needle-nested"),
            third.results.map { it.label },
        )
        assertTrue(session.visitedIds.contains("provider:dir-a"))
    }

    @Test
    fun canceledDocumentRoundRestoresFrontierForContinue() {
        val root = SearchDocumentTarget("tree:root", "root", "content://provider/tree/root", true)
        var shouldCancel = true
        val reader = object : SearchDocumentReader {
            override fun children(parentUri: String, cancellation: SearchCancellation): List<SearchDocumentEntry> {
                if (shouldCancel) {
                    shouldCancel = false
                    throw kotlinx.coroutines.CancellationException("test")
                }
                return listOf(SearchDocumentEntry("provider:file", "content://provider/document/file", "needle"))
            }

            override fun metadata(uri: String, cancellation: SearchCancellation): SearchDocumentEntry? = null
        }
        val session = SearchDocumentScanSession(listOf(root), reader, maxEntries = 10)
        assertTrue(runCatching { session.scanRound("needle") }.isFailure)
        assertTrue(session.hasMore)
        assertEquals(listOf("needle"), session.scanRound("needle").results.map { it.label })
    }

    @Test
    fun pagedTreeScanBoundsSiblingRowsKeepsMatchesAndAvoidsPerChildMetadataQueries() {
        val root = SearchDocumentTarget("tree:root", "root", "content://provider/tree/root", true)
        val total = 10_001
        val metadataCalls = mutableListOf<String>()
        val pageOffsets = mutableListOf<Int>()
        val reader = object : SearchDocumentReader {
            override fun children(
                parentUri: String,
                cancellation: SearchCancellation,
            ): List<SearchDocumentEntry> = error("scanner must use the paged seam")

            override fun children(
                parentUri: String,
                cancellation: SearchCancellation,
                offset: Int,
                limit: Int,
            ): List<SearchDocumentEntry> {
                pageOffsets += offset
                return (offset until minOf(offset + limit, total)).map { index ->
                    SearchDocumentEntry(
                        id = "provider:file-$index",
                        uri = "content://provider/document/file-$index",
                        label = "needle-$index",
                    )
                }
            }

            override fun metadata(uri: String, cancellation: SearchCancellation): SearchDocumentEntry? {
                metadataCalls += uri
                return null
            }
        }
        val session = SearchDocumentScanSession(listOf(root), reader)

        val first = session.scanRound("needle")
        assertEquals(4_999, first.results.size)
        assertEquals("needle-0", first.results.first().label)
        assertEquals("needle-4998", first.results.last().label)
        assertTrue(first.hasMore)

        val second = session.scanRound("needle")
        assertTrue(second.results.map { it.label }.contains("needle-4999"))
        assertTrue(second.results.indexOfFirst { it.label == "needle-4999" } <
            second.results.indexOfFirst { it.label == "needle-5000" })
        assertTrue(second.hasMore)
        assertTrue(pageOffsets.containsAll(listOf(0, 5_000)))
        assertTrue(metadataCalls.isEmpty())
    }

    @Test
    fun overlappingUriOwnershipIsSymmetricButGrantCoverageIsDirectional() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val child = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Ffile.txt"
        assertTrue(uriGrantOwnershipOverlaps(tree, child))
        assertTrue(uriGrantOwnershipOverlaps(child, tree))
        assertTrue(persistedReadGrantCoversTarget(tree, child))
        assertFalse(persistedReadGrantCoversTarget(child, tree))
    }
}
