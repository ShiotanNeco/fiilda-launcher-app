package com.fiilda.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.sharp.Home
import androidx.compose.material.icons.sharp.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt

/** Shared chrome, tile primitives, and small display helpers. */
/** Material icon vocabulary for the built-in widget picker. */
internal fun homeWidgetIcon(widget: HomeWidget): ImageVector = when (widget) {
    HomeWidget.CLOCK -> Icons.Filled.AccessTime
    HomeWidget.WEATHER -> Icons.Filled.Cloud
    HomeWidget.AGENDA -> Icons.AutoMirrored.Filled.EventNote
    HomeWidget.CALENDAR -> Icons.Filled.CalendarMonth
    HomeWidget.BATTERY -> Icons.Filled.BatteryStd
    HomeWidget.REMINDER -> Icons.Filled.NotificationsNone
    HomeWidget.MEDIA -> Icons.Filled.PlayArrow
    HomeWidget.FORECAST -> Icons.Filled.WbSunny
    HomeWidget.PHOTO -> Icons.Filled.Photo
}

// Sizes at scale 1; ContextBar multiplies them by contextBarScale().
private val ContextBarTabWidth = 90.dp
private val ContextBarTabHeight = 56.dp
private val ContextBarIconSize = 32.dp
private val ContextBarCapsulePadding = 4.dp
private val ContextBarSearchGap = 10.dp
private val ContextBarSearchSize = ContextBarTabHeight + ContextBarCapsulePadding * 2
private val ContextBarBaseWidth =
    ContextBarTabWidth * 2 + ContextBarCapsulePadding * 3 + ContextBarSearchGap + ContextBarSearchSize
private val ContextBarTabShape = RoundedCornerShape(percent = 50)
private val ContextBarDotsHeight = 21.dp
private val ContextBarTopPadding = 4.dp

/**
 * The home grid stretches with the screen width, so the bar does too: about half the width,
 * capped so an unfolded inner screen does not get an oversized bar. The lower limit keeps the
 * search button at the 48dp minimum touch target on narrow phones.
 */
internal fun contextBarScale(screenWidthDp: Int): Float =
    (screenWidthDp * 0.5f / ContextBarBaseWidth.value).coerceIn(0.75f, 1.4f)

/** The line style of the bar's Home and Search glyphs. */
internal enum class ContextBarIconStyle { FILLED, OUTLINED, SHARP, ROUNDED }

/**
 * Glyphs follow each theme's widget language: thin lines for the keyboard and Metro, square
 * blocks for Classic, Material's filled-when-selected rule, and soft shapes for Glass.
 */
internal fun contextBarIconStyle(language: WidgetLanguage, active: Boolean): ContextBarIconStyle =
    when (language) {
        WidgetLanguage.KEYBOARD, WidgetLanguage.METRO -> ContextBarIconStyle.OUTLINED
        WidgetLanguage.NISHIKIGOI -> ContextBarIconStyle.SHARP
        WidgetLanguage.MATERIAL -> if (active) ContextBarIconStyle.FILLED else ContextBarIconStyle.OUTLINED
        WidgetLanguage.GLASS -> ContextBarIconStyle.ROUNDED
    }

internal enum class ContextBarGlyph { HOME, APPS, SEARCH }

private fun contextBarIconVector(glyph: ContextBarGlyph, style: ContextBarIconStyle): ImageVector =
    when (glyph) {
        ContextBarGlyph.SEARCH -> when (style) {
            ContextBarIconStyle.FILLED -> Icons.Filled.Search
            ContextBarIconStyle.OUTLINED -> Icons.Outlined.Search
            ContextBarIconStyle.SHARP -> Icons.Sharp.Search
            ContextBarIconStyle.ROUNDED -> Icons.Rounded.Search
        }
        else -> when (style) {
            ContextBarIconStyle.FILLED -> Icons.Filled.Home
            ContextBarIconStyle.OUTLINED -> Icons.Outlined.Home
            ContextBarIconStyle.SHARP -> Icons.Sharp.Home
            ContextBarIconStyle.ROUNDED -> Icons.Rounded.Home
        }
    }

/** A Home, Apps (FiiLDA logo), or Search glyph in the current theme's language. */
@Composable
internal fun ContextBarGlyphIcon(
    glyph: ContextBarGlyph,
    tint: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    waveKey: Int = 0,
    assembly: () -> Float = { 1f },
) {
    if (glyph == ContextBarGlyph.APPS) {
        FiiLDALogoIcon(tint = tint, onAccent = active, waveKey = waveKey, assembly = assembly, modifier = modifier)
        return
    }
    val style = contextBarIconStyle(widgetLanguageFor(LocalLauncherTheme.current), active)
    Icon(
        imageVector = contextBarIconVector(glyph, style),
        contentDescription = null,
        tint = tint,
        modifier = modifier,
    )
}

