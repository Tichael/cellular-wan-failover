package com.example.cellularwanfailover

import com.example.cellularwanfailover.network.StickyWifiTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StickyWifiTrustTest {

    private val trustedSsids = mutableSetOf("Home")
    private lateinit var trust: StickyWifiTrust

    private val homeConnection = 101L
    private val otherConnection = 202L

    @Before
    fun setUp() {
        trust = StickyWifiTrust { it in trustedSsids }
    }

    @Test
    fun hiddenSsidKeepsVerifiedConnectionTrusted() {
        assertEquals("Home", trust.resolveSsid(homeConnection, "Home"))
        // App goes to background / screen locks / Wi-Fi loses internet: SSID hidden
        assertEquals("Home", trust.resolveSsid(homeConnection, null))
        assertEquals("Home", trust.resolveSsid(homeConnection, null))
    }

    @Test
    fun hiddenSsidOnNeverVerifiedConnectionIsUnknown() {
        assertNull(trust.resolveSsid(homeConnection, null))
    }

    @Test
    fun hiddenSsidOnAnotherConnectionIsUnknown() {
        trust.resolveSsid(homeConnection, "Home")
        assertNull(trust.resolveSsid(otherConnection, null))
    }

    @Test
    fun lostConnectionMustBeVerifiedAgain() {
        trust.resolveSsid(homeConnection, "Home")
        trust.onNetworkLost(homeConnection)
        assertNull(trust.resolveSsid(homeConnection, null))
    }

    @Test
    fun losingAnUnrelatedConnectionKeepsTrust() {
        trust.resolveSsid(homeConnection, "Home")
        trust.onNetworkLost(otherConnection)
        assertEquals("Home", trust.resolveSsid(homeConnection, null))
    }

    @Test
    fun readableUntrustedSsidClearsVerification() {
        trust.resolveSsid(homeConnection, "Home")
        assertEquals("Cafe", trust.resolveSsid(homeConnection, "Cafe"))
        assertNull(trust.resolveSsid(homeConnection, null))
    }

    @Test
    fun untrustedReadableSsidIsNotRemembered() {
        trust.resolveSsid(homeConnection, "Cafe")
        assertNull(trust.resolveSsid(homeConnection, null))
    }
}
