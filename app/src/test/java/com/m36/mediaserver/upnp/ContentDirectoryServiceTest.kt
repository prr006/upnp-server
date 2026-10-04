package com.m36.mediaserver.upnp

import com.m36.mediaserver.media.MediaCatalog
import com.m36.mediaserver.media.MediaNode
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

class ContentDirectoryServiceTest {
    @Test
    fun exactVlcBrowseSoapParsesWithoutJaxpFeaturesAndDispatchesValidBrowseResponse() {
        val soapAction = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
        val requestBody = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
<ObjectID>0</ObjectID>
<BrowseFlag>BrowseDirectChildren</BrowseFlag>
<Filter>*</Filter>
<StartingIndex>0</StartingIndex>
<RequestedCount>5000</RequestedCount>
<SortCriteria></SortCriteria>
</u:Browse>
</s:Body>
</s:Envelope>"""
        val service = ContentDirectoryService(TestCatalog())
        var dispatchedAction: String? = null
        val result = SoapXml.dispatch(
            requestBody,
            SoapServiceVersion.CONTENT_DIRECTORY_1,
        ) { request ->
            dispatchedAction = request.actionName
            service.handle(request.actionName, request.arguments, "http://10.221.18.195:8200")
        }

        assertEquals("Browse", SoapXml.actionNameFromSoapAction(soapAction))
        assertEquals("Browse", dispatchedAction)
        assertEquals(SoapServiceVersion.CONTENT_DIRECTORY_1, result.request?.serviceVersion)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:1", result.request?.actionNamespace)
        assertEquals(
            mapOf(
                "ObjectID" to "0",
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to "0",
                "RequestedCount" to "5000",
                "SortCriteria" to "",
            ),
            result.request?.arguments,
        )
        assertEquals(200, result.httpStatus)
        assertEquals("OK", result.reasonPhrase)
        assertNull(result.upnpFault)
        assertFalse(result.responseXml.contains("<errorCode>402</errorCode>"))
        assertTrue(result.responseXml.contains("<u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"))

        val soapDocument = parseDocument(result.responseXml)
        val browseResponse = soapDocument.getElementsByTagNameNS(
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "BrowseResponse",
        ).item(0)
        assertNotNull(browseResponse)
        val didlXml = soapDocument.getElementsByTagName("Result").item(0).textContent
        val didl = parseDocument(didlXml).documentElement
        assertEquals("DIDL-Lite", didl.localName)
        assertEquals("urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/", didl.namespaceURI)
        val containers = didl.getElementsByTagNameNS(didl.namespaceURI, "container")
        assertEquals(1, containers.length)
        val sakamoto = containers.item(0) as Element
        assertEquals("d:sakamoto", sakamoto.getAttribute("id"))
        assertEquals("shared-root", sakamoto.getAttribute("parentID"))
        assertEquals(
            "Sakamoto Days",
            sakamoto.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0).textContent,
        )
        assertEquals("1", soapDocument.getElementsByTagName("NumberReturned").item(0).textContent)
        assertEquals("1", soapDocument.getElementsByTagName("TotalMatches").item(0).textContent)
        assertEquals("17", soapDocument.getElementsByTagName("UpdateID").item(0).textContent)

        val nestedFolders = service.handle(
            "Browse",
            browseArgs("d:sakamoto", "BrowseDirectChildren"),
            "http://10.221.18.195:8200",
        ).toMap().getValue("Result")
        assertTrue(nestedFolders.contains("<container id=\"d:season1\" parentID=\"d:sakamoto\""))
        val nestedFiles = service.handle(
            "Browse",
            browseArgs("d:season1", "BrowseDirectChildren"),
            "http://10.221.18.195:8200",
        ).toMap().getValue("Result")
        assertWellFormedXml(nestedFiles)
        assertTrue(nestedFiles.contains("<item id=\"i:e01\" parentID=\"d:season1\""))
    }

    @Test
    fun directChildrenRecursivelyExposeFoldersAndVideoResourcesOnTheCurrentLanAddress() {
        val service = ContentDirectoryService(TestCatalog())
        val baseUrl = "http://10.221.18.195:8200"

        val selectedRootChildren = browse(service, "0", baseUrl)
        assertTrue(selectedRootChildren.contains("id=\"d:sakamoto\" parentID=\"shared-root\""))
        assertTrue(selectedRootChildren.contains("<dc:title>Sakamoto Days</dc:title>"))

        val show = browse(service, "shared-root", baseUrl)
        assertTrue(show.contains("<container id=\"d:sakamoto\" parentID=\"shared-root\""))
        assertTrue(show.contains("<dc:title>Sakamoto Days</dc:title>"))

        val season = browse(service, "d:sakamoto", baseUrl)
        assertTrue(season.contains("<container id=\"d:season1\" parentID=\"d:sakamoto\""))
        assertTrue(season.contains("childCount=\"22\""))

        val episodeOutputs = service.handle(
            "Browse",
            browseArgs("d:season1", "BrowseDirectChildren"),
            baseUrl,
        ).toMap()
        val episodes = episodeOutputs.getValue("Result")
        assertWellFormedXml(episodes)
        assertEquals("22", episodeOutputs["NumberReturned"])
        assertEquals("22", episodeOutputs["TotalMatches"])
        assertTrue(episodes.contains("<item id=\"i:e01\" parentID=\"d:season1\""))
        assertTrue(episodes.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(episodes.contains("protocolInfo=\"http-get:*:video/x-matroska:"))
        assertTrue(episodes.contains("http://10.221.18.195:8200/media/e01"))
    }

    @Test
    fun browseMetadataReturnsOneObjectAndItsRealChildCountAndUsesTheCurrentUpdateId() {
        val catalog = TestCatalog()
        val service = ContentDirectoryService(catalog)
        val outputs = service.handle(
            "Browse",
            browseArgs("d:season1", "BrowseMetadata"),
            "http://10.221.18.195:8200",
        ).toMap()

        assertEquals("1", outputs["NumberReturned"])
        assertEquals("1", outputs["TotalMatches"])
        assertTrue(outputs.getValue("Result").contains("childCount=\"22\""))
        assertEquals("17", outputs["UpdateID"])

        catalog.systemUpdateId = 18
        assertEquals("18", service.handle("GetSystemUpdateID", emptyMap(), "")[0].second)
        assertEquals("18", service.handle(
            "Browse",
            browseArgs("d:season1", "BrowseMetadata"),
            "http://10.221.18.195:8200",
        ).toMap()["UpdateID"])
    }

    @Test
    fun browseMetadataForAFileIncludesItsCurrentDynamicHttpResource() {
        val service = ContentDirectoryService(TestCatalog())
        val outputs = service.handle(
            "Browse",
            browseArgs("i:e01", "BrowseMetadata"),
            "http://10.221.18.195:8200",
        ).toMap()

        assertEquals("1", outputs["NumberReturned"])
        assertEquals("1", outputs["TotalMatches"])
        assertTrue(outputs.getValue("Result").contains("<item id=\"i:e01\" parentID=\"d:season1\""))
        assertTrue(outputs.getValue("Result").contains("video/x-matroska"))
        assertTrue(outputs.getValue("Result").contains("http://10.221.18.195:8200/media/e01"))

        val alternateAddressOutput = service.handle(
            "Browse",
            browseArgs("i:e01", "BrowseMetadata"),
            "http://192.168.43.1:8200",
        ).toMap().getValue("Result")
        assertTrue(alternateAddressOutput.contains("http://192.168.43.1:8200/media/e01"))
        assertFalse(alternateAddressOutput.contains("10.221.18.195"))
    }

    @Test
    fun paginationCountsAndTemporaryBrowseDiagnosticsReportTheActualRequestAndResult() {
        val traces = ArrayList<Triple<String, String, String>>()
        val catalog = TestCatalog()
        val service = ContentDirectoryService(catalog) { request, result, saf ->
            traces += Triple(request, result, saf)
        }
        val args = browseArgs("d:season1", "BrowseDirectChildren") + mapOf(
            "StartingIndex" to "1",
            "RequestedCount" to "1",
        )
        val outputs = service.handle("Browse", args, "http://10.221.18.195:8200").toMap()

        assertEquals("1", outputs["NumberReturned"])
        assertEquals("22", outputs["TotalMatches"])
        assertEquals("17", outputs["UpdateID"])
        assertTrue(outputs.getValue("Result").contains("id=\"i:e02\""))
        assertTrue(traces.last().first.contains("ObjectID=d:season1"))
        assertTrue(traces.last().first.contains("BrowseFlag=BrowseDirectChildren"))
        assertTrue(traces.last().first.contains("Filter=*"))
        assertTrue(traces.last().first.contains("SortCriteria=+dc:title"))
        assertTrue(traces.last().first.contains("RequestedCount=1"))
        assertTrue(traces.last().first.contains("StartingIndex=1"))
        assertTrue(traces.last().second.contains("NumberReturned=1"))
        assertTrue(traces.last().second.contains("TotalMatches=22"))
        assertTrue(traces.last().second.contains("DIDL-Lite media resource metadata"))
        assertTrue(traces.last().second.contains("protocolInfo=http-get:*:video/x-matroska:DLNA.ORG_OP=01"))
        assertTrue(traces.last().second.contains("MIME=video/x-matroska"))
        assertTrue(traces.last().second.contains("duration=(not present)"))
        assertTrue(traces.last().second.contains("DLNA.ORG_PN=(not present)"))
        assertTrue(traces.last().second.contains("resource URL=http://10.221.18.195:8200/media/e02"))
        assertTrue(traces.last().third.contains("SAF enumerated"))
    }

    @Test
    fun unsupportedActionRaisesTheStandardInvalidActionFault() {
        val service = ContentDirectoryService(TestCatalog())
        val fault = assertThrows(UpnpFault::class.java) {
            service.handle("NotAContentDirectoryAction", emptyMap(), "http://10.221.18.195:8200")
        }
        assertEquals(401, fault.errorCode)
        assertTrue(SoapXml.fault(fault).contains("<errorCode>401</errorCode>"))
    }

    private fun browse(service: ContentDirectoryService, objectId: String, baseUrl: String): String =
        service.handle("Browse", browseArgs(objectId, "BrowseDirectChildren"), baseUrl)
            .toMap().getValue("Result")

    private fun browseArgs(objectId: String, flag: String) = mapOf(
        "ObjectID" to objectId,
        "BrowseFlag" to flag,
        "Filter" to "*",
        "StartingIndex" to "0",
        "RequestedCount" to "0",
        "SortCriteria" to "+dc:title",
    )

    private fun parseDocument(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun assertWellFormedXml(xml: String) {
        assertEquals("DIDL-Lite", parseDocument(xml).documentElement.localName)
    }

    private class TestCatalog : MediaCatalog {
        override var systemUpdateId: Long = 17
        override var lastEnumerationDiagnostics: String = "SAF enumerated test directory"

        private val root = MediaNode(
            objectId = "0", parentId = "-1", documentId = null,
            title = "M36 Media Server", isContainer = true,
            mimeType = "vnd.android.document/directory", size = -1, modifiedMillis = 0,
            childCount = 1,
        )
        private val sharedRoot = MediaNode(
            objectId = "shared-root", parentId = "0", documentId = "root",
            title = "Jellyfin", isContainer = true,
            mimeType = "vnd.android.document/directory", size = -1, modifiedMillis = 0,
            childCount = 1,
        )
        private val show = MediaNode(
            objectId = "d:sakamoto", parentId = "shared-root", documentId = "show",
            title = "Sakamoto Days", isContainer = true,
            mimeType = "vnd.android.document/directory", size = -1, modifiedMillis = 0,
            childCount = 1,
        )
        private val season = MediaNode(
            objectId = "d:season1", parentId = "d:sakamoto", documentId = "season1",
            title = "Season 1", isContainer = true,
            mimeType = "vnd.android.document/directory", size = -1, modifiedMillis = 0,
            childCount = 22,
        )
        private val episodes = (1..22).map { number ->
            val suffix = number.toString().padStart(2, '0')
            val version = if (number == 1) "v2" else ""
            episode("e$suffix", "[Judas] SAKAMOTO DAYS - S01E$suffix$version.mkv")
        }
        private val metadata = (listOf(root, sharedRoot, show, season) + episodes).associateBy { it.objectId }

        override fun metadata(objectId: String): MediaNode? = metadata[objectId]

        override fun children(parentObjectId: String): List<MediaNode> {
            lastEnumerationDiagnostics = "SAF enumerated $parentObjectId"
            return when (parentObjectId) {
                "0" -> listOf(sharedRoot)
                "shared-root" -> listOf(show)
                "d:sakamoto" -> listOf(season)
                "d:season1" -> episodes
                else -> throw java.io.FileNotFoundException(parentObjectId)
            }
        }

        private fun episode(token: String, title: String) = MediaNode(
            objectId = "i:$token", parentId = "d:season1", documentId = title,
            title = title, isContainer = false, mimeType = "application/octet-stream",
            size = 4_000_000L, modifiedMillis = 0, mediaToken = token,
        )
    }
}
