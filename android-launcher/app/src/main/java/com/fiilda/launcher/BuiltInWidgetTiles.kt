package com.fiilda.launcher

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * The visual dialect a built-in widget speaks in the active theme. Each keeps its theme's existing
 * surfaces and tile shape; only the inner components (headers, meters, markers, numerals) change.
 */
internal enum class WidgetLanguage {
    /** Default: MacBook keys — uniform dark key cells, thin lines, cyan only for the current state. */
    KEYBOARD,

    /** Classic: INFOBAR NISHIKIGOI — ivory and pale-blue blocks with a single red accent. */
    NISHIKIGOI,

    /** Windows 8: flat Metro live tile — light numerals, white glyphs, name at the bottom left. */
    METRO,

    /** Material: tonal containers, pills and circles in the dynamic primary color. */
    MATERIAL,

    /** Glass: white ink on translucent white, no opaque inner blocks. */
    GLASS,
}

internal fun widgetLanguageFor(theme: LauncherTheme): WidgetLanguage = when (theme) {
    LauncherTheme.DEFAULT -> WidgetLanguage.KEYBOARD
    LauncherTheme.CLASSIC -> WidgetLanguage.NISHIKIGOI
    LauncherTheme.WINDOWS_8 -> WidgetLanguage.METRO
    LauncherTheme.MATERIAL -> WidgetLanguage.MATERIAL
    LauncherTheme.GLASS, LauncherTheme.DARK_GLASS -> WidgetLanguage.GLASS
}

private class WidgetStyle(
    val language: WidgetLanguage,
    val heroWeight: FontWeight,
    /** Shape of meter segments and small cells. */
    val cellShape: Shape,
    /** Shape of the "today" / "now" marker. */
    val markerShape: Shape,
    val buttonShape: Shape,
    val track: Color,
    val fill: Color,
    val current: Color,
    val onCurrent: Color,
    val chip: Color,
    val onChip: Color,
    val weekend: Color,
)

private val LocalWidgetStyle = staticCompositionLocalOf<WidgetStyle> { error("No widget style") }

@Composable
private fun widgetStyleFor(widget: HomeWidget, metroTileColor: Color? = null): WidgetStyle {
    val palette = LocalLauncherPalette.current
    return when (widgetLanguageFor(LocalLauncherTheme.current)) {
        WidgetLanguage.KEYBOARD -> WidgetStyle(
            language = WidgetLanguage.KEYBOARD,
            heroWeight = FontWeight.Light,
            cellShape = RoundedCornerShape(2.dp),
            markerShape = RoundedCornerShape(6.dp),
            buttonShape = RoundedCornerShape(8.dp),
            track = palette.enabledSurface,
            fill = palette.muted,
            current = palette.accent,
            onCurrent = palette.accentOn,
            chip = palette.accentSurface,
            onChip = palette.accent,
            weekend = palette.quiet,
        )
        WidgetLanguage.NISHIKIGOI -> WidgetStyle(
            language = WidgetLanguage.NISHIKIGOI,
            heroWeight = FontWeight.Medium,
            cellShape = RoundedCornerShape(2.dp),
            markerShape = RoundedCornerShape(4.dp),
            buttonShape = RoundedCornerShape(4.dp),
            track = NISHIKIGOI_IVORY,
            fill = NISHIKIGOI_BLUE,
            current = NISHIKIGOI_RED,
            onCurrent = Color.White,
            chip = NISHIKIGOI_BLUE,
            onChip = palette.ink,
            weekend = NISHIKIGOI_RED,
        )
        WidgetLanguage.METRO -> WidgetStyle(
            language = WidgetLanguage.METRO,
            heroWeight = FontWeight.Light,
            cellShape = RectangleShape,
            markerShape = RectangleShape,
            // Text backgrounds (buttons, chips) are concentric with the tile's rounded corners.
            buttonShape = RoundedCornerShape(LauncherTileCornerRadius - 10.dp),
            track = palette.ink.copy(alpha = 0.28f),
            fill = palette.ink,
            current = palette.ink,
            onCurrent = metroTileColor ?: windows8BuiltInTileColor(widget.id),
            chip = palette.ink.copy(alpha = 0.2f),
            onChip = palette.ink,
            weekend = palette.muted,
        )
        WidgetLanguage.MATERIAL -> WidgetStyle(
            language = WidgetLanguage.MATERIAL,
            heroWeight = FontWeight.Normal,
            cellShape = RoundedCornerShape(percent = 50),
            markerShape = CircleShape,
            buttonShape = RoundedCornerShape(percent = 50),
            track = palette.accentSurface,
            fill = palette.accent,
            current = palette.accent,
            onCurrent = palette.accentOn,
            chip = palette.accentSurface,
            onChip = palette.accent,
            weekend = palette.muted,
        )
        WidgetLanguage.GLASS -> WidgetStyle(
            language = WidgetLanguage.GLASS,
            heroWeight = FontWeight.Light,
            cellShape = RoundedCornerShape(4.dp),
            markerShape = CircleShape,
            buttonShape = RoundedCornerShape(10.dp),
            track = Color.White.copy(alpha = 0.2f),
            // Leaves headroom above the fill so the current cell/marker in pure white stands out.
            fill = Color.White.copy(alpha = 0.55f),
            current = Color.White,
            onCurrent = Color.Black,
            chip = Color.White.copy(alpha = 0.16f),
            onChip = Color.White,
            weekend = palette.quiet,
        )
    }
}

