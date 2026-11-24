package com.example.cellularwanfailover

import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.ui.Headline
import com.example.cellularwanfailover.ui.LogCategory
import com.example.cellularwanfailover.ui.PermissionStatus
import com.example.cellularwanfailover.ui.SetupIssue
import com.example.cellularwanfailover.ui.formatBytes
import com.example.cellularwanfailover.ui.headline
import com.example.cellularwanfailover.ui.logCategory
import com.example.cellularwanfailover.ui.setupIssues
import org.junit.Assert.assertEquals
import org.junit.Test

class UiModelsTest {

    private fun h(state: FailoverState, trusted: Boolean = true, paired: Boolean = true, hot: Boolean = false) =
        headline(state, trusted, paired, hot)

    @Test
    fun `no pairing is first run`() {
        assertEquals(Headline.NOT_SET_UP, h(FailoverState.IDLE, trusted = false, paired = false))
    }

    @Test
    fun `untrusted network pauses`() {
        assertEquals(Headline.PAUSED, h(FailoverState.IDLE, trusted = false))
    }

    @Test
    fun `idle on a paired network is protected`() {
        assertEquals(Headline.PROTECTED, h(FailoverState.IDLE))
        assertEquals(Headline.PROTECTED, h(FailoverState.IDLE, hot = true))
    }

    @Test
    fun `relay up in hot standby is not an outage`() {
        assertEquals(Headline.TUNNEL_READY, h(FailoverState.ACTIVE, hot = true))
        assertEquals(Headline.CARRYING, h(FailoverState.ACTIVE, hot = false))
    }

    @Test
    fun `relay states win over trust and pairing`() {
        assertEquals(Headline.STARTING, h(FailoverState.CONNECTING, trusted = false))
        assertEquals(Headline.ERROR, h(FailoverState.ERROR, paired = false))
    }

    private val allOk = PermissionStatus(
        location = true, locationServicesOn = true, backgroundLocation = true, notifications = true, batteryUnrestricted = true
    )

    @Test
    fun `no issues when everything is allowed`() {
        assertEquals(emptyList<SetupIssue>(), setupIssues(allOk))
        // Notifications don't keep the app from working
        assertEquals(emptyList<SetupIssue>(), setupIssues(allOk.copy(notifications = false)))
    }

    @Test
    fun `missing location hides the follow-up location issues`() {
        assertEquals(
            listOf(SetupIssue.LOCATION_PERMISSION, SetupIssue.BATTERY_OPTIMIZATION),
            setupIssues(allOk.copy(location = false, locationServicesOn = false, backgroundLocation = false, batteryUnrestricted = false))
        )
    }

    @Test
    fun `location services and background location are listed together`() {
        assertEquals(
            listOf(SetupIssue.LOCATION_SERVICES, SetupIssue.BACKGROUND_LOCATION),
            setupIssues(allOk.copy(locationServicesOn = false, backgroundLocation = false))
        )
    }

    @Test
    fun `log tags map to filters`() {
        assertEquals(LogCategory.FAILOVER, logCategory("FailoverController"))
        assertEquals(LogCategory.FAILOVER, logCategory("WireGuard"))
        assertEquals(LogCategory.CONTROL_API, logCategory("KtorServer"))
        assertEquals(LogCategory.CONTROL_API, logCategory("Discovery"))
        assertEquals(LogCategory.PAIRING, logCategory("Pairing"))
        assertEquals(LogCategory.POWER, logCategory("Standby"))
        assertEquals(LogCategory.POWER, logCategory("Battery"))
        assertEquals(LogCategory.OTHER, logCategory("Something"))
    }

    @Test
    fun `bytes are formatted with one decimal below 100`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.2 GB", formatBytes(1_288_490_189))
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
    }
}
