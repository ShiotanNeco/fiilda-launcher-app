package com.fiilda.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal enum class WeatherKind(private val jaLabel: String, private val enLabel: String) {
    CLEAR("快晴", "Clear"),
    MOSTLY_CLEAR("晴れ", "Mostly clear"),
    PARTLY_CLOUDY("晴れ時々くもり", "Partly cloudy"),
    CLOUDY("くもり", "Cloudy"),
    FOG("霧", "Fog"),
    DRIZZLE("霧雨", "Drizzle"),
    RAIN("雨", "Rain"),
    SHOWERS("にわか雨", "Showers"),
    SNOW("雪", "Snow"),
    THUNDER("雷雨", "Thunderstorm"),
    UNKNOWN("不明", "Unknown"),
    ;

    val label: String get() = tr(jaLabel, enLabel)
}

/** Maps a WMO weather interpretation code as returned by Open-Meteo. */
internal fun weatherKindForCode(code: Int): WeatherKind = when (code) {
    0 -> WeatherKind.CLEAR
    1 -> WeatherKind.MOSTLY_CLEAR
    2 -> WeatherKind.PARTLY_CLOUDY
    3 -> WeatherKind.CLOUDY
    45, 48 -> WeatherKind.FOG
    in 51..57 -> WeatherKind.DRIZZLE
    in 61..67 -> WeatherKind.RAIN
    in 80..82 -> WeatherKind.SHOWERS
    in 71..77, 85, 86 -> WeatherKind.SNOW
    in 95..99 -> WeatherKind.THUNDER
    else -> WeatherKind.UNKNOWN
}

internal fun WeatherKind.icon(): ImageVector = when (this) {
    WeatherKind.CLEAR, WeatherKind.MOSTLY_CLEAR -> Icons.Filled.WbSunny
    WeatherKind.PARTLY_CLOUDY -> Icons.Filled.WbCloudy
    WeatherKind.CLOUDY, WeatherKind.UNKNOWN -> Icons.Filled.Cloud
    WeatherKind.FOG -> Icons.Filled.Dehaze
    WeatherKind.DRIZZLE, WeatherKind.RAIN, WeatherKind.SHOWERS -> Icons.Filled.WaterDrop
    WeatherKind.SNOW -> Icons.Filled.AcUnit
    WeatherKind.THUNDER -> Icons.Filled.Thunderstorm
}

internal data class DailyWeather(
    val date: LocalDate,
    val kind: WeatherKind,
    val maxC: Int,
    val minC: Int,
    /** Daily maximum precipitation probability in percent, when the source provides it. */
    val precipitationPercent: Int? = null,
)

internal data class WeatherForecast(
    val temperatureC: Int,
    val apparentC: Int,
    val kind: WeatherKind,
    val daily: List<DailyWeather>,
)

internal data class WeatherReport(
    val forecast: WeatherForecast,
    val placeName: String?,
    val fetchedAtMillis: Long,
) {
    /** Cached days before [today] are dropped so an offline launcher never labels yesterday as today. */
    fun dailyFrom(today: LocalDate): List<DailyWeather> = forecast.daily.filter { !it.date.isBefore(today) }
}

internal data class WeatherUiState(
    val hasAccess: Boolean,
    val report: WeatherReport?,
    val failed: Boolean,
    val requestAccess: () -> Unit,
    val refresh: () -> Unit,
)

internal fun openMeteoForecastUrl(latitude: Double, longitude: Double): String =
    String.format(
        Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7",
        latitude,
        longitude,
    )

internal fun parseOpenMeteoForecast(body: String): WeatherForecast? = runCatching {
    val root = JSONObject(body)
    val current = root.getJSONObject("current")
    val daily = root.getJSONObject("daily")
    val dates = daily.getJSONArray("time")
    val codes = daily.getJSONArray("weather_code")
    val highs = daily.getJSONArray("temperature_2m_max")
    val lows = daily.getJSONArray("temperature_2m_min")
    val precipitation = daily.optJSONArray("precipitation_probability_max")
    WeatherForecast(
        temperatureC = current.getDouble("temperature_2m").roundToInt(),
        apparentC = current.getDouble("apparent_temperature").roundToInt(),
        kind = weatherKindForCode(current.getInt("weather_code")),
        daily = (0 until dates.length()).map { index ->
            DailyWeather(
                date = LocalDate.parse(dates.getString(index)),
                kind = weatherKindForCode(codes.getInt(index)),
                maxC = highs.getDouble(index).roundToInt(),
                minC = lows.getDouble(index).roundToInt(),
                precipitationPercent = precipitation
                    ?.takeUnless { it.isNull(index) }
                    ?.optDouble(index)
                    ?.takeUnless { it.isNaN() }
                    ?.roundToInt(),
            )
        },
    )
}.getOrNull()

/**
 * Process-wide weather source shared by the weather and forecast tiles, so several visible tiles
 * cause at most one network request per refresh window.
 */
