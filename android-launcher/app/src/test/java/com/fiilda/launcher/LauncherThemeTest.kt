package com.fiilda.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LauncherThemeTest {
    @Test
    fun darkGlassIsSelectableAndRetainsTheSharedGlassWidgetAndRotationBehavior() {
        assertEquals("ダークガラス", LauncherTheme.DARK_GLASS.displayName)
        assertEquals("dark_glass", serializeLauncherThemeToken(LauncherTheme.DARK_GLASS))
        assertEquals(LauncherTheme.DARK_GLASS, parseLauncherThemeToken(" DARK_GLASS "))
        assertEquals(
            setOf(LauncherTheme.GLASS, LauncherTheme.DARK_GLASS),
            LauncherTheme.entries.filter { it.isGlass }.toSet(),
        )
        assertEquals(WidgetLanguage.GLASS, widgetLanguageFor(LauncherTheme.DARK_GLASS))
        val themes = setOf(LauncherTheme.GLASS, LauncherTheme.DARK_GLASS)
        assertEquals(LauncherTheme.DARK_GLASS, nextThemeInRotation(LauncherTheme.GLASS, themes))
        assertEquals(LauncherTheme.GLASS, nextThemeInRotation(LauncherTheme.DARK_GLASS, themes))
        val palette = launcherPaletteFor(LauncherTheme.DARK_GLASS)
        assertEquals(Color.Transparent, palette.background)
        assertEquals(Color.Black.copy(alpha = 0.40f), palette.surface)
        assertEquals(Color.White, palette.ink)
        assertFalse(palette.isLight)
    }

    @Test
    fun themeTokensRoundTripAndUnknownValuesFallBackToDefault() {
        assertEquals(LauncherTheme.DEFAULT, parseLauncherThemeToken(null))
        assertEquals(LauncherTheme.DEFAULT, parseLauncherThemeToken(""))
        assertEquals(LauncherTheme.DEFAULT, parseLauncherThemeToken("future-theme"))
        assertEquals(LauncherTheme.CLASSIC, parseLauncherThemeToken(" CLASSIC "))
        assertEquals("default", serializeLauncherThemeToken(LauncherTheme.DEFAULT))
        assertEquals("classic", serializeLauncherThemeToken(LauncherTheme.CLASSIC))
        assertEquals("windows8", serializeLauncherThemeToken(LauncherTheme.WINDOWS_8))
        assertEquals("material", serializeLauncherThemeToken(LauncherTheme.MATERIAL))
        assertEquals("glass", serializeLauncherThemeToken(LauncherTheme.GLASS))
        assertEquals(
            LauncherTheme.CLASSIC,
            parseLauncherThemeToken(serializeLauncherThemeToken(LauncherTheme.CLASSIC)),
        )
        assertEquals(
            LauncherTheme.WINDOWS_8,
            parseLauncherThemeToken(" WINDOWS8 "),
        )
        assertEquals(
            LauncherTheme.MATERIAL,
            parseLauncherThemeToken(" Material "),
        )
        assertEquals(
            LauncherTheme.GLASS,
            parseLauncherThemeToken(" GLASS "),
        )
    }

    @Test
    fun materialThemeMetadataExplainsSystemAndAndroid12DynamicColorBehavior() {
        assertEquals("マテリアル", LauncherTheme.MATERIAL.displayName)
        assertTrue(LauncherTheme.MATERIAL.description.contains("明暗設定"))
        assertTrue(LauncherTheme.MATERIAL.description.contains("Android 12以降"))
        assertTrue(LauncherTheme.MATERIAL.description.contains("壁紙の動的カラー"))
        assertTrue(LauncherTheme.MATERIAL.description.contains("それ以前は標準配色"))
    }

    @Test
    fun glassThemeMetadataAndPaletteKeepWhiteInkReadable() {
        assertEquals("ガラス", LauncherTheme.GLASS.displayName)
        assertTrue(LauncherTheme.GLASS.description.contains("青灰色"))
        assertTrue(LauncherTheme.GLASS.description.contains("白文字"))

        val palette = launcherPaletteFor(LauncherTheme.GLASS)
        assertEquals(Color.White, palette.ink)
        assertEquals(Color.White, palette.photoPreviewInk)
        assertEquals(Color.Transparent, palette.background)
        assertEquals(Color.White.copy(alpha = 0.10f), palette.surface)
        assertEquals(Color.White.copy(alpha = 0.16f), palette.accentSurface)
        assertEquals(Color.White, palette.accent)
        assertEquals(Color.Black, palette.accentOn)
        assertFalse(palette.isLight)
        assertNotEquals(palette.background, Color.Black)
        assertTrue(contrastRatioArgb(palette.ink.toArgb(), palette.background.toArgb()) >= 4.5f)
    }

    @Test
    fun materialColorSchemeMapsSemanticRolesIntoReadableLauncherPalette() {
        val primary = Color(0xFF112233)
        val onPrimary = Color(0xFFEFF0F1)
        val background = Color(0xFF223344)
        val surface = Color(0xFF334455)
        val onSurface = Color(0xFFF0F1F2)
        val surfaceVariant = Color(0xFF445566)
        val onSurfaceVariant = Color(0xFFD0D1D2)
        val outline = Color(0xFF667788)
        val outlineVariant = Color(0xFF778899)
        val surfaceContainer = Color(0xFF556677)
        val surfaceContainerHigh = Color(0xFF667788)
        val surfaceContainerHighest = Color(0xFF778899)
        val surfaceContainerLow = Color(0xFF445566)
        val surfaceContainerLowest = Color(0xFF334455)
        val scheme = lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            background = background,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
        )

        val palette = launcherPaletteFromMaterialColorScheme(scheme, isLight = true)

        assertEquals(background, palette.background)
        assertEquals(surfaceVariant, palette.deep)
        assertEquals(surface, palette.surface)
        assertEquals(surfaceContainerHigh, palette.selectedSurface)
        assertEquals(surfaceContainer, palette.enabledSurface)
        assertEquals(surfaceContainerHighest, palette.accentSurface)
        assertEquals(surfaceContainerLowest, palette.photoFrameSurface)
        assertEquals(Color.White, palette.photoPreviewInk)
        assertEquals(outlineVariant, palette.line)
        assertEquals(outline, palette.lineStrong)
        assertEquals(onSurface, palette.ink)
        assertEquals(onSurfaceVariant, palette.muted)
        assertEquals(onSurfaceVariant, palette.quiet)
        assertEquals(primary, palette.accent)
        assertEquals(onPrimary, palette.accentOn)
        assertTrue(palette.isLight)

        val darkPalette = launcherPaletteFromMaterialColorScheme(scheme, isLight = false)
        assertFalse(darkPalette.isLight)
    }

    @Test
    fun darkMaterialColorSchemeMapsAllSurfaceAndAccentRoles() {
        val primary = Color(0xFFD0BCFF)
        val onPrimary = Color(0xFF381E72)
        val background = Color(0xFF141218)
        val surface = Color(0xFF141218)
        val onSurface = Color(0xFFE6E0E9)
        val surfaceVariant = Color(0xFF49454F)
        val onSurfaceVariant = Color(0xFFCAC4D0)
        val outline = Color(0xFF938F99)
        val outlineVariant = Color(0xFF49454F)
        val surfaceContainer = Color(0xFF211F26)
        val surfaceContainerHigh = Color(0xFF2B2930)
        val surfaceContainerHighest = Color(0xFF36343B)
        val surfaceContainerLow = Color(0xFF1D1B20)
        val surfaceContainerLowest = Color(0xFF0F0D13)
        val scheme = darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            background = background,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
        )

        val palette = launcherPaletteFromMaterialColorScheme(scheme, isLight = false)

        assertEquals(background, palette.background)
        assertEquals(surfaceVariant, palette.deep)
        assertEquals(surface, palette.surface)
        assertEquals(surfaceContainerHigh, palette.selectedSurface)
        assertEquals(surfaceContainer, palette.enabledSurface)
        assertEquals(surfaceContainerHighest, palette.accentSurface)
        assertEquals(surfaceContainerLowest, palette.photoFrameSurface)
        assertEquals(Color.White, palette.photoPreviewInk)
        assertEquals(outlineVariant, palette.line)
        assertEquals(outline, palette.lineStrong)
        assertEquals(onSurface, palette.ink)
        assertEquals(onSurfaceVariant, palette.muted)
        assertEquals(onSurfaceVariant, palette.quiet)
        assertEquals(primary, palette.accent)
        assertEquals(onPrimary, palette.accentOn)
        assertFalse(palette.isLight)
    }

    @Test
    fun classicPaletteUsesTheNishikigoiReferenceRoles() {
        val palette = launcherPaletteFor(LauncherTheme.CLASSIC)

        assertEquals(Color(0xFFC8102E), NISHIKIGOI_RED)
        assertEquals(Color(0xFFF2F0E6), NISHIKIGOI_IVORY)
        assertEquals(Color(0xFFC9DDEA), NISHIKIGOI_BLUE)
        assertEquals(NISHIKIGOI_RED_ARGB, 0xFFC8102E)
        assertEquals(NISHIKIGOI_IVORY_ARGB, 0xFFF2F0E6)
        assertEquals(NISHIKIGOI_BLUE_ARGB, 0xFFC9DDEA)
        assertEquals(NISHIKIGOI_IVORY, palette.background)
        assertEquals(NISHIKIGOI_RED, palette.accent)
        assertEquals(NISHIKIGOI_BLUE, palette.selectedSurface)
        assertEquals(NISHIKIGOI_BLUE, palette.accentSurface)
        assertEquals(NISHIKIGOI_IVORY, palette.photoFrameSurface)
        assertEquals(Color(0xFFFFFFFF), palette.photoPreviewInk)
        assertEquals(Color(0xFF5B7B89), palette.line)
        assertEquals(Color(0xFF3F5E6C), palette.lineStrong)
        assertEquals(Color(0xFFE5F0F5), palette.enabledSurface)
        assertEquals(Color(0xFF4A5C63), palette.muted)
        assertEquals(Color(0xFF4E6066), palette.quiet)
        assertTrue(palette.isLight)
    }

    @Test
    fun defaultPaletteRetainsTheExistingFieldColors() {
        val palette = launcherPaletteFor(LauncherTheme.DEFAULT)

        assertEquals(Color(0xFF080909), palette.background)
        assertEquals(Color(0xFF101312), palette.deep)
        assertEquals(Color(0xFF151918), palette.surface)
        assertEquals(Color(0xFF303634), palette.line)
        assertEquals(Color(0xFF47504D), palette.lineStrong)
        assertEquals(Color(0xFFF4F6F5), palette.ink)
        assertEquals(Color(0xFF9DA5A3), palette.muted)
        assertEquals(Color(0xFF69716F), palette.quiet)
        assertEquals(Color(0xFF52D7EF), palette.accent)
        assertEquals(Color(0xFF182629), palette.accentSurface)
        assertEquals(Color(0xFF192223), palette.selectedSurface)
        assertEquals(Color(0xFF202725), palette.enabledSurface)
        assertEquals(Color(0xFF0B0E0D), palette.photoFrameSurface)
        assertEquals(Color(0xFFF4F6F5), palette.photoPreviewInk)
        assertFalse(palette.isLight)
    }

    @Test
    fun windows8PaletteUsesNavyMetroSurfacesAndStructuralBorderlessRoles() {
        val palette = launcherPaletteFor(LauncherTheme.WINDOWS_8)

        assertEquals("窓", LauncherTheme.WINDOWS_8.displayName)
        assertEquals(Color(0xFF001A33), palette.background)
        assertEquals(Color(0xFF002542), palette.deep)
        assertEquals(Color(0xFF003653), palette.surface)
        assertEquals(Color(0xFF004B6B), palette.selectedSurface)
        assertEquals(Color.Transparent, palette.line)
        assertEquals(Color.Transparent, palette.lineStrong)
        assertTrue(palette.borderless)
        assertEquals(Color(0xFFFFFFFF), palette.ink)
        assertEquals(Color(0xFFBFF7FF), palette.accent)
        assertNotEquals(palette.background, Color.Black)
        assertFalse(palette.isLight)
    }

    @Test
    fun windows8SurfaceRolesKeepVividMetroColorsReadable() {
        val white = 0xFFFFFFFF.toInt()
        val muted = 0xFFE1F2FA.toInt()
        val quiet = 0xFFD0ECF5.toInt()
        val accent = 0xFFBFF7FF.toInt()
        val selectedSettings = 0xFF004B6B.toInt()
        val actionSurface = 0xFF006E98.toInt()
        val agendaPurple = 0xFF7A3E9D.toInt()
        val calendarOrange = 0xFFA72B00.toInt()
        val forecastOchre = 0xFF7A5E00.toInt()

        assertTrue(contrastRatioArgb(white, forecastOchre) >= 4.5f)
        assertTrue(contrastRatioArgb(muted, calendarOrange) >= 4.5f)
        assertTrue(contrastRatioArgb(quiet, actionSurface) >= 4.5f)
        assertTrue(contrastRatioArgb(accent, agendaPurple) >= 4.5f)
        assertTrue(contrastRatioArgb(accent, calendarOrange) >= 4.5f)
        assertTrue(contrastRatioArgb(accent, actionSurface) >= 4.5f)
        assertTrue(contrastRatioArgb(accent, selectedSettings) >= 4.5f)
    }

    @Test
    fun windows8BuiltInSurfacesMeetContrastForTheirRenderedSemanticRoles() {
        val white = 0xFFFFFFFF.toInt()
        val muted = 0xFFE1F2FA.toInt()
        val quiet = 0xFFD0ECF5.toInt()
        val accent = 0xFFBFF7FF.toInt()
        val rolesByWidget = mapOf(
            HomeWidget.CLOCK.id to listOf(white, muted, quiet),
            HomeWidget.WEATHER.id to listOf(white, muted, quiet),
            HomeWidget.AGENDA.id to listOf(white, muted, quiet, accent),
            HomeWidget.CALENDAR.id to listOf(white, muted, quiet, accent),
            // Battery uses ink/muted labels plus the cyan progress bar over Metro green.
            HomeWidget.BATTERY.id to listOf(white, muted, accent),
            HomeWidget.REMINDER.id to listOf(white, muted),
            HomeWidget.MEDIA.id to listOf(white, muted, quiet, accent),
            HomeWidget.FORECAST.id to listOf(white, muted, quiet),
            HomeWidget.PHOTO.id to listOf(white),
        )

        rolesByWidget.forEach { (widgetId, foregrounds) ->
            val surface = windows8BuiltInTileColor(widgetId).toArgb()
            foregrounds.forEach { foreground ->
                assertTrue(
                    "$widgetId foreground 0x${foreground.toUInt().toString(16)}",
                    contrastRatioArgb(foreground, surface) >= 4.5f,
                )
            }
        }
    }

    @Test
    fun wcagLuminanceUsesTheSrgbExponentAndChoosesTheAccessibleForeground() {
        assertEquals(21f, contrastRatioArgb(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.0001f)
        assertEquals(4.4781f, contrastRatioArgb(0xFFFFFFFF.toInt(), 0xFF777777.toInt()), 0.001f)
        // The old cubic approximation incorrectly selected white for this teal. Exact sRGB 2.4
        // luminance makes black the higher-contrast foreground (5.129:1 versus 4.094:1).
        assertEquals(0xFF000000.toInt(), accessibleTileForegroundArgb(0xFF008A9E.toInt()))
        assertEquals(5.1291f, contrastRatioArgb(0xFF000000.toInt(), 0xFF008A9E.toInt()), 0.001f)
    }

    @Test
    fun fullyTransparentIconUsesTheDeterministicFallback() {
        assertEquals(
            fallbackIconTileColor("com.example", "Launcher"),
            selectIconTileColorFromPixels(intArrayOf(0x00E51400, 0), "com.example", "Launcher"),
        )
    }

    @Test
    fun monochromeIconsGetABlackOrWhiteTileMatchingTheirTone() {
        // A white glyph on a black square with a small colored accent, like X.
        val darkIcon = IntArray(100) { index ->
            when {
                index < 70 -> 0xFF000000.toInt()
                index < 95 -> 0xFFFFFFFF.toInt()
                else -> 0xFFE51400.toInt()
            }
        }
        // A black glyph on a white square, like ChatGPT, with transparent corners.
        val lightIcon = IntArray(100) { index ->
            when {
                index < 10 -> 0x00000000
                index < 70 -> 0xFFFFFFFF.toInt()
                else -> 0xFF101010.toInt()
            }
        }

        val dark = selectIconTileColorFromPixels(darkIcon, "com.example", "Dark")
        val light = selectIconTileColorFromPixels(lightIcon, "com.example", "Light")

        assertTrue(contrastRatioArgb(dark, 0xFF000000.toInt()) < 1.5f)
        assertTrue(contrastRatioArgb(light, 0xFFFFFFFF.toInt()) < 1.3f)
        assertEquals(0xFFFFFFFF.toInt(), accessibleTileForegroundArgb(dark))
        assertEquals(0xFF000000.toInt(), accessibleTileForegroundArgb(light))
    }

    @Test
    fun iconColorSelectionPrefersChromaticPixelsAndNormalizesThem() {
        val selected = selectIconTileColorFromPixels(
            pixels = intArrayOf(
                0xFF777777.toInt(),
                0xFF888888.toInt(),
                0xFFEE3322.toInt(),
                0xFFDD2211.toInt(),
            ),
            packageName = "com.example",
            className = "Colorful",
        )

        val red = selected ushr 16 and 0xFF
        val green = selected ushr 8 and 0xFF
        val blue = selected and 0xFF
        assertTrue(red > green)
        assertTrue(red > blue)
        assertTrue((maxOf(red, green, blue) - minOf(red, green, blue)) >= 24)
    }

    @Test
    fun iconFallbackAndAccessibleForegroundAreDeterministic() {
        val first = fallbackIconTileColor("com.example", "Same")
        val second = fallbackIconTileColor("com.example", "Same")
        val other = fallbackIconTileColor("com.example", "Other")

        assertEquals(first, second)
        assertNotEquals(first, other)
        assertEquals(0xFFFFFFFF.toInt(), accessibleTileForegroundArgb(0xFF004B6B.toInt()))
        assertEquals(0xFF000000.toInt(), accessibleTileForegroundArgb(0xFFC19C00.toInt()))
    }

    @Test
    fun iconCacheKeyIsStableButVersionedAndPackageInvalidationIsScoped() {
        val first = launchableAppIconCacheKey(
            packageName = "com.example",
            className = "Launcher",
            packageVersionCode = 7L,
            packageLastUpdateTime = 100L,
        )
        val same = launchableAppIconCacheKey(
            packageName = "com.example",
            className = "Launcher",
            packageVersionCode = 7L,
            packageLastUpdateTime = 100L,
        )
        val changedVersion = first.copy(packageVersionCode = 8L)
        val changedTimestamp = first.copy(packageLastUpdateTime = 101L)

        assertEquals(first, same)
        assertNotEquals(first, changedVersion)
        assertNotEquals(first, changedTimestamp)
        assertTrue(launchableAppIconCacheKeyBelongsToPackage(first, "com.example"))
        assertTrue(launchableAppIconCacheKeyBelongsToPackage(changedVersion, "com.example"))
        assertTrue(!launchableAppIconCacheKeyBelongsToPackage(first, "com.other"))
    }

    @Test
    fun iconCacheDoesNotRepopulateAfterInvalidationDuringCompute() {
        val cache = LaunchableAppIconColorCache<String>()
        val key = launchableAppIconCacheKey("com.example", "Launcher", 1L, 10L)
        val query = cache.beginQuery()
        val computeStarted = CountDownLatch(1)
        val releaseCompute = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val future = executor.submit<LaunchableAppIconCacheLookup<String>> {
                cache.getOrCompute(key, query) {
                    computeStarted.countDown()
                    assertTrue(releaseCompute.await(2L, TimeUnit.SECONDS))
                    "stale"
                }
            }
            assertTrue(computeStarted.await(2L, TimeUnit.SECONDS))
            cache.invalidatePackage("com.example")
            releaseCompute.countDown()

            val result = future.get(2L, TimeUnit.SECONDS)
            assertFalse(result.isCurrent)
            assertEquals(null, cache.getIfPresent(key))
        } finally {
            releaseCompute.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun iconCacheRejectsOutOfOrderPruneButNewQueryReusesValidEntries() {
        val cache = LaunchableAppIconColorCache<String>()
        val retainedKey = launchableAppIconCacheKey("com.example", "Retained", 1L, 10L)
        val prunedKey = launchableAppIconCacheKey("com.example", "Pruned", 1L, 10L)
        val firstQuery = cache.beginQuery()
        assertTrue(cache.getOrCompute(retainedKey, firstQuery) { "retained" }.isCurrent)
        assertTrue(cache.getOrCompute(prunedKey, firstQuery) { "pruned" }.isCurrent)

        val newerQuery = cache.beginQuery()
        var recomputeCount = 0
        val reused = cache.getOrCompute(retainedKey, newerQuery) {
            recomputeCount += 1
            "recomputed"
        }
        assertTrue(reused.isCurrent)
        assertEquals("retained", reused.value)
        assertEquals(0, recomputeCount)

        // A stale query cannot prune entries established by the newer query.
        assertFalse(cache.retainKeys(setOf(retainedKey), firstQuery))
        assertEquals("pruned", cache.getIfPresent(prunedKey))
        assertTrue(cache.retainKeys(setOf(retainedKey), newerQuery))
        assertEquals("retained", cache.getIfPresent(retainedKey))
        assertEquals(null, cache.getIfPresent(prunedKey))
    }
}
