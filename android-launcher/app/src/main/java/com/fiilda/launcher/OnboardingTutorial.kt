package com.fiilda.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val OnboardingSeenKey = "onboarding_seen"

/**
 * The tutorial appears on a fresh install until it is finished or skipped, even if the launcher is
 * left part way through (the Home layout is saved on the first launch). Someone updating from an
 * older version already has a saved layout and an update time after the install, so they are
 * marked as having seen it without being interrupted.
 */
internal fun shouldShowOnboarding(context: Context): Boolean {
    val preferences = context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
    if (preferences.getBoolean(OnboardingSeenKey, false)) return false
    val neverUpdated = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)
    if (onboardingIsForFreshInstall(neverUpdated, hasSavedLayout = readFavoriteIds(context) != null)) return true
    markOnboardingSeen(context)
    return false
}

/** A launcher with no saved layout, or one that was never updated since install, is new. */
internal fun onboardingIsForFreshInstall(neverUpdated: Boolean, hasSavedLayout: Boolean): Boolean =
    neverUpdated || !hasSavedLayout

internal fun markOnboardingSeen(context: Context) {
    runCatching {
        context.getSharedPreferences(LauncherThemePreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OnboardingSeenKey, true)
            .apply()
    }
}

/** Lets Settings replay the tutorial. */
internal val LocalShowOnboarding = staticCompositionLocalOf<() -> Unit> { {} }

/** Hosts the launcher and lays the tutorial over it when it should be shown. */
@Composable
internal fun OnboardingHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var showing by remember { mutableStateOf(shouldShowOnboarding(context)) }
    CompositionLocalProvider(LocalShowOnboarding provides { showing = true }) {
        Box(modifier = Modifier.fillMaxSize()) {
            // The launcher's own layers use zIndex (Settings is 1f); keeping them in a child box
            // stops those values from lifting them above the tutorial.
            Box(modifier = Modifier.fillMaxSize()) { content() }
            if (showing) {
                OnboardingTutorial(
                    onFinish = {
                        markOnboardingSeen(context)
                        showing = false
                    },
                )
            }
        }
    }
}

private class OnboardingPage(
    val title: String,
    val body: String,
    val illustration: @Composable () -> Unit,
)