/** The bar's height before it is first measured, excluding the system navigation inset. */
internal fun contextBarEstimatedHeight(scale: Float): Dp =
    ContextBarTopPadding + ContextBarSearchSize * scale + ContextBarDotsHeight

/**
 * The floating navigation: a capsule holding the Home and Apps tabs, the Home page dots below
 * it, and a separate search button to its right.
 */
@Composable
internal fun ContextBar(
    destination: LauncherDestination,
    presentation: LauncherHomePresentation,
    selectedHomePage: Int,
    homePageCount: Int,
    searchActive: Boolean,
    glassSceneEnabled: Boolean = true,
    glassGeometryVersion: Any? = null,
    onSelect: (LauncherDestination) -> Unit,
    onSearch: () -> Unit,
) {
    val normalizedSelectedHomePage = selectedHomePage.coerceIn(0, homePageCount.coerceAtLeast(1) - 1)
    val homeTabAccessibility = launcherHomeTabAccessibility(
        presentation = presentation,
        destination = destination,
        selectedHomePage = normalizedSelectedHomePage,
        homePageCount = homePageCount,
    )
    var appsWave by remember { mutableIntStateOf(0) }
    val scale = contextBarScale(LocalConfiguration.current.screenWidthDp)
    // Fully round ends: the capsule and search button are as tall as the search button, so their
    // corners stay concentric with the pill-shaped selected tab at every scale.
    val surfaceCorner = ContextBarSearchSize * scale / 2
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ContextBarTopPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier
                    .contextBarSurface(surfaceCorner, glassSceneEnabled, glassGeometryVersion)
                    .padding(ContextBarCapsulePadding * scale),
                horizontalArrangement = Arrangement.spacedBy(ContextBarCapsulePadding * scale),
            ) {
                launcherContextDestinations(presentation).forEach { tab ->
                    val active = tab == destination
                    ContextBarButton(
                        active = active,
                        shape = ContextBarTabShape,
                        role = Role.Tab,
                        contentDescription = when (tab) {
                            LauncherDestination.HOME -> homeTabAccessibility.contentDescription
                            LauncherDestination.DRAWER -> tr("アプリ", "Apps")
                        },
                        stateDescription = when (tab) {
                            LauncherDestination.HOME -> homeTabAccessibility.stateDescription
                            LauncherDestination.DRAWER -> launcherDrawerTabStateDescription(active)
                        },
                        actionLabel = when (tab) {
                            LauncherDestination.HOME -> homeTabAccessibility.actionLabel
                            LauncherDestination.DRAWER -> launcherDrawerTabActionLabel(active)
                        },
                        onPress = {
                            if (tab == LauncherDestination.DRAWER) appsWave++
                            onSelect(tab)
                        },
                        modifier = Modifier.size(ContextBarTabWidth * scale, ContextBarTabHeight * scale),
                    ) { tint ->
                        ContextBarGlyphIcon(
                            glyph = when (tab) {
                                LauncherDestination.HOME -> ContextBarGlyph.HOME
                                LauncherDestination.DRAWER -> ContextBarGlyph.APPS
                            },
                            tint = tint,
                            active = active,
                            waveKey = appsWave,
                            modifier = Modifier.size(ContextBarIconSize * scale),
                        )
                    }
                }
            }
            ContextBarPageDots(
                visible = presentation == LauncherHomePresentation.PAGER && homePageCount > 1,
                selectedPage = normalizedSelectedHomePage,
                pageCount = homePageCount,
                dimmed = destination != LauncherDestination.HOME,
            )
        }
        Spacer(Modifier.width(ContextBarSearchGap * scale))
        ContextBarButton(
            active = searchActive,
            shape = CircleShape,
            role = Role.Button,
            contentDescription = tr("検索", "Search"),
            stateDescription = launcherSearchButtonStateDescription(searchActive),
            actionLabel = launcherSearchButtonActionLabel(searchActive),
            onPress = onSearch,
            modifier = Modifier
                .size(ContextBarSearchSize * scale)
                .contextBarSurface(surfaceCorner, glassSceneEnabled, glassGeometryVersion),
        ) { tint ->
            ContextBarGlyphIcon(
                glyph = ContextBarGlyph.SEARCH,
                tint = tint,
                active = searchActive,
                modifier = Modifier.size(ContextBarIconSize * scale),
            )
        }
    }
}

