package com.m36.mediaserver.data

import java.util.concurrent.atomic.AtomicLong

class ServerMetrics {
    val mSearchCount = AtomicLong(0)
    val ssdpResponsesSent = AtomicLong(0)
    val httpRequestCount = AtomicLong(0)
    val rootDescriptionGetCount = AtomicLong(0)
    val contentDirectoryScpdGetCount = AtomicLong(0)
    val contentDirectoryControlRequestCount = AtomicLong(0)

    @Volatile var lastSsdpRequest: String = "—"
    @Volatile var lastHttpRequest: String = "—"
    @Volatile var lastRootDescriptionGet: String = "—"
    @Volatile var lastContentDirectoryScpdGet: String = "—"
    @Volatile var lastContentDirectoryControlRequest: String = "—"
    @Volatile var advertisedContentDirectoryServiceType: String = "—"
    @Volatile var advertisedContentDirectoryServiceId: String = "—"
    @Volatile var advertisedContentDirectoryScpdUrl: String = "—"
    @Volatile var advertisedContentDirectoryControlUrl: String = "—"
    @Volatile var advertisedContentDirectoryEventSubUrl: String = "—"
    @Volatile var lastContentDirectoryBrowseRequest: String = "—"
    @Volatile var lastContentDirectoryBrowseResult: String = "—"
    @Volatile var lastSafEnumeration: String = "—"
    @Volatile var ssdpStatus: String = "Stopped"
    @Volatile var multicastDetails: String = "—"
    @Volatile var multicastSocketCreated: Boolean = false
    @Volatile var multicastGroupJoined: Boolean = false
    @Volatile var ssdpInterface: String = "—"
    @Volatile var selectedFolder: String = "Not selected"
    @Volatile var serverStatus: String = "Stopped"
}
