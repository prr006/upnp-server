package com.m36.mediaserver.upnp

import com.m36.mediaserver.media.MediaNode
import java.util.Locale

object UpnpXml {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaServer:1"
    const val CONTENT_DIRECTORY_TYPE = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val CONNECTION_MANAGER_TYPE = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val CONTENT_DIRECTORY_ID = "urn:upnp-org:serviceId:ContentDirectory"
    const val CONNECTION_MANAGER_ID = "urn:upnp-org:serviceId:ConnectionManager"
    const val CONTENT_DIRECTORY_SCPD_PATH = "/ContentDirectory/scpd.xml"
    const val CONTENT_DIRECTORY_CONTROL_PATH = "/upnp/control/contentdirectory"
    const val CONTENT_DIRECTORY_EVENT_PATH = "/upnp/event/contentdirectory"

    data class ContentDirectoryEndpoints(
        val serviceType: String,
        val serviceId: String,
        val scpdUrl: String,
        val controlUrl: String,
        val eventSubUrl: String,
        val resolvedScpdUrl: String,
        val resolvedControlUrl: String,
        val resolvedEventSubUrl: String,
    )

    /** Returns the exact service values written into rootDesc.xml plus their active-interface URLs. */
    fun contentDirectoryEndpoints(baseUrl: String): ContentDirectoryEndpoints {
        val normalizedBase = baseUrl.trimEnd('/')
        return ContentDirectoryEndpoints(
            serviceType = CONTENT_DIRECTORY_TYPE,
            serviceId = CONTENT_DIRECTORY_ID,
            scpdUrl = CONTENT_DIRECTORY_SCPD_PATH,
            controlUrl = CONTENT_DIRECTORY_CONTROL_PATH,
            eventSubUrl = CONTENT_DIRECTORY_EVENT_PATH,
            resolvedScpdUrl = normalizedBase + CONTENT_DIRECTORY_SCPD_PATH,
            resolvedControlUrl = normalizedBase + CONTENT_DIRECTORY_CONTROL_PATH,
            resolvedEventSubUrl = normalizedBase + CONTENT_DIRECTORY_EVENT_PATH,
        )
    }

    fun rootDescription(deviceUuid: String, baseUrl: String): String {
        val normalizedBase = baseUrl.trimEnd('/') + "/"
        val contentDirectory = contentDirectoryEndpoints(baseUrl)
        val udn = "uuid:$deviceUuid"
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"
                  xmlns:dlna="urn:schemas-dlna-org:device-1-0">
              <specVersion><major>1</major><minor>0</minor></specVersion>
              <URLBase>${xmlEscape(normalizedBase)}</URLBase>
              <device>
                <deviceType>$DEVICE_TYPE</deviceType>
                <friendlyName>M36 Media Server</friendlyName>
                <manufacturer>Local Network Media</manufacturer>
                <manufacturerURL>https://www.upnp.org/</manufacturerURL>
                <modelDescription>Read-only local UPnP and DLNA media server</modelDescription>
                <modelName>M36 UPnP Media Server</modelName>
                <modelNumber>1</modelNumber>
                <serialNumber>${xmlEscape(deviceUuid.take(12))}</serialNumber>
                <UDN>$udn</UDN>
                <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
                <serviceList>
                  <service>
                    <serviceType>${contentDirectory.serviceType}</serviceType>
                    <serviceId>${contentDirectory.serviceId}</serviceId>
                    <SCPDURL>${contentDirectory.scpdUrl}</SCPDURL>
                    <controlURL>${contentDirectory.controlUrl}</controlURL>
                    <eventSubURL>${contentDirectory.eventSubUrl}</eventSubURL>
                  </service>
                  <service>
                    <serviceType>$CONNECTION_MANAGER_TYPE</serviceType>
                    <serviceId>$CONNECTION_MANAGER_ID</serviceId>
                    <SCPDURL>/ConnectionManager/scpd.xml</SCPDURL>
                    <controlURL>/upnp/control/connectionmanager</controlURL>
                    <eventSubURL>/upnp/event/connectionmanager</eventSubURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
    }

