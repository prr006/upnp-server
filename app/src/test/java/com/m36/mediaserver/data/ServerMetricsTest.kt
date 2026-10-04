package com.m36.mediaserver.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMetricsTest {
    @Test
    fun mediaTransferHistoryIncludesRequestHeadersResponseAndActualByteProgress() {
        val metrics = ServerMetrics()
        repeat(9) { index ->
            metrics.recordMediaHttpExchange(
                clientAddress = "192.168.43.50",
                resourcePath = "/media/token-$index",
                resourceId = "token-$index",
                resourceName = "episode.mkv",
                method = if (index == 8) "GET" else "HEAD",
                rangeHeader = "bytes=10-19",
                responseStatus = 206,
                responseReason = "Partial Content",
                contentType = "video/x-matroska",
                contentLength = 10,
                contentRange = "bytes 10-19/100",
                acceptRanges = "bytes",
                byteOffset = 10,
                sourceByteOffset = 15,
                bytesServed = if (index == 8) 10 else 0,
                complete = true,
                detail = null,
            )
        }

        val latest = metrics.lastMediaHttpExchange
        assertTrue(latest.contains("GET /media/token-8"))
        assertTrue(latest.contains("Client address: 192.168.43.50"))
        assertTrue(latest.contains("Range: bytes=10-19"))
        assertTrue(latest.contains("Response: 206 Partial Content"))
        assertTrue(latest.contains("Content-Type: video/x-matroska"))
        assertTrue(latest.contains("Content-Length: 10"))
        assertTrue(latest.contains("Content-Range: bytes 10-19/100"))
        assertTrue(latest.contains("Accept-Ranges: bytes"))
        assertTrue(latest.contains("Actual media byte offset: 10"))
        assertTrue(latest.contains("Actual underlying source byte offset: 15"))
        assertTrue(latest.contains("Media bytes served: 10"))
        assertEquals(8, metrics.mediaHttpHistorySnapshot().size)
    }
}
