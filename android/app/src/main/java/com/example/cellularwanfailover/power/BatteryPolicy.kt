package com.example.cellularwanfailover.power

/**
 * The phone's battery limits for carrying an outage, sent to its gateways (see PROTOCOL.md).
 * Below [minBattery] % on battery, gateways use another phone if they have one; with
 * [lastResort], this phone still carries an outage when no other phone can.
 */
data class BatterySettings(val minBattery: Int = DEFAULT_MIN_BATTERY, val lastResort: Boolean = true) {
    companion object {
        const val DEFAULT_MIN_BATTERY = 20
        /** Choices offered in the app, in % */
        const val MAX_MIN_BATTERY = 50
        const val MIN_BATTERY_STEP = 5
    }
}

object BatteryPolicy {
    fun isLow(level: Int, plugged: Boolean, settings: BatterySettings): Boolean =
        !plugged && level < settings.minBattery

    /** Whether this phone accepts to start carrying an outage now. */
    fun mayCarry(level: Int, plugged: Boolean, settings: BatterySettings): Boolean =
        settings.lastResort || !isLow(level, plugged, settings)
}
