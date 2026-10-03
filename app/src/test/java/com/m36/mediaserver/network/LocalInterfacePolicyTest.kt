package com.m36.mediaserver.network

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInterfacePolicyTest {
    @Test
    fun cellularDefaultNeverMakesCellularAddressEligible() {
        val cellular = decide(
            name = "rmnet0",
            transport = NetworkTransport.CELLULAR,
            defaultTransport = NetworkTransport.CELLULAR,
            rfc1918 = false,
            route = true,
        )

        assertFalse(cellular.candidate)
        assertEquals("Cellular / WWAN", cellular.transportLabel)
    }

    @Test
    fun knownWwanNamesStayExcludedEvenWhenAndroidTransportMetadataIsMissing() {
        val unclassifiedWwan = decide(
            name = "ccmni0",
            transport = NetworkTransport.OTHER,
            defaultTransport = NetworkTransport.CELLULAR,
            rfc1918 = true,
            route = true,
        )

        assertFalse(unclassifiedWwan.candidate)
        assertEquals("Cellular / WWAN", unclassifiedWwan.transportLabel)
    }

    @Test
    fun explicitlyConnectedWifiOutranksCellularWhenBothAreActive() {
        val rmnet = decide(
            name = "rmnet_data2",
            transport = NetworkTransport.CELLULAR,
            defaultTransport = NetworkTransport.CELLULAR,
            rfc1918 = false,
            route = true,
        )
        val wifi = decide(
            name = "wifi_client1",
            transport = NetworkTransport.WIFI,
            defaultTransport = NetworkTransport.CELLULAR,
            rfc1918 = false,
            route = true,
        )

        assertFalse(rmnet.candidate)
        assertTrue(wifi.candidate)
        assertEquals(wifi, listOf(rmnet, wifi).filter { it.candidate }.maxBy { it.priority })
    }

    @Test
    fun snapshotNeverSelectsAHighPriorityNonCandidate() {
        val cellular = ReachableAddress(
            address = ipv4("100.79.178.31"),
            interfaceName = "rmnet0",
            networkInterface = null,
            network = null,
            priority = Int.MAX_VALUE,
            transportType = "Cellular / WWAN",
            candidate = false,
            candidateReason = "cellular / WWAN interfaces are never eligible",
        )
        val wifi = ReachableAddress(
            address = ipv4("192.168.8.1"),
            interfaceName = "wifi_ap9",
            networkInterface = null,
            network = null,
            priority = 1_000,
            transportType = "Wi-Fi",
            candidate = true,
            candidateReason = "usable local route",
        )

        val snapshot = NetworkSnapshot("Wi-Fi", listOf(cellular, wifi), "rmnet0")

        assertEquals("wifi_ap9", snapshot.primaryAddress?.interfaceName)
    }

    @Test
    fun dynamicallyNamedPrivateLanCanRepresentSoftApWithoutAssumingWlan0() {
        val rmnet = decide(
            name = "rmnet0",
            transport = NetworkTransport.CELLULAR,
            defaultTransport = NetworkTransport.CELLULAR,
            rfc1918 = false,
            route = true,
        )
        val hotspot = decide(
            name = "ap_shared_7",
            transport = NetworkTransport.OTHER,
            defaultTransport = NetworkTransport.CELLULAR,
            rfc1918 = true,
            route = true,
        )

        assertFalse(rmnet.candidate)
        assertTrue(hotspot.candidate)
        assertEquals(900, hotspot.priority)
        assertTrue(hotspot.transportLabel.contains("Soft AP"))
    }

    @Test
    fun loopbackVpnPointToPointAndUnroutedPublicInterfacesAreRejected() {
        assertFalse(decide("lo", NetworkTransport.OTHER, null, true, true, false, true).candidate)
        assertFalse(decide("tun0", NetworkTransport.VPN, null, true, true, false, true).candidate)
        assertFalse(decide("lan0", NetworkTransport.OTHER, null, true, true, true, true).candidate)
        assertFalse(decide("eth99", NetworkTransport.OTHER, null, false, false, false, true).candidate)
    }

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    private fun decide(
        name: String,
        transport: NetworkTransport,
        defaultTransport: NetworkTransport?,
        rfc1918: Boolean,
        route: Boolean,
    ) = decide(name, transport, defaultTransport, rfc1918, route, pointToPoint = false, up = true)

    private fun decide(
        name: String,
        transport: NetworkTransport,
        defaultTransport: NetworkTransport?,
        rfc1918: Boolean,
        route: Boolean,
        pointToPoint: Boolean,
        up: Boolean,
    ) = LocalInterfacePolicy.evaluate(
        InterfacePolicyInput(
            interfaceName = name,
            transport = transport,
            defaultTransport = defaultTransport,
            interfaceUp = up,
            isLoopback = name == "lo",
            isPointToPoint = pointToPoint,
            hasUsableIpv4Route = route,
            isRfc1918Ipv4 = rfc1918,
        ),
    )
}
