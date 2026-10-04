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
    private val mediaHttpHistory = TreeMap<Long, MediaHttpExchange>()
    private val activeMediaTimelines = HashMap<Long, MediaHttpTimeline>()
    private val observedMediaSequences = HashSet<Long>()
    private val finalizedMediaSequences = HashSet<Long>()
    private var firstMediaSequence = Long.MAX_VALUE
    private var lastRecordedMediaSequence = 0L
    private var totalMediaRequests = 0L
    private var finalizedMediaResponses = 0L
    private var completeMediaResponses = 0L
    private var incompleteMediaResponses = 0L
    private var peerDisconnects = 0L
    private var brokenPipeCount = 0L
    private var unexpectedEofCount = 0L
    private var serverIoErrorCount = 0L
    private var safOpenErrorCount = 0L
    private var safSeekErrorCount = 0L
    private var preflightRejectionCount = 0L
    private var rangeRequestCount = 0L
    private var nonZeroRangeRequestCount = 0L
    private var totalSafBytesRead = 0L
    private var totalResponseBytesWritten = 0L
    private var largestRangeExchange: MediaHttpExchange? = null
    private var firstByteExchange: MediaHttpExchange? = null
    private var firstMediaExchange: MediaHttpExchange? = null
    private var lastMediaExchange: MediaHttpExchange? = null

    fun nextMediaHttpSequence(): Long = mediaHttpSequence.incrementAndGet()

    /** Publish a request at arrival so the one-second diagnostics ticker can show it in flight. */
    @Synchronized
    fun beginMediaHttpExchange(exchange: MediaHttpExchange, timeline: MediaHttpTimeline? = null) {
        observeMediaRequest(exchange)
        timeline?.let { activeMediaTimelines[exchange.sequence] = it }
        updateFirstAndLast(exchange)
        storeInHistory(exchange)
    }

    @Synchronized
    fun recordMediaHttpExchange(exchange: MediaHttpExchange) {
        observeMediaRequest(exchange)
        updateFirstAndLast(exchange)
        if (finalizedMediaSequences.add(exchange.sequence)) {
            finalizedMediaResponses++
            if (exchange.complete) completeMediaResponses++ else incompleteMediaResponses++
            totalSafBytesRead = saturatingAdd(totalSafBytesRead, exchange.bytesReadFromSaf)
            totalResponseBytesWritten = saturatingAdd(totalResponseBytesWritten, exchange.bytesWrittenToResponse)
            if (isByteRange(exchange.requestKind)) {
                rangeRequestCount++
                if ((exchange.requestedStartOffset ?: exchange.actualMediaStartOffset ?: 0L) > 0L) {
                    nonZeroRangeRequestCount++
                }
                val currentLargest = largestRangeExchange?.responseContentLength ?: -1L
                val candidate = exchange.responseContentLength ?: -1L
                if (exchange.responseStatus == 206 && candidate > currentLargest) {
                    largestRangeExchange = exchange
                }
            }
            when (exchange.failureKind) {
                "PEER_DISCONNECTED" -> peerDisconnects++
                "UNEXPECTED_EOF" -> unexpectedEofCount++
                "SERVER_IO_ERROR" -> serverIoErrorCount++
                "SAF_OPEN_ERROR" -> safOpenErrorCount++
                "SAF_SEEK_ERROR" -> safSeekErrorCount++
                "PREFLIGHT_REJECTED", "UNSATISFIABLE_RANGE", "INVALID_MEDIA_ID" -> preflightRejectionCount++
            }
            if (exchange.error.orEmpty().contains("broken pipe", ignoreCase = true)) brokenPipeCount++
            if (exchange.timing?.firstMediaByte != null) {
                val currentFirstByteSequence = firstByteExchange?.sequence ?: Long.MAX_VALUE
                if (exchange.sequence < currentFirstByteSequence) firstByteExchange = exchange
            }
        }
        storeInHistory(exchange)
        activeMediaTimelines.remove(exchange.sequence)
    }

    @Synchronized
    fun firstMediaHttpExchangeSnapshot(): String = firstMediaExchange?.let(::liveExchange)?.format() ?: "—"

    @Synchronized
    fun lastMediaHttpExchangeSnapshot(): String = lastMediaExchange?.let(::liveExchange)?.format() ?: "—"

    @Synchronized
    fun mediaHttpHistorySnapshot(): List<String> = mediaHttpHistory.values
        .map(::liveExchange)
        .map(MediaHttpExchange::format)

    @Synchronized
    fun mediaHttpHighlightsSnapshot(): List<String> = mediaHttpHistory.values
        .map(::liveExchange)
        .filter(::isUsefulHighlight)
        .takeLast(MAX_MEDIA_HTTP_HIGHLIGHTS)
        .map(MediaHttpExchange::format)

    @Synchronized
    fun mediaPlaybackSummarySnapshot(): String {
        val first = firstMediaExchange?.let(::liveExchange)
        val latest = lastMediaExchange?.let(::liveExchange)
        if (totalMediaRequests == 0L || first == null || latest == null) return "No media requests yet"

        val retained = mediaHttpHistory.values.map(::liveExchange)
        val measuredRates = retained.mapNotNull { exchange ->
            val timing = exchange.timing ?: return@mapNotNull null
            val span = timing.durationMillis(timing.firstMediaByte, timing.lastMediaByte)
                ?.takeIf { it > 0 } ?: return@mapNotNull null
            if (exchange.bytesWrittenToResponse <= 0) return@mapNotNull null
            exchange.bytesWrittenToResponse.toDouble() * 1_000.0 / span
        }
        val averageBytesPerSecond = measuredRates.takeIf { it.isNotEmpty() }?.average()
        val openDurations = retained.mapNotNull { exchange ->
            exchange.timing?.durationMillis(exchange.timing.safOpenStarted, exchange.timing.safOpenCompleted)
        }
        val seekDurations = retained.mapNotNull { exchange ->
            exchange.timing?.durationMillis(exchange.timing.safSeekStarted, exchange.timing.safSeekCompleted)
        }
        val activeCount = (totalMediaRequests - finalizedMediaResponses).coerceAtLeast(0L)
        val activeReadBytes = retained.filter { it.inProgress }.sumOf { it.bytesReadFromSaf }
        val activeWrittenBytes = retained.filter { it.inProgress }.sumOf { it.bytesWrittenToResponse }
        val largestRange = largestRangeExchange
        val firstByte = listOfNotNull(
            firstByteExchange?.let(::liveExchange),
            retained.filter { it.timing?.firstMediaByte != null }.minByOrNull { it.sequence },
        ).minByOrNull { it.sequence }
        val subtitleEvidence = subtitleEvidence(retained, lastContentDirectoryBrowseResult)

        return buildString {
            appendLine("Media/client: ${latest.mediaTitle ?: "(unknown title)"} | ${latest.clientIp}")
            appendLine("User-Agent: ${latest.userAgentHeader?.substringAfter(':')?.trim() ?: "(not present)"}")
            appendLine(
                "Connection: request=${latest.requestConnectionHeaders.joinToString(" | ").ifBlank { "(not supplied)" }}; " +
                    "server response=close by design; one request per server socket",
            )
            appendLine("Requests: $totalMediaRequests total; $finalizedMediaResponses finalized; $activeCount active")
            appendLine("First request: ${formatRequestTime(first)}")
            appendLine("Last request: ${formatRequestTime(latest)}")
            if (firstByte != null) {
                val timing = firstByte.timing
                val firstRequestMark = first.timing?.requestReceived
                val startupMillis = if (firstRequestMark != null && timing?.firstMediaByte != null) {
                    nonNegativeMillis(timing.firstMediaByte.monotonicNanos - firstRequestMark.monotonicNanos)
                } else null
                val ttfbMillis = timing?.durationMillis(timing.requestReceived, timing.firstMediaByte)
                append("First media byte: ${timing?.firstMediaByte?.formatLocal() ?: "(unknown time)"}")
                startupMillis?.let { append("; ${it}ms after first request") }
                ttfbMillis?.let { append("; request TTFB=${it}ms") }
                appendLine()
            } else {
                appendLine("First media byte: not observed yet")
            }
            appendLine(
                "Throughput: " + (averageBytesPerSecond?.let(::formatBytesPerSecond)
                    ?: "not measurable yet") +
                    " (mean of retained requests with first/last media-byte timestamps)",
            )
            appendLine(
                "Ranges: $rangeRequestCount; non-zero start offsets (seek/reopen indications): $nonZeroRangeRequestCount",
            )
            appendLine(
                "Responses: complete=$completeMediaResponses; incomplete=$incompleteMediaResponses; " +
                    "peer disconnects=$peerDisconnects; Broken pipe=$brokenPipeCount; " +
                    "unexpected EOF=$unexpectedEofCount; server I/O errors=$serverIoErrorCount; " +
                    "SAF open errors=$safOpenErrorCount; SAF seek errors=$safSeekErrorCount; " +
                    "preflight/range rejections=$preflightRejectionCount",
            )
            if (largestRange != null) {
                appendLine(
                    "Largest completed range: ${largestRange.responseContentLength} bytes; " +
                        "${largestRange.responseContentRange ?: "offset ${largestRange.requestedStartOffset ?: "?"}"}",
                )
            } else {
                appendLine("Largest completed range: not observed yet")
            }
            appendLine("SAF open timing (retained measurable requests): ${formatDurationStats(openDurations)}")
            appendLine("SAF seek timing (retained measurable requests): ${formatDurationStats(seekDurations)}")
            appendLine("Subtitle evidence: $subtitleEvidence")
            append(
                "Bytes read from SAF: finalized=$totalSafBytesRead; active=$activeReadBytes; " +
                    "bytes accepted by server response output: finalized=$totalResponseBytesWritten; " +
                    "active=$activeWrittenBytes",
            )
        }.trimEnd()
    }

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

    private fun liveExchange(exchange: MediaHttpExchange): MediaHttpExchange =
        activeMediaTimelines[exchange.sequence]?.let { timeline ->
            exchange.copy(
                timing = timeline.snapshot(),
                bytesReadFromSaf = timeline.bytesReadFromSaf,
                bytesWrittenToResponse = timeline.bytesWrittenToResponse,
            )
        } ?: exchange

    private fun observeMediaRequest(exchange: MediaHttpExchange) {
        if (observedMediaSequences.add(exchange.sequence)) totalMediaRequests++
    }

    private fun updateFirstAndLast(exchange: MediaHttpExchange) {
        if (exchange.sequence < firstMediaSequence) {
            firstMediaSequence = exchange.sequence
            firstMediaExchange = exchange
            firstMediaHttpExchange = exchange.format()
        } else if (exchange.sequence == firstMediaSequence) {
            firstMediaExchange = exchange
            firstMediaHttpExchange = exchange.format()
        }
        if (exchange.sequence >= lastRecordedMediaSequence) {
            lastRecordedMediaSequence = exchange.sequence
            lastMediaExchange = exchange
            lastMediaHttpExchange = exchange.format()
        }
    }

    private fun storeInHistory(exchange: MediaHttpExchange) {
        val firstSequenceToKeep = (mediaHttpSequence.get() - MAX_MEDIA_HTTP_HISTORY + 1).coerceAtLeast(1)
        if (exchange.sequence >= firstSequenceToKeep) {
            mediaHttpHistory[exchange.sequence] = exchange
            while (mediaHttpHistory.size > MAX_MEDIA_HTTP_HISTORY) mediaHttpHistory.pollFirstEntry()
        }
    }

    private fun isUsefulHighlight(exchange: MediaHttpExchange): Boolean =
        exchange.inProgress ||
            isByteRange(exchange.requestKind) ||
            (exchange.requestedStartOffset ?: 0L) > 0L ||
            !exchange.complete ||
            exchange.error != null ||
            exchange.failureKind != null ||
            exchange.responseStatus >= 400

    private fun isByteRange(kind: String): Boolean = kind in setOf(
        "OPEN_ENDED_RANGE",
        "BOUNDED_RANGE",
        "SUFFIX_RANGE",
    )

    private fun formatRequestTime(exchange: MediaHttpExchange): String =
        exchange.timing?.requestReceived?.formatLocal() ?: "(not recorded)"

    private fun formatDurationStats(durations: List<Long>): String = when {
        durations.isEmpty() -> "not measured"
        else -> "avg=${durations.average().toLong()}ms, max=${durations.maxOrNull()}ms (n=${durations.size})"
    }

    private fun subtitleEvidence(exchanges: List<MediaHttpExchange>, browseResult: String): String {
        val subtitleRequests = exchanges.filter { isSubtitleResource(it.mediaTitle) }
        if (subtitleRequests.isNotEmpty()) {
            val titles = subtitleRequests.mapNotNull { it.mediaTitle }.distinct().take(3).joinToString()
            return "separate subtitle-file HTTP request observed in retained history for $titles; this is distinct from embedded-track access"
        }
        if (browseResult == "—" || browseResult.isBlank()) {
            return "no separate subtitle-file request in retained history; no Browse/DIDL subtitle evidence available yet"
        }
        val didlItems = Regex("(?m)^\\s*Exact DIDL item: (.+)$")
            .findAll(browseResult)
            .map { it.groupValues[1] }
            .toList()
        val subtitleItems = didlItems.filter { item ->
            isSubtitleResource(item) || item.contains("text/vtt", ignoreCase = true) ||
                item.contains("application/ttml", ignoreCase = true)
        }
        if (subtitleItems.isNotEmpty()) {
            return "Browse DIDL includes a separate subtitle-like file resource, but no request for it was observed in retained history"
        }
        val hasMatroskaResource = didlItems.any {
            it.contains("video/x-matroska", ignoreCase = true) ||
                Regex("(?i)\\.mkv(?:[?#><\\\" ]|$)").containsMatchIn(it)
        }
        return if (hasMatroskaResource) {
            "no separate subtitle URL/request observed in retained history; emitted DIDL exposes the Matroska resource, not embedded track metadata, so embedded subtitle access is unverified"
        } else {
            "no separate subtitle URL/request observed in retained history; latest Browse DIDL has no subtitle-like item, and embedded-track access is not visible at the HTTP/DIDL layer"
        }
    }

    private fun isSubtitleResource(titleOrItem: String?): Boolean {
        val value = titleOrItem?.lowercase() ?: return false
        return SUBTITLE_EXTENSIONS.any { extension ->
            Regex("\\.${Regex.escape(extension)}(?:[?#><\\\" ]|$)").containsMatchIn(value)
        }
    }

    private fun formatBytesPerSecond(bytesPerSecond: Double): String = String.format(
        java.util.Locale.US,
        "%.2f MiB/s",
        bytesPerSecond / (1024.0 * 1024.0),
    )

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0 && Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun nonNegativeMillis(nanos: Long): Long = nanos.coerceAtLeast(0L) / NANOS_PER_MILLISECOND

    private companion object {
        const val MAX_DIAGNOSTIC_HISTORY = 8
        const val MAX_DIAGNOSTIC_BODY_CHARS = 2_048
        const val MAX_MEDIA_HTTP_HISTORY = 64
        const val MAX_MEDIA_HTTP_HIGHLIGHTS = 16
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "ttml")
    }
}
