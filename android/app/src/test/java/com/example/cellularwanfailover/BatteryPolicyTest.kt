package com.example.cellularwanfailover

import com.example.cellularwanfailover.power.BatteryPolicy
import com.example.cellularwanfailover.power.BatterySettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPolicyTest {

    private val strict = BatterySettings(minBattery = 20, lastResort = false)
    private val lastResort = BatterySettings(minBattery = 20, lastResort = true)

    @Test
    fun lowOnlyOnBatteryBelowTheMinimum() {
        assertTrue(BatteryPolicy.isLow(19, plugged = false, strict))
        assertFalse(BatteryPolicy.isLow(20, plugged = false, strict))
        assertFalse(BatteryPolicy.isLow(5, plugged = true, strict))
    }

    @Test
    fun strictPhoneRefusesBelowItsMinimum() {
        assertFalse(BatteryPolicy.mayCarry(19, plugged = false, strict))
        assertTrue(BatteryPolicy.mayCarry(20, plugged = false, strict))
        assertTrue(BatteryPolicy.mayCarry(5, plugged = true, strict))
    }

    @Test
    fun lastResortPhoneAlwaysAccepts() {
        assertTrue(BatteryPolicy.mayCarry(1, plugged = false, lastResort))
    }

    @Test
    fun noMinimum() {
        assertFalse(BatteryPolicy.isLow(0, plugged = false, BatterySettings(minBattery = 0, lastResort = false)))
    }
}