/** Footprint of a built-in widget in grid cells. One row renders a compact strip. */
private class WidgetFootprint(val rows: Int, val columns: Int) {
    val compact: Boolean get() = rows == 1
    val wide: Boolean get() = columns >= 4
}

@Composable
internal fun BuiltInWidgetTile(
    widget: HomeWidget,
    now: LocalDateTime,
    posture: Posture,
    gridSize: WidgetGridSize,
    battery: BatteryStatus,
    onWeather: () -> Unit,
    onCalendar: () -> Unit,
) {
    val footprint = WidgetFootprint(gridSize.rowSpan, gridSize.columnSpan)
    CompositionLocalProvider(LocalWidgetStyle provides widgetStyleFor(widget)) {
        when (widget) {
            HomeWidget.CLOCK -> ClockWidget(now, footprint)
            HomeWidget.WEATHER -> WeatherWidget(now, footprint, onWeather)
            HomeWidget.FORECAST -> ForecastWidget(now, footprint)
            HomeWidget.AGENDA -> AgendaWidget(now, footprint)
            HomeWidget.CALENDAR -> CalendarWidget(now, footprint, onCalendar)
            HomeWidget.BATTERY -> BatteryWidget(battery, footprint)
            HomeWidget.REMINDER -> ReminderWidget(footprint)
            // Rendered by HomeWidgetTile; listed so the when stays exhaustive.
            HomeWidget.MEDIA, HomeWidget.PHOTO -> Unit
        }
    }
}

// region Shared components

@Composable
private fun Dp.asSp(): TextUnit = with(LocalDensity.current) { this@asSp.toSp() }

/**
 * Tile frame: themed header above the content, or for Metro the name at the bottom left as on a
 * live tile. The surface itself is always the theme's [FiiLDATile].
 */
@Composable
private fun WidgetFrame(
    widget: HomeWidget,
    title: String,
    icon: ImageVector,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalWidgetStyle.current
    FiiLDATile(modifier = Modifier.fillMaxSize(), onClick = onClick, widget = widget) {
        if (style.language != WidgetLanguage.METRO) {
            WidgetHeader(title = title, icon = icon, trailing = trailing, onTrailing = onTrailing)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = if (style.language == WidgetLanguage.METRO) 0.dp else 6.dp),
            content = content,
        )
        if (style.language == WidgetLanguage.METRO) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(text = title, color = FiiLDAInk, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                trailing?.let { TrailingText(it, onTrailing) }
            }
        }
    }
}

@Composable
private fun WidgetHeader(title: String, icon: ImageVector, trailing: String?, onTrailing: (() -> Unit)?) {
    val style = LocalWidgetStyle.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when (style.language) {
            WidgetLanguage.MATERIAL -> Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(style.chip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = style.onChip, modifier = Modifier.size(13.dp))
            }
            WidgetLanguage.NISHIKIGOI -> Box(modifier = Modifier.size(7.dp).background(style.current, style.cellShape))
            else -> Icon(imageVector = icon, contentDescription = null, tint = FiiLDAMuted, modifier = Modifier.size(12.dp))
        }
        Text(
            text = title,
            color = if (style.language == WidgetLanguage.KEYBOARD || style.language == WidgetLanguage.GLASS) {
                FiiLDAMuted
            } else {
                FiiLDAInk
            },
            fontSize = if (style.language == WidgetLanguage.MATERIAL) 11.sp else 10.sp,
            fontWeight = if (style.language == WidgetLanguage.KEYBOARD || style.language == WidgetLanguage.GLASS) {
                FontWeight.Normal
            } else {
                FontWeight.Medium
            },
            letterSpacing = if (style.language == WidgetLanguage.KEYBOARD) 0.5.sp else 0.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).weight(1f),
        )
        trailing?.let { TrailingText(it, onTrailing) }
    }
}

@Composable
private fun TrailingText(text: String, onClick: (() -> Unit)?) {
    val style = LocalWidgetStyle.current
    val clickable = onClick != null
    Text(
        text = text,
        color = if (clickable && style.language != WidgetLanguage.METRO) style.current else FiiLDAMuted,
        fontSize = if (clickable) 11.sp else 9.sp,
        fontWeight = if (clickable) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (clickable) {
            Modifier.clip(style.buttonShape).clickable(onClick = onClick!!).padding(horizontal = 6.dp, vertical = 2.dp)
        } else {
            Modifier.padding(start = 6.dp)
        },
    )
}

private val TabularNumerals = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun HeroText(text: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = FiiLDAInk) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        lineHeight = size,
        fontWeight = LocalWidgetStyle.current.heroWeight,
        letterSpacing = if (LocalWidgetStyle.current.language == WidgetLanguage.MATERIAL) 0.sp else (-1).sp,
        style = TabularNumerals,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

@Composable
private fun SecondaryText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = FiiLDAMuted,
    size: TextUnit = 10.sp,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = modifier,
    )
}

/** A row of key-like cells; [filled] cells use the fill color and [highlight] the current color. */
@Composable
private fun SegmentMeter(
    count: Int,
    filled: Int,
    modifier: Modifier = Modifier,
    highlight: Int? = null,
    height: Dp = 8.dp,
    gap: Dp = 2.dp,
) {
    val style = LocalWidgetStyle.current
    Row(modifier = modifier.height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(count) { index ->
            val color = when {
                index == highlight -> style.current
                index < filled -> style.fill
                else -> style.track
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight().background(color, style.cellShape))
        }
    }
}

