package com.m36.mediaserver.http

import com.m36.mediaserver.media.MediaPayload
import com.m36.mediaserver.upnp.UpnpXml
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class MediaHttpTransferResult(
    val status: Int,
    val reason: String,
    val contentType: String?,
    val contentLength: Long?,
    val contentRange: String?,
    val acceptRanges: String?,
    val byteOffset: Long?,
    val sourceByteOffset: Long?,

    val bytesServed: Long,
    val complete: Boolean,
    val error: String? = null,
)

/** One implementation of the media HTTP byte contract, shared by the server and JVM regressions. */
internal object MediaHttpResponseWriter {
    fun serve(
        output: OutputStream,
        method: String,
        rangeHeader: String?,
        title: String,
        reportedMimeType: String?,
        media: MediaPayload,
    ): MediaHttpTransferResult {
        val size = media.length
        val selectedRange = rangeHeader?.let { parseRange(it, size) }
        if (rangeHeader != null && selectedRange == null) {
            val contentRange = if (size >= 0) "bytes */$size" else null
            return writeTextResponse(
                output = output,
                status = 416,
                reason = "Range Not Satisfiable",
                message = "Requested byte range cannot be served",
                acceptRanges = if (size >= 0) "bytes" else "none",
                contentRange = contentRange,
                byteOffset = null,
                method = method,
            )
        }

        val byteOffset = selectedRange?.start ?: 0L
        try {
            // Every explicit range seeks to its requested file-relative offset. For an AFD slice,
            // offset zero also needs positioning at its non-zero underlying descriptor offset.
            if (selectedRange != null || media.startOffset > 0) media.seek(byteOffset)
        } catch (error: Exception) {
            return writeTextResponse(
                output = output,
                status = 416,
                reason = "Range Not Satisfiable",
                message = "Selected storage provider does not support seeking this file",
                acceptRanges = if (size >= 0) "bytes" else "none",
                contentRange = if (size >= 0) "bytes */$size" else null,
                byteOffset = null,
                sourceByteOffset = null,
                method = method,
                error = error.message ?: error.javaClass.simpleName,
            )
        }

        val mime = UpnpXml.mediaMimeType(title, reportedMimeType)
        val status = if (selectedRange == null) 200 else 206
        val reason = if (status == 206) "Partial Content" else "OK"
        val contentLength = when {
            selectedRange != null -> selectedRange.length
            size >= 0 -> size
            else -> null
        }
        val contentRange = selectedRange?.let { "bytes ${it.start}-${it.endInclusive}/$size" }
        val acceptRanges = if (size >= 0) "bytes" else "none"
        val headers = ArrayList<String>()
        headers += "Content-Type: $mime"
        headers += "Accept-Ranges: $acceptRanges"
        headers += "transferMode.dlna.org: Streaming"
        headers += "Connection: close"
        headers += "Server: M36MediaServer/1.0"
        contentRange?.let { headers += "Content-Range: $it" }
        contentLength?.let { headers += "Content-Length: $it" }

        var headersWritten = false
        val progress = TransferProgress()
        var complete = false
        var errorMessage: String? = null
        try {
            if (contentLength == null) headers += "Transfer-Encoding: chunked"
            writeHttpResponseHead(output, status, reason, headers)
            headersWritten = true
            if (method.equals("HEAD", ignoreCase = true)) {
                output.flush()
                complete = true
            } else {
                if (contentLength == null) {
                    streamChunked(media, output, progress)
                } else {
                    streamExact(media, output, contentLength, progress)
                }
                output.flush()
                complete = true
            }
        } catch (failure: Exception) {
            errorMessage = failure.message ?: failure.javaClass.simpleName
            // If headers were sent, don't append an error body or another status. Closing the
            // connection with a short Content-Length makes the truncated transfer detectable.
            if (!headersWritten) {
                errorMessage = "response headers failed: $errorMessage"
            } else {
                runCatching { output.flush() }
            }
        }

        return MediaHttpTransferResult(
            status = status,
            reason = reason,
            contentType = mime,
            contentLength = contentLength,
            contentRange = contentRange,
            acceptRanges = acceptRanges,
            byteOffset = byteOffset,
            sourceByteOffset = media.startOffset + byteOffset,
            bytesServed = progress.bytesServed,
            complete = complete,
            error = errorMessage,
        )
    }

