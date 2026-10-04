package com.m36.mediaserver.upnp

import com.m36.mediaserver.media.MediaCatalog
import com.m36.mediaserver.media.MediaNode
import java.io.StringReader
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class UpnpFault(val errorCode: Int, override val message: String) : Exception(message)

enum class SoapServiceVersion(val namespaceUri: String) {
    CONTENT_DIRECTORY_1("urn:schemas-upnp-org:service:ContentDirectory:1"),
    CONNECTION_MANAGER_1("urn:schemas-upnp-org:service:ConnectionManager:1"),
    UNKNOWN(""),
}

data class SoapActionRequest(
    val actionName: String,
    val arguments: Map<String, String>,
    val actionNamespace: String,
    val envelopeNamespace: String,
    val serviceVersion: SoapServiceVersion,
)

data class SoapDispatchResult(
    val request: SoapActionRequest?,
    val httpStatus: Int,
    val reasonPhrase: String,
    val responseXml: String,
    val upnpFault: UpnpFault? = null,
    val processingError: String? = null,
)

object SoapXml {
    private const val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val CONTENT_DIRECTORY_NS = "urn:schemas-upnp-org:service:ContentDirectory:1"
    private const val CONNECTION_MANAGER_NS = "urn:schemas-upnp-org:service:ConnectionManager:1"
    private val FORBIDDEN_XML_DECLARATION = Regex("<!\\s*(?:DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)

    /** Parse by XML namespace URI and local names, never by the sender's chosen prefixes. */
    fun parseAction(xml: String): SoapActionRequest {
        val normalizedXml = xml.removePrefix("\uFEFF")
        if (normalizedXml.isBlank()) throw UpnpFault(402, "Invalid Args: empty SOAP body")
        if (FORBIDDEN_XML_DECLARATION.containsMatchIn(normalizedXml)) {
            throw UpnpFault(402, "Invalid Args: DTD and entity declarations are not allowed")
        }

        try {
            // Use the same namespace-aware pull parser on Android and in JVM unit tests. No JAXP
            // FEATURE_SECURE_PROCESSING or provider-specific parser features are required.
            val parser = KXmlParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(StringReader(normalizedXml))
            }
            var envelopeNamespace: String? = null
            var bodyDepth: Int? = null
            var actionDepth: Int? = null
            var actionClosed = false
            var actionName: String? = null
            var actionNamespace = ""
            val arguments = LinkedHashMap<String, String>()
            var argumentDepth: Int? = null
            var argumentName: String? = null
            val argumentText = StringBuilder()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val depth = parser.depth
                        val name = parser.name.orEmpty()
                        val namespace = parser.namespace.orEmpty()
                        val currentBodyDepth = bodyDepth
                        val currentActionDepth = actionDepth
                        val currentArgumentDepth = argumentDepth
                        when {
                            depth == 1 && envelopeNamespace == null -> {
                                if (name != "Envelope" || namespace != SOAP_NS) {
                                    throw UpnpFault(402, "Invalid Args: SOAP 1.1 Envelope namespace missing")
                                }
                                envelopeNamespace = namespace
                            }
                            envelopeNamespace == null ->
                                throw UpnpFault(402, "Invalid Args: SOAP Envelope missing")
                            currentBodyDepth == null && depth == 2 && name == "Body" && namespace == envelopeNamespace ->
                                bodyDepth = depth
                            currentBodyDepth != null && currentActionDepth == null &&
                                depth == currentBodyDepth + 1 -> {
                                actionDepth = depth
                                actionName = name.trim().takeIf { it.isNotEmpty() }
                                    ?: throw UpnpFault(401, "SOAP action name missing")
                                actionNamespace = namespace
                            }
                            currentActionDepth != null && !actionClosed && currentArgumentDepth == null &&
                                depth == currentActionDepth + 1 -> {
                                argumentDepth = depth
                                argumentName = name.trim()
                                argumentText.setLength(0)
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        val currentArgumentDepth = argumentDepth
                        if (currentArgumentDepth != null && parser.depth >= currentArgumentDepth) {
                            argumentText.append(parser.text.orEmpty())
                        }
                    }
                    XmlPullParser.ENTITY_REF -> {
                        // With DTD declarations rejected above, only built-in and numeric XML
                        // references can be resolved safely. Unknown/external references have no text.
                        val replacement = parser.text
                            ?: throw UpnpFault(402, "Invalid Args: unresolved entity references are not allowed")
                        val currentArgumentDepth = argumentDepth
                        if (currentArgumentDepth != null && parser.depth >= currentArgumentDepth) {
                            argumentText.append(replacement)
                        }
                    }
                    XmlPullParser.DOCDECL ->
                        throw UpnpFault(402, "Invalid Args: DTD declarations are not allowed")
                    XmlPullParser.END_TAG -> {
                        if (argumentDepth != null && parser.depth == argumentDepth) {
                            argumentName?.takeIf { it.isNotEmpty() }?.let { name ->
                                arguments[name] = argumentText.toString().trim()
                            }
                            argumentDepth = null
                            argumentName = null
                            argumentText.setLength(0)
                        }
                        if (actionDepth != null && parser.depth == actionDepth) {
                            actionClosed = true
                        }
                    }
                }
                event = parser.nextToken()
            }

            val parsedActionName = actionName ?: throw UpnpFault(401, "SOAP action missing")
            val parsedEnvelopeNamespace = envelopeNamespace
                ?: throw UpnpFault(402, "Invalid Args: SOAP Envelope missing")
            if (bodyDepth == null) throw UpnpFault(402, "Invalid Args: SOAP Body missing")
            return SoapActionRequest(
                actionName = parsedActionName,
                arguments = arguments,
                actionNamespace = actionNamespace,
                envelopeNamespace = parsedEnvelopeNamespace,
                serviceVersion = serviceVersionForNamespace(actionNamespace),
            )
        } catch (fault: UpnpFault) {
            throw fault
        } catch (error: Exception) {
            throw UpnpFault(402, "Invalid Args: ${error.message ?: "malformed SOAP XML"}")
        }
    }

    /** Dispatch from the SOAP body namespace and local action name; SOAPAction is not required. */
    fun dispatch(
        xml: String,
        expectedService: SoapServiceVersion,
        actionHandler: (SoapActionRequest) -> List<Pair<String, String>>,
    ): SoapDispatchResult {
        var request: SoapActionRequest? = null
        return try {
            val parsedRequest = parseAction(xml)
            request = parsedRequest
            if (parsedRequest.serviceVersion != expectedService) {
                throw UpnpFault(401, "Invalid Action namespace for ${expectedService.name}")
            }
            val outputs = actionHandler(parsedRequest)
            SoapDispatchResult(
                request = parsedRequest,
                httpStatus = 200,
                reasonPhrase = "OK",
                responseXml = response(parsedRequest.actionName, expectedService.namespaceUri, outputs),
            )
        } catch (fault: UpnpFault) {
            SoapDispatchResult(
                request = request,
                httpStatus = 500,
                reasonPhrase = "Internal Server Error",
                responseXml = SoapXml.fault(fault),
                upnpFault = fault,
            )
        } catch (error: Exception) {
            val fault = UpnpFault(501, "Action Failed")
            SoapDispatchResult(
                request = request,
                httpStatus = 500,
                reasonPhrase = "Internal Server Error",
                responseXml = SoapXml.fault(fault),
                upnpFault = fault,
                processingError = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    fun serviceVersionForNamespace(namespaceUri: String): SoapServiceVersion = when (namespaceUri) {
        CONTENT_DIRECTORY_NS -> SoapServiceVersion.CONTENT_DIRECTORY_1
        CONNECTION_MANAGER_NS -> SoapServiceVersion.CONNECTION_MANAGER_1
        else -> SoapServiceVersion.UNKNOWN
    }

    /** SOAPAction is a hint; parse quoted or unquoted values for diagnostics/consistency checks. */
    fun actionNameFromSoapAction(rawValue: String?): String? {
        var value = rawValue?.trim().orEmpty()
        if (value.isEmpty() || value == "\"\"" || value == "''") return null
        while (value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        ) {
            value = value.substring(1, value.length - 1).trim()
        }
        val actionName = value.substringAfterLast('#', value).trim().trim('"', '\'').trim()
        return actionName.takeIf { it.isNotEmpty() }
    }

    fun response(actionName: String, serviceType: String, outputs: List<Pair<String, String>>): String {
        val body = outputs.joinToString("") { (name, value) -> "<$name>${xmlEscape(value)}</$name>" }
        return """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body><u:${xmlEscape(actionName)}Response xmlns:u="${xmlEscape(serviceType)}">$body</u:${xmlEscape(actionName)}Response></s:Body>
            </s:Envelope>""".trimIndent()
    }

    fun fault(fault: UpnpFault): String = """<?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>${fault.errorCode}</errorCode><errorDescription>${xmlEscape(fault.message)}</errorDescription></UPnPError></detail>
          </s:Fault></s:Body>
        </s:Envelope>""".trimIndent()

    fun serviceTypeFor(path: String): String = when {
        path.endsWith("contentdirectory", ignoreCase = true) -> CONTENT_DIRECTORY_NS
        else -> CONNECTION_MANAGER_NS
    }
}

class ContentDirectoryService(
    private val repository: MediaCatalog,
    private val onBrowseTrace: (request: String, result: String, safTraversal: String) -> Unit = { _, _, _ -> },
) {
    fun handle(actionName: String, arguments: Map<String, String>, baseUrl: String): List<Pair<String, String>> = when (actionName) {
        "Browse" -> browse(arguments, baseUrl)
        "GetSearchCapabilities" -> listOf("SearchCaps" to "")
        "GetSortCapabilities" -> listOf("SortCaps" to "dc:title")
        "GetSystemUpdateID" -> listOf("Id" to repository.systemUpdateId.toString())
        else -> throw UpnpFault(401, "Invalid Action")
    }

    private fun browse(arguments: Map<String, String>, baseUrl: String): List<Pair<String, String>> {
        val objectId = arguments["ObjectID"]?.takeIf { it.isNotBlank() } ?: "0"
        val browseFlag = arguments["BrowseFlag"]?.takeIf { it.isNotBlank() } ?: "BrowseDirectChildren"
        val filter = arguments["Filter"]?.trim()?.takeIf { it.isNotEmpty() } ?: "*"
        val startingIndex = parseIndex(arguments["StartingIndex"], "StartingIndex")
        val requestedCount = parseIndex(arguments["RequestedCount"], "RequestedCount")
        val sortCriteria = arguments["SortCriteria"].orEmpty().trim()
        val requestTrace = "Browse ObjectID=$objectId | BrowseFlag=$browseFlag | Filter=$filter | " +
            "StartingIndex=$startingIndex | RequestedCount=$requestedCount | SortCriteria=$sortCriteria"
        onBrowseTrace(requestTrace, "Browse processing", repository.lastEnumerationDiagnostics)

        try {
            val matching: List<MediaNode> = when (browseFlag) {
                "BrowseMetadata" -> try {
                    listOfNotNull(repository.metadata(objectId)).ifEmpty {
                        throw UpnpFault(701, "No Such Object")
                    }
                } catch (fault: UpnpFault) {
                    throw fault
                } catch (error: Exception) {
                    throw UpnpFault(720, "Cannot read object metadata: ${error.message ?: "storage provider error"}")
                }
                "BrowseDirectChildren" -> try {
                    // UPnP ObjectID=0 represents the user-selected shared directory itself;
                    // enumerate its children rather than exposing an extra synthetic folder row.
                    val catalogParentId = if (objectId == "0") SELECTED_SHARED_ROOT_ID else objectId
                    repository.children(catalogParentId)
                } catch (_: java.io.FileNotFoundException) {
                    throw UpnpFault(701, "No Such Object")
                } catch (error: Exception) {
                    throw UpnpFault(720, "Cannot enumerate directory: ${error.message ?: "storage provider error"}")
                }
                else -> throw UpnpFault(402, "Invalid Args: BrowseFlag")
            }

            val sorted = sortNodes(matching, sortCriteria)
            val total = sorted.size
            val page = if (browseFlag == "BrowseMetadata") {
                sorted
            } else {
                val from = startingIndex.coerceAtMost(total.toLong()).toInt()
                val to = if (requestedCount == 0L) {
                    total
                } else {
                    (from.toLong() + requestedCount).coerceAtMost(total.toLong()).toInt()
                }
                sorted.subList(from, to)
            }
            val normalizedBase = baseUrl.trimEnd('/')
            val emittedItems = page.map { node ->
                val resource = if (node.isContainer) null else {
                    val token = node.mediaToken?.takeIf { it.isNotBlank() }
                        ?: throw UpnpFault(720, "Media item has no HTTP token: ${node.objectId}")
                    "$normalizedBase/media/$token"
                }
                val outputNode = if (node.isContainer) node else node.copy(
                    mimeType = UpnpXml.mediaMimeType(node.title, node.mimeType),
                )
                node to UpnpXml.didlNode(outputNode, resource)
            }
            val didlNodes = emittedItems.joinToString("") { it.second }
            val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
                "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">$didlNodes</DIDL-Lite>"
            val updateId = repository.systemUpdateId
            val browseResult = buildString {
                append("NumberReturned=${page.size} | TotalMatches=$total | UpdateID=$updateId")
                val mediaItems = emittedItems.filter { !it.first.isContainer }
                if (mediaItems.isNotEmpty()) {
                    appendLine()
                    appendLine("DIDL-Lite media resource metadata (exact emitted item fragments):")
                    mediaItems.forEach { (node, fragment) ->
                        appendLine("ObjectID=${node.objectId} | title=${node.title}")
                        appendLine("  ${didlResourceSummary(fragment)}")
                        appendLine("  Exact DIDL item: $fragment")
                    }
                }
            }
            onBrowseTrace(
                requestTrace,
                browseResult,
                repository.lastEnumerationDiagnostics,
            )
            return listOf(
                "Result" to didl,
                "NumberReturned" to page.size.toString(),
                "TotalMatches" to total.toString(),
                "UpdateID" to updateId.toString(),
            )
        } catch (error: Exception) {
            val description = if (error is UpnpFault) {
                "Browse failed: UPnP ${error.errorCode} ${error.message}"
            } else {
                "Browse failed: ${error.message ?: error.javaClass.simpleName}"
            }
            onBrowseTrace(requestTrace, description, repository.lastEnumerationDiagnostics)
            if (error is UpnpFault) throw error
            throw UpnpFault(501, "Browse failed")
        }
    }

    /** Summarize fields parsed from the exact DIDL item fragment emitted to the SOAP Result. */
    private fun didlResourceSummary(fragment: String): String {
        val resourceTag = Regex("<res\\b([^>]*)>").find(fragment)
            ?: return "No <res> resource element emitted"
        val attributes = Regex("([A-Za-z_:][A-Za-z0-9_.:-]*)=\"([^\"]*)\"")
            .findAll(resourceTag.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2] }
        val protocolInfo = attributes["protocolInfo"]
        val itemClass = Regex("<upnp:class>([^<]*)</upnp:class>").find(fragment)?.groupValues?.get(1)
        val protocolParts = protocolInfo?.split(':', limit = 4).orEmpty()
        val dlnaParameters = protocolParts.getOrNull(3)
            ?.split(';')
            ?.mapNotNull { parameter ->
                val pair = parameter.split('=', limit = 2)
                pair.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }
            ?.toMap()
            .orEmpty()
        val contentStart = resourceTag.range.last + 1
        val contentEnd = fragment.indexOf("</res>", contentStart)
        val resourceUrl = if (contentEnd >= contentStart) fragment.substring(contentStart, contentEnd) else "(not present)"
        return buildString {
            append("UPnP class=${itemClass ?: "(not present)"}")
            append(" | protocolInfo=${protocolInfo ?: "(not present)"}")
            append(" | MIME=${protocolParts.getOrNull(2) ?: "(not present)"}")
            append(" | size=${attributes["size"] ?: "(not present)"}")
            append(" | duration=${attributes["duration"] ?: "(not present)"}")
            append(" | DLNA.ORG_OP=${dlnaParameters["DLNA.ORG_OP"] ?: "(not present)"}")
            append(" | DLNA.ORG_CI=${dlnaParameters["DLNA.ORG_CI"] ?: "(not present)"}")
            append(" | DLNA.ORG_FLAGS=${dlnaParameters["DLNA.ORG_FLAGS"] ?: "(not present)"}")
            append(" | DLNA.ORG_PN=${dlnaParameters["DLNA.ORG_PN"] ?: "(not present)"}")
            append(" | resource URL=$resourceUrl")
        }
    }

    private fun parseIndex(raw: String?, name: String): Long {
        if (raw.isNullOrBlank()) return 0L
        return raw.toLongOrNull()?.takeIf { it in 0L..UINT32_MAX }
            ?: throw UpnpFault(402, "Invalid Args: $name")
    }

    private fun sortNodes(nodes: List<MediaNode>, criteria: String): List<MediaNode> {
        if (criteria.isBlank()) return nodes
        val parts = criteria.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.any { it.removePrefix("+").removePrefix("-") != "dc:title" }) {
            throw UpnpFault(709, "Unsupported or invalid sort criteria")
        }
        var sorted: List<MediaNode> = nodes
        // Apply keys from least to most significant, preserving stable ordering for multiple keys.
        parts.asReversed().forEach { part ->
            val comparator = compareBy<MediaNode, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                .thenBy { it.title }
            sorted = if (part.startsWith("-")) sorted.sortedWith(comparator.reversed()) else sorted.sortedWith(comparator)
        }
        return sorted
    }

    private companion object {
        const val SELECTED_SHARED_ROOT_ID = "shared-root"
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}

class ConnectionManagerService {
    fun handle(actionName: String, arguments: Map<String, String>): List<Pair<String, String>> = when (actionName) {
        "GetProtocolInfo" -> listOf(
            "Source" to "http-get:*:*:DLNA.ORG_OP=01;DLNA.ORG_CI=0",
            "Sink" to "",
        )
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> {
            if (arguments["ConnectionID"]?.toIntOrNull() != 0) {
                throw UpnpFault(706, "Invalid Connection Reference")
            }
            listOf(
                "RcsID" to "-1",
                "AVTransportID" to "-1",
                "ProtocolInfo" to "http-get:*:*:DLNA.ORG_OP=01;DLNA.ORG_CI=0",
                "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1",
                "Direction" to "Output",
                "Status" to "OK",
            )
        }
        else -> throw UpnpFault(401, "Invalid Action")
    }
}
