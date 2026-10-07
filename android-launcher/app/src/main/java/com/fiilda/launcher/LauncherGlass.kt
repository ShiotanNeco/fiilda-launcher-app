package com.fiilda.launcher

import dev.glasslab.glass.resolveGlassGeometry
import dev.glasslab.glass.GlassGeometrySignal
import android.animation.ValueAnimator
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.glasslab.glass.GlassBackdrop
import dev.glasslab.glass.glassSceneContributor
import dev.glasslab.glass.glassSurface
import dev.glasslab.glass.rememberGlassBackdrop
import dev.glasslab.glass.rememberGlassScene

/** The surfaces that receive the FiiLDA GLASS optical treatment. */
internal enum class LauncherGlassSurfaceKind {
    TILE,
    NAVIGATION,
    FOLDER_SHEET,
    SEARCH_CONTROL,
}

/**
 * Stable integration boundary between the launcher surfaces and the glass renderer.
 *
 * Keeping the renderer state behind this small interface is intentional. Home, drawer, and
 * folder code can remain composed once while the host owns the single wallpaper backdrop and
 * scene state. The non-GLASS themes use the no-op instance and therefore retain their existing
 * drawing and interaction paths.
 */
@Stable
internal interface LauncherGlassContext {
    val enabled: Boolean
    val motionEnabled: Boolean

    @Composable
    fun surfaceModifier(
        modifier: Modifier,
        kind: LauncherGlassSurfaceKind,
        fallbackColor: Color?,
        cornerRadius: Dp,
        sceneEnabled: Boolean,
        geometryVersion: Any?,
    ): Modifier

    @Composable
    fun contributorModifier(
        modifier: Modifier,
        enabled: Boolean,
        alpha: Float,
        zIndex: Float,
        fallbackColor: Color?,
        geometryVersion: Any?,
        cornerRadius: Dp,
    ): Modifier
}

private object DisabledLauncherGlassContext : LauncherGlassContext {
    override val enabled: Boolean = false
    override val motionEnabled: Boolean = true

    @Composable
    override fun surfaceModifier(
        modifier: Modifier,
        kind: LauncherGlassSurfaceKind,
        fallbackColor: Color?,
        cornerRadius: Dp,
        sceneEnabled: Boolean,
        geometryVersion: Any?,
    ): Modifier = modifier

    @Composable
    override fun contributorModifier(
        modifier: Modifier,
        enabled: Boolean,
        alpha: Float,
        zIndex: Float,
        fallbackColor: Color?,
        geometryVersion: Any?,
        cornerRadius: Dp,
    ): Modifier = modifier
}

internal val LocalLauncherGlass = compositionLocalOf<LauncherGlassContext> {
    DisabledLauncherGlassContext
}

/**
 * Sharp foreground registration policy for the floating navigation glass.
 *
 * The root owns the visibility/transition values for a home or drawer layer. Child tiles inherit
 * those values through this scope so hidden layers never remain in the navigation scene and a
 * parent graphics-layer transition is reflected in every contributor's geometry revision.
 */
internal data class LauncherGlassSceneScopeState(
    val enabled: Boolean,
    val alpha: Float = 1f,
    val zIndex: Float = 0f,
    val geometryVersion: Any? = null,
)

internal val LocalLauncherGlassSceneScope =
    compositionLocalOf<LauncherGlassSceneScopeState?> { null }

// Equal by its parts, so recomposing a scope with the same signals does not invalidate the scene.
private data class LauncherGlassGeometryScopeVersion(
    val parent: Any?,
    val local: Any?,
) : GlassGeometrySignal {
    override fun read(): Any? = resolveGlassGeometry(parent) to resolveGlassGeometry(local)
}

private fun combineLauncherGlassGeometry(parent: Any?, local: Any?): Any? = when {
    parent == null -> local
    local == null -> parent
    else -> LauncherGlassGeometryScopeVersion(parent = parent, local = local)
}

@Composable
internal fun LauncherGlassSceneScope(
    enabled: Boolean,
    alpha: Float = 1f,
    zIndex: Float = 0f,
    geometryVersion: Any? = null,
    content: @Composable () -> Unit,
) {
    val parent = LocalLauncherGlassSceneScope.current
    val combinedGeometry = combineLauncherGlassGeometry(
        parent = LocalLauncherGlassGeometryVersion.current,
        local = geometryVersion,
    )
    val inheritedEnabled = parent?.enabled ?: true
    CompositionLocalProvider(
        LocalLauncherGlassSceneScope provides LauncherGlassSceneScopeState(
            enabled = inheritedEnabled && enabled,
            alpha = ((parent?.alpha ?: 1f) * alpha).coerceIn(0f, 1f),
            zIndex = (parent?.zIndex ?: 0f) + zIndex,
            geometryVersion = combinedGeometry,
        ),
        LocalLauncherGlassGeometryVersion provides combinedGeometry,
        content = content,
    )
}

