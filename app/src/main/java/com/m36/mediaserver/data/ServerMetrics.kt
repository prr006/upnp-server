package com.m36.mediaserver.data

import java.util.concurrent.atomic.AtomicLong

class ServerMetrics {
    val mSearchCount = AtomicLong(0)
    val ssdpResponsesSent = AtomicLong(0)
    val httpRequestCount = AtomicLong(0)

    @Volatile var lastSsdpRequest: String = "—"
    @Volatile var lastHttpRequest: String = "—"
    @Volatile var ssdpStatus: String = "Stopped"
    @Volatile var multicastDetails: String = "—"
    @Volatile var multicastSocketCreated: Boolean = false
    @Volatile var multicastGroupJoined: Boolean = false
    @Volatile var ssdpInterface: String = "—"
    @Volatile var selectedFolder: String = "Not selected"
    @Volatile var serverStatus: String = "Stopped"
}
