package com.m36.mediaserver.http

import com.m36.mediaserver.media.MediaNode
import com.m36.mediaserver.media.MediaPayload
import com.m36.mediaserver.media.seekFileInputStream
import com.m36.mediaserver.upnp.UpnpXml
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

class MediaHttpResponseWriterTest {
    @Test
    fun mkvDidlMetadataAndInitialFullGetPreserveTheExactSourceBytes() {
        val bytes = sampleMkvBytes()
        val title = "Sakamoto Days E13.mkv"
        val url = "http://192.0.2.22:8200/media/mkv-token"
        val node = MediaNode(
            objectId = "i:mkv-token",
            parentId = "d:season1",
            documentId = title,
            title = title,
            isContainer = false,
            mimeType = "application/octet-stream",
            size = bytes.size.toLong(),
            modifiedMillis = 0,
            mediaToken = "mkv-token",
        )
        val videoNode = node.copy(mimeType = UpnpXml.mediaMimeType(node.title, node.mimeType))
        val didl = UpnpXml.didlNode(videoNode, url)

        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertFalse(didl.contains("object.item.audioItem"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:video/x-matroska:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\""))
        assertFalse(didl.contains("DLNA.ORG_PN="))
        assertFalse(didl.contains("duration="))
        assertTrue(didl.contains("size=\"${bytes.size}\""))
        assertTrue(didl.contains(">$url</res>"))
        val didlDocument = parseXml(
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">$didl</DIDL-Lite>""",
        )
        val resource = didlDocument.getElementsByTagNameNS(DIDL_NS, "res").item(0) as Element
        assertEquals("http-get:*:video/x-matroska:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", resource.getAttribute("protocolInfo"))
        assertEquals(bytes.size.toString(), resource.getAttribute("size"))
        assertEquals(url, resource.textContent)

        val media = TemporaryMkvPayload(bytes)
        val output = ByteArrayOutputStream()
        val result = media.use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "GET",
                rangeHeader = null,
                title = title,
                reportedMimeType = "application/octet-stream",
                media = source,
                didlSize = bytes.size.toLong(),
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals("FULL_GET", result.requestKind)
        assertEquals(200, result.status)
        assertEquals("HTTP/1.1 200 OK", response.statusLine)
        assertEquals("video/x-matroska", response.headers["content-type"])
        assertEquals(bytes.size.toString(), response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertEquals("close", response.headers["connection"])
        assertFalse(response.headers.containsKey("transfer-encoding"))
        assertFalse(response.headers.containsKey("content-encoding"))
        assertTrue(result.complete)
        assertEquals(0L, result.actualMediaStartOffset)
        assertEquals(bytes.lastIndex.toLong(), result.actualMediaEndOffsetInclusive)
        assertEquals(5L, result.actualSourceStartOffset)
        assertEquals((bytes.lastIndex + 5).toLong(), result.actualSourceEndOffsetInclusive)
        assertEquals(bytes.size.toLong(), result.bytesRead)
        assertEquals(bytes.size.toLong(), result.bytesWritten)
        assertArrayEquals(bytes, response.body)
        assertEquals(0L, media.lastSeekRelativeOffset)
    }

    @Test
    fun headReturnsRepresentationMetadataButNoBody() {
        val bytes = sampleMkvBytes()
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "HEAD",
                rangeHeader = null,
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "application/octet-stream",
                media = source,
                didlSize = bytes.size.toLong(),
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals("HEAD", result.requestKind)
        assertEquals(200, result.status)
        assertEquals("video/x-matroska", response.headers["content-type"])
        assertEquals(bytes.size.toString(), response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertTrue(response.body.isEmpty())
        assertEquals(0L, result.bytesRead)
        assertEquals(0L, result.bytesWritten)
        assertTrue(result.complete)
    }

    @Test
    fun boundedAndOpenEndedRangesReturnExact206HeadersAndReadFromRequestedOffset() {
        val bytes = sampleMkvBytes()
        val start = 12_347
        val end = 45_678
        val bounded = serve(bytes, "GET", "bytes=$start-$end")
        val boundedBody = bytes.copyOfRange(start, end + 1)

        assertEquals("BOUNDED_RANGE", bounded.result.requestKind)
        assertEquals(start.toLong(), bounded.result.requestedStartOffset)
        assertEquals(end.toLong(), bounded.result.requestedEndOffset)
        assertEquals(206, bounded.result.status)
        assertEquals("bytes $start-$end/${bytes.size}", bounded.response.headers["content-range"])
        assertEquals(boundedBody.size.toString(), bounded.response.headers["content-length"])
        assertEquals("video/x-matroska", bounded.response.headers["content-type"])
        assertEquals("bytes", bounded.response.headers["accept-ranges"])
        assertEquals("close", bounded.response.headers["connection"])
        assertFalse(bounded.response.headers.containsKey("transfer-encoding"))
        assertEquals(start.toLong(), bounded.result.actualMediaStartOffset)
        assertEquals(end.toLong(), bounded.result.actualMediaEndOffsetInclusive)
        assertEquals((start + 5).toLong(), bounded.result.actualSourceStartOffset)
        assertEquals((end + 5).toLong(), bounded.result.actualSourceEndOffsetInclusive)
        assertEquals(boundedBody.size.toLong(), bounded.result.bytesRead)
        assertEquals(boundedBody.size.toLong(), bounded.result.bytesWritten)
        assertTrue(bounded.result.complete)
        assertArrayEquals(boundedBody, bounded.response.body)

        val initialProbe = serve(bytes, "GET", "bytes=0-1023")
        assertEquals("BOUNDED_RANGE", initialProbe.result.requestKind)
        assertEquals(206, initialProbe.result.status)
        assertEquals("bytes 0-1023/${bytes.size}", initialProbe.response.headers["content-range"])
        assertEquals("1024", initialProbe.response.headers["content-length"])
        assertArrayEquals(bytes.copyOfRange(0, 1024), initialProbe.response.body)

        val fromZero = serve(bytes, "GET", "bytes=0-")
        assertEquals("OPEN_ENDED_RANGE", fromZero.result.requestKind)
        assertEquals(206, fromZero.result.status)
        assertEquals("bytes 0-${bytes.lastIndex}/${bytes.size}", fromZero.response.headers["content-range"])
        assertEquals(bytes.size.toString(), fromZero.response.headers["content-length"])
        assertArrayEquals(bytes, fromZero.response.body)

        val openStart = bytes.size - 997
        val open = serve(bytes, "GET", "bytes=$openStart-")
        val openBody = bytes.copyOfRange(openStart, bytes.size)
        assertEquals("OPEN_ENDED_RANGE", open.result.requestKind)
        assertEquals(openStart.toLong(), open.result.requestedStartOffset)
        assertNull(open.result.requestedEndOffset)
        assertEquals(206, open.result.status)
        assertEquals("bytes $openStart-${bytes.lastIndex}/${bytes.size}", open.response.headers["content-range"])
        assertEquals(openBody.size.toString(), open.response.headers["content-length"])
        assertEquals(openStart.toLong(), open.result.actualMediaStartOffset)
        assertEquals(bytes.lastIndex.toLong(), open.result.actualMediaEndOffsetInclusive)
        assertArrayEquals(openBody, open.response.body)

        val headRangeOutput = ByteArrayOutputStream()
        val headRange = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(headRangeOutput),
                method = "HEAD",
                rangeHeader = "bytes=10-19",
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = bytes.size.toLong(),
            )
        }
        val headRangeResponse = parseResponse(headRangeOutput.toByteArray())
        assertEquals("BOUNDED_RANGE", headRange.requestKind)
        assertEquals(206, headRange.status)
        assertEquals("bytes 10-19/${bytes.size}", headRangeResponse.headers["content-range"])
        assertEquals("10", headRangeResponse.headers["content-length"])
        assertTrue(headRangeResponse.body.isEmpty())
        assertEquals(0L, headRange.bytesWritten)
    }

