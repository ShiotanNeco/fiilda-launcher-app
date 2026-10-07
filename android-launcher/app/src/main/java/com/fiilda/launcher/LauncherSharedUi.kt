package com.fiilda.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

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

internal val NavigationEdgeFadeHeight = 24.dp

@Composable
internal fun LauncherControlEdgeFade(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NavigationEdgeFadeHeight)
            .background(Brush.verticalGradient(listOf(FiiLDABlack.copy(alpha = 0f), FiiLDABlack))),
    )
}

@Composable
internal fun ContextBar(
    destination: LauncherDestination,
    presentation: LauncherHomePresentation,
    selectedHomePage: Int,
    homePageCount: Int,
    glassSceneEnabled: Boolean = true,
    glassGeometryVersion: Any? = null,
    onMeasured: (Int) -> Unit = {},
    onSelect: (LauncherDestination) -> Unit,
) {
    val normalizedSelectedHomePage = selectedHomePage.coerceIn(0, homePageCount.coerceAtLeast(1) - 1)
    val homeTabAccessibility = launcherHomeTabAccessibility(
        presentation = presentation,
        destination = destination,
        selectedHomePage = normalizedSelectedHomePage,
        homePageCount = homePageCount,
    )
    val items = launcherContextDestinations(presentation).map { tab ->
        tab to when (tab) {
            LauncherDestination.HOME -> tr("ホーム", "Home")
            LauncherDestination.DRAWER -> tr("アプリ", "Apps")
        }
    }
    val glassEnabled = LocalLauncherGlass.current.enabled
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                if (glassEnabled) onMeasured(size.height)
            }
            .then(
                if (glassEnabled) {
                    Modifier.launcherGlassNavigation(
                        fallbackColor = FiiLDADeep,
                        sceneEnabled = glassSceneEnabled,
                        geometryVersion = glassGeometryVersion,
                    )
                } else {
                    Modifier.launcherShapedSurface(LauncherNavigationShape, FiiLDALine, FiiLDADeep)
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(61.dp),
        ) {
        items.forEach { (tab, label) ->
            val active = tab == destination
            val tabActionLabel = when (tab) {
                LauncherDestination.HOME -> homeTabAccessibility.actionLabel
                LauncherDestination.DRAWER -> launcherDrawerTabActionLabel(active)
            }
            val interactionSource = remember(tab) { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            // Selection is a discrete state. Animating each tab independently makes the old
            // and new tabs look pressed together when navigation changes on click release.
            // Keep the only animated response below for the tab that is physically pressed.
            val background = if (active) FiiLDAAccentSurface else Color.Transparent
            val tint = when {
                active -> FiiLDACyan
                pressed -> FiiLDAInk
                else -> FiiLDAMuted
            }
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.96f else 1f,
                animationSpec = if (!LocalLauncherGlassMotionEnabled.current) {
                    snap()
                } else {
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    )
                },
                label = "tab press scale",
            )
            val indicatorWidth = if (active) 26.dp else 8.dp
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .launcherBorder(0.5.dp, FiiLDALine)
                    .background(
                        if (glassEnabled) {
                            Color.Transparent
                        } else {
                            background
                        },
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelect(tab) },
                    )
                    .semantics {
                        role = Role.Tab
                        selected = active
                        contentDescription = if (tab == LauncherDestination.HOME) {
                            homeTabAccessibility.contentDescription
                        } else {
                            label
                        }
                        stateDescription = when (tab) {
                            LauncherDestination.HOME -> homeTabAccessibility.stateDescription
                            LauncherDestination.DRAWER -> launcherDrawerTabStateDescription(active)
                        }
                        onClick(label = tabActionLabel) {
                            onSelect(tab)
                            true
                        }
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (tab == LauncherDestination.HOME) Icons.Filled.Home else Icons.Filled.Apps,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(40.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(indicatorWidth)
                        .height(2.dp)
                        .background(if (active) FiiLDACyan else FiiLDAQuiet),
                )
            }
        }
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

@Composable
internal fun Header(
    showWideSwitcher: Boolean,
    page: LauncherPage,
    onToggleWideSurface: () -> Unit,
    includeTopInset: Boolean = true,
) {
    if (!showWideSwitcher) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    if (includeTopInset) {
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    } else {
                        WindowInsetsSides.Horizontal
                    },
                ),
            )
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showWideSwitcher) {
            // StartCanvas adds the shared grid gap as viewport content padding. Mirror that inset
            // here so the switcher's border aligns with the left edge of the first home tile.
            Spacer(Modifier.width(HomeGridGapDp.dp))
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val targetIsDrawer = page == LauncherPage.HOME
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .width(48.dp)
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
                    }
                    .padding(0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = if (targetIsDrawer) Icons.Filled.Apps else Icons.Filled.Home,
                    contentDescription = null,
                    tint = if (pressed) FiiLDACyan else FiiLDAInk,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
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
