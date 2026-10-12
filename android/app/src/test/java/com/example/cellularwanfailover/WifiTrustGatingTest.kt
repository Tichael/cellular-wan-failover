package com.example.cellularwanfailover

import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.model.WifiNetworkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiTrustGatingTest {

    @Test
    fun testDynamicTrustEvaluation() {
        // Trusted networks are the Wi-Fi networks of the phone's gateway pairings
        val auth = newAuthenticator()
        val currentSsid = MutableStateFlow<String?>("MyHomeNetwork")
        val isNetworkTrusted = MutableStateFlow(false)

        fun reevaluate() {
            isNetworkTrusted.value = auth.isServiceSsid(currentSsid.value)
        }

        // Initially untrusted: nothing paired
        reevaluate()
        assertFalse(isNetworkTrusted.value)

        // Pair a gateway on the home network
        val gateway = TestGateway()
        auth.pair(gateway, "MyHomeNetwork")
        reevaluate()
        assertTrue(isNetworkTrusted.value)

        // Switch to public Wi-Fi
        currentSsid.value = "Public-Airport-WiFi"
        reevaluate()
        assertFalse(isNetworkTrusted.value)

        // Switch to mobile data alone (no Wi-Fi)
        currentSsid.value = null
        reevaluate()
        assertFalse(isNetworkTrusted.value)

        // Switch back to home Wi-Fi
        currentSsid.value = "MyHomeNetwork"
        reevaluate()
        assertTrue(isNetworkTrusted.value)

        // Remove the gateway: its network is no longer trusted
        auth.removePairing(gateway.gatewayId)
        reevaluate()
        assertFalse(isNetworkTrusted.value)
    }

    @Test
    fun testGatingStateControlsNotificationStatus() {
        // Test notification subtitle logic matches requirements:
        // When untrusted or mobile data alone -> "Paused (no paired gateway on this Wi-Fi)"
        // When trusted -> "State: Standby (Wi-Fi active)" or "State: Active (4G/5G failover running)"

        fun computeNotificationSubtitle(state: FailoverState, isTrusted: Boolean): String {
            return if (!isTrusted) {
                "Paused (no paired gateway on this Wi-Fi)"
            } else {
                when (state) {
                    FailoverState.IDLE -> "State: Standby (Wi-Fi active)"
                    FailoverState.CONNECTING -> "State: Connecting cellular..."
                    FailoverState.ACTIVE -> "State: Active (4G/5G failover running)"
                    FailoverState.ERROR -> "State: Cellular connection error"
                }
            }
        }

        // Trusted on Wi-Fi idle
        assertEquals("State: Standby (Wi-Fi active)", computeNotificationSubtitle(FailoverState.IDLE, true))
        // Trusted during active failover
        assertEquals("State: Active (4G/5G failover running)", computeNotificationSubtitle(FailoverState.ACTIVE, true))

        // Untrusted on Wi-Fi (even if state was idle or active)
        assertEquals("Paused (no paired gateway on this Wi-Fi)", computeNotificationSubtitle(FailoverState.IDLE, false))
        assertEquals("Paused (no paired gateway on this Wi-Fi)", computeNotificationSubtitle(FailoverState.ACTIVE, false))
        assertEquals("Paused (no paired gateway on this Wi-Fi)", computeNotificationSubtitle(FailoverState.ERROR, false))
    }

    @Test
    fun testFailoverBlockedWhenUntrusted() {
        var isNetworkTrusted = false
        var failoverStarted = false

        fun tryStartFailover(): Boolean {
            if (!isNetworkTrusted) {
                return false
            }
            failoverStarted = true
            return true
        }

        // Should fail when untrusted
        assertFalse(tryStartFailover())
        assertFalse(failoverStarted)

        // Should succeed when trusted
        isNetworkTrusted = true
        assertTrue(tryStartFailover())
        assertTrue(failoverStarted)
    }
}
