package com.m36.mediaserver.upnp

import com.m36.mediaserver.media.DocumentTreeRepository
import com.m36.mediaserver.media.MediaNode
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

class UpnpFault(val errorCode: Int, override val message: String) : Exception(message)

data class SoapActionRequest(val actionName: String, val arguments: Map<String, String>)

object SoapXml {
    private const val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val CONTENT_DIRECTORY_NS = "urn:schemas-upnp-org:service:ContentDirectory:1"
    private const val CONNECTION_MANAGER_NS = "urn:schemas-upnp-org:service:ConnectionManager:1"

    fun parseAction(xml: String): SoapActionRequest {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                setExpandEntityReferences(false)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val body = document.getElementsByTagNameNS(SOAP_NS, "Body").item(0) as? Element
                ?: throw UpnpFault(401, "SOAP Body missing")
            val action = body.childNodes.let { children ->
                (0 until children.length)
                    .mapNotNull { children.item(it) as? Element }
                    .firstOrNull()
            } ?: throw UpnpFault(401, "SOAP action missing")
            val actionName = action.localName ?: action.tagName.substringAfter(':')
            val arguments = LinkedHashMap<String, String>()
            val children = action.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                val name = element.localName ?: element.tagName.substringAfter(':')
                arguments[name] = element.textContent ?: ""
            }
            return SoapActionRequest(actionName, arguments)
        } catch (fault: UpnpFault) {
            throw fault
        } catch (error: Exception) {
            throw UpnpFault(402, "Invalid Args: ${error.message ?: "malformed SOAP XML"}")
        }
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

class ContentDirectoryService(private val repository: DocumentTreeRepository) {
    fun handle(actionName: String, arguments: Map<String, String>, baseUrl: String): List<Pair<String, String>> = when (actionName) {
        "Browse" -> browse(arguments, baseUrl)
        "GetSearchCapabilities" -> listOf("SearchCaps" to "")
        "GetSortCapabilities" -> listOf("SortCaps" to "dc:title")
        "GetSystemUpdateID" -> listOf("Id" to SYSTEM_UPDATE_ID.toString())
        else -> throw UpnpFault(401, "Invalid Action")
    }

    private fun browse(arguments: Map<String, String>, baseUrl: String): List<Pair<String, String>> {
        val objectId = arguments["ObjectID"]?.takeIf { it.isNotBlank() } ?: "0"
        val browseFlag = arguments["BrowseFlag"]?.takeIf { it.isNotBlank() } ?: "BrowseDirectChildren"
        val startingIndex = arguments["StartingIndex"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val requestedCount = arguments["RequestedCount"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val sortCriteria = arguments["SortCriteria"].orEmpty().trim()

        val matching: List<MediaNode> = when (browseFlag) {
            "BrowseMetadata" -> listOfNotNull(repository.metadata(objectId))
                .ifEmpty { throw UpnpFault(701, "No Such Object") }
            "BrowseDirectChildren" -> try {
                repository.children(objectId)
            } catch (_: java.io.FileNotFoundException) {
                throw UpnpFault(701, "No Such Object")
            } catch (error: Exception) {
                throw UpnpFault(720, "Cannot process directory: ${error.message ?: "storage provider error"}")
            }
            else -> throw UpnpFault(402, "Invalid Args: BrowseFlag")
        }

        val sorted = sortNodes(matching, sortCriteria)
        val total = sorted.size
        val page = if (browseFlag == "BrowseMetadata") {
            sorted
        } else {
            val from = startingIndex.coerceAtMost(total.toLong()).toInt()
            val to = if (requestedCount == 0L) total else (from.toLong() + requestedCount).coerceAtMost(total.toLong()).toInt()
            sorted.subList(from, to)
        }
        val normalizedBase = baseUrl.trimEnd('/')
        val didlNodes = page.joinToString("") { node ->
            val resource = if (node.isContainer) null else {
                val token = node.mediaToken ?: ""
                "$normalizedBase/media/$token"
            }
            val outputNode = if (node.isContainer) node else node.copy(
                mimeType = UpnpXml.mediaMimeType(node.title, node.mimeType),
            )
            UpnpXml.didlNode(outputNode, resource)
        }
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">$didlNodes</DIDL-Lite>"

        return listOf(
            "Result" to didl,
            "NumberReturned" to page.size.toString(),
            "TotalMatches" to total.toString(),
            "UpdateID" to SYSTEM_UPDATE_ID.toString(),
        )
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

    companion object {
        const val SYSTEM_UPDATE_ID = 1
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
