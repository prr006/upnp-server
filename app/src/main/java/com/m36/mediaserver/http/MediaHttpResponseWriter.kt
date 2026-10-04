package com.m36.mediaserver.http

import com.m36.mediaserver.data.MediaHttpInstant
import com.m36.mediaserver.data.MediaHttpTimeline
import com.m36.mediaserver.media.MediaPayload
import com.m36.mediaserver.upnp.UpnpXml
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.SocketException
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
    val failureKind: String? = null,
    val failureStage: String? = null,
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
        timeline: MediaHttpTimeline = MediaHttpTimeline(),
        failureKind: String? = null,
        failureStage: String? = null,
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
        timeline = timeline,
        failureKind = failureKind,
        failureStage = failureStage,
    )

    fun serve(
        output: OutputStream,
        method: String,
        rangeHeader: String?,
        title: String,
        reportedMimeType: String?,
        media: MediaPayload,
        didlSize: Long? = null,
        timeline: MediaHttpTimeline = MediaHttpTimeline(),
    ): MediaHttpTransferResult {
        val headOnly = method.equals("HEAD", ignoreCase = true)
        val size = media.length
        val rangeRequest = parseRangeRequest(rangeHeader, size, method)
        val selectedRange = rangeRequest.selectedRange

        if (rangeRequest.unsatisfiable) {
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
                timeline = timeline,
                failureKind = "UNSATISFIABLE_RANGE",
                failureStage = "RANGE_VALIDATION",
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
                timeline = timeline,
                failureKind = "PREFLIGHT_REJECTED",
                failureStage = "SIZE_VALIDATION",
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
                timeline = timeline,
                failureKind = "PREFLIGHT_REJECTED",
                failureStage = "SIZE_VALIDATION",
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
                timeline = timeline,
                failureKind = "PREFLIGHT_REJECTED",
                failureStage = "DESCRIPTOR_SIZE_VALIDATION",
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
                timeline = timeline,
                failureKind = "PREFLIGHT_REJECTED",
                failureStage = "CONTENT_LENGTH_VALIDATION",
            )
        }

        val mediaOffset = selectedRange?.start ?: 0L
        // Position only when the GET body will start away from the descriptor's current asset start.
        // Offset-zero ranges and HEAD requests require no SAF seek or byte-zero discard.
        if (!headOnly && (mediaOffset > 0 || media.startOffset > 0)) {
            timeline.safSeekStarted = MediaHttpInstant.now()
            try {
                media.seek(mediaOffset)
            } catch (failure: Exception) {
                timeline.safSeekCompleted = MediaHttpInstant.now()
                // A valid byte range that the provider cannot seek is a server/storage failure,
                // not an unsatisfiable client range; reserve 416 for actual range validation.
                return writeTextResponse(
                    output = output,
                    status = 500,
                    reason = "Internal Server Error",
                    message = "Storage provider could not position the requested media range",
                    acceptRanges = "bytes",
                    contentRange = null,
                    rangeRequest = rangeRequest,
                    method = method,
                    error = "SAF seek failed for media offset=$mediaOffset: ${describeThrowable(failure)}",
                    timeline = timeline,
                    failureKind = "SAF_SEEK_ERROR",
                    failureStage = "SAF_SEEK",
                )
            }
            timeline.safSeekCompleted = MediaHttpInstant.now()
        }

        val mime = UpnpXml.mediaMimeType(title, reportedMimeType)
        val status = if (selectedRange == null) 200 else 206
        val reason = if (status == 206) "Partial Content" else "OK"
        val contentLength = selectedRange?.length ?: size
        val contentRange = selectedRange?.let { "bytes ${it.start}-${it.endInclusive}/$size" }
        val acceptRanges = "bytes"
        // ClientWorker intentionally serves one request per socket; keep this explicit until the
        // request loop and connection lifecycle are changed and validated as a unit.
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

        val progress = TransferProgress()
        var headersSent = false
        var complete = false
        var errorMessage: String? = null
        var failureKind: String? = null
        var failureStage: String? = null
        try {
            writeHttpResponseHead(output, status, reason, headers)
            output.flush()
            timeline.headersSent = MediaHttpInstant.now()
            headersSent = true
        } catch (failure: Exception) {
            failureKind = classifyOutputFailure(failure)
            failureStage = "RESPONSE_HEADERS"
            errorMessage = describeThrowable(failure)
            recordDisconnectIfPeer(failureKind, timeline)
        }

        if (headersSent) {
            if (headOnly) {
                try {
                    output.flush()
                    complete = true
                } catch (failure: Exception) {
                    failureKind = classifyOutputFailure(failure)
                    failureStage = "RESPONSE_FLUSH"
                    errorMessage = appendError(errorMessage, describeThrowable(failure))
                    recordDisconnectIfPeer(failureKind, timeline)
                }
            } else {
                try {
                    streamExact(media, output, contentLength, progress, timeline)
                    output.flush()
                    complete = progress.bytesRead == contentLength && progress.bytesWritten == contentLength
                    if (!complete) {
                        errorMessage = appendError(
                            errorMessage,
                            "Media transfer did not write the declared Content-Length",
                        )
                    }
                } catch (failure: TransferFailure) {
                    failureKind = failure.kind
                    failureStage = failure.stage
                    errorMessage = appendError(errorMessage, describeThrowable(failure.original))
                    recordDisconnectIfPeer(failureKind, timeline)
                } catch (failure: Exception) {
                    // This covers a final flush failure after the last payload chunk.
                    failureKind = classifyOutputFailure(failure)
                    failureStage = "RESPONSE_FLUSH"
                    errorMessage = appendError(errorMessage, describeThrowable(failure))
                    recordDisconnectIfPeer(failureKind, timeline)
                }
            }
        }

        val sourceStart = if (progress.bytesRead > 0) safeAdd(media.startOffset, mediaOffset) else null
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
            actualMediaStartOffset = if (progress.bytesRead > 0) mediaOffset else null,
            actualMediaEndOffsetInclusive = inclusiveEnd(mediaOffset, progress.bytesRead),
            actualSourceStartOffset = sourceStart,
            actualSourceEndOffsetInclusive = sourceStart?.let { inclusiveEnd(it, progress.bytesRead) },
            bytesRead = progress.bytesRead,
            bytesWritten = progress.bytesWritten,
            complete = complete,
            eofReached = progress.eofReached,
            prematureEof = progress.prematureEof,
            error = errorMessage,
            failureKind = failureKind,
            failureStage = failureStage,
        )
    }

    private fun parseRangeRequest(header: String?, size: Long, method: String): RangeRequest {
        if (header == null) {
            return RangeRequest(
                kind = if (method.equals("HEAD", ignoreCase = true)) "HEAD" else "FULL_GET",
                selectedRange = null,
            )
        }
        val normalized = header.trim()
        if (!normalized.startsWith("bytes=", ignoreCase = true) || normalized.contains(',')) {
            // Unknown range units and unsupported multi-range requests are ignored, not misreported
            // as an unsatisfiable byte range. The response then carries an ordinary full length.
            return RangeRequest("IGNORED_RANGE", null)
        }
        val spec = normalized.substringAfter('=', "").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeRequest("IGNORED_RANGE", null)
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()
        if (left.isBlank()) {
            val suffixLength = right.toLongOrNull()?.takeIf { it >= 0 }
                ?: return RangeRequest("IGNORED_RANGE", null)
            if (suffixLength == 0L) {
                return RangeRequest(
                    kind = "SUFFIX_RANGE",
                    selectedRange = null,
                    requestedSuffixLength = suffixLength,
                    unsatisfiable = true,
                )
            }
            if (size < 0) {
                return RangeRequest("SUFFIX_RANGE", null, requestedSuffixLength = suffixLength)
            }
            if (size == 0L) {
                return RangeRequest("SUFFIX_RANGE", null, requestedSuffixLength = suffixLength, unsatisfiable = true)
            }
            val start = (size - suffixLength).coerceAtLeast(0L)
            return RangeRequest(
                kind = "SUFFIX_RANGE",
                selectedRange = ByteRange(start, size - 1),
                requestedSuffixLength = suffixLength,
            )
        }

        val start = left.toLongOrNull()?.takeIf { it >= 0 }
            ?: return RangeRequest("IGNORED_RANGE", null)
        if (right.isBlank()) {
            if (size < 0) {
                return RangeRequest("OPEN_ENDED_RANGE", null, requestedStartOffset = start)
            }
            if (size == 0L || start >= size) {
                return RangeRequest(
                    kind = "OPEN_ENDED_RANGE",
                    selectedRange = null,
                    requestedStartOffset = start,
                    unsatisfiable = true,
                )
            }
            return RangeRequest(
                kind = "OPEN_ENDED_RANGE",
                selectedRange = ByteRange(start, size - 1),
                requestedStartOffset = start,
            )
        }

        val end = right.toLongOrNull()?.takeIf { it >= start }
            ?: return RangeRequest("IGNORED_RANGE", null, requestedStartOffset = start)
        if (size < 0) {
            return RangeRequest(
                kind = "BOUNDED_RANGE",
                selectedRange = null,
                requestedStartOffset = start,
                requestedEndOffset = end,
            )
        }
        if (size == 0L || start >= size) {
            return RangeRequest(
                kind = "BOUNDED_RANGE",
                selectedRange = null,
                requestedStartOffset = start,
                requestedEndOffset = end,
                unsatisfiable = true,
            )
        }
        return RangeRequest(
            kind = "BOUNDED_RANGE",
            selectedRange = ByteRange(start, end.coerceAtMost(size - 1)),
            requestedStartOffset = start,
            requestedEndOffset = end,
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
        timeline: MediaHttpTimeline,
        error: String? = null,
        failureKind: String? = null,
        failureStage: String? = null,
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
        val headOnly = method.equals("HEAD", ignoreCase = true)
        var complete = false
        var bytesWritten = 0L
        var responseFailureKind = failureKind
        var responseFailureStage = failureStage
        var failureMessage = error
        var headersSent = false
        try {
            writeHttpResponseHead(output, status, reason, headers)
            output.flush()
            timeline.headersSent = MediaHttpInstant.now()
            headersSent = true
        } catch (failure: Exception) {
            responseFailureKind = classifyOutputFailure(failure)
            responseFailureStage = "RESPONSE_HEADERS"
            failureMessage = appendError(failureMessage, describeThrowable(failure))
            recordDisconnectIfPeer(responseFailureKind, timeline)
        }
        if (headersSent) {
            if (headOnly) {
                try {
                    output.flush()
                    complete = true
                } catch (failure: Exception) {
                    responseFailureKind = classifyOutputFailure(failure)
                    responseFailureStage = "RESPONSE_FLUSH"
                    failureMessage = appendError(failureMessage, describeThrowable(failure))
                    recordDisconnectIfPeer(responseFailureKind, timeline)
                }
            } else {
                try {
                    output.write(body)
                    output.flush()
                    bytesWritten = contentLength
                    timeline.bytesWrittenToResponse = bytesWritten
                    complete = true
                } catch (failure: Exception) {
                    responseFailureKind = classifyOutputFailure(failure)
                    responseFailureStage = "RESPONSE_WRITE"
                    failureMessage = appendError(failureMessage, describeThrowable(failure))
                    recordDisconnectIfPeer(responseFailureKind, timeline)
                }
            }
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
            failureKind = responseFailureKind,
            failureStage = responseFailureStage,
        )
    }

    private fun streamExact(
        media: MediaPayload,
        output: OutputStream,
        length: Long,
        progress: TransferProgress,
        timeline: MediaHttpTimeline,
    ) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = readFromMedia(media, buffer, requested, progress)
            if (read < 0) {
                progress.eofReached = true
                progress.prematureEof = true
                throw TransferFailure(
                    stage = "SAF_READ",
                    kind = "UNEXPECTED_EOF",
                    original = EOFException(
                        "Media stream ended early: expected $length bytes, read ${progress.bytesRead}, wrote ${progress.bytesWritten}",
                    ),
                )
            }
            if (read == 0) {
                val singleByte = readOneMediaByte(media, progress)
                if (singleByte < 0) {
                    progress.eofReached = true
                    progress.prematureEof = true
                    throw TransferFailure(
                        stage = "SAF_READ",
                        kind = "UNEXPECTED_EOF",
                        original = EOFException(
                            "Media stream ended early: expected $length bytes, read ${progress.bytesRead}, wrote ${progress.bytesWritten}",
                        ),
                    )
                }
                progress.bytesRead++
                timeline.bytesReadFromSaf = progress.bytesRead
                try {
                    output.write(singleByte)
                    output.flush()
                } catch (failure: Exception) {
                    throw TransferFailure("RESPONSE_WRITE", classifyOutputFailure(failure), failure)
                }
                progress.bytesWritten++
                timeline.bytesWrittenToResponse = progress.bytesWritten
                val now = MediaHttpInstant.now()
                if (timeline.firstMediaByte == null) timeline.firstMediaByte = now
                timeline.lastMediaByte = now
                remaining--
                continue
            }
            progress.bytesRead += read
            timeline.bytesReadFromSaf = progress.bytesRead
            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (failure: Exception) {
                throw TransferFailure("RESPONSE_WRITE", classifyOutputFailure(failure), failure)
            }
            progress.bytesWritten += read
            timeline.bytesWrittenToResponse = progress.bytesWritten
            val now = MediaHttpInstant.now()
            if (timeline.firstMediaByte == null) timeline.firstMediaByte = now
            timeline.lastMediaByte = now
            remaining -= read
        }
    }

    private fun readFromMedia(
        media: MediaPayload,
        buffer: ByteArray,
        requested: Int,
        progress: TransferProgress,
    ): Int = try {
        media.stream.read(buffer, 0, requested)
    } catch (failure: Exception) {
        markUnexpectedEofIfNeeded(failure, progress)
        throw TransferFailure("SAF_READ", classifyMediaReadFailure(failure), failure)
    }

    private fun readOneMediaByte(media: MediaPayload, progress: TransferProgress): Int = try {
        media.stream.read()
    } catch (failure: Exception) {
        markUnexpectedEofIfNeeded(failure, progress)
        throw TransferFailure("SAF_READ", classifyMediaReadFailure(failure), failure)
    }

    private fun markUnexpectedEofIfNeeded(failure: Throwable, progress: TransferProgress) {
        if (failure is EOFException) {
            progress.eofReached = true
            progress.prematureEof = true
        }
    }

    private fun classifyMediaReadFailure(failure: Throwable): String =
        if (failure is EOFException) "UNEXPECTED_EOF" else "SERVER_IO_ERROR"

    private fun classifyOutputFailure(failure: Throwable): String =
        if (isPeerDisconnect(failure)) "PEER_DISCONNECTED" else "SERVER_IO_ERROR"

    private fun isPeerDisconnect(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            val message = current.message.orEmpty().lowercase(Locale.ROOT)
            if (current is SocketException && listOf(
                    "broken pipe",
                    "connection reset",
                    "connection aborted",
                    "connection abort",
                    "forcibly closed",
                    "established connection was aborted",
                ).any(message::contains)
            ) return true
            if (listOf(
                    "broken pipe",
                    "connection reset by peer",
                    "connection reset",
                    "connection aborted",
                    "forcibly closed by the remote host",
                    "established connection was aborted",
                ).any(message::contains)
            ) return true
            current = current.cause
        }
        return false
    }

    private fun recordDisconnectIfPeer(kind: String?, timeline: MediaHttpTimeline) {
        if (kind == "PEER_DISCONNECTED" && timeline.clientDisconnected == null) {
            timeline.clientDisconnected = MediaHttpInstant.now()
        }
    }

    private fun describeThrowable(failure: Throwable): String = generateSequence(failure) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .joinToString(" <- ") { cause ->
            "${cause.javaClass.simpleName}: ${cause.message?.take(MAX_ERROR_DETAIL_CHARS) ?: "(no detail)"}"
        }

    private fun appendError(existing: String?, addition: String): String =
        listOfNotNull(existing?.takeIf { it.isNotBlank() }, addition.takeIf { it.isNotBlank() })
            .joinToString("; ")

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
        val unsatisfiable: Boolean = false,
    )

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    private class TransferProgress(
        var bytesRead: Long = 0,
        var bytesWritten: Long = 0,
        var eofReached: Boolean = false,
        var prematureEof: Boolean = false,
    )

    private class TransferFailure(
        val stage: String,
        val kind: String,
        val original: Throwable,
    ) : IOException(original.message, original)

    private const val IO_BUFFER_SIZE = 128 * 1024
    private const val MAX_ERROR_DETAIL_CHARS = 512
    private const val MAX_CAUSE_DEPTH = 4
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