/** Root-owned geometry revision for surfaces below an animated parent graphics layer. */
internal val LocalLauncherGlassGeometryVersion = compositionLocalOf<Any?> { null }

/** Exposes the accessibility-aware motion state to the small amount of new GLASS motion. */
internal val LocalLauncherGlassMotionEnabled = compositionLocalOf { true }

private val GlassTileCornerRadius = LauncherTileCornerRadius
private val GlassNavigationCornerRadius = LauncherNavigationCornerRadius
private val GlassFolderCornerRadius = LauncherFolderSheetCornerRadius
private val GlassSearchCornerRadius = LauncherSearchControlCornerRadius

/**
 * Installs one fixed wallpaper/backdrop pair for the launcher surfaces.
 *
 * The wallpaper is deliberately outside the home/drawer transition layers. This keeps a moving
 * surface from changing the coordinate space of the source image and lets the renderer apply its
 * own geometry transform to each optical surface. Settings and action dialogs remain outside this
 * host and keep their existing Material treatment.
 */
@Composable
internal fun LauncherGlassHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isGlass = LocalLauncherTheme.current == LauncherTheme.GLASS
    val wallpaperState = LocalGlassWallpaperController.current?.state
    val wallpaperVersion = wallpaperState?.bitmap
    val appearance = wallpaperState?.appearance ?: GlassAppearance()
    val reduceTransparency = LocalGlassReduceTransparency.current
    val highContrast = rememberHighContrastTextEnabled()
    val motionEnabled = rememberGlassMotionEnabled()
    // Keep these remember calls unconditional so switching themes does not recreate the launcher
    // state tree. The renderer remains dormant through the no-op integration context when GLASS is
    // not selected.
    val backdrop = rememberGlassBackdrop()
    val scene = rememberGlassScene()
    val style = remember(appearance, highContrast, reduceTransparency) {
        launcherGlassStyle(
            appearance = appearance,
            reduceTransparency = reduceTransparency,
            highContrast = highContrast,
        )
    }
    val context = remember(isGlass, motionEnabled, backdrop, scene, style) {
        if (!isGlass) {
            DisabledLauncherGlassContext
        } else {
            object : LauncherGlassContext {
                override val enabled: Boolean = true
                override val motionEnabled: Boolean = motionEnabled

                @Composable
                override fun surfaceModifier(
                    modifier: Modifier,
                    kind: LauncherGlassSurfaceKind,
                    fallbackColor: Color?,
                    cornerRadius: Dp,
                    sceneEnabled: Boolean,
                    geometryVersion: Any?,
                ): Modifier {
                    val sceneForSurface = if (
                        sceneEnabled && (kind == LauncherGlassSurfaceKind.NAVIGATION ||
                            kind == LauncherGlassSurfaceKind.SEARCH_CONTROL)
                    ) {
                        scene
                    } else {
                        null
                    }
                    return modifier
                        .glassSurface(
                            backdrop = backdrop,
                            style = style,
                            cornerRadius = cornerRadius,
                            scene = sceneForSurface,
                            geometryVersion = geometryVersion,
                        )
                        .clip(RoundedCornerShape(cornerRadius))
                }

                @Composable
                override fun contributorModifier(
                    modifier: Modifier,
                    enabled: Boolean,
                    alpha: Float,
                    zIndex: Float,
                    fallbackColor: Color?,
                    geometryVersion: Any?,
                    cornerRadius: Dp,
                ): Modifier {
                    return modifier.glassSceneContributor(
                        scene = scene,
                        enabled = enabled,
                        alpha = alpha,
                        zIndex = zIndex,
                        fallbackColor = fallbackColor,
                        geometryVersion = geometryVersion,
                        cornerRadius = cornerRadius,
                    )
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalLauncherGlass provides context,
        LocalLauncherGlassMotionEnabled provides motionEnabled,
    ) {
        Box(modifier = modifier) {
            if (isGlass) {
                GlassBackdrop(
                    state = backdrop,
                    modifier = Modifier.fillMaxSize(),
                    contentVersion = wallpaperVersion ?: "default-wallpaper",
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GlassWallpaper(modifier = Modifier.fillMaxSize())
                        // MainActivity deliberately draws behind transparent system bars and
                        // keeps white system icons for the GLASS palette. Keep the contrast aid
                        // inside the backdrop so the same darkened inset is present when a glass
                        // surface samples the fixed wallpaper, while leaving the rest of the
                        // image fully clear.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsTopHeight(WindowInsets.statusBars)
                                .align(Alignment.TopCenter)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        )
                        // The transparent gesture area can also land on a bright photo. This
                        // small bottom-only scrim protects the white system handle without
                        // changing the app-owned navigation surface or its measured padding.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.35f)),
                        )
                    }
                }
            }
            // Keep one unconditional content call site. Theme changes therefore update the
            // renderer backdrop without remounting the launcher's remember/gesture state tree.
            content()
        }
    }
}

