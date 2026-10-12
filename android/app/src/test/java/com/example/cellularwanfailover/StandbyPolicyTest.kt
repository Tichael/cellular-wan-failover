package com.example.cellularwanfailover

import com.example.cellularwanfailover.power.StandbyMode
import com.example.cellularwanfailover.power.StandbyPolicy
import com.example.cellularwanfailover.power.StandbyPolicy.UNPLUG_GRACE_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyPolicyTest {

    @Test
    fun onDemandIsNeverHot() {
        assertFalse(StandbyPolicy.isHot(StandbyMode.ON_DEMAND, null))
        assertFalse(StandbyPolicy.isHot(StandbyMode.ON_DEMAND, UNPLUG_GRACE_MILLIS * 10))
    }

    @Test
    fun alwaysIsHotOnBattery() {
        assertTrue(StandbyPolicy.isHot(StandbyMode.ALWAYS, null))
        assertTrue(StandbyPolicy.isHot(StandbyMode.ALWAYS, UNPLUG_GRACE_MILLIS * 10))
        assertNull(StandbyPolicy.millisUntilChange(StandbyMode.ALWAYS, 0))
    }

    @Test
    fun whilePluggedIsHotWhenPluggedIn() {
        assertTrue(StandbyPolicy.isHot(StandbyMode.WHILE_PLUGGED, null))
        assertNull(StandbyPolicy.millisUntilChange(StandbyMode.WHILE_PLUGGED, null))
    }

    @Test
    fun whilePluggedStaysHotDuringUnplugGrace() {
        assertTrue(StandbyPolicy.isHot(StandbyMode.WHILE_PLUGGED, 0))
        assertTrue(StandbyPolicy.isHot(StandbyMode.WHILE_PLUGGED, UNPLUG_GRACE_MILLIS - 1))
        assertEquals(1_000L, StandbyPolicy.millisUntilChange(StandbyMode.WHILE_PLUGGED, UNPLUG_GRACE_MILLIS - 1_000))
    }

    @Test
    fun whilePluggedTurnsColdAfterGrace() {
        assertFalse(StandbyPolicy.isHot(StandbyMode.WHILE_PLUGGED, UNPLUG_GRACE_MILLIS))
        assertNull(StandbyPolicy.millisUntilChange(StandbyMode.WHILE_PLUGGED, UNPLUG_GRACE_MILLIS))
    }

    @Test
    fun unknownStoredModeFallsBackToOnDemand() {
        assertEquals(StandbyMode.ON_DEMAND, StandbyMode.fromStorage(null))
        assertEquals(StandbyMode.ON_DEMAND, StandbyMode.fromStorage("bogus"))
        assertEquals(StandbyMode.WHILE_PLUGGED, StandbyMode.fromStorage("while_plugged"))
    }
}