@Composable
private fun OnboardingTutorial(onFinish: () -> Unit) {
    val palette = LocalLauncherPalette.current
    // Glass keeps its launcher surfaces transparent; the tutorial needs an opaque backdrop.
    val background = if (palette.background.alpha < 1f) Color(0xFF26343B) else palette.background
    val pages = listOf(
        OnboardingPage(
            title = tr("FiiLDA Launcherへようこそ", "Welcome to FiiLDA Launcher"),
            body = tr(
                "折りたたみスマホのための、自分だけのホーム。閉じても開いても、画面に合わせて並びます。",
                "A Home of your own, made for foldable phones. It fits the screen whether it's folded or open.",
            ),
            illustration = { MiniHome(highlight = null) },
        ),
        OnboardingPage(
            title = tr("長押しで、並べ替え", "Long press to arrange"),
            body = tr(
                "アプリやウィジェットを長押しすると、ドラッグで移動したり、メニューから大きさを変えたりできます。",
                "Long press an app or widget to drag it somewhere else, or open its menu to change its size.",
            ),
            illustration = { MiniHome(highlight = 3) },
        ),
        OnboardingPage(
            title = tr("天気も予定も、ホームに", "Weather and events on Home"),
            body = tr(
                "天気や予定のウィジェットは、ボタンから許可すると表示されます。位置情報は約1km単位に丸めて使います。",
                "The weather and event widgets appear once you allow access from their buttons. Your location is rounded to about 1 km.",
            ),
            illustration = { MiniWidgets() },
        ),
        OnboardingPage(
            title = tr("ホームとアプリ一覧", "Home and all apps"),
            body = tr(
                "下のバーでホームとアプリ一覧を切り替えます。ホームは左右にスワイプしてページを移動できます。",
                "Use the bar at the bottom to switch between Home and all apps. Swipe sideways on Home to change pages.",
            ),
            illustration = { MiniNavigation() },
        ),
        OnboardingPage(
            title = tr("気分に合わせて、テーマを", "A theme for your mood"),
            body = tr(
                "設定から5つのテーマを選べます。ウィジェットの見た目も、テーマに合わせて変わります。",
                "Pick from five themes in Settings. Widgets change their look to match the theme.",
            ),
            illustration = { MiniThemes() },
        ),
        OnboardingPage(
            title = tr("さっそく使ってみましょう", "You're all set"),
            body = tr(
                "FiiLDAをホームアプリに設定すると、ホームボタンでいつでもこの画面に戻れます。",
                "Set FiiLDA as your Home app so the Home button always brings you back here.",
            ),
            illustration = { SetHomeButton() },
        ),
    )
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pagerState.currentPage == pages.lastIndex
    BackHandler {
        if (pagerState.currentPage > 0) scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } else onFinish()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            // Swallow touches so the launcher underneath can't be used through the tutorial.
            .clickable(enabled = true, indication = null, interactionSource = null) {},
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Spacer(modifier = Modifier.weight(1f))
                if (!last) {
                    Text(
                        text = tr("スキップ", "Skip"),
                        color = palette.muted,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onFinish)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val page = pages[index]
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().heightIn(min = 200.dp),
                        contentAlignment = Alignment.Center,
                    ) { page.illustration() }
                    Text(
                        text = page.title,
                        color = palette.ink,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                    Text(
                        text = page.body,
                        color = palette.muted,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 420.dp).padding(top = 12.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    repeat(pages.size) { index ->
                        val selected = index == pagerState.currentPage
                        val width by animateFloatAsState(if (selected) 20f else 6f, label = "onboardingDot")
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .size(width = width.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(if (selected) palette.accent else palette.line),
                        )
                    }
                }
                Text(
                    text = if (last) tr("はじめる", "Get started") else tr("次へ", "Next"),
                    color = palette.accentOn,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(palette.accent)
                        .clickable {
                            if (last) onFinish() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

// region Illustrations

/** A small mock Home: a 2×2 widget and app tiles. [highlight] marks the tile being long pressed. */
@Composable
private fun MiniHome(highlight: Int?) {
    val palette = LocalLauncherPalette.current
    val pulse = rememberInfiniteTransition(label = "onboardingPulse")
    val ring by pulse.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "onboardingRing",
    )
    val tileShape = RoundedCornerShape(10.dp)
    @Composable
    fun Tile(index: Int, modifier: Modifier, content: @Composable () -> Unit = {}) {
        val active = index == highlight
        Box(
            modifier = modifier
                .scale(if (active) 1.06f else 1f)
                .clip(tileShape)
                .background(palette.surface)
                .border(if (active) 2.dp else 1.dp, if (active) palette.accent else palette.line, tileShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (active) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(30.dp).scale(ring),
                )
            }
        }
    }
    @Composable
    fun AppDot() = Box(modifier = Modifier.size(22.dp).clip(CircleShape).background(palette.muted.copy(alpha = 0.5f)))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tile(0, Modifier.weight(2f).aspectRatio(1f)) {
                Text("12:30", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tile(1, Modifier.fillMaxWidth().aspectRatio(1f)) { AppDot() }
                Tile(2, Modifier.fillMaxWidth().aspectRatio(1f)) { AppDot() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { column ->
                Tile(3 + column, Modifier.weight(1f).aspectRatio(1f)) { AppDot() }
            }
        }
    }
}

@Composable
private fun MiniWidgets() {
    val palette = LocalLauncherPalette.current
    @Composable
    fun Card(icon: ImageVector, label: String, modifier: Modifier) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = modifier
                .clip(shape)
                .background(palette.surface)
                .border(1.dp, palette.line, shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
            Text(label, color = palette.ink, fontSize = 18.sp, fontWeight = FontWeight.Light)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Card(Icons.Filled.WbSunny, "24°", Modifier.weight(1f))
        Card(Icons.AutoMirrored.Filled.EventNote, tr("予定", "Events"), Modifier.weight(1f))
        Card(Icons.Filled.PlayArrow, "♪", Modifier.weight(1f))
    }
}

@Composable
private fun MiniNavigation() {
    val palette = LocalLauncherPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = null, tint = palette.muted, modifier = Modifier.size(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                repeat(2) { page ->
                    Box(
                        modifier = Modifier
                            .size(width = 70.dp, height = 110.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.surface)
                            .border(1.dp, if (page == 0) palette.accent else palette.line, RoundedCornerShape(10.dp)),
                    )
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = palette.muted, modifier = Modifier.size(32.dp))
        }
        val barShape = RoundedCornerShape(percent = 50)
        Row(
            modifier = Modifier
                .padding(top = 20.dp)
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .clip(barShape)
                .background(palette.surface)
                .border(1.dp, palette.line, barShape),
        ) {
            listOf(Icons.Filled.Home, Icons.Filled.Apps).forEachIndexed { index, icon ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (index == 0) palette.accentSurface else Color.Transparent)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = if (index == 0) palette.accent else palette.muted)
                }
            }
        }
    }
}

@Composable
private fun MiniThemes() {
    val swatches = listOf(
        LauncherTheme.DEFAULT to (Color(0xFF080909) to Color(0xFF52D7EF)),
        LauncherTheme.CLASSIC to (NISHIKIGOI_IVORY to NISHIKIGOI_RED),
        LauncherTheme.WINDOWS_8 to (Color(0xFF001A33) to Color(0xFF00A4EF)),
        LauncherTheme.MATERIAL to (Color(0xFF1B1B21) to Color(0xFFB8C3FF)),
        LauncherTheme.GLASS to (Color(0xFF4F6470) to Color.White),
        LauncherTheme.DARK_GLASS to (Color(0xFF17191D) to Color.White),
    )
    val palette = LocalLauncherPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        swatches.forEach { (theme, colors) ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.62f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.first)
                        .border(1.dp, palette.line, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(colors.second))
                }
                Text(
                    theme.displayName,
                    color = palette.muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Asks Android to make FiiLDA the Home app, or shows that it already is. */
@Composable
private fun SetHomeButton() {
    val context = LocalContext.current
    val palette = LocalLauncherPalette.current
    val roleManager = remember(context) { context.getSystemService(RoleManager::class.java) }
    fun isHome(): Boolean = runCatching { roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true }.getOrDefault(false)
    var held by remember { mutableStateOf(isHome()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { held = isHome() }
    val shape = RoundedCornerShape(16.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Home, contentDescription = null, tint = palette.accent, modifier = Modifier.size(64.dp))
        Text(
            text = if (held) tr("ホームアプリに設定済みです", "FiiLDA is your Home app") else tr("ホームアプリに設定", "Set as Home app"),
            color = if (held) palette.muted else palette.accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 20.dp)
                .clip(shape)
                .border(1.dp, if (held) palette.line else palette.accent, shape)
                .clickable(enabled = !held) {
                    val request = runCatching {
                        roleManager?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                            ?.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    }.getOrNull() ?: Intent(Settings.ACTION_HOME_SETTINGS)
                    runCatching { launcher.launch(request) }
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

// endregion
