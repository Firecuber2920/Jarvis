package com.jarvis.huddash.status

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LocationAccess {
    fun isGranted(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}

data class WeatherReading(
    val temperatureFahrenheit: Int,
    val condition: String,
    val fetchedAtElapsedRealtimeMillis: Long,
)

object WeatherState {
    @Volatile
    var current: WeatherReading? = null
        internal set
}

/**
 * Free, no-API-key weather lookup (Open-Meteo) — matches this project's existing
 * preference for on-device/no-key data sources over ones requiring registration
 * (CalendarContract over the Google Calendar API). Uses the phone's last-known
 * location rather than requesting a fresh fix: a background HUD status panel
 * doesn't need continuous location tracking, and last-known is usually good
 * enough for "what's the weather roughly here."
 */
class WeatherFetcher(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val fetchInProgress = AtomicBoolean(false)

    fun fetchIfStale() {
        val reading = WeatherState.current
        val age = if (reading != null) {
            SystemClock.elapsedRealtime() - reading.fetchedAtElapsedRealtimeMillis
        } else {
            Long.MAX_VALUE
        }
        if (age < STALE_AFTER_MILLIS) return
        if (!LocationAccess.isGranted(context)) return
        if (!fetchInProgress.compareAndSet(false, true)) return

        executor.execute {
            try {
                val location = lastKnownLocation()
                if (location != null) {
                    fetchWeather(location.first, location.second)?.let { WeatherState.current = it }
                }
            } catch (e: Exception) {
                // Best-effort — a failed fetch just leaves the last reading (or null)
                // in place; the next stale-check retries on its own.
            } finally {
                fetchInProgress.set(false)
            }
        }
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            try {
                val location = manager.getLastKnownLocation(provider) ?: continue
                return location.latitude to location.longitude
            } catch (e: SecurityException) {
                return null
            } catch (e: IllegalArgumentException) {
                continue // provider not available on this device
            }
        }
        return null
    }

    private fun fetchWeather(latitude: Double, longitude: Double): WeatherReading? {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,weather_code&temperature_unit=fahrenheit",
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        return try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            WeatherReading(
                temperatureFahrenheit = current.getDouble("temperature_2m").toInt(),
                condition = describeWeatherCode(current.getInt("weather_code")),
                fetchedAtElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** WMO weather codes, per Open-Meteo's docs — collapsed to the handful of labels a small HUD badge has room for. */
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2, 3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Storm"
        else -> "—"
    }

    companion object {
        private const val STALE_AFTER_MILLIS = 20 * 60 * 1000L // 20 minutes
        private const val CONNECT_TIMEOUT_MILLIS = 8_000
        private const val READ_TIMEOUT_MILLIS = 8_000
    }
}
