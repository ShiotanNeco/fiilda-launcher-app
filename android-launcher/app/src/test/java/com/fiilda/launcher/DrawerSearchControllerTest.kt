package com.fiilda.launcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import java.util.EnumMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Controller tests use the real Android controller and SharedPreferences, while the provider and
 * permission boundaries remain controlled. The source fake deliberately ignores coroutine
 * cancellation so a stale provider completion must pass the controller's generation checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DrawerSearchControllerTest {
    private lateinit var context: Context
    private lateinit var mainDispatcher: TestDispatcher
    private lateinit var sourceReader: ControlledSourceReader
    private lateinit var permissionGateway: FakePermissionGateway
    private lateinit var grantStore: FakeGrantStore
    private var controller: AndroidDrawerSearchController? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        sourceReader = ControlledSourceReader()
        permissionGateway = FakePermissionGateway()
        grantStore = FakeGrantStore()
    }

    @After
    fun tearDown() {
        // A source fake intentionally outlives cancellation until its provider reply arrives. End
        // every outstanding reply before closing the scope so the test process cannot retain IO
        // jobs between cases.
        sourceReader.calls.forEach { call ->
            call.outcome.complete(Result.success(emptyList()))
        }
        controller?.close()
        Dispatchers.resetMain()
        context.getSharedPreferences(DrawerSearchPreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(FavoritePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun delayedOldQueryResultIsDiscardedAfterAtoBInputChange() {
        saveDrawerSearchSourceEnabled(context, DeviceSearchSource.CONTACTS, true)
        permissionGateway.set(DeviceSearchSource.CONTACTS, DrawerSearchPermissionAccess.FULL)
        val searchController = newController()

        searchController.updateInputs(query = "A", active = true, refreshToken = 1)
        val oldCall = awaitCall(DeviceSearchSource.CONTACTS, index = 0)

        searchController.updateInputs(query = "B", active = true, refreshToken = 2)
        val newCall = awaitCall(DeviceSearchSource.CONTACTS, index = 1)

        oldCall.complete(
            listOf(
                SearchResult(
                    id = "contact:old",
                    label = "A old result",
                    uri = "content://contacts/old",
                    source = DeviceSearchSource.CONTACTS,
                ),
            ),
        )
        awaitAtMost { searchController.state.contacts.isEmpty() }
        assertEquals(SearchSourceStatus.LOADING, searchController.state.contactsStatus)

        newCall.complete(
            listOf(
                SearchResult(
                    id = "contact:new",
                    label = "B fresh result",
                    uri = "content://contacts/new",
                    source = DeviceSearchSource.CONTACTS,
                ),
            ),
        )
        awaitAtMost { searchController.state.contacts.map { it.id } == listOf("contact:new") }
        assertEquals(SearchSourceStatus.READY, searchController.state.contactsStatus)
    }

    @Test
    fun disablingSourceDuringQueryDropsLateRowsAndMarksItDisabled() {
        saveDrawerSearchSourceEnabled(context, DeviceSearchSource.CONTACTS, true)
        permissionGateway.set(DeviceSearchSource.CONTACTS, DrawerSearchPermissionAccess.FULL)
        val searchController = newController()
        searchController.updateInputs("alice", active = true, refreshToken = 1)
        val inFlight = awaitCall(DeviceSearchSource.CONTACTS, index = 0)

        permissionGateway.set(DeviceSearchSource.CONTACTS, DrawerSearchPermissionAccess.NONE)
        searchController.toggleSource(DeviceSearchSource.CONTACTS, enabled = false)
        inFlight.complete(
            listOf(
                SearchResult(
                    id = "contact:late",
                    label = "Alice late",
                    uri = "content://contacts/late",
                    source = DeviceSearchSource.CONTACTS,
                ),
            ),
        )

        awaitAtMost {
            searchController.state.contacts.isEmpty() &&
                searchController.state.contactsStatus == SearchSourceStatus.DISABLED
        }
        assertFalse(searchController.state.sources.first { it.source == DeviceSearchSource.CONTACTS }.enabled)
    }

    @Test
    fun fullToPartialPermissionRefreshDropsOldVisualRows() {
        saveDrawerSearchSourceEnabled(context, DeviceSearchSource.VISUAL_MEDIA, true)
        permissionGateway.set(DeviceSearchSource.VISUAL_MEDIA, DrawerSearchPermissionAccess.FULL)
        val searchController = newController()
        searchController.updateInputs("cat", active = true, refreshToken = 1)
        val oldCall = awaitCall(DeviceSearchSource.VISUAL_MEDIA, index = 0)

        permissionGateway.set(DeviceSearchSource.VISUAL_MEDIA, DrawerSearchPermissionAccess.PARTIAL)
        searchController.onPermissionResult(
            mapOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to true),
        )
        val newCall = awaitCall(DeviceSearchSource.VISUAL_MEDIA, index = 1)

        oldCall.complete(
            listOf(
                SearchResult(
                    id = "media:old",
                    label = "cat old",
                    uri = "content://media/old",
                    source = DeviceSearchSource.VISUAL_MEDIA,
                ),
            ),
        )
        awaitAtMost {
            searchController.state.files.isEmpty() &&
                searchController.state.visualMediaStatus == SearchSourceStatus.LOADING
        }

        newCall.complete(
            listOf(
                SearchResult(
                    id = "media:new",
                    label = "cat fresh",
                    uri = "content://media/new",
                    source = DeviceSearchSource.VISUAL_MEDIA,
                ),
            ),
        )
        awaitAtMost { searchController.state.files.map { it.id } == listOf("media:new") }
        assertEquals(SearchSourceStatus.PARTIAL, searchController.state.visualMediaStatus)
    }

    @Test
    fun sourceFailureLeavesOtherSourceRowsReady() {
        saveDrawerSearchSourceEnabled(context, DeviceSearchSource.CONTACTS, true)
        saveDrawerSearchSourceEnabled(context, DeviceSearchSource.AUDIO, true)
        permissionGateway.set(DeviceSearchSource.CONTACTS, DrawerSearchPermissionAccess.FULL)
        permissionGateway.set(DeviceSearchSource.AUDIO, DrawerSearchPermissionAccess.FULL)
        val searchController = newController()
        searchController.updateInputs("song", active = true, refreshToken = 1)
        val contactCall = awaitCall(DeviceSearchSource.CONTACTS, index = 0)
        val audioCall = awaitCall(DeviceSearchSource.AUDIO, index = 0)

        audioCall.complete(
            listOf(
                SearchResult(
                    id = "audio:1",
                    label = "Song from audio",
                    uri = "content://audio/1",
                    source = DeviceSearchSource.AUDIO,
                ),
            ),
        )
        awaitAtMost { searchController.state.files.map { it.id } == listOf("audio:1") }

        contactCall.fail(IllegalStateException("contacts provider unavailable"))
        awaitAtMost { searchController.state.contactsStatus == SearchSourceStatus.ERROR }
        assertEquals(listOf("audio:1"), searchController.state.files.map { it.id })
        assertEquals(SearchSourceStatus.READY, searchController.state.audioStatus)
    }

    @Test
    fun permissionCallbacksRefreshFullPartialAndDeniedWithoutWaitingForLifecycleResume() {
        saveDrawerSearchSourceEnabled(context, DeviceSearchSource.VISUAL_MEDIA, true)
        permissionGateway.set(DeviceSearchSource.VISUAL_MEDIA, DrawerSearchPermissionAccess.FULL)
        val searchController = newController()
        searchController.updateInputs("photo", active = true, refreshToken = 1)
        val fullCall = awaitCall(DeviceSearchSource.VISUAL_MEDIA, index = 0)
        fullCall.complete(
            listOf(
                SearchResult(
                    id = "media:full",
                    label = "photo full",
                    uri = "content://media/full",
                    source = DeviceSearchSource.VISUAL_MEDIA,
                ),
            ),
        )
        awaitAtMost { searchController.state.visualMediaStatus == SearchSourceStatus.READY }

        // The callback updates state while the Activity is paused; it must not rely on an
        // onResume-only refresh to reflect the selected-media grant.
        searchController.updateLifecycleForeground(false)
        permissionGateway.set(DeviceSearchSource.VISUAL_MEDIA, DrawerSearchPermissionAccess.PARTIAL)
        searchController.onPermissionResult(
            mapOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to true),
        )
        awaitAtMost { searchController.state.visualMediaStatus == SearchSourceStatus.PARTIAL }
        assertTrue(searchController.state.files.isEmpty())

        searchController.updateLifecycleForeground(true)
        val partialCall = awaitCall(DeviceSearchSource.VISUAL_MEDIA, index = 1)
        partialCall.complete(
            listOf(
                SearchResult(
                    id = "media:partial",
                    label = "photo partial",
                    uri = "content://media/partial",
                    source = DeviceSearchSource.VISUAL_MEDIA,
                ),
            ),
        )
        awaitAtMost { searchController.state.visualMediaStatus == SearchSourceStatus.PARTIAL }

        permissionGateway.set(DeviceSearchSource.VISUAL_MEDIA, DrawerSearchPermissionAccess.NONE)
        searchController.onPermissionResult(
            mapOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED to false),
        )
        awaitAtMost {
            searchController.state.visualMediaStatus == SearchSourceStatus.DENIED &&
                searchController.state.files.isEmpty()
        }
    }

    @Test
    @Config(sdk = [33])
    fun api33ImagesOnlyGrantIsPartialAndManageAccessRequestsUpgrade() {
        saveDrawerSearchSourceEnabled(context, DeviceSearchSource.VISUAL_MEDIA, true)
        permissionGateway.setState(
            resolveDrawerSearchPermissionState(
                source = DeviceSearchSource.VISUAL_MEDIA,
                grantedPermissions = setOf(Manifest.permission.READ_MEDIA_IMAGES),
                sdkInt = 33,
            ),
        )
        val searchController = newController()

        assertEquals(SearchSourceStatus.PARTIAL, searchController.state.visualMediaStatus)
        searchController.manageAccess(DeviceSearchSource.VISUAL_MEDIA)
        assertEquals(listOf(DeviceSearchSource.VISUAL_MEDIA), permissionGateway.requests)
    }

    @Test
    @Config(sdk = [33])
    fun api33ImagesOnlyProductionGatewayRequestsVideoThenRoutesPermanentDenialToSettings() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val shadowActivity = Shadows.shadowOf(activity)
        shadowActivity.grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        shadowActivity.denyPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        Shadows.shadowOf(activity.packageManager)
            .setShouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_VIDEO, false)

        val gateway = AndroidDrawerSearchPermissionGateway(activity)
        val launchedPermissions = mutableListOf<List<String>>()
        val source = DeviceSearchSource.VISUAL_MEDIA

        assertEquals(DrawerSearchPermissionAccess.PARTIAL, gateway.permissionState(source).access)
        gateway.request(source) { permissions -> launchedPermissions += permissions.toList() }
        assertEquals(listOf(listOf(Manifest.permission.READ_MEDIA_VIDEO)), launchedPermissions)

        shadowActivity.clearNextStartedActivities()
        gateway.request(source) { error("permanently denied video must open settings") }

        val settingsIntent = requireNotNull(shadowActivity.getNextStartedActivity())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settingsIntent.action)
        assertEquals(Uri.parse("package:${activity.packageName}"), settingsIntent.data)
    }

    @Test
    fun pickerCancelLeavesTargetsUntouchedAndConsumedTypedCallbackAddsTarget() {
        val searchController = newController()
        searchController.addFolder()
        searchController.onFolderPicked(null)
        assertEquals(emptyList<SearchDocumentTarget>(), readSearchDocumentTargets(context))
        assertEquals(null, searchController.state.pendingPicker)

        searchController.addDocuments()
        val request = searchController.state.pendingPicker
        requireNotNull(request)
        searchController.consumePickerRequest(request.requestId)
        assertEquals(null, searchController.state.pendingPicker)

        val picked = Uri.parse("content://documents/document/typed-file")
        searchController.onDocumentsPicked(listOf(picked))
        awaitAtMost { readSearchDocumentTargets(context).map { it.uri } == listOf(picked.toString()) }
    }

    @Test
    fun restoredControllerAcceptsTypedCallbackAfterConsumedFolderEvent() {
        val pickerStore = FakePickerStateStore()
        val first = newController(pickerStore)
        first.addFolder()
        val request = first.state.pendingPicker
        requireNotNull(request)
        first.consumePickerRequest(request.requestId)
        first.close()
        controller = null

        val restored = newController(pickerStore)
        val picked = Uri.parse("content://documents/tree/restored-folder")
        restored.onFolderPicked(picked)
        awaitAtMost { readSearchDocumentTargets(context).map { it.uri } == listOf(picked.toString()) }
    }

    @Test
    fun restoredControllerReplacesTargetAfterConsumedTypedCallback() {
        val oldUri = "content://documents/document/old-file"
        val newUri = Uri.parse("content://documents/document/new-file")
        val oldTarget = searchDocumentTarget(oldUri, isTree = false, label = "old")
        assertTrue(persistSearchDocumentTargetsWithReadGrants(context, listOf(oldTarget), grantStore))

        val pickerStore = FakePickerStateStore()
        val first = newController(pickerStore)
        first.reselectDocumentTarget(oldTarget.id)
        val request = first.state.pendingPicker
        requireNotNull(request)
        assertEquals(oldTarget.id, request.replacementTargetId)
        first.consumePickerRequest(request.requestId)
        first.close()
        controller = null

        val restored = newController(pickerStore)
        restored.onDocumentsPicked(listOf(newUri))
        awaitAtMost {
            readSearchDocumentTargets(context).map { it.uri } == listOf(newUri.toString())
        }
        assertTrue(oldUri in grantStore.released)
    }

    private fun newController(
        pickerStateStore: DrawerSearchPickerStateStore? = null,
    ): AndroidDrawerSearchController {
        return AndroidDrawerSearchController(
            context = context,
            sourceReader = sourceReader,
            permissionGateway = permissionGateway,
            documentReader = EmptySearchDocumentReader,
            grantStore = grantStore,
            scope = CoroutineScope(SupervisorJob() + mainDispatcher),
            ioDispatcher = mainDispatcher,
            debounceMillis = 0L,
        ).also {
            controller = it
            pickerStateStore?.let { store -> it.setPickerStateStore(store) }
        }
    }

    private fun awaitCall(source: DeviceSearchSource, index: Int): PendingSourceCall {
        awaitAtMost {
            sourceReader.calls.count { it.source == source } > index
        }
        return sourceReader.calls.filter { it.source == source }[index]
    }

    private fun awaitAtMost(
        timeoutMillis: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!condition()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Condition was not met before timeout; state=${controller?.state}")
            }
            Thread.sleep(10L)
        }
    }

    private object EmptySearchDocumentReader : SearchDocumentReader {
        override fun children(
            parentUri: String,
            cancellation: SearchCancellation,
        ): List<SearchDocumentEntry> = emptyList()

        override fun metadata(
            uri: String,
            cancellation: SearchCancellation,
        ): SearchDocumentEntry? = null
    }

    private class ControlledSourceReader : DrawerSearchSourceReader {
        val calls = CopyOnWriteArrayList<PendingSourceCall>()

        override suspend fun search(
            source: DeviceSearchSource,
            query: String,
            cancellationSignal: CancellationSignal,
        ): List<SearchResult> {
            val call = PendingSourceCall(source, query, cancellationSignal)
            calls += call
            // Provider completion is intentionally allowed after the controller cancels its Job.
            return withContext(NonCancellable) {
                call.outcome.await().getOrThrow()
            }
        }
    }

    private class PendingSourceCall(
        val source: DeviceSearchSource,
        val query: String,
        val cancellationSignal: CancellationSignal,
    ) {
        val outcome = CompletableDeferred<Result<List<SearchResult>>>()

        fun complete(results: List<SearchResult>) {
            outcome.complete(Result.success(results))
        }

        fun fail(error: Throwable) {
            outcome.complete(Result.failure(error))
        }
    }

    private class FakePermissionGateway : DrawerSearchPermissionGateway {
        private val states = EnumMap<DeviceSearchSource, DrawerSearchPermissionState>(DeviceSearchSource::class.java)
        val requests = mutableListOf<DeviceSearchSource>()

        fun set(source: DeviceSearchSource, access: DrawerSearchPermissionAccess) {
            val granted = when (access) {
                DrawerSearchPermissionAccess.FULL -> drawerSearchPermissionsFor(source, sdkInt = 34).toSet()
                DrawerSearchPermissionAccess.PARTIAL -> setOf(
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
                DrawerSearchPermissionAccess.NONE -> emptySet()
            }
            states[source] = resolveDrawerSearchPermissionState(source, granted, sdkInt = 34)
        }

        fun setState(state: DrawerSearchPermissionState) {
            states[state.source] = state
        }

        override fun permissionState(source: DeviceSearchSource): DrawerSearchPermissionState =
            states[source] ?: resolveDrawerSearchPermissionState(source, emptySet(), sdkInt = 34)

        override fun request(source: DeviceSearchSource) {
            requests += source
        }

        override fun manage(source: DeviceSearchSource) = Unit
    }

    private class FakePickerStateStore : DrawerSearchPickerStateStore {
        private var savedKind: DrawerSearchPickerKind? = null
        private var savedReplacementTargetId: String? = null

        override fun kind(): DrawerSearchPickerKind? = savedKind

        override fun replacementTargetId(): String? = savedReplacementTargetId

        override fun save(kind: DrawerSearchPickerKind, replacementTargetId: String?) {
            savedKind = kind
            savedReplacementTargetId = replacementTargetId
        }

        override fun clear() {
            savedKind = null
            savedReplacementTargetId = null
        }
    }

    private class FakeGrantStore(
        initial: Set<String> = emptySet(),
    ) : PersistedReadGrantStore {
        private val persisted = initial.toMutableSet()
        val released = mutableListOf<String>()
        val failUris = mutableSetOf<String>()

        override fun persistedReadUris(): Set<String> = persisted.toSet()

        override fun takeReadPermission(uri: Uri): Boolean {
            val value = uri.toString()
            if (value in failUris) return false
            persisted += value
            return true
        }

        override fun releaseReadPermission(uri: Uri) {
            val value = uri.toString()
            released += value
            persisted -= value
        }
    }
}
