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
 * Whether the phone is plugged in. "Plugged", not "charging": with a charge limit (e.g. Pixel's
 * 80%) or adaptive charging, a phone kept on its charger mostly reports "not charging".
 */
class PowerMonitor(private val context: Context) {

    private val _pluggedIn = MutableStateFlow(false)
    val pluggedIn: StateFlow<Boolean> = _pluggedIn.asStateFlow()

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
    }
}
