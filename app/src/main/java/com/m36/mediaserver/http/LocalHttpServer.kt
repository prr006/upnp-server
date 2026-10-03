package com.m36.mediaserver.http

import android.content.Context
import com.m36.mediaserver.data.ServerMetrics
import com.m36.mediaserver.media.DocumentTreeRepository
import com.m36.mediaserver.media.OpenedMedia
import com.m36.mediaserver.network.NetworkSnapshot
import com.m36.mediaserver.upnp.ConnectionManagerService
import com.m36.mediaserver.upnp.ContentDirectoryService
import com.m36.mediaserver.upnp.SoapXml
import com.m36.mediaserver.upnp.UpnpFault
import com.m36.mediaserver.upnp.UpnpXml
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small HTTP/1.1 server with bounded worker count and streaming, seekable media responses. */
class LocalHttpServer(
    context: Context,
    private val repository: DocumentTreeRepository,
    private val deviceUuid: String,
    private val metrics: ServerMetrics,
    private val networkSnapshot: () -> NetworkSnapshot,
) : Closeable {
    private val contentDirectory = ContentDirectoryService(repository)
    private val connectionManager = ConnectionManagerService()
    private val executor = ThreadPoolExecutor(
        4,
        16,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        namedThreadFactory("m36-http-client"),
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val acceptExecutor = Executors.newCachedThreadPool(namedThreadFactory("m36-http-accept"))
    private val isRunning = AtomicBoolean(false)
    private val listenerLock = Any()
    private val serverSockets = java.util.concurrent.CopyOnWriteArrayList<ServerSocket>()
    @Volatile private var addressFingerprint: String = ""
    @Volatile private var boundAddresses: List<String> = emptyList()
    @Suppress("unused") private val appContext = context.applicationContext

    val running: Boolean get() = isRunning.get()
    val listeningAddresses: List<String> get() = boundAddresses

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        metrics.serverStatus = "HTTP waiting for a reachable local IPv4 interface"
    }

    /** Bind only the selected local IPv4 interfaces (not cellular/public interfaces). */
    fun updateAddresses(snapshot: NetworkSnapshot) {
        if (!isRunning.get()) return
        val eligible = snapshot.eligibleAddresses
        val addresses = eligible
            .map { it.hostAddress }
            .distinct()
            .sorted()
        val fingerprint = eligible
            .map { "${it.interfaceName}:${it.hostAddress}:${it.transportType}:${it.network ?: "-"}" }
            .distinct()
            .sorted()
            .joinToString("|") +
            "#default=${snapshot.defaultInterface ?: "-"}:${snapshot.defaultTransport}" +
            "#radio=${snapshot.radioFingerprint}"
        synchronized(listenerLock) {
            if (!isRunning.get() || fingerprint == addressFingerprint) return
            serverSockets.forEach { runCatching { it.close() } }
            serverSockets.clear()
            boundAddresses = emptyList()
            addressFingerprint = fingerprint
            val created = ArrayList<ServerSocket>()
            val errors = ArrayList<String>()
            addresses.forEach { address ->
                var listener: ServerSocket? = null
                try {
                    val boundListener = ServerSocket()
                    listener = boundListener
                    boundListener.reuseAddress = true
                    boundListener.bind(InetSocketAddress(InetAddress.getByName(address), PORT), ACCEPT_BACKLOG)
                    created += boundListener
                    acceptExecutor.execute { acceptLoop(boundListener) }
                } catch (error: Exception) {
                    runCatching { listener?.close() }
                    errors += "$address: ${error.message ?: "bind failed"}"
                }
            }
            serverSockets.addAll(created)
            boundAddresses = created.mapNotNull { (it.inetAddress as? Inet4Address)?.hostAddress }
            metrics.serverStatus = when {
                boundAddresses.isNotEmpty() -> "HTTP listening on ${boundAddresses.joinToString(", ")}:$PORT"
                addresses.isEmpty() -> "HTTP waiting for a reachable local IPv4 interface"
                else -> "HTTP bind failed: ${errors.joinToString("; ").ifBlank { "no interface available" }}"
            }
        }
    }

    override fun close() {
        if (!isRunning.getAndSet(false)) return
        synchronized(listenerLock) {
            serverSockets.forEach { runCatching { it.close() } }
            serverSockets.clear()
            boundAddresses = emptyList()
            addressFingerprint = ""
        }
        acceptExecutor.shutdownNow()
        executor.shutdownNow()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (isRunning.get()) {
            try {
                val client = listener.accept()
                client.tcpNoDelay = true
                client.soTimeout = READ_TIMEOUT_MILLIS
                try {
                    executor.execute(ClientWorker(client))
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    runCatching { client.close() }
                }
            } catch (_: SocketException) {
                if (isRunning.get()) metrics.serverStatus = "HTTP accept socket failed"
                break
            } catch (error: IOException) {
                if (isRunning.get()) metrics.serverStatus = "HTTP accept error: ${error.message}"
            }
        }
    }

    private inner class ClientWorker(private val socket: Socket) : Runnable {
        override fun run() {
            socket.use { client ->
                try {
                    val input = BufferedInputStream(client.getInputStream(), IO_BUFFER_SIZE)
                    val output = BufferedOutputStream(client.getOutputStream(), IO_BUFFER_SIZE)
                    val request = readRequest(input) ?: return
                    metrics.httpRequestCount.incrementAndGet()
                    metrics.lastHttpRequest = "${request.method} ${request.path} from ${client.inetAddress.hostAddress}"
                    handle(client, output, request)
                } catch (error: RequestTooLargeException) {
                    runCatching { writeText(socket.getOutputStream(), 413, "Payload Too Large", "Request too large") }
                } catch (error: IOException) {
                    // Client disconnects during seeks/playback are normal; keep diagnostics focused
                    // on the last successfully parsed request.
                } catch (error: Exception) {
                    runCatching { writeText(socket.getOutputStream(), 500, "Internal Server Error", "Server error") }
                }
            }
        }
    }

    private fun handle(socket: Socket, output: BufferedOutputStream, request: HttpRequest) {
        val rawPath = request.path.trimEnd('/').ifBlank { "/" }
        val path = rawPath.lowercase(Locale.ROOT)
        when {
            request.method == "GET" && (path == "/" || path == "/rootdesc.xml" || path == "/devicedesc.xml") -> {
                val baseUrl = baseUrlFor(socket)
                writeText(output, 200, "OK", UpnpXml.rootDescription(deviceUuid, baseUrl), "text/xml; charset=\"utf-8\"")
            }
            request.method == "GET" && path == "/contentdirectory/scpd.xml" ->
                writeText(output, 200, "OK", UpnpXml.contentDirectoryScpd(), "text/xml; charset=\"utf-8\"")
            request.method == "GET" && path == "/connectionmanager/scpd.xml" ->
                writeText(output, 200, "OK", UpnpXml.connectionManagerScpd(), "text/xml; charset=\"utf-8\"")
            request.method == "POST" && isContentDirectoryControl(path) ->
                handleSoap(output, request, baseUrlFor(socket), isContentDirectory = true)
            request.method == "POST" && isConnectionManagerControl(path) ->
                handleSoap(output, request, baseUrlFor(socket), isContentDirectory = false)
            request.method == "SUBSCRIBE" && isEventPath(path) -> handleSubscribe(output, request)
            request.method == "UNSUBSCRIBE" && isEventPath(path) -> writeEmpty(output, 200, "OK")
            request.method == "GET" && path.startsWith("/media/") ->
                handleMedia(output, request, rawPath.substringAfter("/media/"), headOnly = false)
            request.method == "HEAD" && path.startsWith("/media/") ->
                handleMedia(output, request, rawPath.substringAfter("/media/"), headOnly = true)
            request.method == "OPTIONS" -> {
                writeEmpty(output, 200, "OK", extraHeaders = listOf("Allow: GET, HEAD, POST, SUBSCRIBE, UNSUBSCRIBE, OPTIONS"))
            }
            else -> writeText(output, 404, "Not Found", "Not Found", "text/plain; charset=utf-8")
        }
    }

    private fun handleSoap(
        output: BufferedOutputStream,
        request: HttpRequest,
        baseUrl: String,
        isContentDirectory: Boolean,
    ) {
        try {
            val parsed = SoapXml.parseAction(request.body.toString(StandardCharsets.UTF_8))
            val headerAction = request.headers["soapaction"]
                ?.trim()?.trim('"')?.substringAfter('#', "")?.trim()
            if (!headerAction.isNullOrBlank() && !headerAction.equals(parsed.actionName, ignoreCase = true)) {
                throw UpnpFault(401, "SOAPAction does not match body")
            }
            val serviceType = if (isContentDirectory) {
                UpnpXml.CONTENT_DIRECTORY_TYPE
            } else {
                UpnpXml.CONNECTION_MANAGER_TYPE
            }
            val outputs = if (isContentDirectory) {
                contentDirectory.handle(parsed.actionName, parsed.arguments, baseUrl)
            } else {
                connectionManager.handle(parsed.actionName, parsed.arguments)
            }
            val response = SoapXml.response(parsed.actionName, serviceType, outputs)
            writeText(output, 200, "OK", response, "text/xml; charset=\"utf-8\"")
        } catch (fault: UpnpFault) {
            val response = SoapXml.fault(fault)
            writeText(
                output,
                500,
                "Internal Server Error",
                response,
                "text/xml; charset=\"utf-8\"",
                listOf("EXT:"),
            )
        } catch (error: Exception) {
            val response = SoapXml.fault(UpnpFault(501, "Action Failed"))
            writeText(output, 500, "Internal Server Error", response, "text/xml; charset=\"utf-8\"")
        }
    }

    private fun handleSubscribe(output: BufferedOutputStream, request: HttpRequest) {
        val sid = request.headers["sid"] ?: "uuid:${UUID.randomUUID()}"
        val timeout = request.headers["timeout"]?.takeIf { it.startsWith("Second-", true) } ?: "Second-1800"
        val bytes = ByteArray(0)
        output.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("SID: $sid\r\nTIMEOUT: $timeout\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun handleMedia(output: BufferedOutputStream, request: HttpRequest, token: String, headOnly: Boolean) {
        if (token.isBlank() || token.contains('/') || token.contains("..")) {
            writeText(output, 404, "Not Found", "Media not found", "text/plain; charset=utf-8")
            return
        }
        val media = try {
            repository.openMedia(token)
        } catch (error: Exception) {
            writeText(output, 404, "Not Found", "Media not found", "text/plain; charset=utf-8")
            return
        }
        media.use { opened ->
            val size = opened.length
            val rangeHeader = request.headers["range"]
            val selectedRange = if (rangeHeader == null) null else parseRange(rangeHeader, size)
            if (rangeHeader != null && selectedRange == null) {
                val contentRange = if (size >= 0) "Content-Range: bytes */$size" else null
                writeText(
                    output,
                    416,
                    "Range Not Satisfiable",
                    "Requested byte range cannot be served",
                    "text/plain; charset=utf-8",
                    listOfNotNull("Accept-Ranges: bytes", contentRange),
                )
                return
            }

            val start = selectedRange?.start ?: 0L
            val responseLength = when {
                selectedRange != null -> selectedRange.length
                size >= 0 -> size
                else -> -1L
            }
            try {
                // SAF descriptors may be pipe-backed. Normal local files are seekable; if a range
                // was explicitly requested and seeking is unavailable, fail before sending headers.
                if (selectedRange != null || opened.startOffset > 0) opened.seek(start)
            } catch (_: Exception) {
                writeText(
                    output,
                    416,
                    "Range Not Satisfiable",
                    "Selected storage provider does not support seeking this file",
                    "text/plain; charset=utf-8",
                    if (size >= 0) listOf("Accept-Ranges: bytes", "Content-Range: bytes */$size") else listOf("Accept-Ranges: none"),
                )
                return
            }

            val mediaNode = repository.metadataNodeForToken(token)
            val mime = UpnpXml.mediaMimeType(mediaNode?.title ?: "media.bin", mediaNode?.mimeType)
            val status = if (selectedRange == null) 200 else 206
            val reason = if (status == 206) "Partial Content" else "OK"
            val headers = ArrayList<String>()
            headers += "Content-Type: $mime"
            headers += "Accept-Ranges: bytes"
            headers += "transferMode.dlna.org: Streaming"
            headers += "Connection: close"
            headers += "Server: M36MediaServer/1.0"
            if (selectedRange != null) {
                headers += "Content-Range: bytes ${selectedRange.start}-${selectedRange.endInclusive}/$size"
            }
            if (responseLength >= 0) headers += "Content-Length: $responseLength"
            if (headOnly) {
                writeHead(output, status, reason, headers)
                return
            }
            if (responseLength < 0) headers += "Transfer-Encoding: chunked"
            writeHead(output, status, reason, headers)
            if (responseLength < 0) streamChunked(opened, output) else streamExact(opened, output, responseLength)
        }
    }

    private fun streamExact(media: OpenedMedia, output: BufferedOutputStream, length: Long) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = media.stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
        output.flush()
    }

    private fun streamChunked(media: OpenedMedia, output: BufferedOutputStream) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        while (true) {
            val read = media.stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(Integer.toHexString(read).toByteArray(StandardCharsets.US_ASCII))
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(buffer, 0, read)
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
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

    private fun baseUrlFor(socket: Socket): String {
        val local = socket.localAddress
        val address = (local as? Inet4Address)?.takeUnless { it.isAnyLocalAddress }?.hostAddress
            ?: networkSnapshot().primaryAddress?.hostAddress
            ?: "127.0.0.1"
        return "http://$address:$PORT"
    }

    private fun isContentDirectoryControl(path: String): Boolean =
        path == "/upnp/control/contentdirectory" || path == "/contentdirectory/control"

    private fun isConnectionManagerControl(path: String): Boolean =
        path == "/upnp/control/connectionmanager" || path == "/connectionmanager/control"

    private fun isEventPath(path: String): Boolean = path == "/upnp/event/contentdirectory" || path == "/upnp/event/connectionmanager"

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES) ?: return null
        if (requestLine.isBlank()) return null
        val requestParts = requestLine.trim().split(Regex("\\s+"), limit = 3)
        if (requestParts.size < 2) throw IOException("Malformed HTTP request line")
        val method = requestParts[0].uppercase(Locale.ROOT)
        val rawTarget = requestParts[1]
        val path = normalizePath(rawTarget)
        val headers = LinkedHashMap<String, String>()
        var consumedBytes = requestLine.length
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES) ?: throw IOException("Truncated HTTP headers")
            consumedBytes += line.length + 2
            if (consumedBytes > MAX_HEADER_BYTES) throw RequestTooLargeException()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (name !in headers) headers[name] = value
        }
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            throw IOException("Chunked request bodies are not supported")
        }
        val contentLength = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (contentLength > MAX_REQUEST_BODY_BYTES) throw RequestTooLargeException()
        val body = ByteArray(contentLength.toInt())
        if (body.isNotEmpty()) DataInputStream(input).readFully(body)
        return HttpRequest(method, rawTarget, path, headers, body)
    }

    private fun normalizePath(target: String): String {
        return try {
            val uri = URI(target)
            (uri.rawPath?.takeIf { it.isNotBlank() } ?: "/").substringBefore('#')
        } catch (_: Exception) {
            target.substringBefore('?').substringBefore('#').ifBlank { "/" }
        }
    }

    private fun readAsciiLine(input: BufferedInputStream, maxBytes: Int): String? {
        val result = ByteArrayOutputStream()
        while (result.size() <= maxBytes) {
            val value = input.read()
            if (value == -1) return if (result.size() == 0) null else throw IOException("Truncated HTTP line")
            if (value == '\n'.code) break
            if (value != '\r'.code) result.write(value)
        }
        if (result.size() > maxBytes) throw RequestTooLargeException()
        return result.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun writeText(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
        extraHeaders: List<String> = emptyList(),
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = ArrayList<String>(extraHeaders.size + 3)
        headers += "Content-Type: $contentType"
        headers += "Content-Length: ${bytes.size}"
        headers += "Connection: close"
        headers += "Server: M36MediaServer/1.0"
        headers += extraHeaders
        writeHead(output, status, reason, headers)
        output.write(bytes)
        output.flush()
    }

    private fun writeText(socketOutput: java.io.OutputStream, status: Int, reason: String, body: String) {
        val output = BufferedOutputStream(socketOutput)
        writeText(output, status, reason, body)
    }

    private fun writeEmpty(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        extraHeaders: List<String> = emptyList(),
    ) {
        writeHead(output, status, reason, listOf("Content-Length: 0", "Connection: close", "Server: M36MediaServer/1.0") + extraHeaders)
        output.flush()
    }

    private fun writeHead(output: BufferedOutputStream, status: Int, reason: String, headers: List<String>) {
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Date: ${httpDate()}\r\n".toByteArray(StandardCharsets.US_ASCII))
        headers.forEach { header -> output.write("$header\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
    }

    private fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }
        .format(Date())

    private data class HttpRequest(
        val method: String,
        val target: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    private class RequestTooLargeException : IOException("Request exceeded configured size limit")

    companion object {
        const val PORT = 8200
        private const val ACCEPT_BACKLOG = 64
        private const val IO_BUFFER_SIZE = 128 * 1024
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_REQUEST_BODY_BYTES = 1024 * 1024

        private fun namedThreadFactory(prefix: String): ThreadFactory {
            var index = 0
            return ThreadFactory { task -> Thread(task, "$prefix-${++index}").apply { isDaemon = true } }
        }
    }
}
