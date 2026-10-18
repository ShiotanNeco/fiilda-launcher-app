package com.fiilda.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiiLDALogoIconTest {
    @Test
    fun logoSpellsAnFWithTheBlueAccentsOnTheRight() {
        assertEquals(8, FiiLDALogoTiles.flatten().count { it != FiiLDALogoTile.EMPTY })
        assertEquals(FiiLDALogoTile.SECONDARY, FiiLDALogoTiles[0][2])
        assertEquals(FiiLDALogoTile.PRIMARY, FiiLDALogoTiles[1][1])
        assertEquals(FiiLDALogoTile.OUTLINE, FiiLDALogoTiles[1][2])
        assertEquals(FiiLDALogoTile.EMPTY, FiiLDALogoTiles[2][2])
    }

    @Test
    fun waveStartsAtTheBottomLeftAndEndsAtTheTopRight() {
        assertEquals(0, fiildaLogoWaveOrder(row = 2, column = 0))
        assertEquals(4, fiildaLogoWaveOrder(row = 0, column = 2))
        val earlyMs = FiiLDALogoWaveTileMs / 2f
        assertTrue(fiildaLogoWaveScale(row = 2, column = 0, elapsedMs = earlyMs) > 1f)
        assertEquals(1f, fiildaLogoWaveScale(row = 0, column = 2, elapsedMs = earlyMs), 0f)
    }

    @Test
    fun everyTileRestsAtNormalSizeBeforeAndAfterTheWave() {
        for (row in 0..2) {
            for (column in 0..2) {
                assertEquals(1f, fiildaLogoWaveScale(row, column, elapsedMs = 0f), 0f)
                assertEquals(1f, fiildaLogoWaveScale(row, column, FiiLDALogoWaveDurationMs), 0f)
            }
        }
    }

    @Test
    fun accentsFollowTheThemeAndClassicKeepsRedAsItsOnlyAccent() {
        val default = launcherPaletteFor(LauncherTheme.DEFAULT)
        val defaultAccents = fiildaLogoAccents(WidgetLanguage.KEYBOARD, default, default.muted, onAccent = false)
        assertEquals(default.accent, defaultAccents.primary)

        val classic = launcherPaletteFor(LauncherTheme.CLASSIC)
        val classicAccents = fiildaLogoAccents(WidgetLanguage.NISHIKIGOI, classic, classic.muted, onAccent = false)
        assertEquals(NISHIKIGOI_RED, classicAccents.primary)
        assertEquals(classic.line, classicAccents.secondary)
    }

    @Test
    fun onTheAccentFillBothAccentsBecomeALighterTint() {
        val palette = launcherPaletteFor(LauncherTheme.DEFAULT)
        val accents = fiildaLogoAccents(WidgetLanguage.KEYBOARD, palette, palette.accentOn, onAccent = true)
        assertEquals(palette.accentOn.copy(alpha = 0.55f), accents.primary)
        assertEquals(accents.primary, accents.secondary)
    }

    @Test
    fun cellsAssembleInWaveOrderAndAreWholeOrGoneAtTheEnds() {
        for (row in 0..2) {
            for (column in 0..2) {
                assertEquals(1f, fiildaLogoTileAssembly(row, column, assembly = 1f), 0f)
                assertEquals(0f, fiildaLogoTileAssembly(row, column, assembly = 0f), 0f)
            }
        }
        // Part way through, the bottom-left cell is ahead of the top-right one.
        assertTrue(fiildaLogoTileAssembly(2, 0, 0.3f) > fiildaLogoTileAssembly(0, 2, 0.3f))
    }
}
