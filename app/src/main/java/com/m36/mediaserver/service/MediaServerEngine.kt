package com.m36.mediaserver.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.m36.mediaserver.data.ServerMetrics
import com.m36.mediaserver.domain.ServerDiagnostics
import com.m36.mediaserver.http.LocalHttpServer
import com.m36.mediaserver.media.DocumentTreeRepository
import com.m36.mediaserver.network.NetworkAddressDetector
import com.m36.mediaserver.network.NetworkSnapshot
import com.m36.mediaserver.ssdp.SsdpServer
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns the HTTP, SSDP, SAF catalog and network-change lifecycle for one foreground-service run. */
class MediaServerEngine(
    context: Context,
    private val treeUri: Uri,
    private val deviceUuid: String,
) : Closeable {
    private val appContext = context.applicationContext
    private val metrics = ServerMetrics()
    private val detector = NetworkAddressDetector(appContext)
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val refreshQueued = AtomicBoolean(false)

    @Volatile private var currentNetwork = NetworkSnapshot("Detecting network", emptyList(), null)
    @Volatile private var networkFingerprint: String = ""
    private var repository: DocumentTreeRepository? = null
    private var httpServer: LocalHttpServer? = null
    private var ssdpServer: SsdpServer? = null
    private var pollJob: Job? = null
    private var allNetworksCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    val isRunning: Boolean get() = started.get()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val catalog = DocumentTreeRepository(appContext, treeUri)
            repository = catalog
            metrics.selectedFolder = catalog.rootTitle

            acquireMulticastLock()
            acquireCpuWakeLock()
            val http = LocalHttpServer(
                context = appContext,
                repository = catalog,
                deviceUuid = deviceUuid,
                metrics = metrics,
                networkSnapshot = { currentNetwork },
            )
            httpServer = http
            http.start()

            val ssdp = SsdpServer(deviceUuid, LocalHttpServer.PORT, metrics)
            ssdpServer = ssdp
            registerNetworkCallbacks()
            refreshNetwork()
            pollJob = scope.launch {
                while (isActive) {
                    delay(NETWORK_POLL_INTERVAL_MILLIS)
                    refreshNetwork()
                }
            }
        } catch (error: Exception) {
            metrics.serverStatus = "Start failed: ${error.message ?: error.javaClass.simpleName}"
            close()
            throw error
        }
    }

    fun snapshot(): ServerDiagnostics {
        val snapshot = currentNetwork
        val primary = snapshot.primaryAddress
        val httpReady = httpServer?.running == true
        val listeningAddresses = httpServer?.listeningAddresses.orEmpty()
        val addressText = listeningAddresses.joinToString(", ")
        return ServerDiagnostics(
            running = isRunning,
            serverStatus = metrics.serverStatus,
            currentNetwork = snapshot.networkLabel,
            activeInterface = primary?.interfaceName ?: "—",
            activeServerIpv4 = primary?.hostAddress ?: "—",
            activeTransport = snapshot.activeTransport,
            defaultTransport = snapshot.defaultTransport,
            ipv4Addresses = snapshot.displayAddresses,
            httpBindAddress = when {
                !httpReady -> "—"
                addressText.isBlank() -> "Waiting for an eligible local IPv4 interface"
                else -> addressText
            },
            httpPort = LocalHttpServer.PORT,
            ssdpStatus = metrics.ssdpStatus,
            ssdpInterface = metrics.ssdpInterface,
            multicastSocketCreated = metrics.multicastSocketCreated,
            multicastGroupJoined = metrics.multicastGroupJoined,
            multicastDetails = metrics.multicastDetails,
            mSearchCount = metrics.mSearchCount.get(),
            lastSsdpRequest = metrics.lastSsdpRequest,
            ssdpResponsesSent = metrics.ssdpResponsesSent.get(),
            httpRequestCount = metrics.httpRequestCount.get(),
            lastHttpRequest = metrics.lastHttpRequest,
            firstMediaHttpExchange = metrics.firstMediaHttpExchangeSnapshot(),
            lastMediaHttpExchange = metrics.lastMediaHttpExchangeSnapshot(),
            mediaPlaybackSummary = metrics.mediaPlaybackSummarySnapshot(),
            mediaHttpHighlights = metrics.mediaHttpHighlightsSnapshot(),
            mediaHttpHistory = metrics.mediaHttpHistorySnapshot(),
            rootDescriptionGetCount = metrics.rootDescriptionGetCount.get(),
            lastRootDescriptionGet = metrics.lastRootDescriptionGet,
            contentDirectoryScpdGetCount = metrics.contentDirectoryScpdGetCount.get(),
            lastContentDirectoryScpdGet = metrics.lastContentDirectoryScpdGet,
            contentDirectoryControlRequestCount = metrics.contentDirectoryControlRequestCount.get(),
            lastContentDirectoryControlRequest = metrics.lastContentDirectoryControlRequest,
            lastContentDirectoryControlHttpStatus = metrics.lastContentDirectoryControlHttpStatus,
            lastContentDirectorySoapAction = metrics.lastContentDirectorySoapAction,
            lastContentDirectorySoapContentType = metrics.lastContentDirectorySoapContentType,
            lastContentDirectorySoapBody = metrics.lastContentDirectorySoapBody,
            lastContentDirectorySoapActionName = metrics.lastContentDirectorySoapActionName,
            lastContentDirectorySoapActionNamespace = metrics.lastContentDirectorySoapActionNamespace,
            lastContentDirectorySoapRecognition = metrics.lastContentDirectorySoapRecognition,
            contentDirectorySoapHistory = metrics.contentDirectorySoapHistorySnapshot(),
            advertisedContentDirectoryServiceType = metrics.advertisedContentDirectoryServiceType,
            advertisedContentDirectoryServiceId = metrics.advertisedContentDirectoryServiceId,
            advertisedContentDirectoryScpdUrl = metrics.advertisedContentDirectoryScpdUrl,
            advertisedContentDirectoryControlUrl = metrics.advertisedContentDirectoryControlUrl,
            advertisedContentDirectoryEventSubUrl = metrics.advertisedContentDirectoryEventSubUrl,
            lastContentDirectoryBrowseRequest = metrics.lastContentDirectoryBrowseRequest,
            lastContentDirectoryBrowseResult = metrics.lastContentDirectoryBrowseResult,
            lastSafEnumeration = metrics.lastSafEnumeration,
            selectedFolder = metrics.selectedFolder,
        )
    }

    override fun close() {
        if (!started.getAndSet(false)) return
        pollJob?.cancel()
        pollJob = null
        runCatching { defaultNetworkCallback?.let(connectivity::unregisterNetworkCallback) }
        runCatching { allNetworksCallback?.let(connectivity::unregisterNetworkCallback) }
        defaultNetworkCallback = null
        allNetworksCallback = null
        runCatching { ssdpServer?.close() }
        ssdpServer = null
        runCatching { httpServer?.close() }
        httpServer = null
        repository = null
        releaseMulticastLock()
        releaseCpuWakeLock()
        scope.cancel()
        metrics.ssdpStatus = "Stopped"
        metrics.multicastSocketCreated = false
        metrics.multicastGroupJoined = false
        metrics.serverStatus = "Stopped"
    }

    private fun registerNetworkCallbacks() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = enqueueRefresh()
            override fun onLost(network: Network) = enqueueRefresh()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: android.net.NetworkCapabilities) = enqueueRefresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = enqueueRefresh()
        }
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), callback)
            allNetworksCallback = callback
        } catch (_: Exception) {
            // OEMs can restrict the all-network callback. Periodic interface enumeration remains.
        }
        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = enqueueRefresh()
            override fun onLost(network: Network) = enqueueRefresh()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: android.net.NetworkCapabilities) = enqueueRefresh()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = enqueueRefresh()
        }
        try {
            connectivity.registerDefaultNetworkCallback(defaultCallback)
            defaultNetworkCallback = defaultCallback
        } catch (_: Exception) {
            // The periodic poll is the fallback for hotspot state changes.
        }
    }

    private fun enqueueRefresh() {
        if (!started.get() || !refreshQueued.compareAndSet(false, true)) return
        scope.launch {
            try {
                refreshNetwork()
            } finally {
                refreshQueued.set(false)
            }
        }
    }

    @Synchronized
    private fun refreshNetwork() {
        if (!started.get()) return
        val detected = detector.detect()
        currentNetwork = detected
        val fingerprint = detected.fingerprint()
        httpServer?.updateAddresses(detected)
        if (fingerprint != networkFingerprint) {
            networkFingerprint = fingerprint
            ssdpServer?.updateInterfaces(detected)
        }
        val listening = httpServer?.listeningAddresses.orEmpty()
        val hasEligibleLocalAddress = detected.eligibleAddresses.isNotEmpty()
        metrics.serverStatus = when {
            !hasEligibleLocalAddress -> "HTTP waiting for a reachable local IPv4 interface"
            listening.isEmpty() -> "Network found, but HTTP could not bind an eligible local address"
            else -> "Running — ${detected.networkLabel}; HTTP :${LocalHttpServer.PORT}"
        }
    }

    private fun acquireCpuWakeLock() {
        try {
            val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "M36MediaServer:streaming").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            // Foreground service operation still continues if this OEM disallows a partial lock.
        }
    }

    private fun releaseCpuWakeLock() {
        runCatching { cpuWakeLock?.takeIf { it.isHeld }?.release() }
        cpuWakeLock = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("M36MediaServer-SSDP").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            // Still attempt to join multicast groups; this flag is advisory on some hotspot builds.
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
        multicastLock = null
    }

    companion object {
        private const val NETWORK_POLL_INTERVAL_MILLIS = 3_000L
    }
}
