package com.m36.mediaserver.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMetricsTest {
    @Test
    fun mediaPlaybackDiagnosisPreservesArrivalOrderAndCapturesExactRequestAndResponseFields() {
        val metrics = ServerMetrics()
        val firstSequence = metrics.nextMediaHttpSequence()
        val secondSequence = metrics.nextMediaHttpSequence()

        // Complete the later request first to prove the diagnosis is ordered by request arrival,
        // not by which stream happened to finish first.
        metrics.recordMediaHttpExchange(exchange(secondSequence, "GET", "OPEN_ENDED_RANGE", 90, null))
        metrics.recordMediaHttpExchange(exchange(firstSequence, "HEAD", "HEAD", null, null))

        val sequence = metrics.mediaHttpHistorySnapshot()
        assertEquals(2, sequence.size)
        assertTrue(sequence[0].startsWith("REQUEST 1\nHEAD /media/mkv-token"))
        assertTrue(sequence[1].startsWith("REQUEST 2\nGET /media/mkv-token"))
        assertTrue(metrics.firstMediaHttpExchange.startsWith("REQUEST 1\n"))
        assertTrue(metrics.lastMediaHttpExchange.startsWith("REQUEST 2\n"))

        val latest = sequence[1]
        assertTrue(latest.contains("Client IP: 192.168.43.50"))
        assertTrue(latest.contains("ObjectID: i:mkv-token; media ID: mkv-token"))
        assertTrue(latest.contains("User-Agent (raw): User-Agent: VLC/3.0.21 LibVLC/3.0.21"))
        assertTrue(latest.contains("Range (raw): Range: bytes=90- "))
        assertTrue(latest.contains("Request Connection (raw): Connection: keep-alive"))
        assertTrue(latest.contains("Request Transfer-Encoding (raw): (not present)"))
        assertTrue(latest.contains("Request type: OPEN_ENDED_RANGE"))
        assertTrue(latest.contains("Requested start offset: 90"))
        assertTrue(latest.contains("Requested end offset: (open-ended)"))
        assertTrue(latest.contains("-> 206 Partial Content"))
        assertTrue(latest.contains("Content-Type: video/x-matroska"))
        assertTrue(latest.contains("Content-Length: 10"))
        assertTrue(latest.contains("Content-Range: bytes 90-99/100"))
        assertTrue(latest.contains("Accept-Ranges: bytes"))
        assertTrue(latest.contains("Response Connection: close"))
        assertTrue(latest.contains("Response Transfer-Encoding: (not set)"))
        assertTrue(latest.contains("SAF opened media size: 100"))
        assertTrue(latest.contains("DIDL requested size: 100"))
        assertTrue(latest.contains("SAF opened size vs DIDL size: MATCH"))
        assertTrue(latest.contains("Actual media stream offsets (inclusive): 90 .. 99"))
        assertTrue(latest.contains("Actual underlying source offsets (inclusive): 95 .. 104"))
        assertTrue(latest.contains("Response-confirmed media offsets (inclusive): 90 .. 99"))
        assertTrue(latest.contains("Response-confirmed source offsets (inclusive): 95 .. 104"))
        assertTrue(latest.contains("Bytes read from SAF: 10"))
        assertTrue(latest.contains("Bytes written to response: 10"))
        assertTrue(latest.contains("EOF reached: false"))
        assertTrue(latest.contains("Premature EOF: false"))
        assertTrue(latest.contains("Complete: YES"))
    }

    @Test
    fun mediaHistoryKeepsFirstRequestAndMostRecent64Requests() {
        val metrics = ServerMetrics()
        repeat(70) { index ->
            val sequence = metrics.nextMediaHttpSequence()
            metrics.recordMediaHttpExchange(
                exchange(sequence, "GET", "FULL_GET", null, null).copy(
                    requestTarget = "/media/token-$index",
                    resourcePath = "/media/token-$index",
                    mediaId = "token-$index",
                ),
            )
        }

        val history = metrics.mediaHttpHistorySnapshot()
        assertEquals(64, history.size)
        assertTrue(metrics.firstMediaHttpExchange.startsWith("REQUEST 1\n"))
        assertTrue(history.first().startsWith("REQUEST 7\n"))
        assertTrue(history.last().startsWith("REQUEST 70\n"))
    }

    private fun exchange(
        sequence: Long,
        method: String,
        requestKind: String,
        requestedStart: Long?,
        requestedEnd: Long?,
    ) = MediaHttpExchange(
        sequence = sequence,
        clientIp = "192.168.43.50",
        method = method,
        requestTarget = "/media/mkv-token",
        resourcePath = "/media/mkv-token",
        mediaId = "mkv-token",
        objectId = "i:mkv-token",
        mediaTitle = "Sakamoto Days E13.mkv",
        userAgentHeader = "User-Agent: VLC/3.0.21 LibVLC/3.0.21",
        rangeHeader = if (requestKind == "OPEN_ENDED_RANGE") "Range: bytes=90- " else null,
        requestConnectionHeaders = listOf("Connection: keep-alive"),
        requestTransferEncodingHeaders = emptyList(),
        requestKind = requestKind,
        requestedStartOffset = requestedStart,
        requestedEndOffset = requestedEnd,
        requestedSuffixLength = null,
        responseStatus = if (method == "HEAD" || requestKind == "FULL_GET") 200 else 206,
        responseReason = if (method == "HEAD" || requestKind == "FULL_GET") "OK" else "Partial Content",
        responseContentType = "video/x-matroska",
        responseContentLength = if (requestKind == "OPEN_ENDED_RANGE") 10 else 100,
        responseContentRange = when {
            method == "HEAD" || requestKind == "FULL_GET" -> null
            requestKind == "OPEN_ENDED_RANGE" -> "bytes 90-99/100"
            else -> null
        },
        responseAcceptRanges = "bytes",
        responseConnection = "close",
        responseTransferEncoding = null,
        effectiveMediaLength = 100,
        safOpenedFileSize = 100,
        safAssetFileDescriptorLength = 100,
        safDescriptorStatSize = 105,
        didlRequestedSize = 100,
        actualMediaStartOffset = if (requestKind == "OPEN_ENDED_RANGE") 90 else 0,
        actualMediaEndOffsetInclusive = when {
            method == "HEAD" -> null
            requestKind == "OPEN_ENDED_RANGE" -> 99
            else -> 99
        },
        actualSourceStartOffset = if (requestKind == "OPEN_ENDED_RANGE") 95 else 5,
        actualSourceEndOffsetInclusive = if (method == "HEAD") null else 104,
        bytesReadFromSaf = when {
            method == "HEAD" -> 0
            requestKind == "OPEN_ENDED_RANGE" -> 10
            else -> 100
        },
        bytesWrittenToResponse = when {
            method == "HEAD" -> 0
            requestKind == "OPEN_ENDED_RANGE" -> 10
            else -> 100
        },
        complete = true,
        eofReached = false,
        prematureEof = false,
        error = null,
    )
}