@Composable
internal fun rememberHighContrastTextEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        if (Build.VERSION.SDK_INT < 36) {
            onDispose { }
        } else {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            if (manager == null) {
                onDispose { }
            } else {
                enabled = manager.isHighContrastTextEnabled
                val listener = AccessibilityManager.HighContrastTextStateChangeListener {
                    enabled = it
                }
                val executor = ContextCompat.getMainExecutor(context)
                manager.addHighContrastTextStateChangeListener(executor, listener)
                onDispose {
                    manager.removeHighContrastTextStateChangeListener(listener)
                }
            }
        }
    }
    return enabled
}

@Composable
private fun rememberGlassMotionEnabled(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) {
                enabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

/** Applies a GLASS tile while preserving the caller's existing layout and gesture modifiers. */
@Composable
internal fun Modifier.launcherGlassTile(
    fallbackColor: Color? = null,
    geometryVersion: Any? = null,
): Modifier = LocalLauncherGlass.current.surfaceModifier(
    modifier = this,
    kind = LauncherGlassSurfaceKind.TILE,
    fallbackColor = fallbackColor,
    cornerRadius = GlassTileCornerRadius,
    sceneEnabled = false,
    geometryVersion = geometryVersion ?: LocalLauncherGlassGeometryVersion.current,
)

/** Applies the floating rounded navigation surface. */
@Composable
internal fun Modifier.launcherGlassNavigation(
    fallbackColor: Color? = null,
    sceneEnabled: Boolean = true,
    geometryVersion: Any? = null,
): Modifier = LocalLauncherGlass.current.surfaceModifier(
    modifier = this,
    kind = LauncherGlassSurfaceKind.NAVIGATION,
    fallbackColor = fallbackColor,
    cornerRadius = GlassNavigationCornerRadius,
    sceneEnabled = sceneEnabled,
    geometryVersion = geometryVersion ?: LocalLauncherGlassGeometryVersion.current,
)

/** Applies the single optical surface used by the expanded-folder sheet. */
@Composable
internal fun Modifier.launcherGlassFolderSheet(
    fallbackColor: Color? = null,
    geometryVersion: Any? = null,
): Modifier = LocalLauncherGlass.current.surfaceModifier(
    modifier = this,
    kind = LauncherGlassSurfaceKind.FOLDER_SHEET,
    fallbackColor = fallbackColor,
    cornerRadius = GlassFolderCornerRadius,
    sceneEnabled = false,
    geometryVersion = geometryVersion ?: LocalLauncherGlassGeometryVersion.current,
)

/** Applies the same clear material to a text-heavy drawer/search control. */
@Composable
internal fun Modifier.launcherGlassSearchControl(
    fallbackColor: Color? = null,
    geometryVersion: Any? = null,
    sceneEnabled: Boolean = false,
): Modifier = LocalLauncherGlass.current.surfaceModifier(
    modifier = this,
    kind = LauncherGlassSurfaceKind.SEARCH_CONTROL,
    fallbackColor = fallbackColor,
    cornerRadius = GlassSearchCornerRadius,
    sceneEnabled = sceneEnabled,
    geometryVersion = geometryVersion ?: LocalLauncherGlassGeometryVersion.current,
)

/**
 * Publishes sharp foreground below a navigation glass surface. External widgets and video use a
 * fallback color so native surfaces are never assumed to be capturable by the Compose scene.
 */
@Composable
internal fun Modifier.launcherGlassContributor(
    enabled: Boolean = true,
    alpha: Float = 1f,
    zIndex: Float = 0f,
    fallbackColor: Color? = null,
    geometryVersion: Any? = null,
    cornerRadius: Dp = GlassTileCornerRadius,
): Modifier = LocalLauncherGlass.current.contributorModifier(
    modifier = this,
    enabled = enabled && (LocalLauncherGlassSceneScope.current?.enabled == true),
    alpha = alpha * (LocalLauncherGlassSceneScope.current?.alpha ?: 1f),
    zIndex = zIndex + (LocalLauncherGlassSceneScope.current?.zIndex ?: 0f),
    fallbackColor = fallbackColor,
    geometryVersion = combineLauncherGlassGeometry(
        parent = LocalLauncherGlassSceneScope.current?.geometryVersion
            ?: LocalLauncherGlassGeometryVersion.current,
        local = geometryVersion,
    ),
    cornerRadius = cornerRadius,
)

/** Keeps opaque legacy surfaces opaque while the GLASS host remains transparent. */
@Composable
internal fun Modifier.launcherGlassBaseBackground(color: Color): Modifier =
    if (LocalLauncherGlass.current.enabled) this else background(color)
