package com.m36.mediaserver.upnp

import com.m36.mediaserver.media.MediaCatalog
import com.m36.mediaserver.media.MediaNode
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDirectoryServiceTest {
    @Test
    fun rootBrowseReturnsTheSelectedSharedRootContainer() {
        val service = ContentDirectoryService(TestCatalog())
        val outputs = service.handle(
            "Browse",
            browseArgs("0", "BrowseDirectChildren"),
            "http://10.221.18.195:8200",
        ).toMap()

        assertEquals("1", outputs["NumberReturned"])
        assertEquals("1", outputs["TotalMatches"])
        assertEquals("17", outputs["UpdateID"])
        assertTrue(outputs.getValue("Result").contains("<container id=\"shared-root\" parentID=\"0\""))
        assertTrue(outputs.getValue("Result").contains("childCount=\"1\""))
        assertTrue(outputs.getValue("Result").contains("<dc:title>Jellyfin</dc:title>"))
    }

    @Test
    fun directChildrenRecursivelyExposeFoldersAndVideoResourcesOnTheCurrentLanAddress() {
        val service = ContentDirectoryService(TestCatalog())
        val baseUrl = "http://10.221.18.195:8200"

        val jellyfin = browse(service, "0", baseUrl)
        assertTrue(jellyfin.contains("id=\"shared-root\""))

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

    private fun assertWellFormedXml(xml: String) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        assertEquals("DIDL-Lite", root.localName)
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
