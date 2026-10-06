package com.fiilda.launcher

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Exercises the persistence boundary with a real Robolectric Context and real SharedPreferences.
 * Only the platform's persisted-grant calls are faked, which keeps grant ownership and rollback
 * behavior observable without depending on a device DocumentsProvider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawerSearchPersistenceIntegrationTest {
    private companion object {
        const val PagingProviderAuthority = "com.fiilda.test.paging"
    }

    private lateinit var context: Context
    private lateinit var grantStore: FakeGrantStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearPreferences()
        grantStore = FakeGrantStore()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun addReplaceAndRemoveTargetsUseDurableSharedPreferencesAndGrantLedger() {
        val first = searchDocumentTarget(
            "content://provider/document/first",
            isTree = false,
            label = "first.txt",
        )
        val second = searchDocumentTarget(
            "content://provider/document/second",
            isTree = false,
            label = "second.txt",
        )
        val replacement = searchDocumentTarget(
            "content://provider/document/replacement",
            isTree = false,
            label = "replacement.txt",
        )

        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(first, second), grantStore))
        assertEquals(listOf(first, second), readSearchDocumentTargets(context))
        assertEquals(setOf(first.uri, second.uri), grantStore.persistedReadUris())

        assertTrue(
            replaceSearchDocumentTargetWithReadGrants(
                context = context,
                replacedId = first.id,
                selected = listOf(replacement),
                grantStore = grantStore,
            ),
        )
        assertEquals(listOf(replacement, second), readSearchDocumentTargets(context))
        assertTrue(first.uri in grantStore.released)
        assertEquals(setOf(replacement.uri, second.uri), grantStore.persistedReadUris())

        val removed = removeSearchDocumentTarget(context, second.id, grantStore)
        assertNotNull(removed)
        assertEquals(listOf(replacement), readSearchDocumentTargets(context))
        assertTrue(second.uri in grantStore.released)
        assertEquals(setOf(replacement.uri), grantStore.persistedReadUris())
    }

    @Test
    fun sameUriOwnedByPhotoAndSearchIsReleasedOnlyAfterBothOwnersAreGone() {
        val sharedUri = "content://provider/document/shared"
        val target = searchDocumentTarget(sharedUri, isTree = false, label = "shared.txt")
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(target), grantStore))

        // Use the production photo URI serialization format to model a PHOTO home item. The
        // search and photo owners intentionally live in their separate preference namespaces.
        context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString("photo_widget_uris", "$PhotoWidgetHomeId\t$sharedUri")
            .commit()
        assertEquals(mapOf(PhotoWidgetHomeId to sharedUri), readPhotoUris(context))

        assertNotNull(removeSearchDocumentTarget(context, target.id, grantStore))
        assertTrue(sharedUri in grantStore.persistedReadUris())
        assertTrue(grantStore.released.isEmpty())

        context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove("photo_widget_uris")
            .commit()
        assertTrue(releaseLauncherUriPermissionIfUnowned(context, sharedUri, grantStore))
        assertTrue(sharedUri in grantStore.released)
        assertTrue(grantStore.persistedReadUris().isEmpty())
    }

    @Test
    fun failedAddRollsBackNewGrantButKeepsPreExistingGrantAndTargets() {
        val preExisting = "content://provider/document/pre-existing"
        val first = searchDocumentTarget("content://provider/document/first", false)
        val failed = searchDocumentTarget("content://provider/document/fails", false)
        grantStore = FakeGrantStore(setOf(preExisting))
        grantStore.failUris += failed.uri

        assertFalse(
            persistSearchDocumentTargetsWithReadGrants(
                context = context,
                selected = listOf(first, failed),
                grantStore = grantStore,
            ),
        )
        assertTrue(readSearchDocumentTargets(context).isEmpty())
        assertEquals(setOf(preExisting), grantStore.persistedReadUris())
        assertTrue(first.uri in grantStore.released)
        assertTrue(preExisting !in grantStore.released)
    }

    @Test
    fun failedReselectLeavesOldTargetAndItsGrantUntouched() {
        val old = searchDocumentTarget("content://provider/document/old", false, "old.txt")
        val replacement = searchDocumentTarget("content://provider/document/new", false, "new.txt")
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(old), grantStore))
        grantStore.failUris += replacement.uri

        assertFalse(
            replaceSearchDocumentTargetWithReadGrants(
                context = context,
                replacedId = old.id,
                selected = listOf(replacement),
                grantStore = grantStore,
            ),
        )
        assertEquals(listOf(old), readSearchDocumentTargets(context))
        assertEquals(setOf(old.uri), grantStore.persistedReadUris())
        assertTrue(old.uri !in grantStore.released)
    }

    @Test
    fun differentUriReselectReplacesOldRecordAndReleasesOldOwnedGrant() {
        val old = searchDocumentTarget("content://provider/document/old", false, "old.txt")
        val replacement = searchDocumentTarget("content://provider/document/new", false, "new.txt")
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(old), grantStore))

        assertTrue(
            replaceSearchDocumentTargetWithReadGrants(
                context = context,
                replacedId = old.id,
                selected = listOf(replacement),
                grantStore = grantStore,
            ),
        )
        assertEquals(listOf(replacement), readSearchDocumentTargets(context))
        assertTrue(old.uri in grantStore.released)
        assertEquals(setOf(replacement.uri), grantStore.persistedReadUris())
    }

    @Test
    fun failedChildGrantRollbackReleasesNewChildEvenWhenOldTreeOwnerCoversIt() {
        val tree = searchDocumentTarget(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            isTree = true,
            label = "Download",
        )
        val childUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fchild.txt"
        val child = searchDocumentTarget(childUri, isTree = false, label = "child.txt")
        val failed = searchDocumentTarget("content://provider/document/fails", false)
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(tree), grantStore))
        grantStore.failUris += failed.uri

        assertFalse(
            persistSearchDocumentTargetsWithReadGrants(
                context = context,
                selected = listOf(child, failed),
                grantStore = grantStore,
            ),
        )
        assertEquals(listOf(tree), readSearchDocumentTargets(context))
        assertTrue(tree.uri in grantStore.persistedReadUris())
        assertTrue(child.uri in grantStore.released)
        assertTrue(child.uri !in grantStore.persistedReadUris())
    }

    @Test
    fun failedBroadTreeGrantRollbackReleasesItEvenWhenOldChildOwnerRemains() {
        val childUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fchild.txt"
        val child = searchDocumentTarget(childUri, isTree = false, label = "child.txt")
        val tree = searchDocumentTarget(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            isTree = true,
            label = "Download",
        )
        val failed = searchDocumentTarget("content://provider/document/fails", false)
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(child), grantStore))
        grantStore.failUris += failed.uri

        assertFalse(
            persistSearchDocumentTargetsWithReadGrants(
                context = context,
                selected = listOf(tree, failed),
                grantStore = grantStore,
            ),
        )
        assertEquals(listOf(child), readSearchDocumentTargets(context))
        assertTrue(child.uri in grantStore.persistedReadUris())
        assertTrue(tree.uri in grantStore.released)
        assertTrue(tree.uri !in grantStore.persistedReadUris())
    }

    @Test
    fun twoConcurrentRemovalsLeaveNoDocumentTarget() {
        val first = searchDocumentTarget("content://provider/document/first", false)
        val second = searchDocumentTarget("content://provider/document/second", false)
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(first, second), grantStore))

        runConcurrently(
            { removeSearchDocumentTarget(context, first.id, grantStore) },
            { removeSearchDocumentTarget(context, second.id, grantStore) },
        )
        assertTrue(readSearchDocumentTargets(context).isEmpty())
    }

    @Test
    fun concurrentAddAndRemoveRetainsAddedTarget() {
        val old = searchDocumentTarget("content://provider/document/old", false)
        val added = searchDocumentTarget("content://provider/document/added", false)
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(old), grantStore))

        runConcurrently(
            { persistSearchDocumentTargetsWithReadGrants(context, listOf(added), grantStore) },
            { removeSearchDocumentTarget(context, old.id, grantStore) },
        )
        assertEquals(listOf(added), readSearchDocumentTargets(context))
    }

    @Test
    fun documentReaderRetriesPlainQueryWhenProviderHonorsLimitButIgnoresOffset() {
        val provider = Robolectric.setupContentProvider(
            PagingIgnoringOffsetProvider::class.java,
            PagingProviderAuthority,
        ).also {
            it.reportHonoredArgsAsStringArray = true
        }
        val reader = AndroidSearchDocumentReader(context)
        val treeUri = "content://$PagingProviderAuthority/tree/root"

        val firstPage = reader.children(
            parentUri = treeUri,
            cancellation = NoopSearchCancellation,
            offset = 0,
            limit = 2,
        )
        val secondPage = reader.children(
            parentUri = treeUri,
            cancellation = NoopSearchCancellation,
            offset = 2,
            limit = 2,
        )

        assertEquals(listOf("file-1.txt", "file-2.txt"), firstPage.map { it.label })
        assertEquals(listOf("file-3.txt", "file-4.txt"), secondPage.map { it.label })
        assertTrue(provider.bundleQueryCount >= 2)
        assertTrue(provider.legacyQueryCount >= 1)
    }

    @Test
    fun documentReaderAcceptsStringArrayHonoredArgsWhenProviderHonorsBothPageArguments() {
        val provider = Robolectric.setupContentProvider(
            PagingIgnoringOffsetProvider::class.java,
            PagingProviderAuthority,
        ).also {
            it.honorOffset = true
            it.reportHonoredArgsAsStringArray = true
        }
        val reader = AndroidSearchDocumentReader(context)
        val treeUri = "content://$PagingProviderAuthority/tree/root"

        reader.children(
            parentUri = treeUri,
            cancellation = NoopSearchCancellation,
            offset = 0,
            limit = 2,
        )
        val secondPage = reader.children(
            parentUri = treeUri,
            cancellation = NoopSearchCancellation,
            offset = 2,
            limit = 2,
        )

        assertEquals(listOf("file-3.txt", "file-4.txt"), secondPage.map { it.label })
        assertEquals(2, provider.bundleQueryCount)
        assertEquals(0, provider.legacyQueryCount)
    }

    private fun clearPreferences() {
        context.getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun runConcurrently(first: () -> Unit, second: () -> Unit) {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            listOf(first, second).forEach { action ->
                executor.execute {
                    ready.countDown()
                    try {
                        if (!start.await(2, TimeUnit.SECONDS)) return@execute
                        action()
                    } catch (error: Throwable) {
                        errors += error
                    }
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            if (errors.isNotEmpty()) throw AssertionError("Concurrent operation failed", errors.peek())
        } finally {
            executor.shutdownNow()
        }
    }

    private class FakeGrantStore(
        initial: Set<String> = emptySet(),
    ) : PersistedReadGrantStore {
        private val persisted = initial.toMutableSet()
        val released = mutableListOf<String>()
        val failUris = mutableSetOf<String>()

        @Synchronized
        override fun persistedReadUris(): Set<String> = persisted.toSet()

        @Synchronized
        override fun takeReadPermission(uri: Uri): Boolean {
            val value = uri.toString()
            if (value in failUris) return false
            persisted += value
            return true
        }

        @Synchronized
        override fun releaseReadPermission(uri: Uri) {
            val value = uri.toString()
            released += value
            persisted -= value
        }
    }

    /** Can emulate providers that honor both page arguments or silently ignore OFFSET. */
    class PagingIgnoringOffsetProvider : ContentProvider() {
        var bundleQueryCount = 0
        var legacyQueryCount = 0
        var honorOffset = false
        var reportHonoredArgsAsStringArray = false

        private val rows = (1..4).map { index ->
            arrayOf<Any>(
                "file-$index",
                "file-$index.txt",
                "text/plain",
            )
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor {
            bundleQueryCount++
            val offset = queryArgs?.getInt(ContentResolver.QUERY_ARG_OFFSET, 0) ?: 0
            val limit = queryArgs?.getInt(ContentResolver.QUERY_ARG_LIMIT, rows.size) ?: rows.size
            val start = if (honorOffset) offset.coerceAtLeast(0) else 0
            val page = rows.drop(start).take(limit.coerceAtLeast(0))
            return cursor(page).apply {
                extras = Bundle().apply {
                    val honored = if (honorOffset) {
                        arrayOf(
                            ContentResolver.QUERY_ARG_OFFSET,
                            ContentResolver.QUERY_ARG_LIMIT,
                        )
                    } else {
                        arrayOf(ContentResolver.QUERY_ARG_LIMIT)
                    }
                    if (reportHonoredArgsAsStringArray) {
                        putStringArray(ContentResolver.EXTRA_HONORED_ARGS, honored)
                    } else {
                        putStringArrayList(
                            ContentResolver.EXTRA_HONORED_ARGS,
                            honored.toCollection(ArrayList()),
                        )
                    }
                }
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            legacyQueryCount++
            return cursor(rows)
        }

        override fun getType(uri: Uri): String = "vnd.android.document/directory"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        private fun cursor(values: List<Array<Any>>): MatrixCursor = MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
        ).also { cursor -> values.forEach(cursor::addRow) }
    }
}
