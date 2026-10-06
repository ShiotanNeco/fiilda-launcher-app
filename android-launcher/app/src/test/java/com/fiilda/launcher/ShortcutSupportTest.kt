package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutSupportTest {
    @Test
    fun shortcutCellIconSizeUsesNormalAppTileDimension() {
        assertEquals(63, shortcutCellIconSizeDp())
    }

    @Test
    fun folderAppLabelsUseNormalHomeTileSizesForEachPosture() {
        assertEquals(17, homeAppTileLabelFontSizeSp(Posture.COVER))
        assertEquals(15, homeAppTileLabelFontSizeSp(Posture.INNER_PORTRAIT))
        assertEquals(15, homeAppTileLabelFontSizeSp(Posture.INNER_LANDSCAPE))
    }

    @Test
    fun appTileContentModeDefaultsToShortcutPresentation() {
        assertEquals(
            AppTileContentMode.SHORTCUTS,
            appTileContentModeFor("missing", emptyMap()),
        )
        assertEquals(
            AppTileContentMode.SHORTCUTS,
            appTileContentModeFor("default", mapOf("other" to AppTileContentMode.APP_ONLY)),
        )
    }

    @Test
    fun appTileContentModesRoundTripOnlyNonDefaultEntriesInStableOrder() {
        val modes = mapOf(
            "z/app" to AppTileContentMode.APP_ONLY,
            "default/app" to AppTileContentMode.SHORTCUTS,
            "a/app" to AppTileContentMode.APP_ONLY,
        )

        val serialized = serializeAppTileContentModes(modes)

        assertEquals("a/app\tAPP_ONLY\nz/app\tAPP_ONLY", serialized)
        assertEquals(
            mapOf(
                "a/app" to AppTileContentMode.APP_ONLY,
                "z/app" to AppTileContentMode.APP_ONLY,
            ),
            parseAppTileContentModes(serialized),
        )
    }

    @Test
    fun appTileContentModesIgnoreMalformedRowsAndCollapseDuplicates() {
        val parsed = parseAppTileContentModes(
            "first/app\tAPP_ONLY\n" +
                "broken-row\n" +
                "first/app\tAPP_ONLY\n" +
                "default/app\tSHORTCUTS\n" +
                "unknown/app\tNOT_A_MODE\n" +
                "\tAPP_ONLY",
        )

        assertEquals(
            mapOf("first/app" to AppTileContentMode.APP_ONLY),
            parsed,
        )
    }

    @Test
    fun appTileContentModesPruneRemovedFavoritesAndDefaultValues() {
        val modes = mapOf(
            "kept/app" to AppTileContentMode.APP_ONLY,
            "removed/app" to AppTileContentMode.APP_ONLY,
            "default/app" to AppTileContentMode.SHORTCUTS,
        )

        assertEquals(
            mapOf("kept/app" to AppTileContentMode.APP_ONLY),
            pruneAppTileContentModes(modes, setOf("kept/app", "default/app")),
        )
    }

    @Test
    fun emptyInputAndWhitespaceLabelsDoNotCreateActions() {
        assertTrue(selectPublicLauncherShortcuts(emptyList()).isEmpty())
        assertTrue(
            selectPublicLauncherShortcuts(
                listOf(
                    PublicLauncherShortcutCandidate("blank-short", "  ", longLabel = "  "),
                    PublicLauncherShortcutCandidate("blank-long", "", longLabel = "\t"),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun selectsAtMostThreeEnabledLabelledShortcutsByRankThenOrder() {
        val selected = selectPublicLauncherShortcuts(
            listOf(
                PublicLauncherShortcutCandidate("third", "Third", rank = 2, order = 0),
                PublicLauncherShortcutCandidate("ranked-later", "Later", rank = 1, order = 4),
                PublicLauncherShortcutCandidate("ranked-first", "First", rank = 1, order = 1),
                PublicLauncherShortcutCandidate("disabled", "Disabled", rank = 0, enabled = false),
                PublicLauncherShortcutCandidate("missing-label", "  ", longLabel = "  "),
                PublicLauncherShortcutCandidate("fourth", "Fourth", rank = 3, order = 0),
            ),
        )

        assertEquals(listOf("ranked-first", "ranked-later", "third"), selected.map { it.id })
        assertTrue(selected.size <= MaxLargeTileShortcuts)
    }

    @Test
    fun duplicateIdsKeepTheBestUsableRecordAndLongLabelCanBeDisplayed() {
        val selected = selectPublicLauncherShortcuts(
            listOf(
                PublicLauncherShortcutCandidate("mail", "", longLabel = "Old mail", rank = 4),
                PublicLauncherShortcutCandidate("mail", "", longLabel = "Read mail", rank = 2, order = 2),
                PublicLauncherShortcutCandidate("calendar", "Calendar", rank = 2, order = 1),
            ),
        )

        assertEquals(listOf("calendar", "mail"), selected.map { it.id })
        assertEquals(2, selected.last().order)
        assertEquals("Read mail", shortcutDisplayLabel(selected.last()))
    }

    @Test
    fun equalRankAndOrderUseIdForStableTieBreakAndNonPositiveLimitIsEmpty() {
        val candidates = listOf(
            PublicLauncherShortcutCandidate("z", "Zed", rank = 0, order = 0),
            PublicLauncherShortcutCandidate("a", "Alpha", rank = 0, order = 0),
            PublicLauncherShortcutCandidate("unknown", "Unknown", rank = -1, order = -1),
        )

        assertEquals(listOf("a", "z", "unknown"), selectPublicLauncherShortcuts(candidates).map { it.id })
        assertEquals(listOf("a", "z"), selectPublicLauncherShortcuts(candidates, maxCount = 2).map { it.id })
        assertTrue(selectPublicLauncherShortcuts(candidates, maxCount = 0).isEmpty())
    }
}
