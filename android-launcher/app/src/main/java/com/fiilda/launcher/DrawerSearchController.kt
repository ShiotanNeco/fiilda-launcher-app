package com.fiilda.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/** Platform reader boundary. Each source call is isolated in its own coroutine/job. */
internal interface DrawerSearchSourceReader {
    suspend fun search(
        source: DeviceSearchSource,
        query: String,
        cancellationSignal: CancellationSignal,
    ): List<SearchResult>

    /** Invalidates only the foreground metadata cache; query changes reuse that snapshot. */
    fun invalidate(source: DeviceSearchSource? = null) = Unit
}

internal interface DrawerSearchPermissionGateway {
    fun permissionState(source: DeviceSearchSource): DrawerSearchPermissionState
    /** Legacy/test seam for gateways that do not own a Compose Activity Result launcher. */
    fun request(source: DeviceSearchSource)

    /**
     * Requests the missing permissions through the stable Activity Result launcher supplied by the
     * Compose owner. The callback is optional for pure controller fakes; production always passes
     * the launcher so a result can invalidate and restart the source explicitly.
     */
    fun request(
        source: DeviceSearchSource,
        launchPermissions: ((Array<String>) -> Unit)? = null,
    ) = request(source)
    fun manage(source: DeviceSearchSource)
}

/** Small bridge to Compose rememberSaveable for picker identity across recreation. */
internal interface DrawerSearchPickerStateStore {
    fun kind(): DrawerSearchPickerKind?
    fun replacementTargetId(): String?
    fun save(kind: DrawerSearchPickerKind, replacementTargetId: String?)
    fun clear()
}

internal class AndroidDrawerSearchPermissionGateway(
    private val context: Context,
) : DrawerSearchPermissionGateway {
    private val requestAttempted = mutableSetOf<DeviceSearchSource>()

    override fun permissionState(source: DeviceSearchSource): DrawerSearchPermissionState {
        val permissions = drawerSearchPermissionsFor(source, Build.VERSION.SDK_INT)
        val granted = permissions
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            .toSet()
        return resolveDrawerSearchPermissionState(source, granted, Build.VERSION.SDK_INT)
    }

    override fun request(source: DeviceSearchSource) {
        request(source, launchPermissions = null)
    }

    override fun request(
        source: DeviceSearchSource,
        launchPermissions: ((Array<String>) -> Unit)?,
    ) {
        val activity = context.findDrawerSearchActivity()
        val state = permissionState(source)
        if (state.access == DrawerSearchPermissionAccess.FULL) {
            return
        }
        val missing = state.requiredPermissions.filterNot {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            return
        }
        val permanentlyDenied = source in requestAttempted &&
            activity != null &&
            missing.all { !activity.shouldShowRequestPermissionRationale(it) }
        if (permanentlyDenied) {
            openApplicationSettings()
            return
        }
        requestAttempted += source
        // Calling Activity.requestPermissions directly loses the callback when the Activity is
        // recreated. The Compose owner supplies RequestMultiplePermissions' stable launcher.
        launchPermissions?.invoke(missing.toTypedArray()) ?: openApplicationSettings()
    }

    private fun openApplicationSettings() {
        val settingsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(settingsIntent) }
    }

    override fun manage(source: DeviceSearchSource) {
        if (permissionState(source).access == DrawerSearchPermissionAccess.FULL) {
            openApplicationSettings()
        } else {
            request(source)
        }
    }
}

private tailrec fun Context.findDrawerSearchActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findDrawerSearchActivity()
    else -> null
}

