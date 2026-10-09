package com.fiilda.launcher

import android.net.Uri

/** Sources that can be explicitly enabled from the drawer search controls. */
internal enum class DeviceSearchSource(
    private val jaLabel: String,
    private val enLabel: String,
) {
    CONTACTS("連絡先", "Contacts"),
    AUDIO("音楽", "Music"),
    ;

    val label: String get() = tr(jaLabel, enLabel)
}

/** A web destination exposed by the drawer's external search actions. */
internal enum class ExternalSearchTarget(
    private val jaLabel: String,
    private val enLabel: String,
) {
    GOOGLE("Google", "Google"),
    MAPS("マップ", "Maps"),
    YOUTUBE("YouTube", "YouTube"),
    ;

    val label: String get() = tr(jaLabel, enLabel)
}

/** The lifecycle/access result for one independently queried source. */
internal enum class SearchSourceStatus {
    DISABLED,
    LOADING,
    READY,
    NO_RESULTS,
    DENIED,
    PARTIAL,
    ERROR,
}

/** Alias retained for callers that describe source status as a load status. */
internal typealias DrawerSearchLoadStatus = SearchSourceStatus

/** A result containing only metadata needed to render and open a drawer row. */
internal data class SearchResult(
    val id: String,
    val label: String,
    val subtitle: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
    val source: DeviceSearchSource? = null,
) {
    /** Convenience conversion for result openers and picker clients. */
    fun contentUri(): Uri? = uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
}

internal data class DrawerSearchSourceState(
    val source: DeviceSearchSource,
    val label: String = source.label,
    val enabled: Boolean = false,
    val status: SearchSourceStatus = SearchSourceStatus.DISABLED,
    val statusMessage: String? = null,
    val actionLabel: String? = null,
    val errorMessage: String? = null,
) {
    val isLoading: Boolean
        get() = status == SearchSourceStatus.LOADING

    val isPartial: Boolean
        get() = status == SearchSourceStatus.PARTIAL

    /** A compact property useful to switch controls without repeating enum checks. */
    val canShowResults: Boolean
        get() = enabled && status != SearchSourceStatus.DENIED
}

internal data class SearchDocumentTarget(
    val id: String,
    val label: String,
    val uri: String,
    val isTree: Boolean,
    val requiresReselection: Boolean = false,
)

/** Alias for UI code that uses the longer target name from the product copy. */
internal typealias DrawerSearchDocumentTarget = SearchDocumentTarget

internal enum class DrawerSearchPickerKind {
    FOLDER,
    DOCUMENTS,
}

internal data class DrawerSearchPickerRequest(
    val kind: DrawerSearchPickerKind,
    val requestId: Long,
    /** Non-null only when the picker must replace an existing revoked target. */
    val replacementTargetId: String? = null,
)

internal data class DrawerSearchUiState(
    val query: String = "",
    val active: Boolean = false,
    val contacts: List<SearchResult> = emptyList(),
    val files: List<SearchResult> = emptyList(),
    val sources: List<DrawerSearchSourceState> = defaultDrawerSearchSourceStates(),
    val documents: List<SearchDocumentTarget> = emptyList(),
    val documentsStatus: SearchSourceStatus = SearchSourceStatus.NO_RESULTS,
    val documentsStatusMessage: String? = null,
    val documentsErrorMessage: String? = null,
    val documentsLoading: Boolean = false,
    val documentsPartial: Boolean = false,
    val canContinueDocuments: Boolean = false,
    val pendingPicker: DrawerSearchPickerRequest? = null,
) {
    val contactsStatus: SearchSourceStatus
        get() = sources.firstOrNull { it.source == DeviceSearchSource.CONTACTS }
            ?.status ?: SearchSourceStatus.DISABLED

    val audioStatus: SearchSourceStatus
        get() = sources.firstOrNull { it.source == DeviceSearchSource.AUDIO }
            ?.status ?: SearchSourceStatus.DISABLED

    val sourceStates: List<DrawerSearchSourceState>
        get() = sources

    val documentsState: SearchSourceStatus
        get() = documentsStatus

    val isDocumentsLoading: Boolean
        get() = documentsLoading
}

internal fun defaultDrawerSearchSourceStates(): List<DrawerSearchSourceState> =
    DeviceSearchSource.values().map { source ->
        DrawerSearchSourceState(source = source)
    }

/** Picker callbacks are kept on the controller so cancellation can leave state untouched. */
internal interface DrawerSearchController {
    val state: DrawerSearchUiState

    fun toggleSource(source: DeviceSearchSource, enabled: Boolean)
    fun manageAccess(source: DeviceSearchSource)
    fun addFolder()
    fun addDocuments()
    /** Replaces the selected URI for an existing folder/file target atomically. */
    fun reselectDocumentTarget(id: String)
    fun removeDocumentTarget(id: String)
    fun refresh()
    fun continueDocuments()
    fun openResult(result: SearchResult)
    fun openExternal(target: ExternalSearchTarget)

    /** Called by the drawer after the folder Activity Result returns. */
    fun onFolderPicked(uri: Uri?)

    /** Called by the drawer after the multiple document Activity Result returns. */
    fun onDocumentsPicked(uris: List<Uri>)

    /** Marks the state-driven picker request as observed by the UI. */
    fun consumePickerRequest(requestId: Long)

    /** Releases the controller's coroutine scope when its remembered owner leaves composition. */
    fun close()
}