    private fun parseRange(header: String, size: Long): ByteRange? {
        if (size < 0 || !header.startsWith("bytes=", ignoreCase = true) || header.contains(',')) return null
        val spec = header.substringAfter('=', "").trim()
        val dash = spec.indexOf('-')
        if (dash < 0 || size == 0L) return null
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        return if (left.isBlank()) {
            val suffixLength = right.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val start = (size - suffixLength).coerceAtLeast(0L)
            ByteRange(start, size - 1)
        } else {
            val start = left.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (start >= size) return null
            val requestedEnd = if (right.isBlank()) size - 1 else right.toLongOrNull()?.takeIf { it >= start } ?: return null
            ByteRange(start, requestedEnd.coerceAtMost(size - 1))
        }
    }

    private fun writeTextResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        message: String,
        acceptRanges: String?,
        contentRange: String?,
        byteOffset: Long?,
        method: String,
        sourceByteOffset: Long? = null,
        error: String? = null,
    ): MediaHttpTransferResult {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val contentType = "text/plain; charset=utf-8"
        val headers = arrayListOf(
            "Content-Type: $contentType",
            "Content-Length: ${body.size}",
            "Connection: close",
            "Server: M36MediaServer/1.0",
        )
        acceptRanges?.let { headers += "Accept-Ranges: $it" }
        contentRange?.let { headers += "Content-Range: $it" }
        var complete = false
        var failureMessage = error
        try {
            writeHttpResponseHead(output, status, reason, headers)
            if (!method.equals("HEAD", ignoreCase = true)) output.write(body)
            output.flush()
            complete = true
        } catch (failure: Exception) {
            failureMessage = failureMessage ?: failure.message ?: failure.javaClass.simpleName
        }
        return MediaHttpTransferResult(
            status = status,
            reason = reason,
            contentType = contentType,
            contentLength = body.size.toLong(),
            contentRange = contentRange,
            acceptRanges = acceptRanges,
            byteOffset = byteOffset,
            sourceByteOffset = sourceByteOffset,
            bytesServed = 0,
            complete = complete,
            error = failureMessage,
        )
    }

    private fun streamExact(media: MediaPayload, output: OutputStream, length: Long, progress: TransferProgress) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = media.stream.read(buffer, 0, requested)
            if (read < 0) {
                throw EOFException("Media stream ended early: expected $length bytes, sent ${progress.bytesServed}")
            }
            if (read == 0) {
                val singleByte = media.stream.read()
                if (singleByte < 0) {
                    throw EOFException("Media stream ended early: expected $length bytes, sent ${progress.bytesServed}")
                }
                output.write(singleByte)
                progress.bytesServed++
                remaining--
                continue
            }
            output.write(buffer, 0, read)
            progress.bytesServed += read
            remaining -= read
        }
    }

    private fun streamChunked(media: MediaPayload, output: OutputStream, progress: TransferProgress) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        while (true) {
            val read = media.stream.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val singleByte = media.stream.read()
                if (singleByte < 0) break
                output.write("1\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write(singleByte)
                output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                progress.bytesServed++
                continue
            }
            output.write(Integer.toHexString(read).toByteArray(StandardCharsets.US_ASCII))
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(buffer, 0, read)
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            progress.bytesServed += read
        }
        output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
    }

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    private class TransferProgress(var bytesServed: Long = 0)

    private const val IO_BUFFER_SIZE = 128 * 1024
}

internal fun writeHttpResponseHead(output: OutputStream, status: Int, reason: String, headers: List<String>) {
    output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
    val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }
        .format(Date())
    output.write("Date: $date\r\n".toByteArray(StandardCharsets.US_ASCII))
    headers.forEach { header -> output.write("$header\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
}
