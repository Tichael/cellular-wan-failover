package com.example.cellularwanfailover

import com.example.cellularwanfailover.wireguard.WireGuardManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayOrphanTest {

    private val timeout = WireGuardManager.ORPHAN_TIMEOUT_SECONDS
    private val start = 1_700_000_000L

    @Test
    fun freshRelayWithoutHandshakeIsNotOrphaned() {
        assertFalse(WireGuardManager.isOrphaned(now = start + timeout, startedAt = start, lastHandshake = 0))
    }

    @Test
    fun relayNeverReachedByTheGatewayIsOrphaned() {
        assertTrue(WireGuardManager.isOrphaned(now = start + timeout + 1, startedAt = start, lastHandshake = 0))
    }

    @Test
    fun recentHandshakeKeepsLongRunningRelay() {
        val now = start + 7 * 24 * 3600
        assertFalse(WireGuardManager.isOrphaned(now = now, startedAt = start, lastHandshake = now - 120))
    }

    @Test
    fun handshakeTooOldMeansGatewayIsGone() {
        val now = start + 3600
        assertTrue(WireGuardManager.isOrphaned(now = now, startedAt = start, lastHandshake = now - timeout - 1))
    }
}