/** ContentResolver-backed source queries. Only display metadata is selected/read. */
internal class AndroidDrawerSearchSourceReader(
    private val context: Context,
) : DrawerSearchSourceReader {
    private val resolver
        get() = context.contentResolver
    private data class CachedMetadata(
        val results: List<SearchResult>,
        val normalizedLabels: List<String>,
    )
    private val metadataCache = EnumMap<DeviceSearchSource, CachedMetadata>(DeviceSearchSource::class.java)
    private val cacheEpoch = EnumMap<DeviceSearchSource, Long>(DeviceSearchSource::class.java)

    override suspend fun search(
        source: DeviceSearchSource,
        query: String,
        cancellationSignal: CancellationSignal,
    ): List<SearchResult> {
        val cachedAndEpoch = synchronized(metadataCache) {
            metadataCache[source] to (cacheEpoch[source] ?: 0L)
        }
        val metadata = cachedAndEpoch.first ?: run {
            // Provider I/O intentionally happens outside the monitor. A refresh on the main
            // thread must be able to invalidate promptly while this query is in flight.
            val rows = queryAll(source, cancellationSignal)
            val fresh = CachedMetadata(
                results = rows,
                normalizedLabels = rows.map { normalizeDrawerSearchText(it.label) },
            )
            synchronized(metadataCache) {
                val currentEpoch = cacheEpoch[source] ?: 0L
                if (currentEpoch == cachedAndEpoch.second) {
                    metadataCache[source] ?: fresh.also { metadataCache[source] = it }
                } else {
                    // The caller's result is still valid for this generation, but invalidation
                    // prevents it from becoming a new foreground cache entry.
                    fresh
                }
            }
        }
        cancellationSignal.throwIfCanceled()
        val normalizedQuery = normalizeDrawerSearchText(query)
        return metadata.results.mapIndexedNotNull { index, result ->
            val normalizedLabel = metadata.normalizedLabels[index]
            if (normalizedQuery.isBlank()) {
                result
            } else if (
                normalizedLabel == normalizedQuery ||
                normalizedLabel.startsWith(normalizedQuery) ||
                normalizedLabel.contains(normalizedQuery)
            ) {
                result
            } else {
                null
            }
        }
    }

    override fun invalidate(source: DeviceSearchSource?) {
        synchronized(metadataCache) {
            if (source == null) {
                metadataCache.clear()
                DeviceSearchSource.values().forEach { value ->
                    cacheEpoch[value] = (cacheEpoch[value] ?: 0L) + 1L
                }
            } else {
                metadataCache.remove(source)
                cacheEpoch[source] = (cacheEpoch[source] ?: 0L) + 1L
            }
        }
    }

    private fun queryAll(
        source: DeviceSearchSource,
        signal: CancellationSignal,
    ): List<SearchResult> = when (source) {
        DeviceSearchSource.CONTACTS -> queryContacts(signal)
        DeviceSearchSource.AUDIO -> queryAudio(signal)
    }

    private fun queryContacts(signal: CancellationSignal): List<SearchResult> {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
        val result = mutableListOf<SearchResult>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME + " ASC",
            signal,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val lookupIndex = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                val contactId = cursor.getStringOrNull(idIndex).orEmpty()
                val lookup = cursor.getStringOrNull(lookupIndex)
                val name = cursor.getStringOrNull(nameIndex).orEmpty().trim()
                if (contactId.isBlank() || name.isBlank()) continue
                result += SearchResult(
                    id = "contact:${lookup ?: contactId}",
                    label = name,
                    uri = ContentUris.withAppendedId(
                        ContactsContract.Contacts.CONTENT_URI,
                        contactId.toLongOrNull() ?: continue,
                    ).toString(),
                    source = DeviceSearchSource.CONTACTS,
                )
            }
        }
        return result
    }

    private fun queryAudio(signal: CancellationSignal): List<SearchResult> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        val result = mutableListOf<SearchResult>()
        resolver.query(
            collection,
            projection,
            null,
            null,
            MediaStore.Audio.Media.DISPLAY_NAME + " ASC",
            signal,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val mimeIndex = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                val id = cursor.getLongOrNull(idIndex) ?: continue
                val name = (
                    cursor.getStringOrNull(nameIndex)
                        ?: cursor.getStringOrNull(titleIndex)
                    ).orEmpty().trim()
                if (name.isBlank()) continue
                val mime = cursor.getStringOrNull(mimeIndex)
                result += SearchResult(
                    id = "audio:$id",
                    label = name,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    mimeType = mime,
                    subtitle = mime,
                    source = DeviceSearchSource.AUDIO,
                )
            }
        }
        return result
    }
}

private fun Cursor.getStringOrNull(index: Int): String? =
    if (index >= 0 && !isNull(index)) getString(index) else null

private fun Cursor.getLongOrNull(index: Int): Long? =
    if (index >= 0 && !isNull(index)) getLong(index) else null