internal object LauncherWeatherRepository {
    private const val PrefsName = "fiilda_weather"
    private const val KeyBody = "body"
    private const val KeyFetchedAt = "fetched_at"
    private const val KeyLatitude = "latitude"
    private const val KeyLongitude = "longitude"
    private const val KeyPlace = "place"
    private const val StaleAfterMillis = 30 * 60 * 1000L
    private const val LastKnownMaxAgeMillis = 3 * 60 * 60 * 1000L
    private const val PlaceReuseMeters = 5_000f

    private val mutableReport = MutableStateFlow<WeatherReport?>(null)
    private val mutableFailed = MutableStateFlow(false)
    val report: StateFlow<WeatherReport?> = mutableReport
    val failed: StateFlow<Boolean> = mutableFailed
    private val mutex = Mutex()
    private var cacheLoaded = false

    suspend fun refresh(context: Context, force: Boolean = false) {
        mutex.withLock { withContext(Dispatchers.IO) { refreshLocked(context.applicationContext, force) } }
    }

    private suspend fun refreshLocked(context: Context, force: Boolean) {
        val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        if (!cacheLoaded) {
            cacheLoaded = true
            val body = prefs.getString(KeyBody, null)
            val forecast = body?.let(::parseOpenMeteoForecast)
            if (forecast != null) {
                mutableReport.value = WeatherReport(
                    forecast = forecast,
                    placeName = prefs.getString(KeyPlace, null),
                    fetchedAtMillis = prefs.getLong(KeyFetchedAt, 0L),
                )
            }
        }
        val cached = mutableReport.value
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAtMillis < StaleAfterMillis) return
        if (!context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) return

        val location = currentCoarseLocation(context)
        if (location == null) {
            mutableFailed.value = true
            return
        }
        // Two decimals (about 1 km) is enough for a forecast and avoids sending a precise fix.
        val latitude = (location.latitude * 100).roundToInt() / 100.0
        val longitude = (location.longitude * 100).roundToInt() / 100.0
        val body = fetchText(openMeteoForecastUrl(latitude, longitude))
        val forecast = body?.let(::parseOpenMeteoForecast)
        if (forecast == null) {
            mutableFailed.value = true
            return
        }
        val previousPlace = prefs.getString(KeyPlace, null)
        val distance = FloatArray(1)
        if (prefs.contains(KeyLatitude)) {
            Location.distanceBetween(
                prefs.getFloat(KeyLatitude, 0f).toDouble(),
                prefs.getFloat(KeyLongitude, 0f).toDouble(),
                latitude,
                longitude,
                distance,
            )
        }
        val place = previousPlace?.takeIf { prefs.contains(KeyLatitude) && distance[0] < PlaceReuseMeters }
            ?: placeName(context, latitude, longitude)
            ?: previousPlace
        val fetchedAt = System.currentTimeMillis()
        prefs.edit()
            .putString(KeyBody, body)
            .putLong(KeyFetchedAt, fetchedAt)
            .putFloat(KeyLatitude, latitude.toFloat())
            .putFloat(KeyLongitude, longitude.toFloat())
            .putString(KeyPlace, place)
            .apply()
        mutableReport.value = WeatherReport(forecast, place, fetchedAt)
        mutableFailed.value = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentCoarseLocation(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val lastKnown = manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < LastKnownMaxAgeMillis) {
            return lastKnown
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return lastKnown
        val provider = listOfNotNull(
            LocationManager.FUSED_PROVIDER.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
            LocationManager.NETWORK_PROVIDER,
        ).firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return lastKnown
        val fresh = withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
        return fresh ?: lastKnown
    }

    @Suppress("DEPRECATION")
    private fun placeName(context: Context, latitude: Double, longitude: Double): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(context, Locale.getDefault()).getFromLocation(latitude, longitude, 1)
            ?.firstOrNull()
            ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }.getOrNull()

    private fun fetchText(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

@Composable
internal fun rememberWeatherState(): WeatherUiState {
    val context = LocalContext.current.applicationContext
    val permission = rememberGlancePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    val report by LauncherWeatherRepository.report.collectAsState()
    val failed by LauncherWeatherRepository.failed.collectAsState()
    val scope = rememberCoroutineScope()
    // The repository ignores resumes inside its refresh window, so this stays cheap.
    LifecycleResumeEffect(permission.granted) {
        scope.launch { LauncherWeatherRepository.refresh(context) }
        onPauseOrDispose { }
    }
    return WeatherUiState(
        hasAccess = permission.granted,
        report = report,
        failed = failed,
        requestAccess = permission.request,
        refresh = { scope.launch { LauncherWeatherRepository.refresh(context, force = true) } },
    )
}

internal fun forecastDayLabel(date: LocalDate, today: LocalDate): String =
    if (date == today) tr("今日", "Today") else shortWeekday(date.dayOfWeek)
