package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerSearchUiTest {
    @Test
    fun fileErrorWithAnotherSourceResultsIsPartial() {
        val state = fileState(
            sources = listOf(
                source(DeviceSearchSource.VISUAL_MEDIA, SearchSourceStatus.ERROR),
                source(DeviceSearchSource.AUDIO, SearchSourceStatus.READY),
            ),
            files = listOf(SearchResult(id = "audio-1", label = "音楽")),
        )

        assertEquals(SearchSourceStatus.PARTIAL, aggregateDrawerFileSearchStatus(state))
    }

    @Test
    fun documentErrorWithMediaResultsIsPartial() {
        val state = fileState(
            sources = listOf(
                source(DeviceSearchSource.VISUAL_MEDIA, SearchSourceStatus.READY),
                source(DeviceSearchSource.AUDIO, SearchSourceStatus.DISABLED),
            ),
            files = listOf(SearchResult(id = "photo-1", label = "写真")),
            documents = listOf(
                SearchDocumentTarget(
                    id = "file:broken",
                    label = "文書",
                    uri = "content://example/broken",
                    isTree = false,
                ),
            ),
            documentsStatus = SearchSourceStatus.ERROR,
        )

        assertEquals(SearchSourceStatus.PARTIAL, aggregateDrawerFileSearchStatus(state))
    }

    @Test
    fun deniedWithNoResultsRemainsDenied() {
        val state = fileState(
            sources = listOf(
                source(DeviceSearchSource.VISUAL_MEDIA, SearchSourceStatus.DENIED),
                source(DeviceSearchSource.AUDIO, SearchSourceStatus.NO_RESULTS),
            ),
        )

        assertEquals(SearchSourceStatus.DENIED, aggregateDrawerFileSearchStatus(state))
    }

    @Test
    fun successfulFileResultsSuppressNoResultsAggregateMessage() {
        val state = fileState(
            sources = listOf(
                source(
                    DeviceSearchSource.VISUAL_MEDIA,
                    SearchSourceStatus.PARTIAL,
                    statusMessage = "一部の項目だけ表示しています",
                ),
                source(DeviceSearchSource.AUDIO, SearchSourceStatus.READY),
            ),
            files = listOf(SearchResult(id = "audio-1", label = "音声1件")),
            documents = listOf(
                SearchDocumentTarget(
                    id = "file:empty",
                    label = "選択ファイル",
                    uri = "content://example/empty",
                    isTree = false,
                ),
            ),
            documentsStatus = SearchSourceStatus.NO_RESULTS,
            documentsStatusMessage = "一致する項目はありません",
        )

        assertEquals("一部の項目だけ表示しています", aggregateDrawerFileSearchMessage(state))
    }

    @Test
    fun noResultsAggregateMessageRemainsWhenAllFileSourcesAreEmpty() {
        val noResults = "一致する項目はありません"
        val state = fileState(
            sources = listOf(
                source(DeviceSearchSource.VISUAL_MEDIA, SearchSourceStatus.NO_RESULTS, noResults),
                source(DeviceSearchSource.AUDIO, SearchSourceStatus.NO_RESULTS, noResults),
            ),
            documents = listOf(
                SearchDocumentTarget(
                    id = "file:empty",
                    label = "選択ファイル",
                    uri = "content://example/empty",
                    isTree = false,
                ),
            ),
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
