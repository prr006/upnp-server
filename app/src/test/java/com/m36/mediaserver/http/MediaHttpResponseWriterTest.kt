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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

class MediaHttpResponseWriterTest {
    @Test
    fun representativeMkvHasVideoDIDLMetadataAndServesExactBytesInHttp200() {
        val bytes = sampleMkvBytes()
        val title = "S01E01.mkv"
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
        val resultBytes = ByteArrayOutputStream()
        val result = media.use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(resultBytes),
                method = "GET",
                rangeHeader = null,
                title = title,
                reportedMimeType = "application/octet-stream",
                media = source,
            )
        }
        val response = parseResponse(resultBytes.toByteArray())

        assertEquals(200, result.status)
        assertEquals("HTTP/1.1 200 OK", response.statusLine)
        assertEquals("video/x-matroska", response.headers["content-type"])
        assertEquals(bytes.size.toString(), response.headers["content-length"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertFalse(response.headers.containsKey("content-encoding"))
        assertFalse(response.headers.containsKey("transfer-encoding"))
        assertTrue(result.complete)
        assertEquals(0L, result.byteOffset)
        assertEquals(bytes.size.toLong(), result.bytesServed)
        assertArrayEquals(bytes, response.body)
        assertEquals(0L, media.lastSeekRelativeOffset)
        assertEquals(5L, result.sourceByteOffset)
    }

    @Test
    fun nonZeroAndOpenEndedRangesReturnExact206OffsetsLengthsAndOriginalBytes() {
        val bytes = sampleMkvBytes()
        val start = 12_347
        val end = 45_678
        val rangeMedia = TemporaryMkvPayload(bytes)
        val rangeResponseBytes = ByteArrayOutputStream()
        val rangeResult = rangeMedia.use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(rangeResponseBytes),
                method = "GET",
                rangeHeader = "bytes=$start-$end",
                title = "S01E01.mkv",
                reportedMimeType = "application/octet-stream",
                media = source,
            )
        }
        val response = parseResponse(rangeResponseBytes.toByteArray())
        val expectedRangeBytes = bytes.copyOfRange(start, end + 1)

        assertEquals(206, rangeResult.status)
        assertEquals("HTTP/1.1 206 Partial Content", response.statusLine)
        assertEquals("bytes $start-$end/${bytes.size}", response.headers["content-range"])
        assertEquals(expectedRangeBytes.size.toString(), response.headers["content-length"])
        assertEquals("video/x-matroska", response.headers["content-type"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertEquals(start.toLong(), rangeResult.byteOffset)
        assertEquals((start + 5).toLong(), rangeResult.sourceByteOffset)
        assertEquals(expectedRangeBytes.size.toLong(), rangeResult.bytesServed)
        assertEquals(start.toLong(), rangeMedia.lastSeekRelativeOffset)
        assertTrue(rangeResult.complete)
        assertArrayEquals(expectedRangeBytes, response.body)

        val openEndedStart = bytes.size - 997
        val openEndedMedia = TemporaryMkvPayload(bytes)
        val openEndedResponseBytes = ByteArrayOutputStream()
        val openEndedResult = openEndedMedia.use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(openEndedResponseBytes),
                method = "GET",
                rangeHeader = "bytes=$openEndedStart-",
                title = "S01E01.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
            )
        }
        val openEndedResponse = parseResponse(openEndedResponseBytes.toByteArray())
        val expectedOpenEndedBytes = bytes.copyOfRange(openEndedStart, bytes.size)
        assertEquals(206, openEndedResult.status)
        assertEquals("bytes $openEndedStart-${bytes.lastIndex}/${bytes.size}", openEndedResponse.headers["content-range"])
        assertEquals(expectedOpenEndedBytes.size.toString(), openEndedResponse.headers["content-length"])
        assertEquals(openEndedStart.toLong(), openEndedMedia.lastSeekRelativeOffset)
        assertArrayEquals(expectedOpenEndedBytes, openEndedResponse.body)

        val mp4Title = "sample.mp4"
        val mp4Node = nodeFor(mp4Title, bytes.size.toLong())
            .copy(mimeType = UpnpXml.mediaMimeType(mp4Title, "application/octet-stream"))
        val mp4Didl = UpnpXml.didlNode(mp4Node, "http://192.0.2.22:8200/media/mp4-token")
        assertTrue(mp4Didl.contains("protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\""))
        val mp4Bytes = ByteArrayOutputStream()
        TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(mp4Bytes),
                method = "GET",
                rangeHeader = null,
                title = mp4Title,
                reportedMimeType = "application/octet-stream",
                media = source,
            )
        }
        val mp4Response = parseResponse(mp4Bytes.toByteArray())
        assertEquals("video/mp4", mp4Response.headers["content-type"])
        assertArrayEquals(bytes, mp4Response.body)
    }

    @Test
    fun prematureSourceEofIsReportedAsAnIncompleteTransferInsteadOfSuccessfulShortPlayback() {
        val actualBytes = sampleMkvBytes().copyOf(16_384)
        val declaredLength = actualBytes.size + 257L
        val responseBytes = ByteArrayOutputStream()
        val result = TemporaryMkvPayload(actualBytes, declaredLength).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(responseBytes),
                method = "GET",
                rangeHeader = null,
                title = "S01E01.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
            )
        }
        val response = parseResponse(responseBytes.toByteArray())

        assertEquals(200, result.status)
        assertEquals(declaredLength.toString(), response.headers["content-length"])
        assertEquals(actualBytes.size.toLong(), result.bytesServed)
        assertFalse(result.complete)
        assertTrue(result.error.orEmpty().contains("ended early"))
        assertArrayEquals(actualBytes, response.body)
    }

    @Test
    fun invalidRangeIsNeverServedAsAFullFileAndHeadDoesNotSendABody() {
        val bytes = sampleMkvBytes()
        val invalidBytes = ByteArrayOutputStream()
        val invalidResult = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(invalidBytes),
                method = "GET",
                rangeHeader = "bytes=${bytes.size + 9}-",
                title = "S01E01.mkv",
                reportedMimeType = "video/x-matroska",
                media = source,
            )
        }
        val invalidResponse = parseResponse(invalidBytes.toByteArray())
        assertEquals(416, invalidResult.status)
        assertEquals("bytes */${bytes.size}", invalidResponse.headers["content-range"])
        assertEquals(0L, invalidResult.bytesServed)
        assertTrue(invalidResult.complete)
        assertFalse(invalidResponse.body.contentEquals(bytes))

        val headBytes = ByteArrayOutputStream()
        val headResult = TemporaryMkvPayload(bytes).use { source ->
            MediaHttpResponseWriter.serve(
                output = BufferedOutputStream(headBytes),
                method = "HEAD",
                rangeHeader = "bytes=10-19",
                title = "S01E01.mkv",
                reportedMimeType = null,
                media = source,
            )
        }
        val headResponse = parseResponse(headBytes.toByteArray())
        assertEquals(206, headResult.status)
        assertEquals("bytes 10-19/${bytes.size}", headResponse.headers["content-range"])
        assertEquals("10", headResponse.headers["content-length"])
        assertTrue(headResponse.body.isEmpty())
        assertEquals(0L, headResult.bytesServed)
        assertTrue(headResult.complete)
    }

    private fun nodeFor(title: String, size: Long) = MediaNode(
        objectId = "i:token",
        parentId = "d:season1",
        documentId = title,
        title = title,
        isContainer = false,
        mimeType = "application/octet-stream",
        size = size,
        modifiedMillis = 0,
    )

    private fun sampleMkvBytes(): ByteArray = ByteArray(256 * 1024 + 37) { index ->
        ((index * 31 + index / 251) and 0xff).toByte()
    }.apply {
        // Matroska/EBML document signature; the remaining deterministic binary fixture lets the
        // test detect any byte loss, offset error, text conversion, or other transformation.
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

    private data class ParsedResponse(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class TemporaryMkvPayload(
        payload: ByteArray,
        override val length: Long = payload.size.toLong(),
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