@Composable
private fun Modifier.contextBarSurface(cornerRadius: Dp, sceneEnabled: Boolean, geometryVersion: Any?): Modifier =
    if (LocalLauncherGlass.current.enabled) {
        launcherGlassNavigation(
            cornerRadius = cornerRadius,
            fallbackColor = FiiLDADeep,
            sceneEnabled = sceneEnabled,
            geometryVersion = geometryVersion,
        )
    } else {
        launcherShapedSurface(RoundedCornerShape(cornerRadius), FiiLDALine, FiiLDADeep)
    }

@Composable
private fun ContextBarButton(
    active: Boolean,
    shape: Shape,
    role: Role,
    contentDescription: String,
    stateDescription: String,
    actionLabel: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (tint: Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Selection is a discrete state. Animating each button independently makes the old and new
    // tabs look pressed together when navigation changes on click release, so only the button
    // that is physically pressed animates.
    val tint = when {
        active -> LocalLauncherPalette.current.accentOn
        pressed -> FiiLDAInk
        else -> FiiLDAMuted
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = if (!LocalLauncherGlassMotionEnabled.current) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        },
        label = "context bar press scale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (active) FiiLDACyan else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onPress,
            )
            .semantics {
                this.role = role
                if (role == Role.Tab) selected = active
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
                onClick(label = actionLabel) {
                    onPress()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(tint)
    }
}

/** Home page position, under the capsule. Dimmed while the app list is showing. */
@Composable
private fun ContextBarPageDots(
    visible: Boolean,
    selectedPage: Int,
    pageCount: Int,
    dimmed: Boolean,
) {
    val accent = FiiLDACyan
    val idle = FiiLDAInk.copy(alpha = 0.35f)
    val motionEnabled = LocalLauncherGlassMotionEnabled.current
    Row(
        modifier = Modifier
            .height(ContextBarDotsHeight)
            .alpha(if (dimmed) 0.4f else 1f)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!visible) return@Row
        repeat(pageCount) { index ->
            val current = index == selectedPage
            val width by animateDpAsState(
                targetValue = if (current) 14.dp else 5.dp,
                animationSpec = if (motionEnabled) spring(stiffness = Spring.StiffnessMediumLow) else snap(),
                label = "page dot width",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 5.dp)
                    .clip(CircleShape)
                    .background(if (current) accent else idle),
            )
        }
    }
}

@Composable
internal fun FiiLDATile(
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    widget: HomeWidget? = null,
    backgroundless: Boolean = false,
    /** Replaces the Windows 8 built-in tile color, e.g. the media tile's artwork color. */
    metroSurfaceColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalLauncherPalette.current
    val tileSurface = if (LocalLauncherTheme.current == LauncherTheme.WINDOWS_8 && widget != null) {
        metroSurfaceColor ?: windows8BuiltInTileColor(widget.id)
    } else {
        palette.surface
    }
    val interaction = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    val glassEnabled = LocalLauncherGlass.current.enabled
    Column(
        modifier = modifier
            .then(interaction)
            .let { tileModifier ->
                if (glassEnabled) {
                    tileModifier.launcherGlassTile(fallbackColor = tileSurface)
                } else {
                    tileModifier.launcherShapedSurface(
                        LauncherTileShape,
                        palette.line,
                        tileSurface.takeUnless { backgroundless },
                    )
                }
            }
            .padding(10.dp),
    ) {
        // Capture only the sharp widget foreground for the floating navigation glass. Keeping the
        // contributor inside the tile surface prevents the tile's own optical result from entering
        // the scene a second time.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .launcherGlassContributor(),
            content = content,
        )
    }
}

@Composable
internal fun TileKicker(text: String, color: Color = FiiLDAMuted) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        modifier = Modifier,
    )
}

@Composable
internal fun ModeHeading(kicker: String, title: String, count: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column {
            TileKicker(text = kicker)
            Text(
                text = title,
                color = FiiLDAInk,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(text = count, color = FiiLDAMuted, fontSize = 9.sp)
    }
}

@Composable
internal fun EmptyPanel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .launcherBorder(1.dp, FiiLDALineStrong),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = FiiLDAMuted, fontSize = 11.sp)
    }
}

private val WideSwitcherSize = 48.dp
private val WideSwitcherTopPadding = 8.dp
private val WideSwitcherBottomPadding = 6.dp

@Composable
private fun Modifier.wideSwitcherInsets(includeTopInset: Boolean): Modifier = windowInsetsPadding(
    WindowInsets.safeDrawing.only(
        if (includeTopInset) {
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        } else {
            WindowInsetsSides.Horizontal
        },
    ),
)

/**
 * Room for the wide switcher at the top of Home and the drawer. The switcher itself is drawn once
 * above both surfaces so it stays put while they slide.
 */
