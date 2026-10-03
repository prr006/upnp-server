package com.m36.mediaserver.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

/** An IPv4 address plus its Android transport, policy result, and multicast interface. */
data class ReachableAddress(
    val address: Inet4Address,
    val interfaceName: String,
    val networkInterface: NetworkInterface?,
    val network: Network?,
    val priority: Int,
    val transportType: String,
    val candidate: Boolean,
    val candidateReason: String,
) {
    val hostAddress: String get() = address.hostAddress ?: address.toString()
}

/** Immutable view of every IPv4 interface and the currently selected local server endpoint. */
data class NetworkSnapshot(
    val networkLabel: String,
    val addresses: List<ReachableAddress>,
    val defaultInterface: String?,
    val defaultTransport: String = "Unknown",
    val radioFingerprint: String = "",
    val interfacesWithoutIpv4: List<String> = emptyList(),
) {
    val eligibleAddresses: List<ReachableAddress> get() = addresses.filter { it.candidate }
    val primaryAddress: ReachableAddress?
        get() = eligibleAddresses.maxWithOrNull(
            compareBy<ReachableAddress> { it.priority }
                .thenByDescending { it.interfaceName }
                .thenByDescending { it.hostAddress },
        )
    val activeTransport: String
        get() = primaryAddress?.transportType ?: "None — no eligible LAN interface"
    val displayAddresses: List<String>
        get() = addresses.map {
            "Interface: ${it.interfaceName} | IPv4: ${it.hostAddress} | Type: ${it.transportType} | Candidate: ${if (it.candidate) "YES" else "NO"} — ${it.candidateReason}"
        } + interfacesWithoutIpv4

    /** Includes selection changes, active transport changes, and Wi-Fi radio/band changes. */
    fun fingerprint(): String = buildString {
        append(
            eligibleAddresses.map {
                "${it.interfaceName}:${it.hostAddress}:${it.transportType}:${it.priority}:${it.network ?: "-"}"
            }.sorted().joinToString("|"),
        )
        append("#default=").append(defaultTransport)
        append("#radio=").append(radioFingerprint)
    }
}

/**
 * Detects explicit Android Wi-Fi/Ethernet Networks first, then inspects every OS IPv4 interface
 * for an unmapped local LAN/Soft AP. Transport capabilities, routes, interface flags and address
 * scope are combined. Interface names are used only for deny-listing known cellular/tunnel
 * classes, never to identify an AP (there is no assumption that an AP is named wlan0).
 */
