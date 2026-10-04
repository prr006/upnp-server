package com.m36.mediaserver.http

import android.content.Context
import com.m36.mediaserver.data.ServerMetrics
import com.m36.mediaserver.media.DocumentTreeRepository
import com.m36.mediaserver.network.NetworkSnapshot
import com.m36.mediaserver.upnp.ConnectionManagerService
import com.m36.mediaserver.upnp.ContentDirectoryService
import com.m36.mediaserver.upnp.SoapServiceVersion
import com.m36.mediaserver.upnp.SoapXml
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
import java.util.Locale
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
    private val contentDirectory = ContentDirectoryService(repository) { request, result, safTraversal ->
        metrics.lastContentDirectoryBrowseRequest = request
        metrics.lastContentDirectoryBrowseResult = result
        metrics.lastSafEnumeration = safTraversal
    }
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
        if (isContentDirectoryControl(path)) {
            metrics.contentDirectoryControlRequestCount.incrementAndGet()
            metrics.lastContentDirectoryControlRequest =
                "${request.method} ${request.path} from ${socket.inetAddress.hostAddress}"
        }
        when {
            request.method == "GET" && (path == "/" || path == "/rootdesc.xml" || path == "/devicedesc.xml") -> {
                val baseUrl = baseUrlFor(socket)
                if (path == "/rootdesc.xml") {
                    metrics.rootDescriptionGetCount.incrementAndGet()
                    metrics.lastRootDescriptionGet =
                        "GET ${request.path} from ${socket.inetAddress.hostAddress} (served at $baseUrl)"
                    val endpoints = UpnpXml.contentDirectoryEndpoints(baseUrl)
                    metrics.advertisedContentDirectoryServiceType = endpoints.serviceType
                    metrics.advertisedContentDirectoryServiceId = endpoints.serviceId
                    metrics.advertisedContentDirectoryScpdUrl =
                        "${endpoints.scpdUrl} -> ${endpoints.resolvedScpdUrl}"
                    metrics.advertisedContentDirectoryControlUrl =
                        "${endpoints.controlUrl} -> ${endpoints.resolvedControlUrl}"
                    metrics.advertisedContentDirectoryEventSubUrl =
                        "${endpoints.eventSubUrl} -> ${endpoints.resolvedEventSubUrl}"
                }
                writeText(output, 200, "OK", UpnpXml.rootDescription(deviceUuid, baseUrl), "text/xml; charset=\"utf-8\"")
            }
            request.method == "GET" && isContentDirectoryScpd(path) -> {
                metrics.contentDirectoryScpdGetCount.incrementAndGet()
                metrics.lastContentDirectoryScpdGet =
                    "GET ${request.path} from ${socket.inetAddress.hostAddress} (200 OK)"
                writeText(output, 200, "OK", UpnpXml.contentDirectoryScpd(), "text/xml; charset=\"utf-8\"")
            }
            request.method == "GET" && path == "/connectionmanager/scpd.xml" ->
                writeText(output, 200, "OK", UpnpXml.connectionManagerScpd(), "text/xml; charset=\"utf-8\"")
            request.method == "POST" && isContentDirectoryControl(path) ->
                handleSoap(
                    output,
                    request,
                    baseUrlFor(socket),
                    socket.inetAddress.hostAddress,
                    isContentDirectory = true,
                )
            request.method == "POST" && isConnectionManagerControl(path) ->
                handleSoap(
                    output,
                    request,
                    baseUrlFor(socket),
                    socket.inetAddress.hostAddress,
                    isContentDirectory = false,
                )
            request.method == "SUBSCRIBE" && isEventPath(path) -> handleSubscribe(output, request)
            request.method == "UNSUBSCRIBE" && isEventPath(path) -> writeEmpty(output, 200, "OK")
            request.method == "GET" && path.startsWith("/media/") ->
                handleMedia(
                    output,
                    request,
                    rawPath.substringAfter("/media/"),
                    headOnly = false,
                    clientAddress = socket.inetAddress.hostAddress,
                )
            request.method == "HEAD" && path.startsWith("/media/") ->
                handleMedia(
                    output,
                    request,
                    rawPath.substringAfter("/media/"),
                    headOnly = true,
                    clientAddress = socket.inetAddress.hostAddress,
                )
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
        clientAddress: String,
        isContentDirectory: Boolean,
    ) {
        val rawSoapAction = request.rawHeaders["soapaction"] ?: request.headers["soapaction"]
        val contentType = request.rawHeaders["content-type"] ?: request.headers["content-type"]
        val soapBody = request.body.toString(StandardCharsets.UTF_8)
        val expectedService = if (isContentDirectory) {
            SoapServiceVersion.CONTENT_DIRECTORY_1
        } else {
            SoapServiceVersion.CONNECTION_MANAGER_1
        }
        // Content-Type is diagnostic only, and SOAPAction is a hint only. The body namespace and
        // local action name determine dispatch; quoted/unquoted header formatting cannot reject it.
        val result = SoapXml.dispatch(soapBody, expectedService) { parsed ->
            if (isContentDirectory) {
                contentDirectory.handle(parsed.actionName, parsed.arguments, baseUrl)
            } else {
                connectionManager.handle(parsed.actionName, parsed.arguments)
            }
        }
        val parsedRequest = result.request
        val actionName = parsedRequest?.actionName
        val actionNamespace = parsedRequest?.actionNamespace
        val upnpErrorCode = result.upnpFault?.errorCode
        val responseHeaders = if (result.upnpFault == null) emptyList() else listOf("EXT:")
        val recognition = if (!isContentDirectory) {
            "—"
        } else {
            val actionClassification = actionName?.let(::recognizeContentDirectoryAction) ?: "SOAP body not parsed"
            val serviceDescription = when (parsedRequest?.serviceVersion) {
                SoapServiceVersion.CONTENT_DIRECTORY_1 -> "ContentDirectory:1"
                SoapServiceVersion.CONNECTION_MANAGER_1 -> "ConnectionManager:1"
                SoapServiceVersion.UNKNOWN -> "unknown namespace ${parsedRequest?.actionNamespace ?: "—"}"
                null -> "unknown service"
            }
            val baseRecognition = "$actionClassification ($serviceDescription)"
            if (result.upnpFault != null) {
                "$baseRecognition rejected: UPnP ${result.upnpFault.errorCode} ${result.upnpFault.message}"
            } else {
                val headerActionName = SoapXml.actionNameFromSoapAction(rawSoapAction)
                val headerNote = when {
                    rawSoapAction.isNullOrBlank() -> "SOAPAction missing; dispatched from SOAP body"
                    headerActionName == null -> "SOAPAction unparsed; dispatched from SOAP body"
                    headerActionName.equals(actionName, ignoreCase = true) -> "SOAPAction matches SOAP body"
                    else -> "SOAPAction/body mismatch (header action=$headerActionName); body action dispatched"
                }
                "$baseRecognition; $headerNote"
            }
        }

        var responseWritten = false
        try {
            writeText(
                output,
                result.httpStatus,
                result.reasonPhrase,
                result.responseXml,
                "text/xml; charset=\"utf-8\"",
                responseHeaders,
            )
            responseWritten = true
        } finally {
            if (isContentDirectory) {
                val requestDescription = "POST ${request.path} from $clientAddress"
                val status = buildString {
                    append(result.httpStatus).append(' ').append(result.reasonPhrase)
                    upnpErrorCode?.let { append(" (UPnP fault ").append(it).append(')') }
                    result.processingError?.let { append(" (processing error: ").append(it).append(')') }
                    if (!responseWritten) append(" (response write failed)")
                }
                metrics.recordContentDirectorySoapTransaction(
                    request = requestDescription,
                    responseStatus = status,
                    soapAction = rawSoapAction,
                    contentType = contentType,
                    body = soapBody,
                    actionName = actionName,
                    actionNamespace = actionNamespace,
                    recognition = recognition,
                )
            }
        }
    }

    private fun recognizeContentDirectoryAction(actionName: String): String = when (actionName) {
        "Browse" -> "Browse"
        "GetSystemUpdateID" -> "GetSystemUpdateID"
        "GetSearchCapabilities" -> "GetSearchCapabilities"
        "GetSortCapabilities" -> "GetSortCapabilities"
        else -> "Other: $actionName"
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

    private fun handleMedia(
        output: BufferedOutputStream,
        request: HttpRequest,
        token: String,
        headOnly: Boolean,
        clientAddress: String,
    ) {
        val rangeHeader = request.headers["range"]
        val result = if (token.isBlank() || token.contains('/') || token.contains("..")) {
            writeMediaNotFound(output, headOnly, error = "Invalid media resource ID")
        } else {
            val openAttempt = runCatching { repository.openMedia(token) }
            val opened = openAttempt.getOrNull()
            if (opened == null) {
                val cause = openAttempt.exceptionOrNull()?.let { ": ${it.message ?: it.javaClass.simpleName}" }.orEmpty()
                writeMediaNotFound(output, headOnly, error = "SAF media resource could not be opened$cause")
            } else {
                opened.use { media ->
                    val node = repository.metadataNodeForToken(token)
                    val transfer = MediaHttpResponseWriter.serve(
                        output = output,
                        method = if (headOnly) "HEAD" else request.method,
                        rangeHeader = rangeHeader,
                        title = node?.title ?: "media.bin",
                        reportedMimeType = node?.mimeType,
                        media = media,
                    )
                    val listedSize = node?.size?.takeIf { it >= 0 }
                    if (listedSize != null && media.length >= 0 && listedSize != media.length) {
                        transfer.copy(
                            error = listOfNotNull(
                                transfer.error,
                                "SAF-listed size=$listedSize differs from opened media length=${media.length}",
                            ).joinToString("; "),
                        )
                    } else transfer
                }
            }
        }
        metrics.recordMediaHttpExchange(
            clientAddress = clientAddress,
            resourcePath = request.path,
            resourceId = token,
            resourceName = repository.metadataNodeForToken(token)?.title,
            method = request.method,
            rangeHeader = rangeHeader,
            responseStatus = result.status,
            responseReason = result.reason,
            contentType = result.contentType,
            contentLength = result.contentLength,
            contentRange = result.contentRange,
            acceptRanges = result.acceptRanges,
            byteOffset = result.byteOffset,
            sourceByteOffset = result.sourceByteOffset,
            bytesServed = result.bytesServed,
            complete = result.complete,
            detail = result.error,
        )
    }

    private fun writeMediaNotFound(
        output: BufferedOutputStream,
        headOnly: Boolean,
        error: String,
    ): MediaHttpTransferResult {
        val message = "Media not found"
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val contentType = "text/plain; charset=utf-8"
        writeHead(
            output,
            404,
            "Not Found",
            listOf(
                "Content-Type: $contentType",
                "Content-Length: ${body.size}",
                "Connection: close",
                "Server: M36MediaServer/1.0",
            ),
        )
        if (!headOnly) output.write(body)
        output.flush()
        return MediaHttpTransferResult(
            status = 404,
            reason = "Not Found",
            contentType = contentType,
            contentLength = body.size.toLong(),
            contentRange = null,
            acceptRanges = null,
            byteOffset = null,
            sourceByteOffset = null,
            bytesServed = 0,
            complete = true,
            error = error,
        )
    }

    private fun baseUrlFor(socket: Socket): String {
        val local = socket.localAddress
        val address = (local as? Inet4Address)?.takeUnless { it.isAnyLocalAddress }?.hostAddress
            ?: networkSnapshot().primaryAddress?.hostAddress
            ?: "127.0.0.1"
        return "http://$address:$PORT"
    }

    private fun isContentDirectoryScpd(path: String): Boolean =
        path == UpnpXml.CONTENT_DIRECTORY_SCPD_PATH.lowercase(Locale.ROOT)

    private fun isContentDirectoryControl(path: String): Boolean =
        path == UpnpXml.CONTENT_DIRECTORY_CONTROL_PATH.lowercase(Locale.ROOT) ||
            path == "/contentdirectory/control"

    private fun isConnectionManagerControl(path: String): Boolean =
        path == "/upnp/control/connectionmanager" || path == "/connectionmanager/control"

    private fun isEventPath(path: String): Boolean =
        path == UpnpXml.CONTENT_DIRECTORY_EVENT_PATH.lowercase(Locale.ROOT) ||
            path == "/upnp/event/connectionmanager"

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES) ?: return null
        if (requestLine.isBlank()) return null
        val requestParts = requestLine.trim().split(Regex("\\s+"), limit = 3)
        if (requestParts.size < 2) throw IOException("Malformed HTTP request line")
        val method = requestParts[0].uppercase(Locale.ROOT)
        val rawTarget = requestParts[1]
        val path = normalizePath(rawTarget)
        val headers = LinkedHashMap<String, String>()
        val rawHeaders = LinkedHashMap<String, String>()
        var consumedBytes = requestLine.length
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES) ?: throw IOException("Truncated HTTP headers")
            consumedBytes += line.length + 2
            if (consumedBytes > MAX_HEADER_BYTES) throw RequestTooLargeException()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val rawValue = line.substring(colon + 1)
            if (name !in headers) {
                headers[name] = rawValue.trim()
                rawHeaders[name] = rawValue
            }
        }
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
            throw IOException("Chunked request bodies are not supported")
        }
        val contentLength = headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (contentLength > MAX_REQUEST_BODY_BYTES) throw RequestTooLargeException()
        val body = ByteArray(contentLength.toInt())
        if (body.isNotEmpty()) DataInputStream(input).readFully(body)
        return HttpRequest(method, rawTarget, path, headers, rawHeaders, body)
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

    private fun writeHead(output: BufferedOutputStream, status: Int, reason: String, headers: List<String>) =
        writeHttpResponseHead(output, status, reason, headers)

    private data class HttpRequest(
        val method: String,
        val target: String,
        val path: String,
        val headers: Map<String, String>,
        val rawHeaders: Map<String, String>,
        val body: ByteArray,
    )

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