    @Test
    fun arbitrarySeekOffsetsAlwaysReturnTheCorrespondingSourceSlice() {
        val bytes = sampleMkvBytes()
        val offsets = listOf(1, 31, 8_191, 131_077, bytes.lastIndex)
        offsets.forEach { start ->
            val end = (start + 73).coerceAtMost(bytes.lastIndex)
            val transfer = serve(bytes, "GET", "bytes=$start-$end")
            val expected = bytes.copyOfRange(start, end + 1)

            assertEquals(start.toLong(), transfer.result.actualMediaStartOffset)
            assertEquals(end.toLong(), transfer.result.actualMediaEndOffsetInclusive)
            assertEquals((start + 5).toLong(), transfer.result.actualSourceStartOffset)
            assertEquals((end + 5).toLong(), transfer.result.actualSourceEndOffsetInclusive)
            assertEquals(expected.size.toLong(), transfer.result.bytesWritten)
            assertArrayEquals(expected, transfer.response.body)
        }
    }

    @Test
    fun largeMkvSizedStreamCrossesMultipleBuffersWithoutLossOrTransformation() {
        val bytes = sampleMkvBytes(12 * 1024 * 1024 + 257)
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output, 128 * 1024),
                method = "GET",
                rangeHeader = null,
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = bytes.size.toLong(),
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals(200, result.status)
        assertEquals(bytes.size.toLong(), result.bytesRead)
        assertEquals(bytes.size.toLong(), result.bytesWritten)
        assertEquals(bytes.size.toString(), response.headers["content-length"])
        assertTrue(result.complete)
        assertArrayEquals(bytes, response.body)
    }

    @Test
    fun didlAndOpenedFileSizeMismatchIsRejectedBeforeAnyMediaBytesAreSent() {
        val bytes = sampleMkvBytes()
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "GET",
                rangeHeader = null,
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = bytes.size.toLong() + 1,
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals(409, result.status)
        assertTrue(result.complete)
        assertEquals(0L, result.bytesRead)
        assertEquals(response.body.size.toLong(), result.bytesWritten)
        assertEquals(response.body.size.toString(), response.headers["content-length"])
        assertFalse(response.headers.containsKey("transfer-encoding"))
        assertTrue(result.error.orEmpty().contains("differs from DIDL size"))
        assertTrue(response.body.isNotEmpty())
        assertFalse(response.body.contentEquals(bytes))
    }

    @Test
    fun descriptorStatSizeTooShortIsRejectedBeforeSendingAnOverlongContentLength() {
        val bytes = sampleMkvBytes()
        val expectedLength = bytes.size.toLong() + 1
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(
            payload = bytes,
            length = expectedLength,
            openedFileSize = expectedLength,
            assetFileDescriptorLength = expectedLength,
            descriptorStatSize = 5L + bytes.size,
        ).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "GET",
                rangeHeader = null,
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = expectedLength,
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals(409, result.status)
        assertEquals(0L, result.bytesRead)
        assertFalse(response.headers.containsKey("transfer-encoding"))
        assertEquals(response.body.size.toString(), response.headers["content-length"])
        assertTrue(result.error.orEmpty().contains("descriptor stat size"))
        assertFalse(response.body.contentEquals(bytes))
    }

    @Test
    fun unknownLengthNeverFallsBackToChunkedMediaResponse() {
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(
            sampleMkvBytes(),
            length = -1,
            openedFileSize = null,
            assetFileDescriptorLength = null,
            descriptorStatSize = null,
        ).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "GET",
                rangeHeader = null,
                title = "unknown.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = null,
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals(503, result.status)
        assertTrue(response.headers.containsKey("content-length"))
        assertFalse(response.headers.containsKey("transfer-encoding"))
        assertEquals(0L, result.bytesRead)
        assertTrue(result.complete)
    }

    @Test
    fun earlyEofIsDetectedAndNeverMarkedAsACompleteTransfer() {
        val actualBytes = sampleMkvBytes().copyOf(16_384)
        val declaredLength = actualBytes.size + 257L
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(
            payload = actualBytes,
            length = declaredLength,
            openedFileSize = declaredLength,
            assetFileDescriptorLength = declaredLength,
            descriptorStatSize = null,
        ).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "GET",
                rangeHeader = null,
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = declaredLength,
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals(200, result.status)
        assertEquals(declaredLength.toString(), response.headers["content-length"])
        assertEquals(actualBytes.size.toLong(), result.bytesRead)
        assertEquals(actualBytes.size.toLong(), result.bytesWritten)
        assertTrue(result.eofReached)
        assertTrue(result.prematureEof)
        assertFalse(result.complete)
        assertEquals("UNEXPECTED_EOF", result.failureKind)
        assertEquals("SAF_READ", result.failureStage)
        assertTrue(result.error.orEmpty().contains("ended early"))
        assertTrue(response.body.size < response.headers.getValue("content-length").toInt())
        assertArrayEquals(actualBytes, response.body)
    }

    @Test
    fun invalidRangeIsNeverServedAsAFullFile() {
        val bytes = sampleMkvBytes()
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "GET",
                rangeHeader = "bytes=${bytes.size + 9}-",
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = bytes.size.toLong(),
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals("OPEN_ENDED_RANGE", result.requestKind)
        assertEquals(416, result.status)
        assertEquals("bytes */${bytes.size}", response.headers["content-range"])
        assertEquals("close", response.headers["connection"])
        assertFalse(response.headers.containsKey("transfer-encoding"))
        assertTrue(result.complete)
        assertFalse(response.body.contentEquals(bytes))
    }

    @Test
    fun offsetZeroRangeDoesNotSeekOrDiscardBytesOnAZeroBasedSource() {
        val bytes = sampleMkvBytes(4_097)
        val media = object : MediaPayload {
            override val stream: InputStream = ByteArrayInputStream(bytes)
            override val length: Long = bytes.size.toLong()
            override val startOffset: Long = 0
            override fun seek(relativePosition: Long) {
                throw IOException("offset-zero range should not seek")
            }
            override fun close() = Unit
        }
        val output = ByteArrayOutputStream()
        val result = MediaHttpResponseWriter.serve(
            output = output,
            method = "GET",
            rangeHeader = "bytes=0-",
            title = "zero-based.mkv",
            reportedMimeType = "video/x-matroska",
            media = media,
            didlSize = bytes.size.toLong(),
        )
        val response = parseResponse(output.toByteArray())

        assertEquals(206, result.status)
        assertTrue(result.complete)
        assertEquals("bytes 0-${bytes.lastIndex}/${bytes.size}", response.headers["content-range"])
        assertArrayEquals(bytes, response.body)
    }

    @Test
    fun unknownMalformedAndUnsupportedMultiRangesAreIgnoredInsteadOfMisreportedAs416() {
        val bytes = sampleMkvBytes()
        listOf("items=0-9", "bytes=0-9,20-29", "bytes=not-a-range").forEach { header ->
            val transfer = serve(bytes, "GET", header)

            assertEquals("IGNORED_RANGE", transfer.result.requestKind)
            assertEquals(200, transfer.result.status)
            assertNull(transfer.response.headers["content-range"])
            assertEquals(bytes.size.toString(), transfer.response.headers["content-length"])
            assertArrayEquals(bytes, transfer.response.body)
            assertTrue(transfer.result.complete)
        }
    }

    @Test
    fun seekFailureOnAValidRangeIsAStorageErrorNotAFalse416() {
        val bytes = sampleMkvBytes()
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(bytes, failOnSeek = true).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = "GET",
                rangeHeader = "bytes=10-19",
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = bytes.size.toLong(),
            )
        }
        val response = parseResponse(output.toByteArray())

        assertEquals(500, result.status)
        assertEquals("Internal Server Error", result.reason)
        assertNull(response.headers["content-range"])
        assertEquals("SAF_SEEK_ERROR", result.failureKind)
        assertEquals("SAF_SEEK", result.failureStage)
        assertTrue(result.complete)
        assertTrue(result.error.orEmpty().contains("injected seek failure"))
    }

    @Test
    fun brokenPipeIsRecordedAsPeerDisconnectAndNeverAsComplete() {
        val bytes = sampleMkvBytes(512 * 1024)
        val timeline = com.m36.mediaserver.data.MediaHttpTimeline()
        val result = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = FailAfterResponseHeadersOutputStream("Broken pipe"),
                method = "GET",
                rangeHeader = "bytes=0-",
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = bytes.size.toLong(),
                timeline = timeline,
            )
        }

        assertEquals("PEER_DISCONNECTED", result.failureKind)
        assertEquals("RESPONSE_WRITE", result.failureStage)
        assertFalse(result.complete)
        assertFalse(result.eofReached)
        assertFalse(result.prematureEof)
        assertEquals(128L * 1024L, result.bytesRead)
        assertEquals(0L, result.bytesWritten)
        assertTrue(result.error.orEmpty().contains("Broken pipe"))
        assertNotNull(timeline.headersSent)
        assertNotNull(timeline.clientDisconnected)
        assertNull(timeline.firstMediaByte)
    }

    @Test
    fun safReadIoFailureIsNotClassifiedAsClientCancellation() {
        val source = object : MediaPayload {
            override val stream: InputStream = object : InputStream() {
                override fun read(): Int = throw IOException("injected SAF read failure")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IOException("injected SAF read failure")
            }
            override val length: Long = 32
            override val startOffset: Long = 0
            override fun seek(relativePosition: Long) = Unit
            override fun close() = Unit
        }
        val output = ByteArrayOutputStream()
        val result = MediaHttpResponseWriter.serve(
            output = output,
            method = "GET",
            rangeHeader = null,
            title = "broken.mkv",
            reportedMimeType = "video/x-matroska",
            media = source,
            didlSize = 32,
        )

        assertEquals("SERVER_IO_ERROR", result.failureKind)
        assertEquals("SAF_READ", result.failureStage)
        assertFalse(result.complete)
        assertFalse(result.eofReached)
        assertFalse(result.prematureEof)
        assertEquals(0L, result.bytesRead)
        assertEquals(0L, result.bytesWritten)
        assertTrue(result.error.orEmpty().contains("injected SAF read failure"))
    }

    @Test
    fun loopbackSocketControlPreservesRangeFramingAndExplicitCloseBehavior() {
        val bytes = sampleMkvBytes(8 * 1024 + 17)
        val start = 313
        val end = 1_777
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val serverResult = executor.submit<MediaHttpTransferResult> {
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val input = BufferedInputStream(peer.getInputStream())
                    assertEquals("GET /media/control HTTP/1.1", readHttpLine(input))
                    var range: String? = null
                    var sawKeepAlive = false
                    while (true) {
                        val line = readHttpLine(input)
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                        if (line.equals("Connection: keep-alive", ignoreCase = true)) sawKeepAlive = true
                    }
                    assertTrue("control request should exercise a client keep-alive preference", sawKeepAlive)
                    TemporaryMkvPayload(bytes).use { source ->
                        MediaHttpResponseWriter.serve(
                            output = peer.getOutputStream(),
                            method = "GET",
                            rangeHeader = range,
                            title = "control.mkv",
                            reportedMimeType = "video/x-matroska",
                            media = source,
                            didlSize = bytes.size.toLong(),
                        )
                    }
                }
            }

            val responseBytes = Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                client.soTimeout = 5_000
                client.getOutputStream().write(
                    ("GET /media/control HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Range: bytes=$start-$end\r\n" +
                        "Connection: keep-alive\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
                )
                client.getOutputStream().flush()
                client.getInputStream().readBytes()
            }
            val result = serverResult.get(5, TimeUnit.SECONDS)
            val response = parseResponse(responseBytes)

            assertEquals(206, result.status)
            assertTrue(result.complete)
            assertEquals("HTTP/1.1 206 Partial Content", response.statusLine)
            assertEquals("bytes $start-$end/${bytes.size}", response.headers["content-range"])
            assertEquals((end - start + 1).toString(), response.headers["content-length"])
            assertEquals("close", response.headers["connection"])
            assertFalse(response.headers.containsKey("transfer-encoding"))
            assertArrayEquals(bytes.copyOfRange(start, end + 1), response.body)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    private fun readHttpLine(input: InputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw AssertionError("Unexpected EOF in loopback HTTP control request")
            if (value == '\n'.code) break
            if (value != '\r'.code) line.write(value)
        }
        return line.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun serve(bytes: ByteArray, method: String, range: String): TransferAndResponse {
        val output = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(output),
                method = method,
                rangeHeader = range,
                title = "Sakamoto Days E13.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
                didlSize = bytes.size.toLong(),
            )
        }
        return TransferAndResponse(result, parseResponse(output.toByteArray()))
    }

    private fun sampleMkvBytes(size: Int = 256 * 1024 + 37): ByteArray = ByteArray(size) { index ->
        ((index * 31 + index / 251) and 0xff).toByte()
    }.apply {
        // Matroska/EBML document signature plus deterministic binary payload for exact-byte checks.
        this[0] = 0x1a.toByte()
        this[1] = 0x45.toByte()
        this[2] = 0xdf.toByte()
        this[3] = 0xa3.toByte()
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun parseResponse(raw: ByteArray): ParsedResponse {
        val separator = byteArrayOf(13, 10, 13, 10)
        val headerEnd = raw.indices.firstOrNull { index ->
            index + separator.size <= raw.size && separator.indices.all { raw[index + it] == separator[it] }
        } ?: throw AssertionError("HTTP response header terminator missing")
        val headerText = String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val headers = lines.drop(1).associate { line ->
            val colon = line.indexOf(':')
            line.substring(0, colon).trim().lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }
        return ParsedResponse(
            statusLine = lines.first(),
            headers = headers,
            body = raw.copyOfRange(headerEnd + separator.size, raw.size),
        )
    }

    private data class TransferAndResponse(
        val result: MediaHttpTransferResult,
        val response: ParsedResponse,
    )

    private data class ParsedResponse(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class TemporaryMkvPayload(
        payload: ByteArray,
        override val length: Long = payload.size.toLong(),
        override val openedFileSize: Long? = payload.size.toLong(),
        override val assetFileDescriptorLength: Long? = openedFileSize,
        override val descriptorStatSize: Long? = 5L + payload.size,
        private val failOnSeek: Boolean = false,
    ) : MediaPayload {
        private val file: File = Files.createTempFile("m36-mkv-range-test", ".mkv").toFile()
        private val assetPrefix = byteArrayOf(0x51, 0x22, 0x7f, 0x11, 0x03)
        private val fileStream: FileInputStream
        override val stream: InputStream get() = fileStream
        override val startOffset: Long = assetPrefix.size.toLong()
        var lastSeekRelativeOffset: Long? = null
            private set

        init {
            FileOutputStream(file).use { output ->
                output.write(assetPrefix)
                output.write(payload)
            }
            fileStream = FileInputStream(file)
        }

        override fun seek(relativePosition: Long) {
            lastSeekRelativeOffset = relativePosition
            if (failOnSeek) throw IOException("injected seek failure")
            seekFileInputStream(fileStream, startOffset, relativePosition)
        }

        override fun close() {
            fileStream.close()
            Files.deleteIfExists(file.toPath())
        }
    }

    private class FailAfterResponseHeadersOutputStream(
        private val failureMessage: String,
    ) : OutputStream() {
        private var previous3 = -1
        private var previous2 = -1
        private var previous1 = -1
        private var responseHeadersComplete = false

        override fun write(value: Int) {
            if (responseHeadersComplete) throw SocketException(failureMessage)
            val current = value and 0xff
            if (previous3 == 13 && previous2 == 10 && previous1 == 13 && current == 10) {
                responseHeadersComplete = true
            }
            previous3 = previous2
            previous2 = previous1
            previous1 = current
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            for (index in offset until offset + length) write(buffer[index].toInt())
        }

        override fun flush() = Unit
    }

    private companion object {
        const val DIDL_NS = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    }
}
