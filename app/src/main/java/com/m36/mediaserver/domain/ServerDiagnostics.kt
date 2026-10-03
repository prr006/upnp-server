package com.m36.mediaserver.domain

import com.m36.mediaserver.ssdp.SsdpServer

/** Immutable, UI-safe snapshot of the server's live state. */
data class ServerDiagnostics(
    val running: Boolean = false,
    val serverStatus: String = "Stopped",
    val currentNetwork: String = "Not connected",
    val activeInterface: String = "—",
    val activeServerIpv4: String = "—",
    val activeTransport: String = "None",
    val defaultTransport: String = "Unknown",
    val ipv4Addresses: List<String> = emptyList(),
    val httpBindAddress: String = "—",
    val httpPort: Int = 8200,
    val ssdpStatus: String = "Stopped",
    val ssdpInterface: String = "—",
    val multicastAddress: String = "${SsdpServer.GROUP_ADDRESS}:${SsdpServer.PORT}",
    val multicastSocketCreated: Boolean = false,
    val multicastGroupJoined: Boolean = false,
    val multicastDetails: String = "—",
    val mSearchCount: Long = 0,
    val lastSsdpRequest: String = "—",
    val ssdpResponsesSent: Long = 0,
    val httpRequestCount: Long = 0,
    val lastHttpRequest: String = "—",
    val lastContentDirectoryBrowseRequest: String = "—",
    val lastContentDirectoryBrowseResult: String = "—",
    val lastSafEnumeration: String = "—",
    val selectedFolder: String = "Not selected",
)
