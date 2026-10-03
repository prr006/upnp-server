package com.m36.mediaserver.upnp

import com.m36.mediaserver.media.MediaNode
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

class UpnpXmlTest {
    @Test
    fun escapesXmlCharactersAndDropsIllegalControls() {
        assertEquals("A&amp;B &lt;episode&gt; &quot;quoted&quot;", xmlEscape("A&B <episode> \"quoted\""))
        assertEquals("ab", xmlEscape("a\u0000b"))
    }

    @Test
    fun mapsMatroskaFilesToVlcFriendlyMimeAndDidlRangeProtocol() {
        val node = MediaNode(
            objectId = "i:abcd",
            parentId = "d:root",
            documentId = "video.mkv",
            title = "Episode & One.mkv",
            isContainer = false,
            mimeType = "application/octet-stream",
            size = 4_000_000_000L,
            modifiedMillis = 0,
        )
        val output = UpnpXml.didlNode(
            node.copy(mimeType = UpnpXml.mediaMimeType(node.title, node.mimeType)),
            "http://192.0.2.2:8200/media/abcd",
        )
        assertTrue(output.contains("video/x-matroska"))
        assertTrue(output.contains("DLNA.ORG_OP=01"))
        assertTrue(output.contains("size=\"4000000000\""))
        assertTrue(output.contains("Episode &amp; One.mkv"))
    }

    @Test
    fun containersSerializeAnActualChildCount() {
        val node = MediaNode(
            objectId = "d:season1",
            parentId = "d:show",
            documentId = "season1",
            title = "Season 1",
            isContainer = true,
            mimeType = "vnd.android.document/directory",
            size = -1,
            modifiedMillis = 0,
            childCount = 22,
        )

        val output = UpnpXml.didlNode(node)
        assertTrue(output.startsWith("<container id=\"d:season1\" parentID=\"d:show\""))
        assertTrue(output.contains("childCount=\"22\""))
        assertTrue(output.contains("<upnp:class>object.container.storageFolder</upnp:class>"))
    }

    @Test
    fun rootDescriptionPublishesReachableDynamicContentDirectoryEndpoints() {
        val baseUrl = "http://10.221.18.195:8200"
        val description = UpnpXml.rootDescription(
            "11111111-2222-3333-4444-555555555555",
            baseUrl,
        )
        val root = parseXml(description).documentElement
        val endpoints = UpnpXml.contentDirectoryEndpoints(baseUrl)
        val services = root.getElementsByTagNameNS(DEVICE_NS, "service")
        val service = (0 until services.length)
            .map { services.item(it) as Element }
            .first { childText(it, "serviceType") == UpnpXml.CONTENT_DIRECTORY_TYPE }

        assertEquals("http://10.221.18.195:8200/", childText(root, "URLBase"))
        assertEquals("serviceList", service.parentNode.localName)
        assertEquals(UpnpXml.CONTENT_DIRECTORY_TYPE, childText(service, "serviceType"))
        assertEquals(UpnpXml.CONTENT_DIRECTORY_ID, childText(service, "serviceId"))
        assertEquals(endpoints.scpdUrl, childText(service, "SCPDURL"))
        assertEquals(endpoints.controlUrl, childText(service, "controlURL"))
        assertEquals(endpoints.eventSubUrl, childText(service, "eventSubURL"))
        assertEquals("http://10.221.18.195:8200${endpoints.scpdUrl}", endpoints.resolvedScpdUrl)
        assertEquals("http://10.221.18.195:8200${endpoints.controlUrl}", endpoints.resolvedControlUrl)
        assertEquals("http://10.221.18.195:8200${endpoints.eventSubUrl}", endpoints.resolvedEventSubUrl)
        val urlBase = URI(childText(root, "URLBase"))
        assertEquals(endpoints.resolvedScpdUrl, urlBase.resolve(childText(service, "SCPDURL")).toString())
        assertEquals(endpoints.resolvedControlUrl, urlBase.resolve(childText(service, "controlURL")).toString())
        assertEquals(endpoints.resolvedEventSubUrl, urlBase.resolve(childText(service, "eventSubURL")).toString())
        assertEquals("DMS-1.50", root.getElementsByTagNameNS(DLNA_DEVICE_NS, "X_DLNADOC").item(0).textContent)
        assertEquals("M36 Media Server", childText(root, "friendlyName"))
        assertEquals("urn:schemas-upnp-org:device:MediaServer:1", childText(root, "deviceType"))
        assertEquals("uuid:11111111-2222-3333-4444-555555555555", childText(root, "UDN"))

        val alternate = UpnpXml.contentDirectoryEndpoints("http://192.168.43.1:8200/")
        assertEquals("http://192.168.43.1:8200${alternate.scpdUrl}", alternate.resolvedScpdUrl)
    }

