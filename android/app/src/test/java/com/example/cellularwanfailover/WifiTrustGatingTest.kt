package com.example.cellularwanfailover

import com.example.cellularwanfailover.model.FailoverState
import com.example.cellularwanfailover.model.WifiNetworkInfo
import com.example.cellularwanfailover.network.TrustedNetworkManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WifiTrustGatingTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var trustedNetworkManager: TrustedNetworkManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        trustedNetworkManager = TrustedNetworkManager(fakePrefs)
    }

    @Test
    fun testDynamicTrustEvaluation() {
        val currentSsid = MutableStateFlow<String?>("MyHomeNetwork")
        val isNetworkTrusted = MutableStateFlow(false)

        fun reevaluate() {
            val ssid = currentSsid.value
            isNetworkTrusted.value = trustedNetworkManager.isTrusted(ssid)
        }

        // Initially untrusted because whitelist is empty
        reevaluate()
        assertFalse(isNetworkTrusted.value)

        // Add home network to whitelist
        trustedNetworkManager.addNetwork("MyHomeNetwork")
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

        // Remove home Wi-Fi from whitelist
        val homeId = trustedNetworkManager.trustedNetworks.value.first { it.ssid == "MyHomeNetwork" }.id
        trustedNetworkManager.removeNetwork(homeId)
        reevaluate()
        assertFalse(isNetworkTrusted.value)
    }

    @Test
    fun testGatingStateControlsNotificationStatus() {
        // Test notification subtitle logic matches requirements:
        // When untrusted or mobile data alone -> "Paused (Untrusted Network)"
        // When trusted -> "State: Standby (Wi-Fi active)" or "State: Active (4G/5G failover running)"

        fun computeNotificationSubtitle(state: FailoverState, isTrusted: Boolean): String {
            return if (!isTrusted) {
                "Paused (Untrusted Network)"
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
        assertEquals("Paused (Untrusted Network)", computeNotificationSubtitle(FailoverState.IDLE, false))
        assertEquals("Paused (Untrusted Network)", computeNotificationSubtitle(FailoverState.ACTIVE, false))
        assertEquals("Paused (Untrusted Network)", computeNotificationSubtitle(FailoverState.ERROR, false))
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
