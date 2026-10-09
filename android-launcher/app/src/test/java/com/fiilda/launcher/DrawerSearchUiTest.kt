package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawerSearchUiTest {
    @Test
    fun fileErrorWithAnotherSourceResultsIsPartial() {
        val state = fileState(
            sources = listOf(source(DeviceSearchSource.AUDIO, SearchSourceStatus.ERROR)),
            files = listOf(SearchResult(id = "doc-1", label = "文書")),
            documents = listOf(document("file:ok")),
            documentsStatus = SearchSourceStatus.READY,
        )

        assertEquals(SearchSourceStatus.PARTIAL, aggregateDrawerFileSearchStatus(state))
    }

    @Test
    fun documentErrorWithMediaResultsIsPartial() {
        val state = fileState(
            sources = listOf(source(DeviceSearchSource.AUDIO, SearchSourceStatus.READY)),
            files = listOf(SearchResult(id = "audio-1", label = "音楽")),
            documents = listOf(document("file:broken")),
            documentsStatus = SearchSourceStatus.ERROR,
        )

        assertEquals(SearchSourceStatus.PARTIAL, aggregateDrawerFileSearchStatus(state))
    }

    @Test
    fun deniedWithNoResultsRemainsDenied() {
        val state = fileState(
            sources = listOf(source(DeviceSearchSource.AUDIO, SearchSourceStatus.DENIED)),
        )

        assertEquals(SearchSourceStatus.DENIED, aggregateDrawerFileSearchStatus(state))
    }

    @Test
    fun successfulFileResultsSuppressNoResultsAggregateMessage() {
        val state = fileState(
            sources = listOf(source(DeviceSearchSource.AUDIO, SearchSourceStatus.READY)),
            files = listOf(SearchResult(id = "audio-1", label = "音声1件")),
            documents = listOf(document("file:empty")),
            documentsStatus = SearchSourceStatus.NO_RESULTS,
            documentsStatusMessage = "一致する項目はありません",
        )

        assertNull(aggregateDrawerFileSearchMessage(state))
    }

    @Test
    fun noResultsAggregateMessageRemainsWhenAllFileSourcesAreEmpty() {
        val noResults = "一致する項目はありません"
        val state = fileState(
            sources = listOf(source(DeviceSearchSource.AUDIO, SearchSourceStatus.NO_RESULTS, noResults)),
            documents = listOf(document("file:empty")),
            documentsStatus = SearchSourceStatus.NO_RESULTS,
            documentsStatusMessage = noResults,
        )

        assertEquals(noResults, aggregateDrawerFileSearchMessage(state))
    }

    private fun fileState(
        sources: List<DrawerSearchSourceState>,
        files: List<SearchResult> = emptyList(),
        documents: List<SearchDocumentTarget> = emptyList(),
        documentsStatus: SearchSourceStatus = SearchSourceStatus.NO_RESULTS,
        documentsStatusMessage: String? = null,
    ): DrawerSearchUiState = DrawerSearchUiState(
        query = "q",
        active = true,
        files = files,
        sources = sources,
        documents = documents,
        documentsStatus = documentsStatus,
        documentsStatusMessage = documentsStatusMessage,
    )

    private fun document(id: String): SearchDocumentTarget = SearchDocumentTarget(
        id = id,
        label = "選択ファイル",
        uri = "content://example/$id",
        isTree = false,
    )

    private fun source(
        source: DeviceSearchSource,
        status: SearchSourceStatus,
        statusMessage: String? = null,
    ): DrawerSearchSourceState = DrawerSearchSourceState(
        source = source,
        enabled = status != SearchSourceStatus.DISABLED,
        status = status,
        statusMessage = statusMessage,
    )
}
