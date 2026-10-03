package com.m36.mediaserver.upnp

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource

class SoapXmlTest {
    @Test
    fun parsesSoap11UsingArbitraryPrefixesAndAllBrowseArguments() {
        val request = SoapXml.parseAction(
            """<?xml version="1.0" encoding="utf-8"?>
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="$SOAP_NS">
                  <SOAP-ENV:Header />
                  <SOAP-ENV:Body>
                    <u:Browse xmlns:u="$CONTENT_DIRECTORY_NS">
                      <ObjectID> 0 </ObjectID>
                      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                      <Filter>*</Filter>
                      <StartingIndex>0</StartingIndex>
                      <RequestedCount>9</RequestedCount>
                      <SortCriteria>+dc:title</SortCriteria>
                    </u:Browse>
                  </SOAP-ENV:Body>
                </SOAP-ENV:Envelope>""".trimIndent(),
        )

        assertEquals("Browse", request.actionName)
        assertEquals(CONTENT_DIRECTORY_NS, request.actionNamespace)
        assertEquals(SOAP_NS, request.envelopeNamespace)
        assertEquals(
            mapOf(
                "ObjectID" to "0",
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to "0",
                "RequestedCount" to "9",
                "SortCriteria" to "+dc:title",
            ),
            request.arguments,
        )
    }

    @Test
    fun parsesDefaultSoap11NamespaceAndUnprefixedActionElements() {
        val request = SoapXml.parseAction(
            """<Envelope xmlns="$SOAP_NS">
                <Body>
                  <GetSystemUpdateID xmlns="$CONTENT_DIRECTORY_NS" />
                </Body>
              </Envelope>""".trimIndent(),
        )

        assertEquals("GetSystemUpdateID", request.actionName)
        assertEquals(CONTENT_DIRECTORY_NS, request.actionNamespace)
        assertTrue(request.arguments.isEmpty())
    }

    @Test
    fun parsesQuotedUnquotedAndSingleQuotedSoapActionValues() {
        val actionUri = "$CONTENT_DIRECTORY_NS#Browse"

        assertEquals("Browse", SoapXml.actionNameFromSoapAction("\"$actionUri\""))
        assertEquals("Browse", SoapXml.actionNameFromSoapAction(actionUri))
        assertEquals("Browse", SoapXml.actionNameFromSoapAction("  '$actionUri'  "))
        assertEquals("GetSystemUpdateID", SoapXml.actionNameFromSoapAction("GetSystemUpdateID"))
        assertTrue(SoapXml.actionNameFromSoapAction("\"\"") == null)
        assertTrue(SoapXml.actionNameFromSoapAction(null) == null)
    }

    @Test
    fun generatedSystemUpdateResponseAndUnsupportedActionFaultAreWellFormed() {
        val response = SoapXml.response(
            "GetSystemUpdateID",
            CONTENT_DIRECTORY_NS,
            listOf("Id" to "17"),
        )
        val responseRoot = parseXml(response).documentElement
        assertEquals("Envelope", responseRoot.localName)
        assertEquals(SOAP_NS, responseRoot.namespaceURI)
        assertEquals("17", responseRoot.getElementsByTagName("Id").item(0).textContent)

        val fault = SoapXml.fault(UpnpFault(401, "Invalid Action"))
        val faultRoot = parseXml(fault).documentElement
        assertEquals("Envelope", faultRoot.localName)
        assertEquals("401", faultRoot.getElementsByTagName("errorCode").item(0).textContent)
        assertEquals("Invalid Action", faultRoot.getElementsByTagName("errorDescription").item(0).textContent)
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))

    companion object {
        private const val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val CONTENT_DIRECTORY_NS = "urn:schemas-upnp-org:service:ContentDirectory:1"
    }
}