/** Horizontal temperature range [low, high] within the span [min, max], with an optional marker. */
@Composable
private fun RangeBar(low: Int, high: Int, min: Int, max: Int, modifier: Modifier = Modifier, marker: Int? = null) {
    val style = LocalWidgetStyle.current
    val span = (max - min).coerceAtLeast(1).toFloat()
    val start = ((low - min) / span).coerceIn(0f, 1f)
    val end = ((high - min) / span).coerceIn(start, 1f)
    BoxWithConstraints(modifier = modifier.height(5.dp).clip(style.cellShape).background(style.track)) {
        Box(
            modifier = Modifier
                .padding(start = maxWidth * start)
                .width((maxWidth * (end - start)).coerceAtLeast(5.dp))
                .fillMaxHeight()
                .background(style.fill, style.cellShape),
        )
        marker?.let {
            val position = ((it - min) / span).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .padding(start = (maxWidth * position - 2.5.dp).coerceAtLeast(0.dp))
                    .size(5.dp)
                    .background(style.current, CircleShape),
            )
        }
    }
}

@Composable
internal fun WidgetActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val style = LocalWidgetStyle.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(style.buttonShape)
            .background(style.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        FitText(
            text = text,
            color = style.onChip,
            maxFontSize = 10.sp,
            minFontSize = 7.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusChip(text: String, emphasized: Boolean) {
    val style = LocalWidgetStyle.current
    Text(
        text = text,
        color = if (emphasized) style.onCurrent else style.onChip,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(style.buttonShape)
            .background(if (emphasized) style.current else style.chip)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun ColumnScope.FillSpace() = Spacer(modifier = Modifier.weight(1f))

@Composable
private fun RowScope.FillSpace() = Spacer(modifier = Modifier.weight(1f))

// endregion

// region Clock

private val ClockTimeFormat = DateTimeFormatter.ofPattern("H:mm")

@Composable
private fun ClockWidget(now: LocalDateTime, footprint: WidgetFootprint) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val alarm = remember(now) { nextAlarmTriggerMillis(context)?.let { nextAlarmLabel(it, now, zone) } }
    val time = now.format(ClockTimeFormat)
    // The Reiwa era line is a Japanese calendar convention, so English shows no trailing label.
    val era = if (isJapaneseUi()) "令和${reiwaYear(now.year)}年" else null
    WidgetFrame(widget = HomeWidget.CLOCK, title = tr("時計", "Clock"), icon = Icons.Filled.Schedule, trailing = era) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val width = maxWidth
            val height = maxHeight
            when {
                footprint.compact -> Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    HeroText(time, minOf(height * 0.72f, width * 0.2f).asSp())
                    FillSpace()
                    Column(horizontalAlignment = Alignment.End) {
                        SecondaryText(fullDateLabel(now.toLocalDate()), color = FiiLDAInk, size = 11.sp)
                        alarm?.let { AlarmLine(it) }
                    }
                }
                footprint.wide -> Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        HeroText(time, minOf(height * 0.52f, width * 0.2f).asSp())
                        FillSpace()
                        Column(horizontalAlignment = Alignment.End) {
                            SecondaryText(fullDateLabel(now.toLocalDate()), color = FiiLDAInk, size = 13.sp)
                            alarm?.let { AlarmLine(it) }
                        }
                    }
                    HourStrip(now)
                }
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    HeroText(time, minOf(height * 0.4f, width * 0.3f).asSp())
                    SecondaryText(fullDateLabel(now.toLocalDate()), color = FiiLDAInk, size = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    FillSpace()
                    alarm?.let { AlarmLine(it, modifier = Modifier.padding(bottom = 6.dp)) }
                    HourStrip(now)
                }
            }
        }
    }
}

@Composable
private fun AlarmLine(label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = Icons.Filled.Alarm, contentDescription = tr("次のアラーム", "Next alarm"), tint = FiiLDAMuted, modifier = Modifier.size(11.dp))
        SecondaryText(label, modifier = Modifier.padding(start = 3.dp))
    }
}

/** 24 hour cells: elapsed hours filled, the current hour highlighted. */
@Composable
private fun HourStrip(now: LocalDateTime) {
    SegmentMeter(count = 24, filled = now.hour, highlight = now.hour, height = 6.dp, gap = 1.5.dp, modifier = Modifier.fillMaxWidth())
}

// endregion

// region Weather

/** Without location access a tap asks for it; otherwise it forces a refresh. */
private fun weatherTileClick(weather: WeatherUiState, onClick: () -> Unit): () -> Unit = {
    if (weather.hasAccess) {
        weather.refresh()
        onClick()
    } else {
        weather.requestAccess()
    }
}

@Composable
private fun ColumnScope.WeatherUnavailable(weather: WeatherUiState, compact: Boolean) {
    if (!weather.hasAccess) {
        if (!compact) {
            SecondaryText(tr("現在地の天気を表示します", "Shows the weather where you are"), modifier = Modifier.padding(bottom = 6.dp))
        }
        WidgetActionButton(text = tr("位置情報を許可", "Allow location"), onClick = weather.requestAccess)
    } else {
        SecondaryText(if (weather.failed) tr("天気を取得できません", "Couldn't get the weather") else tr("取得中…", "Loading…"), size = 11.sp)
    }
}