    @Test
    fun contentDirectoryScpdDeclaresRequiredActionsAndStandardsCorrectBrowseArguments() {
        val root = parseXml(UpnpXml.contentDirectoryScpd()).documentElement
        val actions = root.getElementsByTagNameNS(SERVICE_NS, "action")
        val actionNames = (0 until actions.length).map { childText(actions.item(it) as Element, "name") }.toSet()
        assertTrue(actionNames.containsAll(setOf("Browse", "GetSearchCapabilities", "GetSortCapabilities", "GetSystemUpdateID")))
        val actionElements = (0 until actions.length).map { actions.item(it) as Element }
        fun action(name: String) = actionElements.first { childText(it, "name") == name }
        fun arguments(action: Element): List<Element> {
            val nodes = action.getElementsByTagNameNS(SERVICE_NS, "argument")
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
        fun argumentNames(action: Element) = arguments(action).map { childText(it, "name") }
        fun argumentMappings(action: Element) = arguments(action).associate { argument ->
            childText(argument, "name") to
                (childText(argument, "direction") to childText(argument, "relatedStateVariable"))
        }

        assertEquals(listOf("SearchCaps"), argumentNames(action("GetSearchCapabilities")))
        assertEquals(
            mapOf("SearchCaps" to ("out" to "SearchCapabilities")),
            argumentMappings(action("GetSearchCapabilities")),
        )
        assertEquals(listOf("SortCaps"), argumentNames(action("GetSortCapabilities")))
        assertEquals(
            mapOf("SortCaps" to ("out" to "SortCapabilities")),
            argumentMappings(action("GetSortCapabilities")),
        )
        assertEquals(listOf("Id"), argumentNames(action("GetSystemUpdateID")))
        assertEquals(
            mapOf("Id" to ("out" to "SystemUpdateID")),
            argumentMappings(action("GetSystemUpdateID")),
        )

        val browse = action("Browse")
        val expectedBrowseArgumentNames = listOf(
            "ObjectID", "BrowseFlag", "Filter", "StartingIndex", "RequestedCount", "SortCriteria",
            "Result", "NumberReturned", "TotalMatches", "UpdateID",
        )
        assertEquals(expectedBrowseArgumentNames, argumentNames(browse))
        val expectedBrowseMappings = linkedMapOf(
            "ObjectID" to ("in" to "A_ARG_TYPE_ObjectID"),
            "BrowseFlag" to ("in" to "A_ARG_TYPE_BrowseFlag"),
            "Filter" to ("in" to "A_ARG_TYPE_Filter"),
            "StartingIndex" to ("in" to "A_ARG_TYPE_Index"),
            "RequestedCount" to ("in" to "A_ARG_TYPE_Count"),
            "SortCriteria" to ("in" to "A_ARG_TYPE_SortCriteria"),
            "Result" to ("out" to "A_ARG_TYPE_Result"),
            "NumberReturned" to ("out" to "A_ARG_TYPE_Count"),
            "TotalMatches" to ("out" to "A_ARG_TYPE_Count"),
            "UpdateID" to ("out" to "A_ARG_TYPE_UpdateID"),
        )
        val actualBrowseMappings = argumentMappings(browse)
        assertEquals(expectedBrowseMappings, actualBrowseMappings)
        assertEquals(6, actualBrowseMappings.values.count { it.first == "in" })
        assertEquals(4, actualBrowseMappings.values.count { it.first == "out" })
        assertEquals("A_ARG_TYPE_UpdateID", actualBrowseMappings.getValue("UpdateID").second)
        assertEquals("SystemUpdateID", argumentMappings(action("GetSystemUpdateID")).getValue("Id").second)

        val stateVariables = root.getElementsByTagNameNS(SERVICE_NS, "stateVariable")
        val stateVariableElements = (0 until stateVariables.length).map { stateVariables.item(it) as Element }
        val stateVariableByName = stateVariableElements.associateBy { childText(it, "name") }
        val browseFlag = stateVariableByName.getValue("A_ARG_TYPE_BrowseFlag")
        val allowedValues = browseFlag.getElementsByTagNameNS(SERVICE_NS, "allowedValue")
        assertEquals(
            listOf("BrowseMetadata", "BrowseDirectChildren"),
            (0 until allowedValues.length).map { allowedValues.item(it).textContent },
        )
        assertEquals("ui4", childText(stateVariableByName.getValue("A_ARG_TYPE_UpdateID"), "dataType"))
        assertEquals("ui4", childText(stateVariableByName.getValue("SystemUpdateID"), "dataType"))
    }

    @Test
    fun rootDescriptionAdvertisesBothServicesAndStableUdn() {
        val description = UpnpXml.rootDescription(
            "11111111-2222-3333-4444-555555555555",
            "http://192.0.2.2:8200",
        )
        assertTrue(description.contains("<friendlyName>M36 Media Server</friendlyName>"))
        assertTrue(description.contains("urn:schemas-upnp-org:device:MediaServer:1"))
        assertTrue(description.contains("urn:schemas-upnp-org:service:ContentDirectory:1"))
        assertTrue(description.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
        assertTrue(description.contains("uuid:11111111-2222-3333-4444-555555555555"))
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun childText(parent: Element, name: String): String =
        parent.getElementsByTagNameNS(if (name == "X_DLNADOC") DLNA_DEVICE_NS else DEVICE_NS, name)
            .item(0).textContent.trim()

    companion object {
        private const val DEVICE_NS = "urn:schemas-upnp-org:device-1-0"
        private const val DLNA_DEVICE_NS = "urn:schemas-dlna-org:device-1-0"
        private const val SERVICE_NS = "urn:schemas-upnp-org:service-1-0"
    }
}
