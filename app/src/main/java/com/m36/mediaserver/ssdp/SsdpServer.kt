package com.m36.mediaserver.ssdp

import android.net.Network
import android.os.Build
import com.m36.mediaserver.data.ServerMetrics
import com.m36.mediaserver.network.NetworkSnapshot
import com.m36.mediaserver.network.ReachableAddress
import com.m36.mediaserver.upnp.UpnpXml
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real IPv4 SSDP responder: multicast M-SEARCH listener plus alive/byebye announcements. */
class SsdpServer(
    private val deviceUuid: String,
    private val httpPort: Int,
    private val metrics: ServerMetrics,
) : AutoCloseable {
    private val lock = Any()
    private val activeSockets = CopyOnWriteArrayList<InterfaceSocket>()
    private val responseScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { task ->
        Thread(task, "m36-ssdp-response").apply { isDaemon = true }
    }
    private val isClosed = AtomicBoolean(false)
    @Volatile private var currentFingerprint: String = ""
    @Volatile private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null

    fun updateInterfaces(snapshot: NetworkSnapshot) {
        val fingerprint = snapshot.fingerprint()
        synchronized(lock) {
            if (isClosed.get() || fingerprint == currentFingerprint) return
            sendByeByeAndCloseSockets()
            currentFingerprint = fingerprint
            val candidates = snapshot.addresses
                .filter { it.priority >= MIN_INTERFACE_PRIORITY }
                .groupBy { it.interfaceName }
                .mapNotNull { (_, addresses) -> addresses.maxByOrNull { it.priority } }
                .sortedByDescending { it.priority }

            val created = ArrayList<InterfaceSocket>()
            val details = ArrayList<String>()
            candidates.forEach { candidate ->
                val networkInterface = candidate.networkInterface
                    ?: runCatching { NetworkInterface.getByName(candidate.interfaceName) }.getOrNull()
                if (networkInterface == null) {
                    details += "${candidate.interfaceName}: no NetworkInterface"
                    return@forEach
                }
                val attempt = openInterface(candidate, networkInterface)
                if (attempt.socket != null) {
                    created += attempt.socket
                    details += "${candidate.interfaceName} (${candidate.hostAddress}): group joined"
                } else {
                    details += "${candidate.interfaceName} (${candidate.hostAddress}): ${attempt.error ?: "socket/join failed"}"
                }
            }

            activeSockets.addAll(created)
            metrics.multicastSocketCreated = created.isNotEmpty()
            metrics.multicastGroupJoined = created.any { it.joined }
            metrics.multicastDetails = details.ifEmpty { listOf("No eligible active IPv4 interface") }.joinToString("; ")
            metrics.ssdpStatus = when {
                created.any { it.joined } -> "Listening on ${created.filter { it.joined }.joinToString { it.interfaceName }}"
                created.isNotEmpty() -> "Socket created, but multicast group join failed"
                else -> "No multicast-capable local IPv4 interface"
            }
            created.forEach { sendAlive(it) }
            heartbeat?.cancel(false)
            heartbeat = if (created.isNotEmpty()) {
                responseScheduler.scheduleAtFixedRate(
                    { activeSockets.forEach(::sendAlive) },
                    ALIVE_INTERVAL_SECONDS,
                    ALIVE_INTERVAL_SECONDS,
                    TimeUnit.SECONDS,
                )
            } else null
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        synchronized(lock) {
            sendByeByeAndCloseSockets()
            currentFingerprint = ""
            metrics.ssdpStatus = "Stopped"
            metrics.multicastDetails = "Sockets closed"
            metrics.multicastSocketCreated = false
            metrics.multicastGroupJoined = false
        }
        responseScheduler.shutdownNow()
    }

    private fun openInterface(candidate: ReachableAddress, networkInterface: NetworkInterface): InterfaceOpenResult {
        var lastError: Throwable? = null
        val possibleNetworks: List<Network?> = if (candidate.network == null) {
            listOf(null)
        } else {
            listOf(candidate.network, null)
        }
        for (network in possibleNetworks) {
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket(null)
                socket.reuseAddress = true
                if (network != null) {
                    // Bind the socket to Android's actual Network when it maps to this interface.
                    // Tethering AP interfaces often have no Network object, in which case the
                    // explicit NetworkInterface membership/outbound selection below is used.
                    network.bindSocket(socket)
                }
                socket.networkInterface = networkInterface
                socket.timeToLive = 2
                socket.bind(InetSocketAddress(PORT))
                socket.joinGroup(InetSocketAddress(GROUP, PORT), networkInterface)
                val context = InterfaceSocket(
                    interfaceName = candidate.interfaceName,
                    localAddress = candidate.address,
                    networkInterface = networkInterface,
                    network = network,
                    socket = socket,
                )
                startReceiver(context)
                return InterfaceOpenResult(context, null)
            } catch (error: Throwable) {
                lastError = error
                runCatching { socket?.close() }
            }
        }
        return InterfaceOpenResult(null, lastError?.message ?: "Unable to create multicast socket")
    }

    private fun startReceiver(context: InterfaceSocket) {
        Thread({
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (!isClosed.get() && !context.closed.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    context.socket.receive(packet)
                    val message = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    onDatagram(context, packet, message)
                } catch (_: SocketException) {
                    if (!isClosed.get() && !context.closed.get()) {
                        metrics.ssdpStatus = "Receive socket closed on ${context.interfaceName}"
                    }
                    break
                } catch (error: IOException) {
                    if (!isClosed.get() && !context.closed.get()) {
                        metrics.ssdpStatus = "Receive error on ${context.interfaceName}: ${error.message}"
                    }
                } catch (_: RuntimeException) {
                    // Drop malformed or OEM-specific datagrams without taking down the listener.
                }
            }
        }, "m36-ssdp-${context.interfaceName}").apply { isDaemon = true }.start()
    }

    private fun onDatagram(context: InterfaceSocket, packet: DatagramPacket, message: String) {
        val lines = message.split(Regex("\\r?\\n"))
        val firstLine = lines.firstOrNull()?.trim().orEmpty()
        if (!firstLine.startsWith("M-SEARCH ", ignoreCase = true)) return
        val headers = parseHeaders(lines.drop(1))
        val searchTarget = headers["st"]?.trim()?.trim('"').orEmpty()
        metrics.mSearchCount.incrementAndGet()
        metrics.lastSsdpRequest = "${firstLine.take(120)} | ST=${searchTarget.ifBlank { "(missing)" }} from ${packet.address.hostAddress}:${packet.port}"

        val man = headers["man"]?.trim()?.trim('"')
        if (searchTarget.isBlank() || (man != null && !man.equals("ssdp:discover", ignoreCase = true))) return
        val matches = matchingAdvertisements(searchTarget)
        if (matches.isEmpty()) return
        val mx = headers["mx"]?.toIntOrNull()?.coerceIn(1, 5) ?: 1
        matches.forEachIndexed { index, advertisement ->
            val delay = if (searchTarget.equals("ssdp:all", true)) {
                ThreadLocalRandom.current().nextLong(0L, (mx.coerceAtMost(2) * 100L + 1L)) + index * 8L
            } else {
                ThreadLocalRandom.current().nextLong(0L, 50L)
            }
            responseScheduler.schedule({
                if (!isClosed.get() && !context.closed.get()) {
                    sendSearchResponse(context, packet.address, packet.port, advertisement)
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun matchingAdvertisements(searchTarget: String): List<Advertisement> {
        val uuidTarget = "uuid:$deviceUuid"
        val all = searchTarget.equals("ssdp:all", ignoreCase = true)
        if (all) return advertisements()
        return when {
            searchTarget.equals("upnp:rootdevice", ignoreCase = true) -> listOf(advertisements().first())
            searchTarget.equals(uuidTarget, ignoreCase = true) -> listOf(advertisements()[1])
            searchTarget.equals(UpnpXml.DEVICE_TYPE, ignoreCase = true) -> listOf(advertisements()[2])
            searchTarget.equals(UpnpXml.CONTENT_DIRECTORY_TYPE, ignoreCase = true) -> listOf(advertisements()[3])
            searchTarget.equals(UpnpXml.CONNECTION_MANAGER_TYPE, ignoreCase = true) -> listOf(advertisements()[4])
            else -> emptyList()
        }
    }

    private fun advertisements(): List<Advertisement> {
        val uuidTarget = "uuid:$deviceUuid"
        return listOf(
            Advertisement("upnp:rootdevice", "$uuidTarget::upnp:rootdevice"),
            Advertisement(uuidTarget, uuidTarget),
            Advertisement(UpnpXml.DEVICE_TYPE, "$uuidTarget::${UpnpXml.DEVICE_TYPE}"),
            Advertisement(UpnpXml.CONTENT_DIRECTORY_TYPE, "$uuidTarget::${UpnpXml.CONTENT_DIRECTORY_TYPE}"),
            Advertisement(UpnpXml.CONNECTION_MANAGER_TYPE, "$uuidTarget::${UpnpXml.CONNECTION_MANAGER_TYPE}"),
        )
    }

    private fun sendSearchResponse(
        context: InterfaceSocket,
        destination: InetAddress,
        destinationPort: Int,
        advertisement: Advertisement,
    ) {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=$CACHE_MAX_AGE_SECONDS\r\n")
            append("EXT:\r\n")
            append("LOCATION: ${location(context.localAddress)}\r\n")
            append("SERVER: $SERVER_HEADER\r\n")
            append("ST: ${advertisement.searchTarget}\r\n")
            append("USN: ${advertisement.usn}\r\n")
            append("BOOTID.UPNP.ORG: 1\r\n")
            append("CONFIGID.UPNP.ORG: 1\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        try {
            synchronized(context.sendLock) {
                context.socket.send(DatagramPacket(response, response.size, destination, destinationPort))
            }
            metrics.ssdpResponsesSent.incrementAndGet()
        } catch (error: IOException) {
            metrics.ssdpStatus = "Response failed on ${context.interfaceName}: ${error.message}"
        }
    }

    private fun sendAlive(context: InterfaceSocket) {
        if (context.closed.get()) return
        advertisements().forEach { advertisement ->
            val notify = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $GROUP_ADDRESS:$PORT\r\n")
                append("CACHE-CONTROL: max-age=$CACHE_MAX_AGE_SECONDS\r\n")
                append("LOCATION: ${location(context.localAddress)}\r\n")
                append("NT: ${advertisement.searchTarget}\r\n")
                append("NTS: ssdp:alive\r\n")
                append("SERVER: $SERVER_HEADER\r\n")
                append("USN: ${advertisement.usn}\r\n")
                append("BOOTID.UPNP.ORG: 1\r\n")
                append("CONFIGID.UPNP.ORG: 1\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            sendMulticast(context, notify)
        }
    }

    private fun sendByeByeAndCloseSockets() {
        heartbeat?.cancel(false)
        heartbeat = null
        activeSockets.toList().forEach { context ->
            if (context.joined && !context.closed.get()) {
                advertisements().forEach { advertisement ->
                    val notify = buildString {
                        append("NOTIFY * HTTP/1.1\r\n")
                        append("HOST: $GROUP_ADDRESS:$PORT\r\n")
                        append("NT: ${advertisement.searchTarget}\r\n")
                        append("NTS: ssdp:byebye\r\n")
                        append("USN: ${advertisement.usn}\r\n")
                        append("\r\n")
                    }.toByteArray(StandardCharsets.US_ASCII)
                    sendMulticast(context, notify)
                }
            }
            context.closed.set(true)
            runCatching { context.socket.leaveGroup(InetSocketAddress(GROUP, PORT), context.networkInterface) }
            runCatching { context.socket.close() }
        }
        activeSockets.clear()
    }

    private fun sendMulticast(context: InterfaceSocket, bytes: ByteArray) {
        try {
            synchronized(context.sendLock) {
                context.socket.send(DatagramPacket(bytes, bytes.size, GROUP, PORT))
            }
        } catch (error: IOException) {
            metrics.ssdpStatus = "Multicast send failed on ${context.interfaceName}: ${error.message}"
        }
    }

    private fun location(address: Inet4Address): String = "http://${address.hostAddress}:$httpPort/rootDesc.xml"

    private fun parseHeaders(lines: List<String>): Map<String, String> {
        val headers = HashMap<String, String>()
        lines.forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                if (key !in headers) headers[key] = line.substring(separator + 1).trim()
            }
        }
        return headers
    }

    private data class Advertisement(val searchTarget: String, val usn: String)
    private data class InterfaceOpenResult(val socket: InterfaceSocket?, val error: String?)

    private class InterfaceSocket(
        val interfaceName: String,
        val localAddress: Inet4Address,
        val networkInterface: NetworkInterface,
        @Suppress("unused") val network: Network?,
        val socket: MulticastSocket,
    ) {
        val sendLock = Any()
        val closed = AtomicBoolean(false)
        @Volatile var joined: Boolean = true
    }

    companion object {
        const val GROUP_ADDRESS = "239.255.255.250"
        const val PORT = 1900
        private const val CACHE_MAX_AGE_SECONDS = 1800
        private const val ALIVE_INTERVAL_SECONDS = 900L
        private const val MAX_PACKET_BYTES = 16 * 1024
        private const val MIN_INTERFACE_PRIORITY = 100
        private val GROUP: InetAddress by lazy { InetAddress.getByName(GROUP_ADDRESS) }
        private val SERVER_HEADER: String by lazy {
            "Android/${Build.VERSION.RELEASE ?: "unknown"} UPnP/1.1 M36MediaServer/1.0"
        }
    }
}
