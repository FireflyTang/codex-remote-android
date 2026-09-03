package com.firefly.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalNetworkSelectionTest {
    @Test
    fun `socket stays unbound when Android has a default route`() {
        assertEquals(true, shouldUseDefaultRoute(hasActiveNetwork = true))
    }

    @Test
    fun `socket binding reports unavailable without a default route`() {
        assertEquals(false, shouldUseDefaultRoute(hasActiveNetwork = false))
    }

    @Test
    fun `without VPN keeps the active physical network`() {
        val candidates = listOf(
            candidate(1, transport = PhysicalTransport.WIFI),
            candidate(2, active = true, validated = false, transport = PhysicalTransport.CELLULAR),
        )

        assertEquals(2L, select(active = 2, activeIsVpn = false, candidates = candidates))
    }

    @Test
    fun `VPN uses an eligible explicit underlying network`() {
        val candidates = listOf(
            candidate(10, vpn = true, notVpn = false, transport = null),
            candidate(20, transport = PhysicalTransport.WIFI),
            candidate(30, transport = PhysicalTransport.ETHERNET),
        )

        assertEquals(
            20L,
            select(active = 10, activeIsVpn = true, underlying = listOf(20), candidates = candidates),
        )
    }

    @Test
    fun `unusable VPN underlying falls back to another non VPN network`() {
        val candidates = listOf(
            candidate(10, vpn = true, notVpn = false, transport = null),
            candidate(20, internet = false, transport = PhysicalTransport.WIFI),
            candidate(30, transport = PhysicalTransport.CELLULAR),
        )

        assertEquals(
            30L,
            select(active = 10, activeIsVpn = true, underlying = listOf(20), candidates = candidates),
        )
    }

    @Test
    fun `fallback prefers validated WiFi over cellular`() {
        val candidates = listOf(
            candidate(30, transport = PhysicalTransport.CELLULAR),
            candidate(20, transport = PhysicalTransport.WIFI),
        )

        assertEquals(20L, select(active = 10, activeIsVpn = true, candidates = candidates))
    }

    @Test
    fun `validation wins before transport preference`() {
        val candidates = listOf(
            candidate(20, validated = false, transport = PhysicalTransport.WIFI),
            candidate(30, validated = true, transport = PhysicalTransport.CELLULAR),
        )

        assertEquals(30L, select(active = 10, activeIsVpn = true, candidates = candidates))
    }

    @Test
    fun `VPN itself is never returned as fallback`() {
        val candidates = listOf(
            candidate(10, vpn = true, notVpn = false, transport = null),
            candidate(20, notVpn = false, transport = PhysicalTransport.WIFI),
            candidate(30, transport = null),
        )

        assertNull(select(active = 10, activeIsVpn = true, candidates = candidates))
    }

    private fun select(
        active: Long,
        activeIsVpn: Boolean,
        underlying: List<Long> = emptyList(),
        candidates: List<PhysicalNetworkCandidate>,
    ) = selectPhysicalNetworkHandle(active, activeIsVpn, underlying, candidates)

    private fun candidate(
        handle: Long,
        active: Boolean = false,
        vpn: Boolean = false,
        internet: Boolean = true,
        notVpn: Boolean = true,
        validated: Boolean = true,
        transport: PhysicalTransport?,
    ) = PhysicalNetworkCandidate(
        networkHandle = handle,
        isActive = active,
        isVpn = vpn,
        hasInternet = internet,
        isNotVpn = notVpn,
        isValidated = validated,
        transport = transport,
    )
}