@Composable
internal fun WideSwitcherSpace(includeTopInset: Boolean = true) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .wideSwitcherInsets(includeTopInset)
            .padding(top = WideSwitcherTopPadding, bottom = WideSwitcherBottomPadding)
            .height(WideSwitcherSize),
    )
}

/** Logo assembly during the switcher's morph: whole at 0, gathered by 0.55. */
internal fun wideSwitcherLogoAssembly(morph: Float): Float = ((0.55f - morph) / 0.55f).coerceIn(0f, 1f)

/** House reveal during the switcher's morph, overlapping the end of the gathering tiles. */
internal fun wideSwitcherHouseProgress(morph: Float): Float = ((morph - 0.45f) / 0.55f).coerceIn(0f, 1f)

/** Ease-out with an overshoot of about 25%, for the house's pop. */
private fun easeOutBack(t: Float): Float {
    val overshoot = 3f
    val x = t - 1f
    return 1f + (overshoot + 1f) * x * x * x + overshoot * x * x
}

/** The wide posture's Home/Apps switcher. It shows the surface it switches to. */
@Composable
internal fun WideSwitcher(
    page: LauncherPage,
    onToggleWideSurface: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val targetIsDrawer = page == LauncherPage.HOME
    val motionEnabled = LocalLauncherGlassMotionEnabled.current
    val showsApps = targetIsDrawer
    Row(
        modifier = modifier
            .wideSwitcherInsets(includeTopInset = true)
            .padding(top = WideSwitcherTopPadding, bottom = WideSwitcherBottomPadding),
    ) {
        // StartCanvas adds the shared grid gap as viewport content padding. Mirror that inset
        // here so the switcher's border aligns with the left edge of the first home tile.
        Spacer(Modifier.width(HomeGridGapDp.dp))
        Box(
            modifier = Modifier
                .size(WideSwitcherSize)
                .then(
                    if (LocalLauncherGlass.current.enabled) {
                        Modifier.launcherGlassTile(fallbackColor = FiiLDASurface)
                    } else {
                        Modifier.launcherShapedSurface(
                            LauncherTileShape,
                            if (pressed) FiiLDACyan else FiiLDALineStrong,
                            if (pressed) FiiLDAAccentSurface else FiiLDASurface,
                        )
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClickLabel = if (targetIsDrawer) tr("アプリ一覧を表示", "Show app list") else tr("ホームを表示", "Show Home"),
                    onClick = onToggleWideSurface,
                )
                .semantics {
                    contentDescription = if (targetIsDrawer) {
                        tr("アプリ。アプリ一覧へ切り替え", "Apps. Switch to the app list")
                    } else {
                        tr("ホーム。ホームへ切り替え", "Home. Switch to Home")
                    }
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            // 0 shows the logo, 1 the Home glyph. Opening the app list gathers the logo's cells
            // into the centre and pops the house out of them, starting with the tap like the
            // list's own tiles. Returning to Home reverses this, cells popping back in wave order.
            val morph = remember { Animatable(if (showsApps) 0f else 1f) }
            LaunchedEffect(showsApps, motionEnabled) {
                val target = if (showsApps) 0f else 1f
                if (!motionEnabled) {
                    morph.snapTo(target)
                    return@LaunchedEffect
                }
                // Back to the logo in step with the leaving app list.
                val durationMs = if (showsApps) (DrawerRevealDurationMs * DrawerExitTimeScale).roundToInt() else 320
                morph.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing))
            }
            val tint = if (pressed) FiiLDACyan else FiiLDAInk
            ContextBarGlyphIcon(
                glyph = ContextBarGlyph.APPS,
                tint = tint,
                active = false,
                assembly = { wideSwitcherLogoAssembly(morph.value) },
                modifier = Modifier.size(20.dp),
            )
            ContextBarGlyphIcon(
                glyph = ContextBarGlyph.HOME,
                tint = tint,
                active = false,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        val house = wideSwitcherHouseProgress(morph.value)
                        val houseScale = 0.8f + 0.2f * easeOutBack(house)
                        alpha = (house * 3f).coerceAtMost(1f)
                        scaleX = houseScale
                        scaleY = houseScale
                    },
            )
        }
    }
}

/**
 * One line of text that shrinks (down to [minFontSize]) instead of being cut off, so app names
 * and button labels stay readable on narrow phones and in longer translations.
 */
@Composable
internal fun FitText(
    text: String,
    color: Color,
    maxFontSize: androidx.compose.ui.unit.TextUnit,
    minFontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Center,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = maxFontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = minFontSize,
            maxFontSize = maxFontSize,
            stepSize = 0.5.sp,
        ),
    )
}
