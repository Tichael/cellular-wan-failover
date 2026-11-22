package com.example.cellularwanfailover.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone is plugged in, and its battery level. "Plugged", not "charging": with a
 * charge limit (e.g. Pixel's 80%) or adaptive charging, a phone kept on its charger mostly
 * reports "not charging".
 */
class PowerMonitor(private val context: Context) {

    companion object {
        /** Battery state of charge in % (0-100), or null if the broadcast doesn't say. */
        fun batteryPercent(level: Int, scale: Int): Int? =
            if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
    }

    private val _pluggedIn = MutableStateFlow(false)
    val pluggedIn: StateFlow<Boolean> = _pluggedIn.asStateFlow()

    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun start() {
        if (receiver != null) return
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update(intent)
        }
        // Sticky: the current battery state comes back right away
        ContextCompat.registerReceiver(
            context, newReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )?.let(::update)
        receiver = newReceiver
    }

    @Synchronized
    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    private fun update(intent: Intent) {
        _pluggedIn.value = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        batteryPercent(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        )?.let { _batteryLevel.value = it }
    }
}
