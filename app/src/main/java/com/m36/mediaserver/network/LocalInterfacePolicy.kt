package com.m36.mediaserver.network

import java.util.Locale

/** Android transport classification used before any IPv4 address can be advertised or bound. */
enum class NetworkTransport(val label: String) {
    WIFI("Wi-Fi"),
    CELLULAR("Cellular / WWAN"),
    VPN("VPN / tunnel"),
    ETHERNET("Ethernet"),
    OTHER("Other / unclassified"),
}

data class InterfacePolicyInput(
    val interfaceName: String,
    val transport: NetworkTransport,
    val defaultTransport: NetworkTransport?,
    val interfaceUp: Boolean,
    val isLoopback: Boolean,
    val isPointToPoint: Boolean,
    val hasUsableIpv4Route: Boolean,
    val isRfc1918Ipv4: Boolean,
    val addressIsUsableLanIpv4: Boolean = true,
)

data class InterfacePolicyDecision(
    val candidate: Boolean,
    val priority: Int,
    val transportLabel: String,
    val reason: String,
)

/**
 * Strict LAN-only policy shared by the Android detector and JVM tests. Cellular and tunnel
 * exclusions run before transport or default-network preference, so the system's default route
 * can never make a WWAN address eligible.
 */
object LocalInterfacePolicy {
    fun evaluate(input: InterfacePolicyInput): InterfacePolicyDecision {
        val name = input.interfaceName.lowercase(Locale.ROOT)

        if (isCellularName(name) || input.transport == NetworkTransport.CELLULAR) {
            return excluded(NetworkTransport.CELLULAR, "cellular / WWAN interfaces are never eligible")
        }
        if (isTunnelName(name) || input.transport == NetworkTransport.VPN) {
            return excluded(NetworkTransport.VPN, "VPN / tunnel interfaces are never eligible")
        }
        if (!input.interfaceUp) return excluded(input.transport, "interface is down")
        if (input.isLoopback || name == "lo") return excluded(NetworkTransport.OTHER, "loopback interface")
        if (!input.addressIsUsableLanIpv4) {
            return excluded(input.transport, "IPv4 address is unspecified, loopback, link-local, or multicast")
        }
        if (input.isPointToPoint) return excluded(input.transport, "point-to-point interface is not a LAN")

        return when (input.transport) {
            NetworkTransport.WIFI -> if (input.hasUsableIpv4Route) {
                InterfacePolicyDecision(
                    candidate = true,
                    priority = WIFI_PRIORITY,
                    transportLabel = "Wi-Fi",
                    reason = "Android reports Wi-Fi transport with a usable IPv4 route",
                )
            } else {
                excluded(NetworkTransport.WIFI, "Wi-Fi has no usable IPv4 route")
            }

            NetworkTransport.ETHERNET -> if (input.hasUsableIpv4Route) {
                InterfacePolicyDecision(
                    candidate = true,
                    priority = ETHERNET_PRIORITY,
                    transportLabel = "Ethernet LAN",
                    reason = "Android reports Ethernet transport with a usable IPv4 route",
                )
            } else {
                excluded(NetworkTransport.ETHERNET, "Ethernet has no usable IPv4 route")
            }

            NetworkTransport.CELLULAR -> excluded(NetworkTransport.CELLULAR, "cellular / WWAN transport")
            NetworkTransport.VPN -> excluded(NetworkTransport.VPN, "VPN transport")
            NetworkTransport.OTHER -> if (input.isRfc1918Ipv4 && input.hasUsableIpv4Route) {
                val likelyHotspot = input.defaultTransport == NetworkTransport.CELLULAR
                InterfacePolicyDecision(
                    candidate = true,
                    priority = if (likelyHotspot) INFERRED_HOTSPOT_PRIORITY else INFERRED_LAN_PRIORITY,
                    transportLabel = if (likelyHotspot) "Local LAN / possible Soft AP (inferred)" else "Local LAN (inferred)",
                    reason = if (likelyHotspot) {
                        "private IPv4 LAN route detected on a non-cellular interface while the default network is cellular"
                    } else {
                        "private IPv4 LAN route detected on a non-cellular interface not mapped to an Android transport"
                    },
                )
            } else {
                excluded(NetworkTransport.OTHER, "not identified as Wi-Fi/Ethernet and no private IPv4 LAN route")
            }
        }
    }

    private fun excluded(transport: NetworkTransport, reason: String) = InterfacePolicyDecision(
        candidate = false,
        priority = EXCLUDED_PRIORITY,
        transportLabel = transport.label,
        reason = reason,
    )

    private fun isCellularName(name: String): Boolean = CELLULAR_INTERFACE.matches(name)
    private fun isTunnelName(name: String): Boolean = TUNNEL_INTERFACE.matches(name)

    private val CELLULAR_INTERFACE = Regex(
        "^(?:rmnet.*|ccmni\\d*|ccemni\\d*|ccinet\\d*|pdp(?:_ip)?\\d*|wwan\\d*|wwp.*|cellular\\d*|clat\\d*|v4-rmnet.*)$",
    )
    private val TUNNEL_INTERFACE = Regex(
        "^(?:tun.*|tap.*|wg.*|utun.*|ppp.*|ipsec.*|sit.*|ip6tnl.*|vti.*|gre.*|gretap.*|dummy.*|veth.*|docker.*|virbr.*)$",
    )

    private const val WIFI_PRIORITY = 1_000
    private const val INFERRED_HOTSPOT_PRIORITY = 900
    private const val INFERRED_LAN_PRIORITY = 700
    private const val ETHERNET_PRIORITY = 650
    private const val EXCLUDED_PRIORITY = Int.MIN_VALUE
}
