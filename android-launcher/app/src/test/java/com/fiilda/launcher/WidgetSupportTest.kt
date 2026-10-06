package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSupportTest {
    @Test
    fun optionalWidgetConfigurationCancellationIsAcceptedForBoundMatchingId() {
        assertTrue(
            shouldAcceptWidgetConfigureResult(
                resultCode = WidgetActivityResultCanceled,
                returnedAppWidgetId = 40,
                allocatedAppWidgetId = 40,
                providerBound = true,
                configurationOptional = true,
            ),
        )
        // Some configuration activities omit the ID on cancellation; the durable allocated ID
        // is still the matching ID selected by the launcher-owned flow.
        assertTrue(
            shouldAcceptWidgetConfigureResult(
                resultCode = WidgetActivityResultCanceled,
                returnedAppWidgetId = -1,
                allocatedAppWidgetId = 40,
                providerBound = true,
                configurationOptional = true,
            ),
        )
    }

    @Test
    fun optionalWidgetConfigurationCancellationRejectsUnboundOrNonOptionalProvider() {
        assertFalse(
            shouldAcceptWidgetConfigureResult(
                resultCode = WidgetActivityResultCanceled,
                returnedAppWidgetId = 40,
                allocatedAppWidgetId = 40,
                providerBound = false,
                configurationOptional = true,
            ),
        )
        assertFalse(
            shouldAcceptWidgetConfigureResult(
                resultCode = WidgetActivityResultCanceled,
                returnedAppWidgetId = 40,
                allocatedAppWidgetId = 40,
                providerBound = true,
                configurationOptional = false,
            ),
        )
    }

    @Test
    fun successfulWidgetConfigurationResultRemainsAccepted() {
        assertTrue(
            shouldAcceptWidgetConfigureResult(
                resultCode = WidgetActivityResultOk,
                returnedAppWidgetId = 40,
                allocatedAppWidgetId = 40,
                providerBound = false,
                configurationOptional = false,
            ),
        )
    }

    @Test
    fun widgetConfigurationResultWithMismatchedIdIsRejected() {
        assertFalse(
            shouldAcceptWidgetConfigureResult(
                resultCode = WidgetActivityResultOk,
                returnedAppWidgetId = 41,
                allocatedAppWidgetId = 40,
                providerBound = true,
                configurationOptional = true,
            ),
        )
        assertFalse(
            shouldAcceptWidgetConfigureResult(
                resultCode = WidgetActivityResultCanceled,
                returnedAppWidgetId = 41,
                allocatedAppWidgetId = 40,
                providerBound = true,
                configurationOptional = true,
            ),
        )
    }

    @Test
    fun rejectedActivityResultCleansOnlyTheKnownAllocatedId() {
        assertEquals(
            listOf(40),
            widgetActivityResultCleanupIds(
                allocatedAppWidgetId = 40,
                returnedAppWidgetId = 41,
            ),
        )
        assertEquals(
            emptyList<Int>(),
            widgetActivityResultCleanupIds(
                allocatedAppWidgetId = null,
                returnedAppWidgetId = 41,
            ),
        )
        assertEquals(
            emptyList<Int>(),
            widgetActivityResultCleanupIds(
                allocatedAppWidgetId = 0,
                returnedAppWidgetId = 41,
            ),
        )
    }

    @Test
    fun durablePendingMarkerWinsOverStaleSavedInstanceState() {
        assertEquals(
            PendingWidgetRestore(
                appWidgetId = 42,
                resultReady = false,
                savedStateWasStale = true,
            ),
            resolvePendingWidgetRestore(
                savedAppWidgetId = 12,
                savedResultReady = true,
                persistedAppWidgetId = 42,
                persistedResultReady = false,
            ),
        )
        assertEquals(
            PendingWidgetRestore(
                appWidgetId = null,
                resultReady = false,
                savedStateWasStale = true,
            ),
            resolvePendingWidgetRestore(
                savedAppWidgetId = 12,
                savedResultReady = true,
                persistedAppWidgetId = null,
                persistedResultReady = false,
            ),
        )
        assertFalse(
            resolvePendingWidgetRestore(
                savedAppWidgetId = 42,
                savedResultReady = true,
                persistedAppWidgetId = 42,
                persistedResultReady = true,
            ).savedStateWasStale,
        )
    }

    @Test
    fun persistedPendingPageWinsWhenSavedBundleIdOrPageIsStale() {
        val mismatched = resolvePendingWidgetRestore(
            savedAppWidgetId = 12,
            savedResultReady = true,
            savedHomePage = 0,
            persistedAppWidgetId = 42,
            persistedResultReady = true,
            persistedHomePage = 1,
        )
        assertEquals(42, mismatched.appWidgetId)
        assertTrue(mismatched.savedStateWasStale)
        assertEquals(1, mismatched.targetHomePage)

        val matching = resolvePendingWidgetRestore(
            savedAppWidgetId = 42,
            savedResultReady = true,
            savedHomePage = 1,
            persistedAppWidgetId = 42,
            persistedResultReady = true,
            persistedHomePage = 1,
        )
        assertFalse(matching.savedStateWasStale)
        assertEquals(1, matching.targetHomePage)
    }

    @Test
    fun providerDimensionsBecomeGridSpansAndClampWidth() {
        val size = calculateWidgetGridSpans(
            spec = WidgetSizeSpec(
                minWidthDp = 310,
                minHeightDp = 180,
                minResizeWidthDp = 250,
                minResizeHeightDp = 140,
            ),
            cellWidthDp = 100f,
            gapDp = 3f,
            columns = 2,
        )
        assertEquals(2, size.columnSpan)
        assertEquals(2, size.rowSpan)
    }

    @Test
    fun largerResizeMinimumDoesNotEnlargeNativeDefaultFootprint() {
        val spec = WidgetSizeSpec(
            minWidthDp = 100,
            minHeightDp = 80,
            minResizeWidthDp = 360,
            minResizeHeightDp = 240,
            resizeMode = WidgetResizeBoth,
        )
        assertEquals(100, spec.effectiveMinWidthDp)
        assertEquals(80, spec.effectiveMinHeightDp)
        assertEquals(
            WidgetGridSize(columnSpan = 2, rowSpan = 2),
            calculateWidgetGridSpans(spec, cellWidthDp = 72f, gapDp = 3f, columns = 6),
        )
    }

    @Test
    fun defaultHostPaddingIsAddedAroundProviderContentBeforeSpanConversion() {
        val spec = WidgetSizeSpec(
            minWidthDp = 190,
            minHeightDp = 90,
            defaultPaddingHorizontalDp = 20,
            defaultPaddingVerticalDp = 10,
        )
        assertEquals(210, spec.effectiveMinWidthDp)
        assertEquals(100, spec.effectiveMinHeightDp)
        assertEquals(
            WidgetGridSize(columnSpan = 3, rowSpan = 1),
            calculateWidgetGridSpans(
                spec = spec,
                cellWidthDp = 100f,
                gapDp = 3f,
                columns = 6,
            ),
        )
    }

    @Test
    fun positiveTargetCellFootprintWinsOverPaddedDimensionsAndOnlyWidthClamps() {
        val spec = WidgetSizeSpec(
            minWidthDp = 900,
            minHeightDp = 900,
            defaultPaddingHorizontalDp = 100,
            defaultPaddingVerticalDp = 100,
            targetCellWidth = 3,
            targetCellHeight = 4,
        )
        assertEquals(
            WidgetGridSize(columnSpan = 3, rowSpan = 4),
            calculateWidgetGridSpans(spec, cellWidthDp = 100f, gapDp = 3f, columns = 3),
        )
        assertEquals(
            WidgetGridSize(columnSpan = 3, rowSpan = 4),
            calculateWidgetGridSpans(spec, cellWidthDp = 100f, gapDp = 3f, columns = 6),
        )
    }

    @Test
    fun targetCellAxisFallsBackIndependentlyWhenOnlyOneAxisIsDeclared() {
        val spec = WidgetSizeSpec(
            minWidthDp = 80,
            minHeightDp = 210,
            targetCellWidth = 2,
        )
        assertEquals(
            WidgetGridSize(columnSpan = 2, rowSpan = 3),
            calculateWidgetGridSpans(spec, cellWidthDp = 100f, gapDp = 3f, columns = 6),
        )
    }

    @Test
    fun providerPixelDimensionsConvertToDpUsingDensity() {
        val spec = widgetSizeSpecFromProviderPixels(
            minWidthPx = 300,
            minHeightPx = 180,
            minResizeWidthPx = 240,
            minResizeHeightPx = 120,
            resizeMode = WidgetResizeHorizontal,
            density = 3f,
        )
        assertEquals(100, spec.minWidthDp)
        assertEquals(60, spec.minHeightDp)
        assertEquals(80, spec.minResizeWidthDp)
        assertEquals(40, spec.minResizeHeightDp)
        assertEquals(100, spec.effectiveMinWidthDp)
        // A horizontal-only provider must retain its original height; minResizeHeight is ignored.
        assertEquals(60, spec.effectiveMinHeightDp)
    }

    @Test
    fun providerPaddingPixelsAndTargetCellsConvertWithoutLosingUnits() {
        val spec = widgetSizeSpecFromProviderPixels(
            minWidthPx = 300,
            minHeightPx = 180,
            minResizeWidthPx = 0,
            minResizeHeightPx = 0,
            resizeMode = WidgetResizeBoth,
            density = 3f,
            defaultPaddingHorizontalPx = 12,
            defaultPaddingVerticalPx = 6,
            targetCellWidth = 2,
            targetCellHeight = 3,
        )
        assertEquals(4, spec.defaultPaddingHorizontalDp)
        assertEquals(2, spec.defaultPaddingVerticalDp)
        assertEquals(2, spec.targetCellWidth)
        assertEquals(3, spec.targetCellHeight)
        assertEquals(104, spec.effectiveMinWidthDp)
        assertEquals(62, spec.effectiveMinHeightDp)
    }

    @Test
    fun multiRowGridFootprintIncludesEveryInternalGap() {
        assertEquals(306f, calculateGridRowHeightDp(100f, rowSpan = 3, gapDp = 3f), 0.001f)
        assertEquals(
            512f,
            calculateGridBoardHeightDp(100f, rowSpans = listOf(3, 2), gapDp = 3f),
            0.001f,
        )
    }

    @Test
    fun descriptorCodecRoundTripsLabelsWithSeparators() {
        val original = LauncherWidgetDescriptor(
            appWidgetId = 42,
            provider = "com.example/.WidgetProvider",
            label = "予定\t今日\n次",
            sizeSpec = WidgetSizeSpec(120, 80, 90, 70, resizeMode = WidgetResizeHorizontal),
        )
        val parsed = parseWidgetDescriptors(serializeWidgetDescriptors(listOf(original)))
        assertEquals(listOf(original), parsed)
    }

    @Test
    fun malformedAndDuplicateDescriptorsAreIgnored() {
        val valid = LauncherWidgetDescriptor(
            appWidgetId = 7,
            provider = "com.example/.WidgetProvider",
            label = "Widget",
            sizeSpec = WidgetSizeSpec(100, 100),
        )
        val encoded = serializeWidgetDescriptors(listOf(valid))
        val parsed = parseWidgetDescriptors(
            "$encoded\n$encoded\nnot-a-widget",
        )
        assertEquals(listOf(valid), parsed)
        assertTrue(parseWidgetDescriptors(null).isEmpty())
    }

    @Test
    fun sevenFieldDescriptorRowsRemainBackwardCompatible() {
        val descriptor = LauncherWidgetDescriptor(
            appWidgetId = 8,
            provider = "com.example/.LegacyWidget",
            label = "Legacy",
            sizeSpec = WidgetSizeSpec(100, 80, 90, 70),
        )
        val sevenFields = serializeWidgetDescriptors(listOf(descriptor))
            .split('\t')
            .take(7)
            .joinToString("\t")
        assertEquals(descriptor, parseWidgetDescriptors(sevenFields).single())
    }

    @Test
    fun oldEightFieldDescriptorRowsRemainBackwardCompatible() {
        val provider = "com.example/.OldWidget"
        val label = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("Old".toByteArray(Charsets.UTF_8))
        val row = listOf("9", provider.toByteArray().let {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }, label, "100", "80", "90", "70", WidgetResizeHorizontal.toString())
            .joinToString("\t")
        val parsed = parseWidgetDescriptors(row).single()
        assertEquals(provider, parsed.provider)
        assertEquals("Old", parsed.label)
        assertEquals(0, parsed.sizeSpec.targetCellWidth)
        assertEquals(0, parsed.sizeSpec.defaultPaddingHorizontalDp)
    }

    @Test
    fun newDescriptorCodecRoundTripsPaddingAndTargetCellsAndMigratesTenFields() {
        val descriptor = LauncherWidgetDescriptor(
            appWidgetId = 19,
            provider = "com.example/.ResponsiveWidget",
            label = "Responsive",
            sizeSpec = WidgetSizeSpec(
                minWidthDp = 120,
                minHeightDp = 80,
                minResizeWidthDp = 90,
                minResizeHeightDp = 70,
                resizeMode = WidgetResizeBoth,
                defaultPaddingHorizontalDp = 8,
                defaultPaddingVerticalDp = 6,
                targetCellWidth = 2,
                targetCellHeight = 3,
            ),
        )
        assertEquals(
            listOf(descriptor),
            parseWidgetDescriptors(serializeWidgetDescriptors(listOf(descriptor))),
        )
        assertEquals(
            listOf(descriptor.copy(sizeSpec = descriptor.sizeSpec.copy(targetCellHeight = 0))),
            parseWidgetDescriptors(serializeWidgetDescriptors(listOf(descriptor)).substringBeforeLast('\t')),
        )

        val encodedProvider = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(descriptor.provider.toByteArray(Charsets.UTF_8))
        val encodedLabel = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(descriptor.label.toByteArray(Charsets.UTF_8))
        val tenFieldRow = listOf(
            "19",
            encodedProvider,
            encodedLabel,
            "120",
            "80",
            "90",
            "70",
            WidgetResizeBoth.toString(),
            "2",
            "3",
        ).joinToString("\t")
        val migrated = parseWidgetDescriptors(tenFieldRow).single()
        assertEquals(2, migrated.sizeSpec.targetCellWidth)
        assertEquals(3, migrated.sizeSpec.targetCellHeight)
        assertEquals(0, migrated.sizeSpec.defaultPaddingHorizontalDp)
    }

    @Test
    fun persistedHomeOrderDoesNotResurrectRemovedBuiltInWidget() {
        val stored = listOf("widget:clock", "app.example/.Main")
        assertEquals(
            stored,
            normalizeHomeOrder(stored, favoriteIds = listOf("app.example/.Main")),
        )
    }

    @Test
    fun persistedPhotoWidgetRemainsInHomeOrderWhileFreshDefaultsStayOptIn() {
        assertEquals(
            listOf("widget:photo"),
            normalizeHomeOrder(stored = listOf("widget:photo"), favoriteIds = emptyList()),
        )
        assertFalse(
            normalizeHomeOrder(stored = null, favoriteIds = emptyList())
                .contains("widget:photo"),
        )
    }

    @Test
    fun homeOrderAdditionsAreDistinct() {
        assertEquals(
            listOf("app", "widget:external:12"),
            normalizeHomeOrder(
                stored = listOf("app"),
                favoriteIds = listOf("app", "app"),
                externalWidgetIds = listOf("widget:external:12", "widget:external:12"),
            ),
        )
    }

    @Test
    fun widgetSizeChoicesMapToExactRowsAndColumns() {
        assertEquals(
            listOf(
                WidgetGridSize(columnSpan = 2, rowSpan = 1),
                WidgetGridSize(columnSpan = 2, rowSpan = 2),
                WidgetGridSize(columnSpan = 4, rowSpan = 1),
                WidgetGridSize(columnSpan = 4, rowSpan = 2),
            ),
            FixedWidgetSizeChoices.map { WidgetGridSize(it.columnSpan, it.rowSpan) },
        )
        assertEquals("1×2", WidgetSizeChoice.ROW_1_COLUMN_2.label)
        assertEquals("自動（元のサイズ）", WidgetSizeChoice.AUTO.label)
    }

    @Test
    fun photoWidgetExposesDedicatedPortraitSquareAndPosterSizes() {
        assertEquals(
            listOf(
                WidgetGridSize(columnSpan = 1, rowSpan = 1),
                WidgetGridSize(columnSpan = 2, rowSpan = 1),
                WidgetGridSize(columnSpan = 1, rowSpan = 2),
                WidgetGridSize(columnSpan = 2, rowSpan = 2),
                WidgetGridSize(columnSpan = 4, rowSpan = 2),
                WidgetGridSize(columnSpan = 4, rowSpan = 4),
            ),
            PhotoWidgetSizeChoices.map { WidgetGridSize(it.columnSpan, it.rowSpan) },
        )
    }

    @Test
    fun exifOrientationMapsRotationAndMirrorTransforms() {
        assertEquals(PhotoFrameExifTransform(rotationDegrees = 0), photoFrameExifTransform(1))
        assertEquals(
            PhotoFrameExifTransform(rotationDegrees = 0, mirrorHorizontal = true),
            photoFrameExifTransform(2),
        )
        assertEquals(PhotoFrameExifTransform(rotationDegrees = 180), photoFrameExifTransform(3))
        assertEquals(
            PhotoFrameExifTransform(rotationDegrees = 180, mirrorHorizontal = true),
            photoFrameExifTransform(4),
        )
        assertEquals(
            PhotoFrameExifTransform(rotationDegrees = 90, mirrorHorizontal = true),
            photoFrameExifTransform(5),
        )
        assertEquals(PhotoFrameExifTransform(rotationDegrees = 90), photoFrameExifTransform(6))
        assertEquals(
            PhotoFrameExifTransform(rotationDegrees = 270, mirrorHorizontal = true),
            photoFrameExifTransform(7),
        )
        assertEquals(PhotoFrameExifTransform(rotationDegrees = 270), photoFrameExifTransform(8))
        assertEquals(PhotoFrameExifTransform(rotationDegrees = 0), photoFrameExifTransform(99))
    }

    @Test
    fun photoHomeOrderHelpersAddOnlyForInitialSelectionAndRemoveEveryOccurrence() {
        val original = listOf("widget:clock")
        assertEquals(
            listOf("widget:clock", PhotoWidgetHomeId),
            photoHomeOrderAfterSelection(original, addToHome = true),
        )
        assertEquals(
            original,
            photoHomeOrderAfterSelection(original, addToHome = false),
        )
        assertEquals(
            listOf("widget:clock", PhotoWidgetHomeId),
            photoHomeOrderAfterSelection(
                listOf("widget:clock", PhotoWidgetHomeId),
                addToHome = true,
            ),
        )
        assertEquals(
            listOf("widget:clock"),
            photoHomeOrderAfterRemoval(
                listOf(PhotoWidgetHomeId, "widget:clock", PhotoWidgetHomeId),
            ),
        )
    }

    @Test
    fun photoSizeOverrideRoundTripsWithoutChangingLegacyTokens() {
        val original = mapOf(
            "widget:photo" to WidgetSizeChoice.ROW_4_COLUMN_4,
            "widget:clock" to WidgetSizeChoice.ROW_2_COLUMN_2,
        )
        assertEquals(original, parseWidgetSizeOverrides(serializeWidgetSizeOverrides(original)))
        assertEquals(
            WidgetSizeChoice.ROW_1_COLUMN_2,
            parseWidgetSizeOverrides("widget:clock\tROW_1_COLUMN_2")["widget:clock"],
        )
    }

    @Test
    fun widgetSizeCodecRejectsMalformedUnknownAndPrunesMissingItems() {
        val raw = listOf(
            "widget:clock\tROW_1_COLUMN_2",
            "widget:external:17\tROW_2_COLUMN_4",
            "widget:missing\tROW_2_COLUMN_2",
            "widget:external:-2\tAUTO",
            "widget:external:18\tNOT_A_SIZE",
            "broken",
        ).joinToString("\n")
        val parsed = parseWidgetSizeOverrides(raw)
        assertEquals(
            mapOf(
                "widget:clock" to WidgetSizeChoice.ROW_1_COLUMN_2,
                "widget:external:17" to WidgetSizeChoice.ROW_2_COLUMN_4,
            ),
            parsed,
        )
        assertEquals(
            mapOf("widget:clock" to WidgetSizeChoice.ROW_1_COLUMN_2),
            pruneWidgetSizeOverrides(parsed, setOf("widget:clock")),
        )
        assertEquals(
            emptyMap<String, WidgetSizeChoice>(),
            parseWidgetSizeOverrides(
                "widget:external:17\tROW_1_COLUMN_2",
                knownHomeIds = setOf("widget:clock"),
            ),
        )
    }

    @Test
    fun widgetSizeCodecRoundTripsExplicitExternalAuto() {
        val original = mapOf(
            "widget:clock" to WidgetSizeChoice.ROW_2_COLUMN_4,
            "widget:external:22" to WidgetSizeChoice.AUTO,
        )
        assertEquals(original, parseWidgetSizeOverrides(serializeWidgetSizeOverrides(original)))
    }

    @Test
    fun widgetDefaultsResolveBuiltInAndExternalSizesAndBoardHeight() {
        val provider = WidgetGridSize(columnSpan = 3, rowSpan = 3)
        assertEquals(
            WidgetGridSize(columnSpan = 2, rowSpan = 2),
            resolveWidgetGridSize(
                choice = null,
                providerSize = provider,
                columns = 4,
                builtIn = true,
            ),
        )
        assertEquals(
            provider,
            resolveWidgetGridSize(
                choice = null,
                providerSize = provider,
                columns = 4,
                builtIn = false,
            ),
        )
        assertEquals(
            WidgetGridSize(columnSpan = 4, rowSpan = 1),
            resolveWidgetGridSize(
                choice = WidgetSizeChoice.ROW_1_COLUMN_4,
                providerSize = provider,
                columns = 4,
                builtIn = false,
            ),
        )
        assertEquals(
            306f,
            calculateGridBoardHeightDp(cellWidthDp = 100f, rowSpans = listOf(1, 2), gapDp = 3f),
            0.001f,
        )
    }

    @Test
    fun pickerKeepsLegacyAndExplicitHomeScreenCategoriesAndRejectsOtherOnlyProviders() {
        assertTrue(isHomeScreenWidgetCategory(0))
        assertTrue(isHomeScreenWidgetCategory(WidgetCategoryHomeScreen))
        assertTrue(isHomeScreenWidgetCategory(WidgetCategoryHomeScreen or 2))
        assertFalse(isHomeScreenWidgetCategory(2))
    }

    @Test
    fun hideFromPickerIsOnlyAHintForTheLauncherOwnedPicker() {
        assertTrue(
            shouldIncludeWidgetProviderInPicker(
                providerAvailable = true,
                widgetCategory = WidgetCategoryHomeScreen,
                hideFromPicker = true,
            ),
        )
        assertTrue(
            shouldIncludeWidgetProviderInPicker(
                providerAvailable = true,
                widgetCategory = WidgetCategoryHomeScreen,
                hideFromPicker = false,
            ),
        )
    }

    @Test
    fun launcherOwnedPickerStillRequiresProviderAndHomeScreenCategory() {
        assertFalse(
            shouldIncludeWidgetProviderInPicker(
                providerAvailable = false,
                widgetCategory = WidgetCategoryHomeScreen,
                hideFromPicker = true,
            ),
        )
        assertFalse(
            shouldIncludeWidgetProviderInPicker(
                providerAvailable = true,
                widgetCategory = 2,
                hideFromPicker = true,
            ),
        )
    }

    @Test
    fun pickerGroupIdentityIncludesProfileAndNormalizesEmptyParts() {
        assertEquals(
            WidgetPickerGroupKey("com.example", "work"),
            widgetPickerGroupKey("  com.example ", " work "),
        )
        assertEquals(
            WidgetPickerGroupKey("com.example", "current"),
            widgetPickerGroupKey("com.example", "  "),
        )
        assertTrue(
            widgetPickerGroupKey("com.example", "personal") !=
                widgetPickerGroupKey("com.example", "work"),
        )
    }

    @Test
    fun pickerFootprintLabelUsesPhysicalRowsThenColumns() {
        assertEquals(
            "標準 2×4",
            widgetPickerFootprintLabel(WidgetGridSize(columnSpan = 4, rowSpan = 2)),
        )
        assertEquals(
            "標準 1×1",
            widgetPickerFootprintLabel(WidgetGridSize(columnSpan = 0, rowSpan = 0)),
        )
    }

    @Test
    fun preferredPickerPackageExpandsEveryProfileGroup() {
        val personal = widgetPickerGroupKey("com.example", "personal")
        val work = widgetPickerGroupKey("com.example", "work")
        val other = widgetPickerGroupKey("com.other", "personal")
        assertEquals(
            setOf(personal, work),
            preferredWidgetPickerGroupKeys(
                groups = listOf(personal, work, other),
                preferredPackage = "com.example",
            ),
        )
    }
}
