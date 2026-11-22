package com.example.cellularwanfailover

import com.example.cellularwanfailover.power.PowerMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerMonitorTest {

    @Test
    fun batteryPercentFromLevelAndScale() {
        assertEquals(85, PowerMonitor.batteryPercent(85, 100))
        assertEquals(50, PowerMonitor.batteryPercent(128, 256))
        assertEquals(100, PowerMonitor.batteryPercent(100, 100))
        assertEquals(0, PowerMonitor.batteryPercent(0, 100))
    }

    @Test
    fun batteryPercentIsAlwaysAPercentage() {
        assertEquals(100, PowerMonitor.batteryPercent(120, 100))
    }

    @Test
    fun missingBatteryExtrasAreUnknown() {
        assertNull(PowerMonitor.batteryPercent(-1, 100))
        assertNull(PowerMonitor.batteryPercent(50, -1))
        assertNull(PowerMonitor.batteryPercent(50, 0))
    }
}