/** Metadata-only SAF reader. It never opens file contents. */
internal class AndroidSearchDocumentReader(
    private val context: Context,
) : SearchDocumentReader {
    private val resolver
        get() = context.contentResolver

    override fun children(
        parentUri: String,
        cancellation: SearchCancellation,
    ): List<SearchDocumentEntry> = children(
        parentUri = parentUri,
        cancellation = cancellation,
        offset = 0,
        limit = Int.MAX_VALUE,
    )

    override fun children(
        parentUri: String,
        cancellation: SearchCancellation,
        offset: Int,
        limit: Int,
    ): List<SearchDocumentEntry> {
        cancellation.throwIfCanceled()
        val boundedOffset = offset.coerceAtLeast(0)
        val boundedLimit = limit.coerceAtLeast(0)
        if (boundedLimit == 0) return emptyList()
        val tree = Uri.parse(parentUri)
        val documentId = runCatching {
            // Nested directory URIs built with buildDocumentUriUsingTree contain both tree and
            // document segments. Their document ID must win; otherwise every level scans root.
            if (tree.path.orEmpty().contains("/document/")) {
                DocumentsContract.getDocumentId(tree)
            } else if (DocumentsContract.isTreeUri(tree)) {
                DocumentsContract.getTreeDocumentId(tree)
            } else {
                DocumentsContract.getDocumentId(tree)
            }
        }.getOrNull() ?: return emptyList()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val result = mutableListOf<SearchDocumentEntry>()
        // Prefer provider query arguments, but trust them only when the provider reports the
        // offset as honored. Some DocumentsProviders silently ignore LIMIT/OFFSET; the client
        // range below still guarantees that this round never materializes more than its page.
        var providerPageHonored = false
        val firstCursor = try {
            val queryArgs = Bundle().apply {
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    android.content.ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                )
                putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, boundedOffset)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, boundedLimit)
            }
            resolver.query(childUri, projection, queryArgs, cancellationSignal(cancellation))
        } catch (_: IllegalArgumentException) {
            null
        }
        val honoredArgs = firstCursor?.extras
            ?.getStringArray(android.content.ContentResolver.EXTRA_HONORED_ARGS)
            ?.toSet()
            .orEmpty()
        val cursor = if (
            firstCursor != null &&
            android.content.ContentResolver.QUERY_ARG_OFFSET in honoredArgs &&
            android.content.ContentResolver.QUERY_ARG_LIMIT in honoredArgs
        ) {
            providerPageHonored = true
            firstCursor
        } else {
            // If only one argument was honored, close that cursor and reissue a plain sorted
            // query. Otherwise a provider honoring LIMIT but ignoring OFFSET would make page two
            // appear empty after the client-side skip.
            firstCursor?.close()
            queryWithCancellation(
                uri = childUri,
                projection = projection,
                sortOrder = DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC",
                cancellation = cancellation,
            )
        }
        cursor?.use { cursorValue ->
            val idIndex = cursorValue.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursorValue.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursorValue.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            var cursorRow = 0
            while (cursorValue.moveToNext() && result.size < boundedLimit) {
                cancellation.throwIfCanceled()
                if (!providerPageHonored && cursorRow++ < boundedOffset) continue
                val documentIdValue = cursorValue.getStringOrNull(idIndex) ?: continue
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentIdValue)
                val mime = cursorValue.getStringOrNull(mimeIndex)
                result += SearchDocumentEntry(
                    id = documentEntryIdentity(documentUri.authority, documentIdValue),
                    uri = documentUri.toString(),
                    label = cursorValue.getStringOrNull(nameIndex).orEmpty().ifBlank { documentIdValue },
                    mimeType = mime,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return result
    }

    override fun metadata(
        uri: String,
        cancellation: SearchCancellation,
    ): SearchDocumentEntry? {
        cancellation.throwIfCanceled()
        val parsed = Uri.parse(uri)
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        )
        return queryWithCancellation(
            uri = parsed,
            projection = projection,
            cancellation = cancellation,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getStringOrNull(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                .orEmpty()
                .ifBlank { searchDocumentDisplayName(parsed) }
            val mime = cursor.getStringOrNull(
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE),
            )
            val documentIdValue = cursor.getStringOrNull(
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            )
            SearchDocumentEntry(
                id = documentEntryIdentity(parsed.authority, documentIdValue ?: uri),
                uri = uri,
                label = name,
                mimeType = mime,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            )
        }
    }

    private fun queryWithCancellation(
        uri: Uri,
        projection: Array<String>,
        sortOrder: String? = null,
        cancellation: SearchCancellation,
    ): Cursor? {
        val signal = (cancellation as? AndroidSearchCancellation)?.signal
        return if (signal == null) {
            resolver.query(uri, projection, null, null, sortOrder)
        } else {
            resolver.query(uri, projection, null, null, sortOrder, signal)
        }
    }

    private fun cancellationSignal(cancellation: SearchCancellation): CancellationSignal? =
        (cancellation as? AndroidSearchCancellation)?.signal
}

private fun documentEntryIdentity(authority: String?, documentId: String?): String =
    "${authority.orEmpty()}:$documentId"

