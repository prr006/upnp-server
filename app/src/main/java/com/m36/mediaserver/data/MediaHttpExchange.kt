package com.m36.mediaserver.data

/** A single request/response observation from the media endpoint. */
data class MediaHttpExchange(
    val sequence: Long,
    val clientIp: String,
    val method: String,
    val requestTarget: String,
    val resourcePath: String,
    val mediaId: String,
    val objectId: String?,
    val mediaTitle: String?,
    val userAgentHeader: String?,
    val rangeHeader: String?,
    val requestConnectionHeaders: List<String>,
    val requestTransferEncodingHeaders: List<String>,
    val requestKind: String,
    val requestedStartOffset: Long?,
    val requestedEndOffset: Long?,
    val requestedSuffixLength: Long?,
    val responseStatus: Int,
    val responseReason: String,
    val responseContentType: String?,
    val responseContentLength: Long?,
    val responseContentRange: String?,
    val responseAcceptRanges: String?,
    val responseConnection: String?,
    val responseTransferEncoding: String?,
    val effectiveMediaLength: Long?,
    val safOpenedFileSize: Long?,
    val safAssetFileDescriptorLength: Long?,
    val safDescriptorStatSize: Long?,
    val didlRequestedSize: Long?,
    val actualMediaStartOffset: Long?,
    val actualMediaEndOffsetInclusive: Long?,
    val actualSourceStartOffset: Long?,
    val actualSourceEndOffsetInclusive: Long?,
    val bytesReadFromSaf: Long,
    val bytesWrittenToResponse: Long,
    val complete: Boolean,
    val eofReached: Boolean,
    val prematureEof: Boolean,
    val error: String?,
    val timing: MediaHttpTimingSnapshot? = null,
    val failureKind: String? = null,
    val failureStage: String? = null,
) {
    val inProgress: Boolean get() = timing?.completed == null && responseStatus == 0

    fun format(): String = buildString {
        appendLine("REQUEST $sequence${if (inProgress) " — IN PROGRESS" else ""}")
        appendLine("$method $resourcePath")
        if (requestTarget != resourcePath) appendLine("Request target: $requestTarget")
        appendLine("Client IP: $clientIp")
        appendLine("ObjectID: ${objectId ?: "(unknown)"}; media ID: $mediaId")
        appendLine("Media title: ${mediaTitle ?: "(unknown)"}")
        appendLine("User-Agent (raw): ${userAgentHeader ?: "(not present)"}")
        appendLine("Range (raw): ${rangeHeader ?: "(not present)"}")
        appendLine("Request Connection (raw): ${requestConnectionHeaders.joinToString("\n  ").ifEmpty { "(not present)" }}")
        appendLine("Request Transfer-Encoding (raw): ${requestTransferEncodingHeaders.joinToString("\n  ").ifEmpty { "(not present)" }}")
        appendLine("Request type: $requestKind")
        appendLine("Requested start offset: ${requestedStartOffset?.toString() ?: "(not specified)"}")
        appendLine("Requested end offset: ${requestedEndOffset?.toString() ?: if (requestKind == "OPEN_ENDED_RANGE") "(open-ended)" else "(not specified)"}")
        if (requestedSuffixLength != null) appendLine("Requested suffix length: $requestedSuffixLength")
        if (responseStatus > 0) {
            appendLine("-> $responseStatus $responseReason")
        } else {
            appendLine("-> (response not finished)")
        }
        appendLine("Content-Type: ${responseContentType ?: "(not set)"}")
        appendLine("Content-Length: ${responseContentLength?.toString() ?: "(not set)"}")
        appendLine("Content-Range: ${responseContentRange ?: "(not set)"}")
        appendLine("Accept-Ranges: ${responseAcceptRanges ?: "(not set)"}")
        appendLine("Response Connection: ${responseConnection ?: "(not set)"}")
        appendLine("Response Transfer-Encoding: ${responseTransferEncoding ?: "(not set)"}")
        appendLine("Effective media length: ${effectiveMediaLength?.toString() ?: "(unknown)"}")
        appendLine("SAF opened media size: ${safOpenedFileSize?.toString() ?: "(unknown)"}")
        appendLine("SAF AssetFileDescriptor length: ${safAssetFileDescriptorLength?.toString() ?: "(unknown)"}")
        appendLine("SAF descriptor stat size: ${safDescriptorStatSize?.toString() ?: "(unknown)"}")
        appendLine("DIDL requested size: ${didlRequestedSize?.toString() ?: "(unknown)"}")
        val sizeCheck = when {
            safOpenedFileSize == null || didlRequestedSize == null -> "cannot compare (one size unknown)"
            safOpenedFileSize == didlRequestedSize -> "MATCH"
            else -> "MISMATCH"
        }
        appendLine("SAF opened size vs DIDL size: $sizeCheck")
        appendLine("Actual media stream offsets (inclusive): ${formatOffsets(actualMediaStartOffset, actualMediaEndOffsetInclusive)}")
        appendLine("Actual underlying source offsets (inclusive): ${formatOffsets(actualSourceStartOffset, actualSourceEndOffsetInclusive)}")
        appendLine("Write-confirmed media offsets (inclusive): ${formatWrittenOffsets(actualMediaStartOffset, bytesWrittenToResponse)}")
        appendLine("Write-confirmed source offsets (inclusive): ${formatWrittenOffsets(actualSourceStartOffset, bytesWrittenToResponse)}")
        appendLine("Bytes read from SAF: $bytesReadFromSaf")
        appendLine("Bytes written to response: $bytesWrittenToResponse")
        appendTiming(timing)
        appendLine("EOF reached: $eofReached")
        appendLine("Premature EOF: $prematureEof")
        appendLine("HTTP response entity complete: ${if (complete) "YES" else "NO"}")
        if (failureKind != null || failureStage != null) {
            appendLine("Failure classification: ${failureKind ?: "(unclassified)"}${failureStage?.let { " at $it" }.orEmpty()}")
        }
        error?.takeIf { it.isNotBlank() }?.let { appendLine("Error detail: $it") }
    }.trimEnd()

    private fun StringBuilder.appendTiming(timing: MediaHttpTimingSnapshot?) {
        if (timing == null) {
            appendLine("Timing: (not captured)")
            return
        }
        appendLine("Request received: ${timing.requestReceived.formatLocal()}")
        if (inProgress) {
            val ageMillis = (System.nanoTime() - timing.requestReceived.monotonicNanos)
                .coerceAtLeast(0L) / 1_000_000L
            appendLine("Current request age: ${ageMillis}ms (still in progress)")
        }
        appendEvent("SAF open started", timing.safOpenStarted, timing)
        appendEvent("SAF open completed", timing.safOpenCompleted, timing)
        timing.durationMillis(timing.safOpenStarted, timing.safOpenCompleted)?.let {
            appendLine("SAF open duration: ${it}ms")
        }
        appendEvent("SAF seek started", timing.safSeekStarted, timing)
        appendEvent("SAF seek completed", timing.safSeekCompleted, timing)
        timing.durationMillis(timing.safSeekStarted, timing.safSeekCompleted)?.let {
            appendLine("SAF seek duration: ${it}ms")
        }
        appendEvent("Response headers sent", timing.headersSent, timing)
        appendEvent("First media byte", timing.firstMediaByte, timing)
        timing.elapsedMillis(timing.firstMediaByte)?.let { appendLine("Time to first media byte: ${it}ms from request") }
        appendEvent("Last media byte", timing.lastMediaByte, timing)
        val payloadDuration = timing.durationMillis(timing.firstMediaByte, timing.lastMediaByte)
        if (payloadDuration != null) {
            appendLine("First-to-last media byte span: ${payloadDuration}ms")
            formatBytesPerSecond(bytesWrittenToResponse, payloadDuration)?.let {
                appendLine("Media write rate: $it (response bytes / first-to-last-byte span)")
            }
        }
        appendEvent("Peer disconnect/cancellation", timing.clientDisconnected, timing)
        appendEvent("Server completion", timing.completed, timing)
        timing.totalDurationMillis()?.let { appendLine("Total request duration: ${it}ms") }
    }

    private fun StringBuilder.appendEvent(
        label: String,
        instant: MediaHttpInstant?,
        timing: MediaHttpTimingSnapshot,
    ) {
        if (instant == null) return
        val elapsed = timing.elapsedMillis(instant)
        appendLine("$label: ${instant.formatLocal()}${elapsed?.let { " (T+${it}ms)" }.orEmpty()}")
    }

    private fun formatOffsets(start: Long?, endInclusive: Long?): String = when {
        start == null -> "(not reached)"
        endInclusive == null -> "$start .. (no bytes read)"
        else -> "$start .. $endInclusive"
    }

    private fun formatWrittenOffsets(start: Long?, byteCount: Long): String = when {
        start == null -> "(not reached)"
        byteCount <= 0 -> "$start .. (no bytes written)"
        start < 0 || Long.MAX_VALUE - start < byteCount - 1 -> "$start .. (end offset overflow)"
        else -> "$start .. ${start + byteCount - 1}"
    }

    private fun formatBytesPerSecond(bytes: Long, durationMillis: Long): String? {
        if (bytes <= 0 || durationMillis <= 0) return null
        val bytesPerSecond = bytes * 1_000.0 / durationMillis
        return String.format(java.util.Locale.US, "%.2f MiB/s", bytesPerSecond / (1024.0 * 1024.0))
    }
}