@Composable
private fun WeatherWidget(now: LocalDateTime, footprint: WidgetFootprint, onWeather: () -> Unit) {
    val weather = rememberWeatherState()
    val report = weather.report
    WidgetFrame(
        widget = HomeWidget.WEATHER,
        title = tr("天気", "Weather"),
        icon = report?.forecast?.kind?.icon() ?: Icons.Filled.WbSunny,
        trailing = report?.placeName,
        onClick = weatherTileClick(weather, onWeather),
    ) {
        if (!weather.hasAccess || report == null) {
            WeatherUnavailable(weather, footprint.compact)
            return@WidgetFrame
        }
        val forecast = report.forecast
        val days = report.dailyFrom(now.toLocalDate())
        val today = days.firstOrNull()
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val height = maxHeight
            if (footprint.compact) {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(forecast.kind.icon(), contentDescription = null, tint = FiiLDAInk, modifier = Modifier.size(height * 0.5f))
                    HeroText("${forecast.temperatureC}°", (height * 0.62f).asSp(), modifier = Modifier.padding(start = 8.dp))
                    FillSpace()
                    Column(horizontalAlignment = Alignment.End) {
                        SecondaryText(forecast.kind.label, color = FiiLDAInk, size = 11.sp)
                        today?.let { SecondaryText("↑${it.maxC}° ↓${it.minC}°") }
                    }
                }
                return@BoxWithConstraints
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HeroText("${forecast.temperatureC}°", (height * 0.34f).asSp())
                        FillSpace()
                        Icon(forecast.kind.icon(), contentDescription = null, tint = FiiLDAInk, modifier = Modifier.size(height * 0.22f))
                    }
                    SecondaryText(forecast.kind.label, color = FiiLDAInk, size = 12.sp)
                    FillSpace()
                    today?.let { day ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SecondaryText("${day.minC}°", size = 9.sp)
                            RangeBar(
                                low = day.minC,
                                high = day.maxC,
                                min = day.minC,
                                max = day.maxC,
                                marker = forecast.temperatureC,
                                modifier = Modifier.weight(1f).padding(horizontal = 5.dp),
                            )
                            SecondaryText("${day.maxC}°", size = 9.sp)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        SecondaryText(tr("体感 ${forecast.apparentC}°", "Feels ${forecast.apparentC}°"), size = 9.sp)
                        FillSpace()
                        today?.precipitationPercent?.let { SecondaryText(tr("降水 $it%", "Rain $it%"), size = 9.sp) }
                    }
                }
                if (footprint.wide && days.size > 1) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        days.drop(1).take(4).forEach { day -> ForecastRow(day, now.toLocalDate(), days) }
                    }
                }
            }
        }
    }
}

// endregion

// region Forecast

@Composable
private fun ForecastRow(day: DailyWeather, today: LocalDate, week: List<DailyWeather>) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SecondaryText(forecastDayLabel(day.date, today), color = FiiLDAInk, modifier = Modifier.width(26.dp))
        Icon(day.kind.icon(), contentDescription = day.kind.label, tint = FiiLDAInk, modifier = Modifier.size(14.dp))
        SecondaryText(
            "${day.minC}°",
            size = 9.sp,
            align = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
        RangeBar(
            low = day.minC,
            high = day.maxC,
            min = week.minOf { it.minC },
            max = week.maxOf { it.maxC },
            modifier = Modifier.weight(1f).padding(horizontal = 5.dp),
        )
        SecondaryText("${day.maxC}°", color = FiiLDAInk, size = 9.sp, modifier = Modifier.width(24.dp))
    }
}

@Composable
private fun ForecastWidget(now: LocalDateTime, footprint: WidgetFootprint) {
    val weather = rememberWeatherState()
    val report = weather.report
    WidgetFrame(
        widget = HomeWidget.FORECAST,
        title = tr("週間天気予報", "Weekly forecast"),
        icon = Icons.Filled.CalendarMonth,
        trailing = report?.placeName,
        onClick = weatherTileClick(weather) {},
    ) {
        if (!weather.hasAccess || report == null) {
            WeatherUnavailable(weather, footprint.compact)
            return@WidgetFrame
        }
        val today = now.toLocalDate()
        val days = report.dailyFrom(today)
        if (days.isEmpty()) {
            SecondaryText(tr("予報がありません", "No forecast"))
            return@WidgetFrame
        }
        when {
            footprint.compact -> Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
                days.take(if (footprint.wide) 7 else 4).forEach { day ->
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        SecondaryText(forecastDayLabel(day.date, today), size = 9.sp)
                        Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(day.kind.icon(), contentDescription = day.kind.label, tint = FiiLDAInk, modifier = Modifier.size(14.dp))
                            SecondaryText("${day.maxC}°", color = FiiLDAInk, size = 10.sp, modifier = Modifier.padding(start = 2.dp))
                        }
                    }
                }
            }
            footprint.wide -> Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
                val low = days.minOf { it.minC }
                val high = days.maxOf { it.maxC }
                days.take(7).forEach { day -> ForecastColumn(day, today, low, high) }
            }
            else -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                days.take(5).forEach { day -> ForecastRow(day, today, days) }
            }
        }
    }
}

