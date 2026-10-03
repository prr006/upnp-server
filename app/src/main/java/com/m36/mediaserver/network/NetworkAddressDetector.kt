package com.m36.mediaserver.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.Locale

/** An IPv4 endpoint and the interface Android should use for multicast on that endpoint. */
data class ReachableAddress(
    val address: Inet4Address,
    val interfaceName: String,
    val networkInterface: NetworkInterface?,
    val network: Network?,
    val priority: Int,
) {
    val hostAddress: String get() = address.hostAddress ?: address.toString()
}

data class NetworkSnapshot(
    val networkLabel: String,
    val addresses: List<ReachableAddress>,
    val defaultInterface: String?,
) {
    val primaryAddress: ReachableAddress? get() = addresses.firstOrNull()
    val displayAddresses: List<String>
        get() = addresses.map { "${it.interfaceName}: ${it.hostAddress}" }

    fun fingerprint(): String = addresses
        .map { "${it.interfaceName}:${it.hostAddress}" }
        .distinct()
        .sorted()
        .joinToString("|") + "#$networkLabel"
}

/**
 * Combines Android's routing metadata with actual up IPv4 interfaces. In particular, tethering
 * interfaces are not consistently represented by getActiveNetwork() on Android, so interface
 * enumeration is deliberately used as a second source instead of assuming a subnet or IP.
 */
class NetworkAddressDetector(context: Context) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    fun detect(): NetworkSnapshot {
        val allNetworks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        val defaultNetwork = runCatching { connectivity.activeNetwork }.getOrNull()
        val capabilities = HashMap<Network, NetworkCapabilities?>()
        val properties = HashMap<Network, LinkProperties?>()
        allNetworks.forEach { network ->
            capabilities[network] = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
            properties[network] = runCatching { connectivity.getLinkProperties(network) }.getOrNull()
        }

        val wifiInterfaces = HashSet<String>()
        val defaultInterfaces = HashSet<String>()
        val cellularInterfaces = HashSet<String>()
        val interfaceNetworks = HashMap<String, Network>()
        var hasWifi = false
        var hasCellular = false
        var hasEthernet = false
        var defaultIsWifi = false
        var defaultIsCellular = false

        allNetworks.forEach { network ->
            val caps = capabilities[network] ?: return@forEach
            val iface = properties[network]?.interfaceName
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                hasWifi = true
                if (!iface.isNullOrBlank()) {
                    wifiInterfaces += iface
                    interfaceNetworks[iface] = network
                }
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                hasCellular = true
                if (!iface.isNullOrBlank()) cellularInterfaces += iface
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) hasEthernet = true
            if (network == defaultNetwork) {
                if (!iface.isNullOrBlank()) defaultInterfaces += iface
                defaultIsWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                defaultIsCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        }

        data class CandidateKey(val iface: String, val address: String)
        val candidates = LinkedHashMap<CandidateKey, ReachableAddress>()

        fun addAddress(
            ifaceName: String,
            address: Inet4Address,
            network: Network? = interfaceNetworks[ifaceName],
            interfaceOverride: NetworkInterface? = null,
        ) {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress) return
            val networkInterface = interfaceOverride ?: runCatching { NetworkInterface.getByName(ifaceName) }.getOrNull()
            val lower = ifaceName.lowercase(Locale.ROOT)
            val isWifiLike = ifaceName in wifiInterfaces || WIFI_INTERFACE_HINT.containsMatchIn(lower)
            val isCellularLike = ifaceName in cellularInterfaces || CELLULAR_INTERFACE_HINT.containsMatchIn(lower)
            val priority = when {
                ifaceName in wifiInterfaces -> 400
                ifaceName in defaultInterfaces && defaultIsWifi -> 390
                isWifiLike -> 350
                ifaceName in defaultInterfaces -> 250
                !isCellularLike -> 150
                else -> 10
            }
            val item = ReachableAddress(address, ifaceName, networkInterface, network, priority)
            candidates[CandidateKey(ifaceName, address.hostAddress ?: address.toString())] = item
        }

        // LinkProperties is authoritative for Android-managed Wi-Fi and Ethernet networks.
        allNetworks.forEach { network ->
            val iface = properties[network]?.interfaceName ?: return@forEach
            properties[network]?.linkAddresses?.forEach { link ->
                (link.address as? Inet4Address)?.let { addAddress(iface, it, network) }
            }
        }

        // Tethering/AP interfaces may exist outside ConnectivityManager's app-visible network list.
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.forEach { networkInterface ->
                val usable = runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)
                if (!usable) return@forEach
                val name = networkInterface.name ?: return@forEach
                Collections.list(networkInterface.inetAddresses).forEach { address ->
                    (address as? Inet4Address)?.let { addAddress(name, it, interfaceOverride = networkInterface) }
                }
            }
        } catch (_: SocketException) {
            // ConnectivityManager-derived addresses above remain usable when interface enumeration
            // is restricted by an OEM build.
        } catch (_: SecurityException) {
            // Best effort only; the app never needs location or broad storage permissions.
        }

        val sorted = candidates.values
            .distinctBy { "${it.interfaceName}:${it.hostAddress}" }
            .sortedWith(compareByDescending<ReachableAddress> { it.priority }
                .thenBy { it.interfaceName }
                .thenBy { it.hostAddress })

        val defaultCaps = defaultNetwork?.let { capabilities[it] }
        val hasWifiLikeInterface = sorted.any { WIFI_INTERFACE_HINT.containsMatchIn(it.interfaceName.lowercase(Locale.ROOT)) }
        val label = when {
            defaultCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            defaultCaps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            hasWifi && defaultIsWifi -> "Wi-Fi"
            hasWifiLikeInterface && !defaultIsWifi -> "Wi-Fi hotspot / local AP"
            hasWifi -> "Wi-Fi network"
            hasEthernet -> "Ethernet"
            sorted.isNotEmpty() -> "Local network interface"
            defaultIsCellular || hasCellular -> "Cellular (no reachable local IPv4 found)"
            else -> "No active local network"
        }

        // Keep the variable referenced for diagnostics-oriented classification even if Android
        // reports cellular and a hotspot interface is currently absent.
        @Suppress("UNUSED_VARIABLE") val cellularOnly = defaultIsCellular && !hasWifiLikeInterface
        return NetworkSnapshot(label, sorted, defaultInterfaces.firstOrNull())
    }

    companion object {
        private val WIFI_INTERFACE_HINT = Regex("(^|[^a-z])(wlan|wifi|ap|swlan)([0-9_]*|[^a-z].*)?", RegexOption.IGNORE_CASE)
        private val CELLULAR_INTERFACE_HINT = Regex("(rmnet|ccmni|pdp[_-]?ip|wwan|cellular|rmnet_data)", RegexOption.IGNORE_CASE)
    }
}
