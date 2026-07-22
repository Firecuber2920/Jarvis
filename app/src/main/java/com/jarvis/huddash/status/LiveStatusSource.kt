package com.jarvis.huddash.status

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.jarvis.huddash.panel.StatusSnapshot
import com.jarvis.huddash.panel.StatusSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time and battery need no permission and are always real — only weather depends
 * on [LocationAccess] and a background fetch, and degrades to null (rendered as
 * "weather unavailable" by [com.jarvis.huddash.panel.StatusPanelProvider]) rather
 * than losing the rest of the panel. [WeatherFetcher.fetchIfStale] is cheap to call
 * on every read — it only performs a network call once the cached reading is
 * actually stale.
 */
class LiveStatusSource(private val context: Context) : StatusSource {
    private val weatherFetcher = WeatherFetcher(context)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun currentStatus(): StatusSnapshot {
        weatherFetcher.fetchIfStale()
        val reading = WeatherState.current
        val battery = batteryStatus()

        return StatusSnapshot(
            timeText = timeFormat.format(Date()),
            weatherText = reading?.let { "${it.temperatureFahrenheit}°F ${it.condition}" },
            batteryPercent = battery?.first,
            isCharging = battery?.second ?: false,
        )
    }

    private fun batteryStatus(): Pair<Int, Boolean>? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return (level * 100 / scale) to isCharging
    }
}