class NetworkAddressDetector(context: Context) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private data class InterfaceFacts(
        val networks: MutableList<Network> = ArrayList(),
        val transports: MutableSet<NetworkTransport> = LinkedHashSet(),
        var hasUsableIpv4Route: Boolean = false,
    ) {
        fun effectiveTransport(): NetworkTransport = when {
            NetworkTransport.CELLULAR in transports -> NetworkTransport.CELLULAR
            NetworkTransport.VPN in transports -> NetworkTransport.VPN
            NetworkTransport.WIFI in transports -> NetworkTransport.WIFI
            NetworkTransport.ETHERNET in transports -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        }

        fun preferredNetwork(): Network? = networks.firstOrNull()
    }

    private data class EvidenceKey(val interfaceName: String, val address: String)

    private data class AddressEvidence(
        val address: Inet4Address,
        val interfaceName: String,
        var networkInterface: NetworkInterface? = null,
        var network: Network? = null,
        var hasUsableIpv4Route: Boolean = false,
        var hasInterfacePrefixRoute: Boolean = false,
        var interfaceUp: Boolean? = null,
        var isLoopback: Boolean = false,
        var isPointToPoint: Boolean = false,
    )

    fun detect(): NetworkSnapshot {
        val defaultNetwork = runCatching { connectivity.activeNetwork }.getOrNull()
        val allNetworks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        val networks = (allNetworks + listOfNotNull(defaultNetwork)).distinct()
        val factsByInterface = LinkedHashMap<String, InterfaceFacts>()
        val evidence = LinkedHashMap<EvidenceKey, AddressEvidence>()
        val networkCapabilities = HashMap<Network, NetworkCapabilities?>()
        val networkProperties = HashMap<Network, LinkProperties?>()
        val radioDetails = ArrayList<String>()

        fun transportFor(caps: NetworkCapabilities?): NetworkTransport {
            if (caps == null) return NetworkTransport.OTHER
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
                else -> NetworkTransport.OTHER
            }
        }

        fun evidenceFor(name: String, address: Inet4Address): AddressEvidence {
            val key = EvidenceKey(name, address.hostAddress ?: address.toString())
            return evidence.getOrPut(key) { AddressEvidence(address, name) }
        }

        networks.forEach { network ->
            val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
            val properties = runCatching { connectivity.getLinkProperties(network) }.getOrNull()
            networkCapabilities[network] = caps
            networkProperties[network] = properties
            val linkProperties = properties ?: return@forEach
            val iface = linkProperties.interfaceName?.takeIf { it.isNotBlank() } ?: return@forEach
            val facts = factsByInterface.getOrPut(iface) { InterfaceFacts() }
            facts.networks += network
            facts.transports += transportFor(caps)

            val hasV4Route = linkProperties.routes.any { route ->
                route.destination.address is Inet4Address
            }
            facts.hasUsableIpv4Route = facts.hasUsableIpv4Route || hasV4Route
            linkProperties.linkAddresses.forEach linkLoop@{ linkAddress ->
                val address = linkAddress.address as? Inet4Address ?: return@linkLoop
                val item = evidenceFor(iface, address)
                item.network = item.network ?: network
                item.hasUsableIpv4Route = item.hasUsableIpv4Route || hasV4Route
                item.hasInterfacePrefixRoute = item.hasInterfacePrefixRoute ||
                    (linkAddress.prefixLength in 1..30)
            }

            val wifiInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { caps?.transportInfo as? WifiInfo }.getOrNull()
            } else {
                null
            }
            if (transportFor(caps) == NetworkTransport.WIFI) {
                val frequency = runCatching { wifiInfo?.frequency ?: 0 }.getOrDefault(0)
                radioDetails += "$iface:$frequency"
            }
        }

        // Enumerate every OS interface/address, including cellular, loopback and tunnel addresses,
        // so diagnostics show why each was rejected instead of silently hiding a bad selection.
        val interfacesWithoutIpv4 = LinkedHashMap<String, String>()
        try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { networkInterface ->
                val name = networkInterface.name ?: return@forEach
                val up = runCatching { networkInterface.isUp }.getOrDefault(false)
                val loopback = runCatching { networkInterface.isLoopback }.getOrDefault(name == "lo")
                val pointToPoint = runCatching { networkInterface.isPointToPoint }.getOrDefault(false)
                val facts = factsByInterface[name]
                val ipv4Addresses = Collections.list(networkInterface.inetAddresses).filterIsInstance<Inet4Address>()
                val hasConnectivityIpv4 = evidence.keys.any { it.interfaceName == name }
                if (ipv4Addresses.isEmpty() && !hasConnectivityIpv4) {
                    val decision = LocalInterfacePolicy.evaluate(
                        InterfacePolicyInput(
                            interfaceName = name,
                            transport = facts?.effectiveTransport() ?: NetworkTransport.OTHER,
                            defaultTransport = null,
                            interfaceUp = up,
                            isLoopback = loopback || name == "lo",
                            isPointToPoint = pointToPoint,
                            hasUsableIpv4Route = facts?.hasUsableIpv4Route == true,
                            isRfc1918Ipv4 = false,
                            addressIsUsableLanIpv4 = false,
                        ),
                    )
                    interfacesWithoutIpv4[name] =
                        "Interface: $name | IPv4: none | Type: ${decision.transportLabel} | Candidate: NO — no IPv4 address"
                }
                ipv4Addresses.forEach { address ->
                    val item = evidenceFor(name, address)
                    item.networkInterface = networkInterface
                    item.network = item.network ?: facts?.preferredNetwork()
                    item.interfaceUp = up
                    item.isLoopback = loopback || address.isLoopbackAddress
                    item.isPointToPoint = pointToPoint
                    val interfaceAddress = runCatching {
                        networkInterface.interfaceAddresses.firstOrNull { it.address == address }
                    }.getOrNull()
                    if (interfaceAddress != null) {
                        item.hasInterfacePrefixRoute = item.hasInterfacePrefixRoute ||
                            interfaceAddress.networkPrefixLength.toInt() in 1..30 || interfaceAddress.broadcast != null
                    }
                    item.hasUsableIpv4Route = item.hasUsableIpv4Route || facts?.hasUsableIpv4Route == true
                }
            }
        } catch (_: SocketException) {
            // Android ConnectivityManager evidence remains available if interface enumeration fails.
        } catch (_: SecurityException) {
            // Enumeration is best-effort and does not require broad permissions.
        }

        // LinkProperties may contain an active IPv4 interface address even if Java interface
        // enumeration is restricted by an OEM. Keep that address in the same diagnostics/policy path.
        networkProperties.forEach propertiesLoop@{ (network, properties) ->
            val linkProperties = properties ?: return@propertiesLoop
            val iface = linkProperties.interfaceName ?: return@propertiesLoop
            val facts = factsByInterface[iface]
            linkProperties.linkAddresses.forEach linkLoop@{ linkAddress ->
                val address = linkAddress.address as? Inet4Address ?: return@linkLoop
                val item = evidenceFor(iface, address)
                item.network = item.network ?: network
                item.hasUsableIpv4Route = item.hasUsableIpv4Route || facts?.hasUsableIpv4Route == true
                item.hasInterfacePrefixRoute = item.hasInterfacePrefixRoute || linkAddress.prefixLength in 1..30
                if (item.interfaceUp == null) item.interfaceUp = facts != null
            }
        }

        val defaultCaps = defaultNetwork?.let { networkCapabilities[it] }
            ?: defaultNetwork?.let { runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() }
        val defaultTransport = transportFor(defaultCaps)
        val defaultInterface = defaultNetwork?.let { networkProperties[it]?.interfaceName }
        val addresses = evidence.values.map { item ->
            val facts = factsByInterface[item.interfaceName]
            val transport = facts?.effectiveTransport() ?: NetworkTransport.OTHER
            val hasRoute = item.hasUsableIpv4Route || item.hasInterfacePrefixRoute
            val usableAddress = !item.address.isAnyLocalAddress &&
                !item.address.isLoopbackAddress &&
                !item.address.isLinkLocalAddress &&
                !item.address.isMulticastAddress
            val decision = LocalInterfacePolicy.evaluate(
                InterfacePolicyInput(
                    interfaceName = item.interfaceName,
                    transport = transport,
                    defaultTransport = defaultTransport,
                    interfaceUp = item.interfaceUp ?: (facts != null),
                    isLoopback = item.isLoopback || item.interfaceName == "lo",
                    isPointToPoint = item.isPointToPoint,
                    hasUsableIpv4Route = hasRoute,
                    isRfc1918Ipv4 = item.address.isRfc1918Address(),
                    addressIsUsableLanIpv4 = usableAddress,
                ),
            )
            ReachableAddress(
                address = item.address,
                interfaceName = item.interfaceName,
                networkInterface = item.networkInterface,
                network = item.network ?: facts?.preferredNetwork(),
                priority = decision.priority,
                transportType = decision.transportLabel,
                candidate = decision.candidate,
                candidateReason = decision.reason,
            )
        }.sortedWith(
            compareByDescending<ReachableAddress> { it.candidate }
                .thenByDescending { it.priority }
                .thenBy { it.interfaceName }
                .thenBy { it.hostAddress },
        )

        val primary = addresses.firstOrNull { it.candidate }
        val defaultTransportLabel = if (defaultNetwork == null) {
            "None"
        } else {
            when (defaultTransport) {
                NetworkTransport.WIFI -> "Wi-Fi"
                NetworkTransport.CELLULAR -> "Cellular / WWAN"
                NetworkTransport.VPN -> "VPN / tunnel"
                NetworkTransport.ETHERNET -> "Ethernet"
                NetworkTransport.OTHER -> "Other / unclassified"
            }
        }
        val label = when {
            primary != null -> primary.transportType
            defaultNetwork == null -> "No active network"
            defaultTransport == NetworkTransport.CELLULAR -> "Cellular default active — no eligible LAN interface"
            defaultTransport == NetworkTransport.VPN -> "VPN default active — no eligible Wi-Fi/Soft AP/LAN interface"
            defaultTransport == NetworkTransport.WIFI -> "Wi-Fi connected — no usable local IPv4 route"
            else -> "$defaultTransportLabel active — no eligible local LAN interface"
        }

        return NetworkSnapshot(
            networkLabel = label,
            addresses = addresses,
            defaultInterface = defaultInterface,
            defaultTransport = defaultTransportLabel,
            radioFingerprint = radioDetails.sorted().joinToString(","),
            interfacesWithoutIpv4 = interfacesWithoutIpv4.values.sorted(),
        )
    }

    private fun Inet4Address.isRfc1918Address(): Boolean {
        val octets = address.map { it.toInt() and 0xff }
        return when {
            octets[0] == 10 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            else -> false
        }
    }
}