/** One day of the wide forecast: the vertical bar spans the day's range within the week. */
@Composable
private fun ForecastColumn(day: DailyWeather, today: LocalDate, weekLow: Int, weekHigh: Int) {
    val style = LocalWidgetStyle.current
    Column(modifier = Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        SecondaryText(forecastDayLabel(day.date, today), color = if (day.date == today) FiiLDAInk else FiiLDAMuted, size = 9.sp)
        Icon(day.kind.icon(), contentDescription = day.kind.label, tint = FiiLDAInk, modifier = Modifier.padding(vertical = 3.dp).size(16.dp))
        SecondaryText(day.precipitationPercent?.takeIf { it >= 30 }?.let { "$it%" } ?: " ", size = 8.sp)
        SecondaryText("${day.maxC}°", color = FiiLDAInk, size = 10.sp)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .width(5.dp)
                .padding(vertical = 3.dp)
                .clip(style.cellShape)
                .background(style.track),
        ) {
            val span = (weekHigh - weekLow).coerceAtLeast(1).toFloat()
            val top = (weekHigh - day.maxC) / span
            val bottom = (weekHigh - day.minC) / span
            Box(
                modifier = Modifier
                    .padding(top = maxHeight * top)
                    .height((maxHeight * (bottom - top)).coerceAtLeast(5.dp))
                    .fillMaxWidth()
                    .background(style.fill, style.cellShape),
            )
        }
        SecondaryText("${day.minC}°", size = 9.sp)
    }
}

// endregion

// region Agenda