internal fun drawerSearchViewIntent(
    uri: Uri,
    mimeType: String? = null,
    addNewTask: Boolean = false,
): Intent = Intent(Intent.ACTION_VIEW).apply {
    if (mimeType.isNullOrBlank()) {
        data = uri
    } else {
        setDataAndType(uri, mimeType)
    }
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (addNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * The concrete Compose-facing implementation. Its public surface is the small internal contract
 * above; all Android query and grant details stay behind this class.
 */
internal class AndroidDrawerSearchController(
    private val context: Context,
    private val sourceReader: DrawerSearchSourceReader = AndroidDrawerSearchSourceReader(context),
    private val permissionGateway: DrawerSearchPermissionGateway =
        AndroidDrawerSearchPermissionGateway(context),
    private val documentReader: SearchDocumentReader = AndroidSearchDocumentReader(context),
    private val grantStore: PersistedReadGrantStore = ContentResolverReadGrantStore(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = 200L,
) : DrawerSearchController {
    private val nextPickerRequestId = AtomicLong(0L)
    private val sourceJobs = EnumMap<DeviceSearchSource, Job>(DeviceSearchSource::class.java)
    private val sourceSignals = EnumMap<DeviceSearchSource, CancellationSignal>(DeviceSearchSource::class.java)
    private var documentJob: Job? = null
    private var documentSignal: CancellationSignal? = null
    private var inputGeneration = 0L
    private var documentGeneration = 0L
    private var lastRefreshToken = Int.MIN_VALUE
    private var lastRawQuery = ""
    private var normalizedQuery = ""
    private var isDrawerActive = false
    private var lifecycleForeground = true
    private var enabledSources = readDrawerSearchSourcePreferences(context).enabled
    private var documentTargets = refreshDocumentTargetGrantStatus(readSearchDocumentTargets(context))
    private var documentSession: SearchDocumentScanSession? = null
    private var documentResults = emptyList<SearchResult>()
    private val sourceResults = EnumMap<DeviceSearchSource, List<SearchResult>>(DeviceSearchSource::class.java)
    // Picker event visibility is separate from the in-flight kind. The UI may consume the event
    // immediately after launching Activity Result; the callback still needs its type on return.
    private var inFlightPickerKind: DrawerSearchPickerKind? = null
    private var pickerReplacementTargetId: String? = null
    private var pickerEventVisible = false
    private var permissionRequestSource: DeviceSearchSource? = null
    private var permissionResultLauncher: ((Array<String>) -> Unit)? = null
    private var pickerStateStore: DrawerSearchPickerStateStore? = null
    private var closed = false

    private var mutableUiState by mutableStateOf(
        DrawerSearchUiState(
            sources = sourceStates(),
            documents = documentTargets,
            documentsStatus = documentsStatusForTargets(documentTargets),
            documentsPartial = documentTargets.any { it.requiresReselection },
        ),
    )

    override val state: DrawerSearchUiState
        get() = mutableUiState

    override fun toggleSource(source: DeviceSearchSource, enabled: Boolean) {
        if (closed) return
        if (!saveDrawerSearchSourceEnabled(context, source, enabled)) {
            showToast(tr("検索設定を保存できませんでした", "Couldn't save search settings"))
            return
        }
        enabledSources = readDrawerSearchSourcePreferences(context).enabled
        sourceReader.invalidate(source)
        if (enabled) requestPermission(source)
        restartFromCurrentInputs(resetDocuments = false)
    }

    override fun manageAccess(source: DeviceSearchSource) {
        if (closed) return
        sourceReader.invalidate(source)
        if (permissionGateway.permissionState(source).access == DrawerSearchPermissionAccess.FULL) {
            permissionGateway.manage(source)
        } else {
            requestPermission(source)
        }
        // Rebuild the row immediately. onResume/refreshToken performs the authoritative recheck
        // after the platform permission/settings surface returns.
        mutableUiState = mutableUiState.copy(sources = sourceStates())
    }

    private fun requestPermission(source: DeviceSearchSource) {
        permissionRequestSource = source
        permissionGateway.request(source, permissionResultLauncher)
    }

    internal fun setPickerStateStore(store: DrawerSearchPickerStateStore?) {
        pickerStateStore = store
        if (store != null && inFlightPickerKind == null) {
            inFlightPickerKind = store.kind()
            pickerReplacementTargetId = store.replacementTargetId()
        }
    }

    private fun savePickerContext() {
        val kind = inFlightPickerKind ?: return
        pickerStateStore?.save(kind, pickerReplacementTargetId)
    }

    private fun clearPickerContext() {
        pickerStateStore?.clear()
    }

    override fun addFolder() {
        if (closed) return
        inFlightPickerKind = DrawerSearchPickerKind.FOLDER
        pickerReplacementTargetId = null
        savePickerContext()
        pickerEventVisible = true
        mutableUiState = mutableUiState.copy(
            pendingPicker = DrawerSearchPickerRequest(
                kind = DrawerSearchPickerKind.FOLDER,
                requestId = nextPickerRequestId.incrementAndGet(),
            ),
        )
    }

    override fun addDocuments() {
        if (closed) return
        inFlightPickerKind = DrawerSearchPickerKind.DOCUMENTS
        pickerReplacementTargetId = null
        savePickerContext()
        pickerEventVisible = true
        mutableUiState = mutableUiState.copy(
            pendingPicker = DrawerSearchPickerRequest(
                kind = DrawerSearchPickerKind.DOCUMENTS,
                requestId = nextPickerRequestId.incrementAndGet(),
            ),
        )
    }

    override fun reselectDocumentTarget(id: String) {
        if (closed) return
        val target = documentTargets.firstOrNull { it.id == id } ?: return
        inFlightPickerKind = if (target.isTree) {
            DrawerSearchPickerKind.FOLDER
        } else {
            DrawerSearchPickerKind.DOCUMENTS
        }
        pickerReplacementTargetId = target.id
        savePickerContext()
        pickerEventVisible = true
        mutableUiState = mutableUiState.copy(
            pendingPicker = DrawerSearchPickerRequest(
                kind = inFlightPickerKind!!,
                requestId = nextPickerRequestId.incrementAndGet(),
                replacementTargetId = target.id,
            ),
        )
    }

    override fun removeDocumentTarget(id: String) {
        if (closed) return
        scope.launch {
            val removed = withContext(ioDispatcher) {
                removeSearchDocumentTarget(context, id, grantStore)
            }
            if (removed != null) {
                documentTargets = refreshDocumentTargetGrantStatus(readSearchDocumentTargets(context))
                restartFromCurrentInputs(resetDocuments = true)
            }
        }
    }

    override fun refresh() {
        if (closed) return
        sourceReader.invalidate()
        lastRefreshToken = if (lastRefreshToken == Int.MAX_VALUE) 0 else lastRefreshToken + 1
        restartFromCurrentInputs(resetDocuments = true)
    }

    override fun continueDocuments() {
        if (closed || !isDrawerActive || normalizedQuery.isBlank() || mutableUiState.documentsLoading) return
        val session = documentSession ?: return
        if (!session.hasMore) return
        documentGeneration++
        cancelDocumentJob()
        launchDocumentSearch(
            expectedInputGeneration = inputGeneration,
            reset = false,
            debounce = false,
        )
    }

    override fun openResult(result: SearchResult) {
        val uri = result.contentUri() ?: return
        launchViewIntent(uri, result.mimeType)
    }

    override fun openExternal(target: ExternalSearchTarget) {
        val query = lastRawQuery.trim()
        if (query.isBlank()) return
        launchViewIntent(Uri.parse(externalSearchUrl(target, query)), null)
    }

    override fun onFolderPicked(uri: Uri?) {
        val requestKind = inFlightPickerKind ?: mutableUiState.pendingPicker?.kind
        val replacementId = pickerReplacementTargetId ?: mutableUiState.pendingPicker?.replacementTargetId
        inFlightPickerKind = null
        pickerReplacementTargetId = null
        clearPickerContext()
        pickerEventVisible = false
        mutableUiState = mutableUiState.copy(pendingPicker = null)
        // The ActivityResultRegistry can restore a callback into a new controller after process
        // recreation. A typed callback is authoritative even when the in-memory picker kind was
        // lost; a mismatched non-null kind is still ignored defensively.
        if (uri == null || requestKind == DrawerSearchPickerKind.DOCUMENTS) return
        scope.launch {
            val saved = withContext(ioDispatcher) {
                val target = pickedDocumentTarget(uri, isTree = true)
                if (replacementId == null) {
                    persistSearchDocumentTargetsWithReadGrants(context, listOf(target), grantStore)
                } else {
                    replaceSearchDocumentTargetWithReadGrants(
                        context = context,
                        replacedId = replacementId,
                        selected = listOf(target),
                        grantStore = grantStore,
                    )
                }
            }
            if (saved) {
                documentTargets = refreshDocumentTargetGrantStatus(readSearchDocumentTargets(context))
                restartFromCurrentInputs(resetDocuments = true)
            } else {
                showToast(tr("フォルダへのアクセスを保存できませんでした", "Couldn't save folder access"))
            }
        }
    }

    override fun onDocumentsPicked(uris: List<Uri>) {
        val requestKind = inFlightPickerKind ?: mutableUiState.pendingPicker?.kind
        val replacementId = pickerReplacementTargetId ?: mutableUiState.pendingPicker?.replacementTargetId
        inFlightPickerKind = null
        pickerReplacementTargetId = null
        clearPickerContext()
        pickerEventVisible = false
        mutableUiState = mutableUiState.copy(pendingPicker = null)
        if (requestKind == DrawerSearchPickerKind.FOLDER || uris.isEmpty()) return
        scope.launch {
            val saved = withContext(ioDispatcher) {
                val targets = uris.distinctBy { it.toString() }
                    .map { uri -> pickedDocumentTarget(uri, isTree = false) }
                if (replacementId == null) {
                    persistSearchDocumentTargetsWithReadGrants(context, targets, grantStore)
                } else {
                    replaceSearchDocumentTargetWithReadGrants(
                        context = context,
                        replacedId = replacementId,
                        selected = targets,
                        grantStore = grantStore,
                    )
                }
            }
            if (saved) {
                documentTargets = refreshDocumentTargetGrantStatus(readSearchDocumentTargets(context))
                restartFromCurrentInputs(resetDocuments = true)
            } else {
                showToast(tr("ファイルへのアクセスを保存できませんでした", "Couldn't save file access"))
            }
        }
    }

    override fun consumePickerRequest(requestId: Long) {
        if (mutableUiState.pendingPicker?.requestId == requestId) {
            pickerEventVisible = false
            mutableUiState = mutableUiState.copy(pendingPicker = null)
        }
    }

    /** Installs the stable Compose Activity Result launcher used for runtime permissions. */
    internal fun setPermissionResultLauncher(
        launcher: ((Array<String>) -> Unit)?,
    ) {
        permissionResultLauncher = launcher
    }

    /**
     * Called by RequestMultiplePermissions. The result map is authoritative even if this
     * controller was recreated while the platform dialog was visible; source inference handles
     * that restored callback when the in-memory request marker is absent.
     */
    internal fun onPermissionResult(result: Map<String, Boolean>) {
        if (closed) return
        val source = permissionRequestSource
            ?: inferDrawerSearchPermissionSource(result.keys, Build.VERSION.SDK_INT)
        permissionRequestSource = null
        sourceReader.invalidate(source)
        inputGeneration++
        documentGeneration++
        restartFromCurrentInputs(resetDocuments = true)
    }

    /** Called by the Compose factory whenever query, drawer visibility, or lifecycle changes. */
    internal fun updateInputs(query: String, active: Boolean, refreshToken: Int) {
        if (closed) return
        val newNormalized = normalizeDrawerSearchText(query)
        val refreshChanged = refreshToken != lastRefreshToken
        val changed = query != lastRawQuery ||
            active != isDrawerActive ||
            refreshChanged
        if (!changed) return
        lastRawQuery = query
        normalizedQuery = newNormalized
        isDrawerActive = active
        lastRefreshToken = refreshToken
        if (refreshChanged) sourceReader.invalidate()
        inputGeneration++
        documentGeneration++
        cancelSourceJobs()
        cancelDocumentJob()
        enabledSources = readDrawerSearchSourcePreferences(context).enabled
        documentTargets = refreshDocumentTargetGrantStatus(readSearchDocumentTargets(context))
        sourceResults.clear()
        documentResults = emptyList()
        documentSession = null
        mutableUiState = DrawerSearchUiState(
            query = query,
            active = active,
            sources = sourceStates(),
            documents = documentTargets,
            documentsStatus = documentsStatusForTargets(documentTargets),
            documentsStatusMessage = if (documentTargets.isEmpty()) null else tr("検索語を入力してください", "Type something to search"),
            documentsPartial = documentTargets.any { it.requiresReselection },
            pendingPicker = if (pickerEventVisible) {
                mutableUiState.pendingPicker ?: inFlightPickerKind?.let { kind ->
                    DrawerSearchPickerRequest(
                        kind = kind,
                        requestId = nextPickerRequestId.incrementAndGet(),
                        replacementTargetId = pickerReplacementTargetId,
                    )
                }
            } else {
                null
            },
        )
        if (!active || !lifecycleForeground || newNormalized.isBlank()) return
        val generation = inputGeneration
        DeviceSearchSource.values().forEach { source ->
            if (enabledSources[source] == true && permissionGateway.permissionState(source).access != DrawerSearchPermissionAccess.NONE) {
                launchSourceSearch(source, generation)
            }
        }
        if (documentTargets.any { !it.requiresReselection }) {
            launchDocumentSearch(generation, reset = true, debounce = true)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelSourceJobs()
        cancelDocumentJob()
        scope.cancel()
    }

    /** The drawer can remain composed while its Activity is stopped by an opened result app. */
    internal fun updateLifecycleForeground(resumed: Boolean) {
        if (closed || lifecycleForeground == resumed) return
        lifecycleForeground = resumed
        if (!resumed) {
            inputGeneration++
            documentGeneration++
            cancelSourceJobs()
            cancelDocumentJob()
            sourceReader.invalidate()
            documentSession = null
            sourceResults.clear()
            documentResults = emptyList()
            mutableUiState = mutableUiState.copy(
                active = false,
                contacts = emptyList(),
                files = emptyList(),
                sources = sourceStates(),
                documents = documentTargets,
                documentsLoading = false,
                canContinueDocuments = false,
                // Keep pendingPicker/inFlightPickerKind untouched for a picker round-trip.
            )
        } else {
            sourceReader.invalidate()
            restartFromCurrentInputs(resetDocuments = true)
        }
    }

    private fun restartFromCurrentInputs(resetDocuments: Boolean) {
        if (closed) return
        inputGeneration++
        documentGeneration++
        cancelSourceJobs()
        cancelDocumentJob()
        enabledSources = readDrawerSearchSourcePreferences(context).enabled
        documentTargets = refreshDocumentTargetGrantStatus(readSearchDocumentTargets(context))
        // Any settings/permission refresh cancels the old provider call. Do not let the old
        // mutable frontier be consumed concurrently by a replacement job; Continue is the only
        // operation that intentionally reuses a completed session frontier.
        documentSession = null
        sourceResults.clear()
        documentResults = emptyList()
        mutableUiState = mutableUiState.copy(
            query = lastRawQuery,
            active = isDrawerActive,
            contacts = emptyList(),
            files = emptyList(),
            sources = sourceStates(),
            documents = documentTargets,
            documentsStatus = documentsStatusForTargets(documentTargets),
            documentsStatusMessage = if (documentTargets.isEmpty()) null else tr("検索語を入力してください", "Type something to search"),
            documentsErrorMessage = null,
            documentsLoading = false,
            documentsPartial = documentTargets.any { it.requiresReselection },
            canContinueDocuments = false,
        )
        if (!isDrawerActive || !lifecycleForeground || normalizedQuery.isBlank()) return
        val generation = inputGeneration
        DeviceSearchSource.values().forEach { source ->
            if (enabledSources[source] == true && permissionGateway.permissionState(source).access != DrawerSearchPermissionAccess.NONE) {
                launchSourceSearch(source, generation)
            }
        }
        if (documentTargets.any { !it.requiresReselection }) {
            launchDocumentSearch(generation, reset = resetDocuments, debounce = true)
        }
    }

    private fun launchSourceSearch(
        source: DeviceSearchSource,
        generation: Long,
    ) {
        val queryForJob = normalizedQuery
        val permission = permissionGateway.permissionState(source)
        if (permission.access == DrawerSearchPermissionAccess.NONE) return
        val signal = CancellationSignal()
        sourceSignals[source] = signal
        setSourceState(source) {
            it.copy(
                status = SearchSourceStatus.LOADING,
                statusMessage = tr("検索中", "Searching"),
                actionLabel = null,
                errorMessage = null,
            )
        }
        val job = scope.launch(ioDispatcher) {
            try {
                delay(debounceMillis)
                signal.throwIfCanceled()
                val raw = sourceReader.search(source, queryForJob, signal)
                signal.throwIfCanceled()
                val ranked = rankDrawerSearchValues(queryForJob, raw) { it.label }
                withContext(Dispatchers.Main.immediate) {
                    if (!isCurrentSourceGeneration(source, generation, signal)) return@withContext
                    val latestPermission = permissionGateway.permissionState(source)
                    if (latestPermission.access != permission.access) {
                        // A permission result/revocation may race a slow provider call. Never
                        // publish rows read under a broader grant; invalidate and start a fresh
                        // query under the current access level.
                        sourceReader.invalidate(source)
                        sourceResults.remove(source)
                        setSourceState(source) {
                            sourceStateFor(source)
                        }
                        rebuildResults()
                        restartFromCurrentInputs(resetDocuments = true)
                        return@withContext
                    }
                    sourceResults[source] = ranked
                    val finalStatus = if (ranked.isEmpty()) {
                        SearchSourceStatus.NO_RESULTS
                    } else {
                        SearchSourceStatus.READY
                    }
                    setSourceState(source) {
                        it.copy(
                            status = finalStatus,
                            statusMessage = if (finalStatus == SearchSourceStatus.NO_RESULTS) {
                                tr("一致する項目はありません", "No matches")
                            } else {
                                null
                            },
                            actionLabel = null,
                            errorMessage = null,
                        )
                    }
                    rebuildResults()
                }
            } catch (_: CancellationException) {
                // Cancellation is expected on query/settings/permission changes.
            } catch (_: OperationCanceledException) {
                // ContentResolver cancellation can arrive as a platform exception first.
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    if (!isCurrentSourceGeneration(source, generation, signal)) return@withContext
                    sourceResults.remove(source)
                    setSourceState(source) {
                        it.copy(
                            status = SearchSourceStatus.ERROR,
                            statusMessage = tr("検索できませんでした", "Search failed"),
                            actionLabel = tr("再試行", "Retry"),
                            errorMessage = error.message,
                        )
                    }
                    rebuildResults()
                }
            }
        }
        sourceJobs[source] = job
    }

    private fun launchDocumentSearch(
        expectedInputGeneration: Long,
        reset: Boolean,
        debounce: Boolean,
    ) {
        if (!isDrawerActive || !lifecycleForeground || normalizedQuery.isBlank()) return
        if (reset || documentSession == null) {
            documentSession = SearchDocumentScanSession(
                targets = documentTargets,
                reader = documentReader,
            )
        }
        val session = documentSession ?: return
        val queryForJob = normalizedQuery
        val signal = CancellationSignal()
        documentSignal = signal
        val roundGeneration = ++documentGeneration
        mutableUiState = mutableUiState.copy(
            documentsStatus = SearchSourceStatus.LOADING,
            documentsStatusMessage = if (session.hasMore) tr("フォルダを検索中", "Searching folders") else tr("ファイルを確認中", "Checking files"),
            documentsErrorMessage = null,
            documentsLoading = true,
            documentsPartial = false,
            canContinueDocuments = false,
        )
        documentJob = scope.launch(ioDispatcher) {
            try {
                if (debounce) delay(debounceMillis)
                signal.throwIfCanceled()
                val round = session.scanRound(
                    query = queryForJob,
                    cancellation = AndroidSearchCancellation(signal),
                )
                withContext(Dispatchers.Main.immediate) {
                    if (!isCurrentDocumentGeneration(expectedInputGeneration, roundGeneration, signal)) {
                        return@withContext
                    }
                    documentResults = round.results
                    val missingTargets = documentTargets.any { it.requiresReselection }
                    mutableUiState = mutableUiState.copy(
                        documents = documentTargets,
                        documentsStatus = when {
                            missingTargets || round.hasMore -> SearchSourceStatus.PARTIAL
                            round.results.isEmpty() -> SearchSourceStatus.NO_RESULTS
                            else -> SearchSourceStatus.READY
                        },
                        documentsStatusMessage = when {
                            missingTargets -> tr("再選択が必要な項目があります", "Some items need to be selected again")
                            round.hasMore -> tr("続きがあります", "More results available")
                            round.results.isEmpty() -> tr("一致する項目はありません", "No matches")
                            else -> null
                        },
                        documentsErrorMessage = null,
                        documentsLoading = false,
                        documentsPartial = missingTargets || round.hasMore,
                        canContinueDocuments = round.hasMore,
                    )
                    rebuildResults()
                }
            } catch (_: CancellationException) {
                // Keep the existing frontier for Continue after an expected cancellation.
            } catch (_: OperationCanceledException) {
                // Same as coroutine cancellation; the session remains resumable.
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    if (!isCurrentDocumentGeneration(expectedInputGeneration, roundGeneration, signal)) {
                        return@withContext
                    }
                    mutableUiState = mutableUiState.copy(
                        documentsStatus = SearchSourceStatus.ERROR,
                        documentsStatusMessage = tr("フォルダを検索できませんでした", "Couldn't search folders"),
                        documentsErrorMessage = error.message,
                        documentsLoading = false,
                        documentsPartial = documentTargets.any { it.requiresReselection },
                        canContinueDocuments = session.hasMore,
                    )
                }
            }
        }
    }

    private fun isCurrentSourceGeneration(
        source: DeviceSearchSource,
        generation: Long,
        signal: CancellationSignal,
    ): Boolean = !closed &&
        generation == inputGeneration &&
        enabledSources[source] == true &&
        isDrawerActive &&
        lifecycleForeground &&
        normalizedQuery.isNotBlank() &&
        sourceSignals[source] === signal &&
        !signal.isCanceled

    private fun isCurrentDocumentGeneration(
        expectedInputGeneration: Long,
        roundGeneration: Long,
        signal: CancellationSignal,
    ): Boolean = !closed &&
        expectedInputGeneration == inputGeneration &&
        roundGeneration == documentGeneration &&
        documentSignal === signal &&
        isDrawerActive &&
        lifecycleForeground &&
        normalizedQuery.isNotBlank() &&
        !signal.isCanceled

    private fun cancelSourceJobs() {
        sourceSignals.values.forEach { runCatching { it.cancel() } }
        sourceSignals.clear()
        sourceJobs.values.forEach { it.cancel() }
        sourceJobs.clear()
    }

    private fun cancelDocumentJob() {
        documentSignal?.let { runCatching { it.cancel() } }
        documentSignal = null
        documentJob?.cancel()
        documentJob = null
    }

    private fun setSourceState(
        source: DeviceSearchSource,
        update: (DrawerSearchSourceState) -> DrawerSearchSourceState,
    ) {
        mutableUiState = mutableUiState.copy(
            sources = mutableUiState.sources.map { state ->
                if (state.source == source) update(state) else state
            },
        )
    }

    private fun sourceStates(): List<DrawerSearchSourceState> = DeviceSearchSource.values().map { source ->
        sourceStateFor(source)
    }

    private fun sourceStateFor(source: DeviceSearchSource): DrawerSearchSourceState {
        val enabled = enabledSources[source] == true
        val permission = permissionGateway.permissionState(source)
        val status = drawerSearchStatusForPermission(enabled, permission)
        return DrawerSearchSourceState(
            source = source,
            enabled = enabled,
            status = status,
            statusMessage = when (status) {
                SearchSourceStatus.DISABLED -> null
                SearchSourceStatus.DENIED -> tr("アクセスを許可すると検索できます", "Allow access to search")
                else -> null
            },
            actionLabel = if (status == SearchSourceStatus.DENIED) tr("アクセスを管理", "Manage access") else null,
        )
    }

    private fun rebuildResults() {
        val contacts = sourceResults[DeviceSearchSource.CONTACTS].orEmpty()
        val files = rankDrawerSearchValues(
            normalizedQuery,
            sourceResults[DeviceSearchSource.AUDIO].orEmpty() +
                documentResults,
        ) { it.label }
        mutableUiState = mutableUiState.copy(contacts = contacts, files = files)
    }

    private fun refreshDocumentTargetGrantStatus(
        targets: List<SearchDocumentTarget>,
    ): List<SearchDocumentTarget> {
        val grants = grantStore.persistedReadUris()
        return targets.map { target ->
            target.copy(
                requiresReselection = grants.none { grant ->
                    persistedReadGrantCoversTarget(grant, target.uri)
                },
            )
        }
    }

    private fun documentsStatusForTargets(
        targets: List<SearchDocumentTarget>,
    ): SearchSourceStatus = when {
        targets.isEmpty() -> SearchSourceStatus.NO_RESULTS
        targets.any { it.requiresReselection } -> SearchSourceStatus.PARTIAL
        else -> SearchSourceStatus.READY
    }

    private fun pickedDocumentTarget(
        uri: Uri,
        isTree: Boolean,
    ): SearchDocumentTarget {
        val fallback = searchDocumentTarget(uri, isTree)
        // The URI tail for DownloadsProvider is often an opaque `msf:<id>`. A metadata-only
        // projection is safe while the picker grant is still temporary and gives the UI the real
        // filename; a provider that rejects the query keeps the deterministic fallback label.
        val metadata = runCatching {
            documentReader.metadata(uri.toString(), NoopSearchCancellation)
        }.getOrNull()
        return fallback.copy(label = metadata?.label?.ifBlank { null } ?: fallback.label)
    }

    private fun launchViewIntent(uri: Uri, mimeType: String?) {
        val intent = drawerSearchViewIntent(
            uri = uri,
            mimeType = mimeType,
            addNewTask = context !is Activity,
        )
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showToast(tr("開けるアプリがありません", "No app can open this"))
        } catch (_: SecurityException) {
            showToast(tr("この項目を開けませんでした", "Couldn't open this item"))
        } catch (_: RuntimeException) {
            showToast(tr("この項目を開けませんでした", "Couldn't open this item"))
        }
    }

    private fun showToast(message: String) {
        if (closed) return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun rememberDrawerSearchController(
    query: String,
    active: Boolean,
    refreshToken: Int,
): DrawerSearchController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(context) { AndroidDrawerSearchController(context) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result: Map<String, Boolean> ->
        controller.onPermissionResult(result)
    }
    DisposableEffect(controller, permissionLauncher) {
        controller.setPermissionResultLauncher { permissions ->
            permissionLauncher.launch(permissions)
        }
        onDispose { controller.setPermissionResultLauncher(null) }
    }
    var savedPickerKind by rememberSaveable(controller) { mutableStateOf<String?>(null) }
    var savedPickerReplacementId by rememberSaveable(controller) { mutableStateOf<String?>(null) }
    val pickerStateStore = remember(controller) {
        object : DrawerSearchPickerStateStore {
            override fun kind(): DrawerSearchPickerKind? = savedPickerKind?.let {
                runCatching { DrawerSearchPickerKind.valueOf(it) }.getOrNull()
            }

            override fun replacementTargetId(): String? = savedPickerReplacementId

            override fun save(kind: DrawerSearchPickerKind, replacementTargetId: String?) {
                savedPickerKind = kind.name
                savedPickerReplacementId = replacementTargetId
            }

            override fun clear() {
                savedPickerKind = null
                savedPickerReplacementId = null
            }
        }
    }
    DisposableEffect(controller, pickerStateStore) {
        controller.setPickerStateStore(pickerStateStore)
        onDispose { controller.setPickerStateStore(null) }
    }
    var resumed by remember(controller, lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    controller.updateLifecycleForeground(true)
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> {
                    resumed = false
                    controller.updateLifecycleForeground(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller, query, active, refreshToken, resumed) {
        controller.updateInputs(query, active && resumed, refreshToken)
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}