    fun contentDirectoryScpd(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action><name>Browse</name><argumentList>
              <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
              <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
              <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
              <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
              <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
              <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
              <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
              <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
              <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
              <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
            </argumentList></action>
            <action><name>GetSearchCapabilities</name><argumentList>
              <argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument>
            </argumentList></action>
            <action><name>GetSortCapabilities</name><argumentList>
              <argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument>
            </argumentList></action>
            <action><name>GetSystemUpdateID</name><argumentList>
              <argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument>
            </argumentList></action>
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no">
              <name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType>
              <allowedValueList>
                <allowedValue>BrowseMetadata</allowedValue>
                <allowedValue>BrowseDirectChildren</allowedValue>
              </allowedValueList>
            </stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
    """.trimIndent()

    fun connectionManagerScpd(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action><name>GetProtocolInfo</name><argumentList>
              <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
              <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
            </argumentList></action>
            <action><name>GetCurrentConnectionIDs</name><argumentList>
              <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
            </argumentList></action>
            <action><name>GetCurrentConnectionInfo</name><argumentList>
              <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
              <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>
              <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>
              <argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>
              <argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>
              <argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
              <argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>
              <argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>
            </argumentList></action>
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
    """.trimIndent()

    fun didlNode(node: MediaNode, resourceUrl: String? = null): String {
        val title = xmlEscape(node.title)
        return if (node.isContainer) {
            val childCount = node.childCount?.let { " childCount=\"$it\"" }.orEmpty()
            "<container id=\"${xmlEscape(node.objectId)}\" parentID=\"${xmlEscape(node.parentId)}\" restricted=\"1\"$childCount>" +
                "<dc:title>$title</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>"
        } else {
            val itemClass = mediaClass(node.mimeType)
            val resource = if (resourceUrl == null) "" else {
                val sizeAttribute = if (node.size >= 0) " size=\"${node.size}\"" else ""
                val protocolInfo = "http-get:*:${xmlEscape(node.mimeType)}:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                "<res protocolInfo=\"$protocolInfo\"$sizeAttribute>${xmlEscape(resourceUrl)}</res>"
            }
            "<item id=\"${xmlEscape(node.objectId)}\" parentID=\"${xmlEscape(node.parentId)}\" restricted=\"1\">" +
                "<dc:title>$title</dc:title><upnp:class>$itemClass</upnp:class>$resource</item>"
        }
    }

    fun mediaMimeType(name: String, reportedMime: String?): String {
        val given = reportedMime?.trim()?.lowercase(Locale.ROOT)
        val extensionType = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mkv" -> "video/x-matroska"
            "mk3d" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "wmv" -> "video/x-ms-wmv"
            "mpg", "mpeg", "mpe" -> "video/mpeg"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "3gp", "3g2" -> "video/3gpp"
            "ogv" -> "video/ogg"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "srt" -> "text/plain"
            else -> null
        }
        return extensionType ?: given?.takeIf {
            it.isNotBlank() && it != "application/octet-stream" && it != "*/*"
        } ?: "application/octet-stream"
    }

    private fun mediaClass(mime: String): String = when {
        mime.startsWith("video/") -> "object.item.videoItem"
        mime.startsWith("audio/") -> "object.item.audioItem.musicTrack"
        mime.startsWith("image/") -> "object.item.imageItem.photo"
        else -> "object.item"
    }
}

/** Escapes XML 1.0 text/attribute values and strips characters XML cannot represent. */
fun xmlEscape(value: String): String {
    val out = StringBuilder(value.length + 16)
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        index += Character.charCount(codePoint)
        val valid = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
            codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF
        if (!valid) continue
        when (codePoint) {
            '&'.code -> out.append("&amp;")
            '<'.code -> out.append("&lt;")
            '>'.code -> out.append("&gt;")
            '"'.code -> out.append("&quot;")
            '\''.code -> out.append("&apos;")
            else -> out.appendCodePoint(codePoint)
        }
    }
    return out.toString()
}
