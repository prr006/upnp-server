package com.m36.mediaserver.http

import com.m36.mediaserver.media.MediaNode
import com.m36.mediaserver.media.MediaPayload
import com.m36.mediaserver.media.seekFileInputStream
import com.m36.mediaserver.upnp.UpnpXml
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            seekFileInputStream(fileStream, startOffset, relativePosition)
        }

        override fun close() {
            fileStream.close()
            Files.deleteIfExists(file.toPath())
        }
    }

    private companion object {
        const val DIDL_NS = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    }
}
