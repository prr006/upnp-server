package com.m36.mediaserver.data

import java.util.ArrayDeque
import java.util.TreeMap
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
    @Volatile var firstMediaHttpExchange: String = "—"
    @Volatile var lastMediaHttpExchange: String = "—"
    @Volatile var lastRootDescriptionGet: String = "—"
    @Volatile var lastContentDirectoryScpdGet: String = "—"
    @Volatile var lastContentDirectoryControlRequest: String = "—"
    @Volatile var lastContentDirectoryControlHttpStatus: String = "—"
    @Volatile var lastContentDirectorySoapAction: String = "—"
    @Volatile var lastContentDirectorySoapContentType: String = "—"
    @Volatile var lastContentDirectorySoapBody: String = "—"
    @Volatile var lastContentDirectorySoapActionName: String = "—"
    @Volatile var lastContentDirectorySoapActionNamespace: String = "—"
    @Volatile var lastContentDirectorySoapRecognition: String = "—"
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

    private val contentDirectorySoapHistory = ArrayDeque<String>()
    private val mediaHttpSequence = AtomicLong(0)
    private val mediaHttpHistory = TreeMap<Long, String>()
    private var firstMediaSequence = Long.MAX_VALUE
    private var lastRecordedMediaSequence = 0L

    fun nextMediaHttpSequence(): Long = mediaHttpSequence.incrementAndGet()

    @Synchronized
    fun recordMediaHttpExchange(exchange: MediaHttpExchange) {
        val transaction = exchange.format()
        if (exchange.sequence < firstMediaSequence) {
            firstMediaSequence = exchange.sequence
            firstMediaHttpExchange = transaction
        }
        if (exchange.sequence >= lastRecordedMediaSequence) {
            lastRecordedMediaSequence = exchange.sequence
            lastMediaHttpExchange = transaction
        }

        // Keep the newest requests by arrival order rather than response completion order. A slow
        // transfer can finish after later seeks; it must not make the visible playback sequence lie.
        val firstSequenceToKeep = (mediaHttpSequence.get() - MAX_MEDIA_HTTP_HISTORY + 1).coerceAtLeast(1)
        if (exchange.sequence >= firstSequenceToKeep) {
            mediaHttpHistory[exchange.sequence] = transaction
            while (mediaHttpHistory.size > MAX_MEDIA_HTTP_HISTORY) {
                mediaHttpHistory.pollFirstEntry()
            }
        }
    }

    @Synchronized
    fun mediaHttpHistorySnapshot(): List<String> = mediaHttpHistory.values.toList()

    @Synchronized
    fun recordContentDirectorySoapTransaction(
        request: String,
        responseStatus: String,
        soapAction: String?,
        contentType: String?,
        body: String,
        actionName: String?,
        actionNamespace: String?,
        recognition: String,
    ) {
        val displayedBody = body.ifBlank { "(empty)" }.take(MAX_DIAGNOSTIC_BODY_CHARS).let { preview ->
            if (body.length > MAX_DIAGNOSTIC_BODY_CHARS) "$preview\n… [body truncated]" else preview
        }
        val displayedAction = actionName?.takeIf { it.isNotBlank() } ?: "(not parsed)"
        val displayedNamespace = actionNamespace?.takeIf { it.isNotBlank() } ?: "(none)"
        val displayedSoapAction = soapAction?.takeIf { it.isNotBlank() } ?: "(missing)"
        val displayedContentType = contentType?.takeIf { it.isNotBlank() } ?: "(missing)"

        lastContentDirectoryControlHttpStatus = responseStatus
        lastContentDirectorySoapAction = displayedSoapAction
        lastContentDirectorySoapContentType = displayedContentType
        lastContentDirectorySoapBody = displayedBody
        lastContentDirectorySoapActionName = displayedAction
        lastContentDirectorySoapActionNamespace = displayedNamespace
        lastContentDirectorySoapRecognition = recognition

        val transaction = buildString {
            appendLine(request)
            appendLine("HTTP response: $responseStatus")
            appendLine("SOAPAction: $displayedSoapAction")
            appendLine("Content-Type: $displayedContentType")
            appendLine("SOAP action: $displayedAction")
            appendLine("SOAP action namespace: $displayedNamespace")
            appendLine("Recognized as: $recognition")
            appendLine("SOAP body:")
            append(displayedBody)
        }
        if (contentDirectorySoapHistory.size >= MAX_DIAGNOSTIC_HISTORY) {
            contentDirectorySoapHistory.removeFirst()
        }
        contentDirectorySoapHistory.addLast(transaction)
    }

    @Synchronized
    fun contentDirectorySoapHistorySnapshot(): List<String> = contentDirectorySoapHistory.toList()

    private companion object {
        const val MAX_DIAGNOSTIC_HISTORY = 8
        const val MAX_DIAGNOSTIC_BODY_CHARS = 2_048
        const val MAX_MEDIA_HTTP_HISTORY = 64
    }
}
