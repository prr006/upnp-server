package com.m36.mediaserver.data

import java.util.ArrayDeque
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
    private val mediaHttpHistory = ArrayDeque<String>()

    @Synchronized
    fun recordMediaHttpExchange(
        clientAddress: String,
        resourcePath: String,
        resourceId: String,
        resourceName: String?,
        method: String,
        rangeHeader: String?,
        responseStatus: Int,
        responseReason: String,
        contentType: String?,
        contentLength: Long?,
        contentRange: String?,
        acceptRanges: String?,
        byteOffset: Long?,
        sourceByteOffset: Long?,
        bytesServed: Long,
        complete: Boolean,
        detail: String?,
    ) {
        val transaction = buildString {
            appendLine("$method $resourcePath (resource ID=$resourceId)")
            appendLine("Client address: $clientAddress")
            appendLine("Media title: ${resourceName ?: "(unknown)"}")
            appendLine("Range: ${rangeHeader?.takeIf { it.isNotBlank() } ?: "(none)"}")
            appendLine("Response: $responseStatus $responseReason")
            appendLine("Content-Type: ${contentType ?: "(not set)"}")
            appendLine("Content-Length: ${contentLength?.toString() ?: "(not set)"}")
            appendLine("Content-Range: ${contentRange ?: "(not set)"}")
            appendLine("Accept-Ranges: ${acceptRanges ?: "(not set)"}")
            appendLine("Actual media byte offset: ${byteOffset?.toString() ?: "(not reached/unknown)"}")
            appendLine("Actual underlying source byte offset: ${sourceByteOffset?.toString() ?: "(not reached/unknown)"}")
            appendLine("Media bytes served: $bytesServed")
            appendLine("Transfer complete: $complete")
            detail?.takeIf { it.isNotBlank() }?.let { appendLine("Transfer detail: $it") }
        }.trimEnd()
        lastMediaHttpExchange = transaction
        if (mediaHttpHistory.size >= MAX_MEDIA_HTTP_HISTORY) mediaHttpHistory.removeFirst()
        mediaHttpHistory.addLast(transaction)
    }

    @Synchronized
    fun mediaHttpHistorySnapshot(): List<String> = mediaHttpHistory.toList()

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
        const val MAX_MEDIA_HTTP_HISTORY = 8
    }
}