@Composable
private fun AgendaWidget(now: LocalDateTime, footprint: WidgetFootprint) {
    val context = LocalContext.current
    val agenda = rememberAgendaState(now)
    val zone = ZoneId.systemDefault()
    WidgetFrame(
        widget = HomeWidget.AGENDA,
        title = tr("今日の予定", "Today"),
        icon = Icons.AutoMirrored.Filled.EventNote,
        trailing = tr("＋", "+"),
        onTrailing = { insertCalendarEvent(context) },
    ) {
        if (!agenda.hasAccess) {
            if (!footprint.compact) SecondaryText(tr("カレンダーの予定を表示します", "Shows your calendar events"), modifier = Modifier.padding(bottom = 6.dp))
            WidgetActionButton(text = tr("カレンダーへのアクセスを許可", "Allow calendar access"), onClick = agenda.requestAccess)
            return@WidgetFrame
        }
        val entries = agenda.entries
        if (entries.isEmpty()) {
            SecondaryText(if (agenda.loaded) tr("今日の予定はありません", "No events today") else tr("読み込み中…", "Loading…"), size = 11.sp)
            return@WidgetFrame
        }
        val shown = when {
            footprint.compact -> if (footprint.wide) 2 else 1
            else -> 3
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (footprint.wide && !footprint.compact) {
                Column(modifier = Modifier.weight(0.34f).fillMaxHeight().padding(end = 12.dp)) {
                    HeroText("${now.dayOfMonth}", 40.sp)
                    SecondaryText(weekdayLabel(now.toLocalDate()), color = FiiLDAInk, size = 11.sp)
                    FillSpace()
                    SecondaryText(tr("予定 ${entries.size}件", "${entries.size} events"))
                }
            }
            if (footprint.compact && footprint.wide) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    entries.take(shown).forEach { entry ->
                        AgendaItem(entry, now, zone, emphasize = entry.active, modifier = Modifier.weight(1f)) {
                            openCalendarEvent(context, entry.event)
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    entries.take(shown).forEach { entry ->
                        AgendaItem(entry, now, zone, emphasize = entry.active) { openCalendarEvent(context, entry.event) }
                    }
                    val hidden = entries.size - shown
                    if (hidden > 0 && !footprint.compact) {
                        FillSpace()
                        TrailingText(tr("ほか${hidden}件 ›", "${hidden} more ›")) { openCalendarDay(context, now) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgendaItem(
    entry: AgendaEntry,
    now: LocalDateTime,
    zone: ZoneId,
    emphasize: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val style = LocalWidgetStyle.current
    val marker = when {
        style.language == WidgetLanguage.METRO -> FiiLDAInk
        else -> entry.event.colorArgb?.let { Color(it) } ?: style.fill
    }
    val countdown = agendaCountdownLabel(entry.event, now, zone)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(if (style.language == WidgetLanguage.METRO) RectangleShape else RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val markerShape = if (style.language == WidgetLanguage.METRO) RectangleShape else RoundedCornerShape(2.dp)
        Box(modifier = Modifier.width(3.dp).height(26.dp).background(marker, markerShape))
        Column(modifier = Modifier.weight(1f).padding(start = 7.dp)) {
            Text(
                text = entry.event.title,
                color = if (emphasize) FiiLDAInk else FiiLDAMuted,
                fontSize = 11.sp,
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOf(entry.timeLabel, entry.event.location).filter { it.isNotBlank() }.joinToString(" · ")
            SecondaryText(detail, size = 9.sp)
        }
        if (emphasize && countdown != null) {
            Box(modifier = Modifier.padding(start = 4.dp)) { StatusChip(countdown, emphasized = countdown == tr("進行中", "Now")) }
        }
    }
}

// endregion

// region Calendar

/** Sunday-first weeks covering [month], including the neighbouring months' days. */
internal fun calendarMonthCells(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value % 7
    val rows = ceil((offset + month.lengthOfMonth()) / 7.0).toInt()
    val start = first.minusDays(offset.toLong())
    return List(rows * 7) { start.plusDays(it.toLong()) }
}

/** Sunday-first column headers in the current UI language. */
private fun weekdayHeaders(): List<String> =
    listOf(java.time.DayOfWeek.SUNDAY) .plus(java.time.DayOfWeek.entries.take(6)).map(::weekdayInitial)

@Composable
private fun CalendarWidget(now: LocalDateTime, footprint: WidgetFootprint, onCalendar: () -> Unit) {
    val context = LocalContext.current
    val today = now.toLocalDate()
    val month = YearMonth.from(today)
    WidgetFrame(
        widget = HomeWidget.CALENDAR,
        title = tr("${month.year}年${month.monthValue}月", "${englishMonthYear(month)}"),
        icon = Icons.Filled.CalendarMonth,
        trailing = if (footprint.compact || !isJapaneseUi()) null else "令和${reiwaYear(month.year)}年",
        onClick = {
            onCalendar()
            openCalendarDay(context, now)
        },
    ) {
        when {
            footprint.compact -> {
                val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong())
                CalendarWeekRow(
                    days = List(7) { sunday.plusDays(it.toLong()) },
                    today = today,
                    month = month,
                    showWeekday = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            footprint.wide -> Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(0.36f).fillMaxHeight().padding(end = 12.dp)) {
                    HeroText("${today.dayOfMonth}", 52.sp)
                    SecondaryText(weekdayLabel(today), color = FiiLDAInk, size = 12.sp)
                    FillSpace()
                    SecondaryText(tr("${today.dayOfYear}日目 / ${today.lengthOfYear()}日", "Day ${today.dayOfYear} of ${today.lengthOfYear()}"), size = 9.sp)
                }
                CalendarMonthGrid(month, today, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            else -> CalendarMonthGrid(month, today, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CalendarMonthGrid(month: YearMonth, today: LocalDate, modifier: Modifier) {
    val cells = remember(month) { calendarMonthCells(month) }
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayHeaders().forEachIndexed { index, label ->
                WeekdayLabel(label, index, modifier = Modifier.weight(1f))
            }
        }
        cells.chunked(7).forEach { week ->
            CalendarWeekRow(
                days = week,
                today = today,
                month = month,
                showWeekday = false,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun WeekdayLabel(label: String, index: Int, modifier: Modifier) {
    val style = LocalWidgetStyle.current
    Text(
        text = label,
        color = if (index == 0) style.weekend else FiiLDAQuiet,
        fontSize = 8.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun CalendarWeekRow(
    days: List<LocalDate>,
    today: LocalDate,
    month: YearMonth,
    showWeekday: Boolean,
    modifier: Modifier,
) {
    val style = LocalWidgetStyle.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        days.forEachIndexed { index, date ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showWeekday) WeekdayLabel(weekdayHeaders()[index], index, Modifier)
                val isToday = date == today
                val inMonth = YearMonth.from(date) == month
                Box(
                    modifier = Modifier
                        .size(if (showWeekday) 26.dp else 20.dp)
                        .then(if (isToday) Modifier.background(style.current, style.markerShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = date.dayOfMonth.toString(),
                        color = when {
                            isToday -> style.onCurrent
                            !inMonth -> FiiLDAQuiet.copy(alpha = 0.5f)
                            index == 0 && style.language == WidgetLanguage.NISHIKIGOI -> style.weekend
                            else -> FiiLDAInk
                        },
                        fontSize = if (showWeekday) 11.sp else 9.sp,
                        fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// endregion

// region Battery

@Composable
private fun BatteryWidget(battery: BatteryStatus, footprint: WidgetFootprint) {
    val temperature = battery.temperatureC?.let { String.format(java.util.Locale.US, "%.1f℃", it) }
    val status = batteryStatusLabel(battery)
    WidgetFrame(
        widget = HomeWidget.BATTERY,
        title = tr("バッテリー", "Battery"),
        icon = if (battery.charging) Icons.Filled.Bolt else Icons.Filled.BatteryFull,
        trailing = temperature,
    ) {
        val cells = 10
        val filled = (battery.percent + 9) / 10
        // While charging, the cell being filled is shown as the current state.
        val highlight = if (battery.charging && battery.percent < 100) (filled - 1).coerceAtLeast(0) else null
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val height = maxHeight
            if (footprint.compact) {
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    HeroText("${battery.percent}%", (height * 0.55f).asSp())
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        SegmentMeter(cells, filled, highlight = highlight, height = 10.dp, modifier = Modifier.fillMaxWidth())
                        SecondaryText(status, size = 9.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                return@BoxWithConstraints
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HeroText("${battery.percent}", (height * 0.36f).asSp())
                        Text(text = "%", color = FiiLDAMuted, fontSize = (height * 0.14f).asSp(), modifier = Modifier.padding(start = 2.dp))
                        if (battery.charging) {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = tr("充電中", "Charging"),
                                tint = LocalWidgetStyle.current.current,
                                modifier = Modifier.padding(start = 4.dp).size(height * 0.14f),
                            )
                        }
                    }
                    FillSpace()
                    SegmentMeter(cells, filled, highlight = highlight, height = 16.dp, modifier = Modifier.fillMaxWidth())
                    SecondaryText(status, color = FiiLDAInk, size = 10.sp, modifier = Modifier.padding(top = 6.dp))
                    if (!footprint.wide) {
                        battery.healthLabel?.let { SecondaryText(tr("状態 $it", "Health $it"), size = 9.sp) }
                    }
                }
                if (footprint.wide) {
                    Column(
                        modifier = Modifier.weight(0.8f).fillMaxHeight().padding(start = 16.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        BatteryFact(tr("電源", "Power"), battery.source?.label ?: tr("未接続", "Unplugged"))
                        BatteryFact(tr("温度", "Temperature"), temperature ?: "—")
                        BatteryFact(tr("状態", "Health"), battery.healthLabel ?: "—")
                        battery.chargeTimeRemainingMillis?.let { BatteryFact(tr("満充電まで", "Full in"), durationLabel(it)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryFact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SecondaryText(label, size = 9.sp)
        FillSpace()
        SecondaryText(value, color = FiiLDAInk, size = 10.sp)
    }
}

// endregion

// region Reminder

@Composable
private fun ReminderWidget(footprint: WidgetFootprint) {
    WidgetFrame(widget = HomeWidget.REMINDER, title = tr("リマインダー", "Reminders"), icon = Icons.Filled.NotificationsNone) {
        val content: @Composable () -> Unit = {
            Icon(Icons.Filled.NotificationsNone, contentDescription = null, tint = FiiLDAQuiet, modifier = Modifier.size(20.dp))
            SecondaryText(tr("リマインダーはありません", "No reminders"), size = 11.sp, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
        }
        if (footprint.compact) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) { content() }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        }
    }
}

// endregion

// region Media controls

/** Lets tiles rendered outside [BuiltInWidgetTile] (the media tile) speak the same language. */
@Composable
internal fun ProvideWidgetStyle(
    widget: HomeWidget,
    metroTileColor: Color? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWidgetStyle provides widgetStyleFor(widget, metroTileColor), content = content)
}

private const val ArtworkColorSampleSize = 32
private const val MetroTileColorFadeMillis = 900

/**
 * Darkens [argb] until the Windows 8 theme's white ink stays readable. Its muted/quiet roles are
 * near-white too, so the target is a little above AA for normal text.
 */
internal fun readableMetroTileColor(argb: Int, minimumContrast: Float = 5f): Int {
    var color = argb
    repeat(24) {
        if (contrastRatioArgb(color, 0xFFFFFFFF.toInt()) >= minimumContrast) return color
        val red = ((color ushr 16 and 0xFF) * 0.92f).toInt()
        val green = ((color ushr 8 and 0xFF) * 0.92f).toInt()
        val blue = ((color and 0xFF) * 0.92f).toInt()
        color = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return color
}

/** The artwork's dominant vivid hue, chosen exactly like an app tile's icon color. */
private fun artworkMetroTileColor(art: Bitmap, identity: String): Int? = runCatching {
    val software = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
    val sample = Bitmap.createScaledBitmap(software, ArtworkColorSampleSize, ArtworkColorSampleSize, true)
    val pixels = IntArray(ArtworkColorSampleSize * ArtworkColorSampleSize)
    sample.getPixels(pixels, 0, ArtworkColorSampleSize, 0, 0, ArtworkColorSampleSize, ArtworkColorSampleSize)
    if (sample !== software) sample.recycle()
    if (software !== art) software.recycle()
    readableMetroTileColor(selectIconTileColorFromPixels(pixels, "media", identity))
}.getOrNull()

/**
 * Windows 8 only: the media tile takes its color from the current artwork and fades between
 * tracks. Without artwork it fades back to the fixed media tile color. Other themes return null.
 */
@Composable
internal fun rememberMetroMediaTileColor(snapshot: MediaSnapshot): Color? {
    if (LocalLauncherTheme.current != LauncherTheme.WINDOWS_8) return null
    val fallback = windows8BuiltInTileColor(HomeWidget.MEDIA.id)
    val art = snapshot.albumArt
    // Keyed on the artwork identity: providers resend equal bitmaps on every metadata callback.
    val sampled by produceState<Color?>(initialValue = null, snapshot.artworkKey, art != null) {
        value = art?.let {
            withContext(Dispatchers.Default) {
                artworkMetroTileColor(it, snapshot.artworkKey ?: snapshot.title)
            }?.let { argb -> Color(argb) }
        }
    }
    val target = if (art == null) fallback else sampled ?: fallback
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = MetroTileColorFadeMillis),
        label = "metroMediaTileColor",
    )
    return animated
}

@Composable
private fun mediaScrimColor(): Color {
    val style = LocalWidgetStyle.current
    return when (style.language) {
        WidgetLanguage.METRO -> style.onCurrent
        WidgetLanguage.GLASS -> Color.Black
        else -> LocalLauncherPalette.current.background
    }
}

/**
 * Keeps text and controls legible over arbitrary artwork while leaving the centre of the art
 * untouched. Metro avoids gradients, so it draws nothing here and uses [mediaArtworkBand] instead.
 */
@Composable
internal fun MediaArtworkScrim(modifier: Modifier = Modifier) {
    if (LocalWidgetStyle.current.language == WidgetLanguage.METRO) return
    val base = mediaScrimColor()
    Box(
        modifier = modifier.background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to base.copy(alpha = 0.82f),
                0.24f to base.copy(alpha = 0.55f),
                0.44f to base.copy(alpha = 0f),
                0.56f to base.copy(alpha = 0f),
                1f to base.copy(alpha = 0.88f),
            ),
        ),
    )
}

/** The media tile's content inset; the band's corners are concentric with the tile's. */
private val MediaContentInset = 10.dp
private val MediaBandShape = RoundedCornerShape(LauncherTileCornerRadius - MediaContentInset)

/**
 * Metro's flat band behind the title or controls only, so the art between them stays clear. It is
 * kept light and follows the tile's corner radius so it reads as part of the tile, not a label.
 */
@Composable
internal fun Modifier.mediaArtworkBand(hasArtwork: Boolean): Modifier =
    if (hasArtwork && LocalWidgetStyle.current.language == WidgetLanguage.METRO) {
        clip(MediaBandShape).background(mediaScrimColor().copy(alpha = 0.66f)).padding(horizontal = 8.dp, vertical = 6.dp)
    } else {
        this
    }

/** A soft halo in the scrim color so title text survives very bright or busy artwork. */
@Composable
internal fun mediaTextStyle(hasArtwork: Boolean): TextStyle =
    if (hasArtwork && LocalWidgetStyle.current.language != WidgetLanguage.METRO) {
        TextStyle(shadow = androidx.compose.ui.graphics.Shadow(color = mediaScrimColor().copy(alpha = 0.9f), blurRadius = 10f))
    } else {
        TextStyle.Default
    }

/** Previous / play-pause / next in the active theme. [showPrevious] is false on the narrowest strip. */
@Composable
internal fun MediaTransportRow(
    state: MediaSessionState,
    height: Dp,
    modifier: Modifier = Modifier,
    showPrevious: Boolean = true,
) {
    val style = LocalWidgetStyle.current
    val controller = state.controller
    val enabled = controller != null
    val playing = state.snapshot.isPlaying
    val filledKeys = style.language == WidgetLanguage.KEYBOARD || style.language == WidgetLanguage.NISHIKIGOI
    Row(
        modifier = modifier,
        horizontalArrangement = if (filledKeys) Arrangement.spacedBy(5.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPrevious) {
            MediaTransportButton(
                icon = Icons.Filled.SkipPrevious,
                description = tr("前の曲", "Previous"),
                primary = false,
                enabled = enabled,
                height = height,
                modifier = if (filledKeys) Modifier.weight(1f) else Modifier,
            ) { controller?.let { runCatching { it.transportControls.skipToPrevious() } } }
        }
        MediaTransportButton(
            icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            description = if (playing) tr("一時停止", "Pause") else tr("再生", "Play"),
            primary = true,
            enabled = enabled,
            height = height,
            modifier = if (filledKeys) Modifier.weight(1.4f) else Modifier,
        ) {
            controller?.let {
                runCatching { if (playing) it.transportControls.pause() else it.transportControls.play() }
            }
        }
        MediaTransportButton(
            icon = Icons.Filled.SkipNext,
            description = tr("次の曲", "Next"),
            primary = false,
            enabled = enabled,
            height = height,
            modifier = if (filledKeys) Modifier.weight(1f) else Modifier,
        ) { controller?.let { runCatching { it.transportControls.skipToNext() } } }
    }
}

@Composable
private fun MediaTransportButton(
    icon: ImageVector,
    description: String,
    primary: Boolean,
    enabled: Boolean,
    height: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val style = LocalWidgetStyle.current
    val palette = LocalLauncherPalette.current
    val shape: Shape = when (style.language) {
        WidgetLanguage.KEYBOARD -> RoundedCornerShape(8.dp)
        WidgetLanguage.NISHIKIGOI -> RoundedCornerShape(4.dp)
        WidgetLanguage.MATERIAL -> if (primary) RoundedCornerShape(18.dp) else CircleShape
        WidgetLanguage.METRO, WidgetLanguage.GLASS -> CircleShape
    }
    val background: Color
    val content: Color
    var border: Pair<Dp, Color>? = null
    when (style.language) {
        WidgetLanguage.KEYBOARD -> if (primary) {
            background = palette.accent; content = palette.accentOn
        } else {
            background = palette.enabledSurface.copy(alpha = 0.94f); content = palette.ink
            border = 1.dp to palette.lineStrong
        }
        WidgetLanguage.NISHIKIGOI -> if (primary) {
            background = NISHIKIGOI_RED; content = Color.White
        } else {
            background = NISHIKIGOI_BLUE; content = palette.ink
        }
        WidgetLanguage.METRO -> if (primary) {
            background = palette.ink; content = style.onCurrent
        } else {
            background = Color.Transparent; content = palette.ink
            border = 2.dp to palette.ink
        }
        WidgetLanguage.MATERIAL -> if (primary) {
            background = palette.accent; content = palette.accentOn
        } else {
            background = palette.accentSurface.copy(alpha = 0.96f); content = palette.ink
        }
        WidgetLanguage.GLASS -> if (primary) {
            background = Color.White; content = Color.Black
        } else {
            background = Color.White.copy(alpha = 0.22f); content = Color.White
            border = 1.dp to Color.White.copy(alpha = 0.5f)
        }
    }
    val sized = when {
        style.language == WidgetLanguage.KEYBOARD || style.language == WidgetLanguage.NISHIKIGOI -> modifier.height(height)
        style.language == WidgetLanguage.MATERIAL && primary -> modifier.size(width = height * 1.5f, height = height)
        primary -> modifier.size(height)
        else -> modifier.size(height * 0.84f)
    }
    Box(
        modifier = sized
            .clip(shape)
            .background(background.copy(alpha = if (enabled) background.alpha else background.alpha * 0.45f))
            .then(border?.let { (width, color) -> Modifier.androidxBorder(width, color, shape) } ?: Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) content else content.copy(alpha = 0.45f),
            modifier = Modifier.size(height * if (primary) 0.52f else 0.46f),
        )
    }
}

// Deliberately not launcherBorder, which drops borders on borderless palettes: Metro's outlined
// transport buttons are part of that language, not a tile outline.
private fun Modifier.androidxBorder(width: Dp, color: Color, shape: Shape): Modifier = border(width, color, shape)

// endregion
