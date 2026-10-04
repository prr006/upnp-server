package com.m36.mediaserver.http

import com.m36.mediaserver.media.MediaPayload
import com.m36.mediaserver.upnp.UpnpXml
import java.io.EOFException
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
    val responseConnection: String?,
    val responseTransferEncoding: String?,
    val requestKind: String,
    val requestedStartOffset: Long?,
    val requestedEndOffset: Long?,
    val requestedSuffixLength: Long?,
    val actualMediaStartOffset: Long?,
    val actualMediaEndOffsetInclusive: Long?,
    val actualSourceStartOffset: Long?,
    val actualSourceEndOffsetInclusive: Long?,
    val bytesRead: Long,
    val bytesWritten: Long,
    val complete: Boolean,
    val eofReached: Boolean,
    val prematureEof: Boolean,
    val error: String? = null,
)

/** One implementation of the media HTTP byte contract, shared by the server and JVM regressions. */
internal object MediaHttpResponseWriter {
    fun writeErrorResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        message: String,
        method: String,
        rangeHeader: String?,
        error: String,
    ): MediaHttpTransferResult = writeTextResponse(
        output = output,
        status = status,
        reason = reason,
        message = message,
        acceptRanges = null,
        contentRange = null,
        rangeRequest = parseRangeRequest(rangeHeader, size = -1, method = method),
        method = method,
        error = error,
    )

    fun serve(
        output: OutputStream,
        method: String,
        rangeHeader: String?,
        title: String,
        reportedMimeType: String?,
        media: MediaPayload,
        didlSize: Long? = null,
    ): MediaHttpTransferResult {
        val size = media.length
        val rangeRequest = parseRangeRequest(rangeHeader, size, method)
        val selectedRange = rangeRequest.selectedRange

        if (rangeRequest.invalid) {
            val contentRange = if (size >= 0) "bytes */$size" else null
            return writeTextResponse(
                output = output,
                status = 416,
                reason = "Range Not Satisfiable",
                message = "Requested byte range cannot be served",
                acceptRanges = if (size >= 0) "bytes" else "none",
                contentRange = contentRange,
                rangeRequest = rangeRequest,
                method = method,
            )
        }

        val openedSize = media.openedFileSize?.takeIf { it >= 0 }
        val advertisedSize = didlSize?.takeIf { it >= 0 }
        if (openedSize != null && advertisedSize != null && openedSize != advertisedSize) {
            return writeTextResponse(
                output = output,
                status = 409,
                reason = "Conflict",
                message = "Opened media size differs from the size advertised in DIDL-Lite",
                acceptRanges = null,
                contentRange = null,
                rangeRequest = rangeRequest,
                method = method,
                error = "SAF opened size=$openedSize differs from DIDL size=$advertisedSize",
            )
        }
        if (openedSize != null && size >= 0 && openedSize != size) {
            return writeTextResponse(
                output = output,
                status = 409,
                reason = "Conflict",
                message = "Opened media size differs from the HTTP representation length",
                acceptRanges = null,
                contentRange = null,
                rangeRequest = rangeRequest,
                method = method,
                error = "SAF opened size=$openedSize differs from HTTP representation length=$size",
            )
        }
        val descriptorStatSize = media.descriptorStatSize?.takeIf { it >= 0 }
        val requiredSourceEndExclusive = safeAdd(media.startOffset, size)
        if (descriptorStatSize != null && requiredSourceEndExclusive != null && descriptorStatSize < requiredSourceEndExclusive) {
            return writeTextResponse(
                output = output,
                status = 409,
                reason = "Conflict",
                message = "Opened descriptor is shorter than the advertised media representation",
                acceptRanges = null,
                contentRange = null,
                rangeRequest = rangeRequest,
                method = method,
                error = "SAF descriptor stat size=$descriptorStatSize, but media requires source bytes through $requiredSourceEndExclusive",
            )
        }
        if (size < 0) {
            return writeTextResponse(
                output = output,
                status = 503,
                reason = "Service Unavailable",
                message = "Media size is unavailable; refusing a lengthless media response",
                acceptRanges = "none",
                contentRange = null,
                rangeRequest = rangeRequest,
                method = method,
                error = "No SAF opened size or DIDL size is available for Content-Length",
            )
        }

        val mediaOffset = selectedRange?.start ?: 0L
        try {
            // Explicit byte ranges always seek relative to this asset. A non-zero AFD slice also
            // requires positioning for a full GET/HEAD; ordinary zero-offset streams stay sequential.
            if (selectedRange != null || media.startOffset > 0) media.seek(mediaOffset)
        } catch (error: Exception) {
            return writeTextResponse(
                output = output,
                status = 416,
                reason = "Range Not Satisfiable",
                message = "Selected storage provider does not support seeking this file",
                acceptRanges = "bytes",
                contentRange = "bytes */$size",
                rangeRequest = rangeRequest,
                method = method,
                error = error.message ?: error.javaClass.simpleName,
            )
        }

        val mime = UpnpXml.mediaMimeType(title, reportedMimeType)
        val status = if (selectedRange == null) 200 else 206
        val reason = if (status == 206) "Partial Content" else "OK"
        val contentLength = selectedRange?.length ?: size
        val contentRange = selectedRange?.let { "bytes ${it.start}-${it.endInclusive}/$size" }
        val acceptRanges = "bytes"
        val responseConnection = "close"
        val headers = arrayListOf(
            "Content-Type: $mime",
            "Accept-Ranges: $acceptRanges",
            "transferMode.dlna.org: Streaming",
            "Connection: $responseConnection",
            "Server: M36MediaServer/1.0",
            "Content-Length: $contentLength",
        )
        contentRange?.let { headers += "Content-Range: $it" }

        var headersWritten = false
        val progress = TransferProgress()
        var complete = false
        var errorMessage: String? = null
        var prematureEof = false
        try {
            writeHttpResponseHead(output, status, reason, headers)
            headersWritten = true
            if (method.equals("HEAD", ignoreCase = true)) {
                output.flush()
                complete = true
            } else {
                streamExact(media, output, contentLength, progress) {
                    prematureEof = true
                }
                output.flush()
                complete = progress.bytesRead == contentLength && progress.bytesWritten == contentLength
                if (!complete) {
                    errorMessage = "Media transfer did not write the declared Content-Length"
                }
            }
        } catch (failure: Exception) {
            errorMessage = failure.message ?: failure.javaClass.simpleName
            // Once headers are sent, never append a second response or substitute another body.
            // The client worker closes the connection; diagnostics mark any short write incomplete.
            if (!headersWritten) {
                errorMessage = "response headers failed: $errorMessage"
            } else {
                runCatching { output.flush() }
            }
        }

        val sourceStart = safeAdd(media.startOffset, mediaOffset)
        return MediaHttpTransferResult(
            status = status,
            reason = reason,
            contentType = mime,
            contentLength = contentLength,
            contentRange = contentRange,
            acceptRanges = acceptRanges,
            responseConnection = responseConnection,
            responseTransferEncoding = null,
            requestKind = rangeRequest.kind,
            requestedStartOffset = rangeRequest.requestedStartOffset,
            requestedEndOffset = rangeRequest.requestedEndOffset,
            requestedSuffixLength = rangeRequest.requestedSuffixLength,
            actualMediaStartOffset = mediaOffset,
            actualMediaEndOffsetInclusive = inclusiveEnd(mediaOffset, progress.bytesRead),
            actualSourceStartOffset = sourceStart,
            actualSourceEndOffsetInclusive = sourceStart?.let { inclusiveEnd(it, progress.bytesRead) },
            bytesRead = progress.bytesRead,
            bytesWritten = progress.bytesWritten,
            complete = complete,
            eofReached = progress.eofReached,
            prematureEof = prematureEof,
            error = errorMessage,
        )
    }

    private fun parseRangeRequest(header: String?, size: Long, method: String): RangeRequest {
        if (header == null) {
            return RangeRequest(
                kind = if (method.equals("HEAD", ignoreCase = true)) "HEAD" else "FULL_GET",
                selectedRange = null,
                invalid = false,
            )
        }
        if (!header.trim().startsWith("bytes=", ignoreCase = true) || header.contains(',')) {
            return RangeRequest("INVALID_RANGE", null, invalid = true)
        }
        val spec = header.substringAfter('=', "").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeRequest("INVALID_RANGE", null, invalid = true)
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        if (left.isBlank()) {
            val suffixLength = right.toLongOrNull()?.takeIf { it > 0 }
                ?: return RangeRequest("INVALID_RANGE", null, invalid = true)
            if (size <= 0) {
                return RangeRequest("SUFFIX_RANGE", null, requestedSuffixLength = suffixLength, invalid = true)
            }
            val start = (size - suffixLength).coerceAtLeast(0L)
            return RangeRequest(
                kind = "SUFFIX_RANGE",
                selectedRange = ByteRange(start, size - 1),
                requestedSuffixLength = suffixLength,
                invalid = false,
            )
        }

        val start = left.toLongOrNull()?.takeIf { it >= 0 }
            ?: return RangeRequest("INVALID_RANGE", null, invalid = true)
        if (right.isBlank()) {
            if (size <= 0 || start >= size) {
                return RangeRequest(
                    kind = "OPEN_ENDED_RANGE",
                    selectedRange = null,
                    requestedStartOffset = start,
                    invalid = true,
                )
            }
            return RangeRequest(
                kind = "OPEN_ENDED_RANGE",
                selectedRange = ByteRange(start, size - 1),
                requestedStartOffset = start,
                invalid = false,
            )
        }

        val end = right.toLongOrNull()?.takeIf { it >= start }
            ?: return RangeRequest(
                kind = "INVALID_RANGE",
                selectedRange = null,
                requestedStartOffset = start,
                invalid = true,
            )
        if (size <= 0 || start >= size) {
            return RangeRequest(
                kind = "BOUNDED_RANGE",
                selectedRange = null,
                requestedStartOffset = start,
                requestedEndOffset = end,
                invalid = true,
            )
        }
        return RangeRequest(
            kind = "BOUNDED_RANGE",
            selectedRange = ByteRange(start, end.coerceAtMost(size - 1)),
            requestedStartOffset = start,
            requestedEndOffset = end,
            invalid = false,
        )
    }

    private fun writeTextResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        message: String,
        acceptRanges: String?,
        contentRange: String?,
        rangeRequest: RangeRequest,
        method: String,
        error: String? = null,
    ): MediaHttpTransferResult {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val contentType = "text/plain; charset=utf-8"
        val contentLength = body.size.toLong()
        val responseConnection = "close"
        val headers = arrayListOf(
            "Content-Type: $contentType",
            "Content-Length: $contentLength",
            "Connection: $responseConnection",
            "Server: M36MediaServer/1.0",
        )
        acceptRanges?.let { headers += "Accept-Ranges: $it" }
        contentRange?.let { headers += "Content-Range: $it" }
        var complete = false
        var bytesWritten = 0L
        var failureMessage = error
        try {
            writeHttpResponseHead(output, status, reason, headers)
            if (!method.equals("HEAD", ignoreCase = true)) {
                output.write(body)
                output.flush()
                bytesWritten = contentLength
            } else {
                output.flush()
            }
            complete = true
        } catch (failure: Exception) {
            failureMessage = listOfNotNull(
                failureMessage,
                failure.message ?: failure.javaClass.simpleName,
            ).joinToString("; ")
        }
        return MediaHttpTransferResult(
            status = status,
            reason = reason,
            contentType = contentType,
            contentLength = contentLength,
            contentRange = contentRange,
            acceptRanges = acceptRanges,
            responseConnection = responseConnection,
            responseTransferEncoding = null,
            requestKind = rangeRequest.kind,
            requestedStartOffset = rangeRequest.requestedStartOffset,
            requestedEndOffset = rangeRequest.requestedEndOffset,
            requestedSuffixLength = rangeRequest.requestedSuffixLength,
            actualMediaStartOffset = null,
            actualMediaEndOffsetInclusive = null,
            actualSourceStartOffset = null,
            actualSourceEndOffsetInclusive = null,
            bytesRead = 0,
            bytesWritten = bytesWritten,
            complete = complete,
            eofReached = false,
            prematureEof = false,
            error = failureMessage,
        )
    }

    private fun streamExact(
        media: MediaPayload,
        output: OutputStream,
        length: Long,
        progress: TransferProgress,
        onPrematureEof: () -> Unit,
    ) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = media.stream.read(buffer, 0, requested)
            if (read < 0) {
                progress.eofReached = true
                onPrematureEof()
                throw EOFException("Media stream ended early: expected $length bytes, read ${progress.bytesRead}, wrote ${progress.bytesWritten}")
            }
            if (read == 0) {
                val singleByte = media.stream.read()
                if (singleByte < 0) {
                    progress.eofReached = true
                    onPrematureEof()
                    throw EOFException("Media stream ended early: expected $length bytes, read ${progress.bytesRead}, wrote ${progress.bytesWritten}")
                }
                progress.bytesRead++
                output.write(singleByte)
                output.flush()
                progress.bytesWritten++
                remaining--
                continue
            }
            progress.bytesRead += read
            output.write(buffer, 0, read)
            output.flush()
            progress.bytesWritten += read
            remaining -= read
        }
    }

    private fun safeAdd(left: Long, right: Long): Long? =
        if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) null else left + right

    private fun inclusiveEnd(start: Long, byteCount: Long): Long? =
        if (byteCount <= 0 || Long.MAX_VALUE - start < byteCount - 1) null else start + byteCount - 1

    private data class RangeRequest(
        val kind: String,
        val selectedRange: ByteRange?,
        val requestedStartOffset: Long? = null,
        val requestedEndOffset: Long? = null,
        val requestedSuffixLength: Long? = null,
        val invalid: Boolean,
    )

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    private class TransferProgress(
        var bytesRead: Long = 0,
        var bytesWritten: Long = 0,
        var eofReached: Boolean = false,
    )

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
