package com.m36.mediaserver.upnp

import com.m36.mediaserver.media.MediaNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun rootDescriptionAdvertisesBothServicesAndStableUdn() {
        val description = UpnpXml.rootDescription("11111111-2222-3333-4444-555555555555", "http://192.0.2.2:8200")
        assertTrue(description.contains("<friendlyName>M36 Media Server</friendlyName>"))
        assertTrue(description.contains("urn:schemas-upnp-org:device:MediaServer:1"))
        assertTrue(description.contains("urn:schemas-upnp-org:service:ContentDirectory:1"))
        assertTrue(description.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
        assertTrue(description.contains("uuid:11111111-2222-3333-4444-555555555555"))
    }
}
