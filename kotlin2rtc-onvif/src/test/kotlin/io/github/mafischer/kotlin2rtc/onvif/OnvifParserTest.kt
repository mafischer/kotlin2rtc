package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.core.XmlParseException
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.envelope
import io.github.mafischer.kotlin2rtc.testing.expectThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Reading cameras' answers whatever their prefixes: the real Amcrest answers, minimal hand-written answers
 * of other cameras, SOAP 1.2 and 1.1 faults, and refusing what is not plain XML.
 */
class OnvifParserTest {
    private val device = "http://10.20.30.204/onvif/device_service"

    @Test
    fun `the Amcrest camera's clock`() {
        val millis = OnvifParser.systemDateAndTime(SoapXml.parse(OnvifFixtures.read("amcrest-system-date-and-time.xml")))
        assertEquals(Instant.parse("2026-10-27T00:51:47Z").toEpochMilli(), millis)
        assertNull(OnvifParser.systemDateAndTime(SoapXml.parse(envelope("<tds:GetSystemDateAndTimeResponse/>"))))
    }

    @Test
    fun `the Amcrest camera's capabilities and services`() {
        val capabilities = OnvifParser.capabilities(SoapXml.parse(OnvifFixtures.read("amcrest-capabilities.xml")), device)
        // It has no PTZ service: the analytics, imaging and device XAddrs are not taken for one
        // DeviceIO says the camera has a speaker
        assertEquals(OnvifEndpoints("http://10.20.30.204/onvif/media_service", null, "http://10.20.30.204/onvif/event_service", audioOutputs = 1), capabilities)
        val services = OnvifParser.services(SoapXml.parse(OnvifFixtures.read("amcrest-services.xml")), device)
        assertEquals(OnvifEndpoints("http://10.20.30.204/onvif/media_service", null, "http://10.20.30.204/onvif/event_service"), services)
    }

    @Test
    fun `capabilities give the media, PTZ and events services`() {
        val xml = SoapXml.parse(
            envelope(
                """
                <tds:GetCapabilitiesResponse><tds:Capabilities>
                  <tt:Device><tt:XAddr>http://10.20.30.204/onvif/device_service</tt:XAddr></tt:Device>
                  <tt:Events><tt:XAddr>http://10.20.30.204/onvif/event_service</tt:XAddr><tt:WSPullPointSupport>true</tt:WSPullPointSupport></tt:Events>
                  <tt:Media><tt:XAddr>http://10.20.30.204:8000/onvif/media_service</tt:XAddr>
                    <tt:StreamingCapabilities><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP></tt:StreamingCapabilities></tt:Media>
                  <tt:PTZ><tt:XAddr>http://10.20.30.204/onvif/ptz_service</tt:XAddr></tt:PTZ>
                </tds:Capabilities></tds:GetCapabilitiesResponse>
                """,
            ),
        )
        assertEquals(
            OnvifEndpoints("http://10.20.30.204:8000/onvif/media_service", "http://10.20.30.204/onvif/ptz_service", "http://10.20.30.204/onvif/event_service"),
            OnvifParser.capabilities(xml, device),
        )
        // Without a Media service: the default path next to the device service
        val bare = SoapXml.parse(
            envelope("<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Device><tt:XAddr>x</tt:XAddr></tt:Device></tds:Capabilities></tds:GetCapabilitiesResponse>"),
        )
        assertEquals(OnvifEndpoints("http://cam:8080/onvif/media_service"), OnvifParser.capabilities(bare, "http://cam:8080/onvif/device_service"))
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.capabilities(SoapXml.parse(envelope("<x/>")), device) }
    }

    @Test
    fun `service addresses on another host are moved to the camera's address`() {
        val xml = SoapXml.parse(
            envelope(
                "<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr>http://192.168.0.2:8899/onvif/media</tt:XAddr></tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>",
            ),
        )
        assertEquals("http://10.20.30.204:8899/onvif/media", OnvifParser.capabilities(xml, device).mediaUrl)
        assertEquals("rtsp://u:p@10.20.30.204:554/a?b=1&c=2", OnvifUrls.onHost("rtsp://u:p@0.0.0.0:554/a?b=1&c=2", device))
        assertEquals("rtsp://10.20.30.204/live", OnvifUrls.onHost("rtsp://10.20.30.204/live", device))
        assertEquals("not a url", OnvifUrls.onHost("not a url", device))
        assertEquals("http://cam/onvif/media_service", OnvifUrls.sibling("http://cam/onvif/device_service?x=1", "media_service"))
        assertEquals("http://cam/media_service", OnvifUrls.sibling("http://cam", "media_service"))
        assertEquals("http://10.0.0.5/onvif/device_service", OnvifUrls.deviceService("10.0.0.5", 80))
        assertEquals("http://[fe80::1]:8080/onvif/device_service", OnvifUrls.deviceService("fe80::1", 8080))
    }

    @Test
    fun `GetServices by namespace`() {
        val xml = SoapXml.parse(
            envelope(
                "<tds:GetServicesResponse>" +
                    "<tds:Service><tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace><tds:XAddr>http://10.20.30.204/media</tds:XAddr></tds:Service>" +
                    "<tds:Service><tds:Namespace>http://www.onvif.org/ver20/ptz/wsdl</tds:Namespace><tds:XAddr>http://10.20.30.204/ptz</tds:XAddr></tds:Service>" +
                    "</tds:GetServicesResponse>",
            ),
        )
        assertEquals(OnvifEndpoints("http://10.20.30.204/media", "http://10.20.30.204/ptz"), OnvifParser.services(xml, device))
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.services(SoapXml.parse(envelope("<x/>")), device) }
    }

    @Test
    fun `device information whatever the prefix, missing fields empty`() {
        val xml = SoapXml.parse(
            envelope(
                """
                <tds:GetDeviceInformationResponse>
                  <tds:Manufacturer>Reolink</tds:Manufacturer>
                  <tds:Model>E1 Zoom</tds:Model>
                  <tds:FirmwareVersion>v3.1.0.2368</tds:FirmwareVersion>
                  <SerialNumber>00000000000000</SerialNumber>
                  <tds:HardwareId>IPC_566SD54MP</tds:HardwareId>
                </tds:GetDeviceInformationResponse>
                """,
            ),
        )
        val info = OnvifParser.deviceInformation(xml)
        assertEquals(OnvifDeviceInformation("Reolink", "E1 Zoom", "v3.1.0.2368", "00000000000000", "IPC_566SD54MP"), info)
        assertEquals("Reolink E1 Zoom", info.displayName)
        val partial = OnvifParser.deviceInformation(SoapXml.parse(envelope("<tds:GetDeviceInformationResponse><tds:Model>X</tds:Model></tds:GetDeviceInformationResponse>")))
        assertEquals(OnvifDeviceInformation(model = "X"), partial)
        assertNull(OnvifDeviceInformation().displayName)
    }

    @Test
    fun `profiles with token, name, resolution, encoding and PTZ`() {
        val xml = SoapXml.parse(
            envelope(
                """
                <trt:GetProfilesResponse>
                  <trt:Profiles token="MainStream" fixed="true">
                    <tt:Name>mainStream</tt:Name>
                    <tt:VideoSourceConfiguration token="VideoSource"><tt:Name>VideoSource</tt:Name><tt:Bounds x="0" y="0" width="2560" height="1920"></tt:Bounds></tt:VideoSourceConfiguration>
                    <tt:VideoEncoderConfiguration token="Main"><tt:Name>Main</tt:Name><tt:Encoding>H264</tt:Encoding>
                      <tt:Resolution><tt:Width>2560</tt:Width><tt:Height>1920</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration>
                    <tt:PTZConfiguration token="ptz0"><tt:Name>PTZ</tt:Name></tt:PTZConfiguration>
                  </trt:Profiles>
                  <trt:Profiles fixed="true" token="SubStream">
                    <tt:VideoEncoderConfiguration token="Sub"><tt:Resolution><tt:Width>640</tt:Width><tt:Height>480</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration>
                  </trt:Profiles>
                  <trt:Profiles token="Audio"><tt:Name>audio only</tt:Name></trt:Profiles>
                </trt:GetProfilesResponse>
                """,
            ),
        )
        assertEquals(
            listOf(
                OnvifProfile("MainStream", "mainStream", 2560, 1920, "H264", hasPtz = true),
                // No name: the token names it
                OnvifProfile("SubStream", "SubStream", 640, 480),
                // No video: no resolution
                OnvifProfile("Audio", "audio only"),
            ),
            OnvifParser.profiles(xml),
        )
        // A minimal answer (trt: prefixes inside the profile)
        val minimal = SoapXml.parse(
            envelope(
                "<trt:GetProfilesResponse><trt:Profiles token=\"Profile000\"><trt:Name>MainStream</trt:Name><trt:VideoEncoderConfiguration>" +
                    "<trt:Resolution><trt:Width>1920</trt:Width><trt:Height>1080</trt:Height></trt:Resolution></trt:VideoEncoderConfiguration>" +
                    "</trt:Profiles></trt:GetProfilesResponse>",
            ),
        )
        assertEquals(listOf(OnvifProfile("Profile000", "MainStream", 1920, 1080)), OnvifParser.profiles(minimal))
        assertTrue(OnvifParser.profiles(SoapXml.parse(envelope("<trt:GetProfilesResponse></trt:GetProfilesResponse>"))).isEmpty())
    }

    @Test
    fun `stream and snapshot URIs with their entities decoded`() {
        val xml = SoapXml.parse(
            envelope(
                "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://10.20.30.204:554/cam/realmonitor?channel=1&amp;subtype=0</tt:Uri>" +
                    "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:Timeout>PT60S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>",
            ),
        )
        assertEquals("rtsp://10.20.30.204:554/cam/realmonitor?channel=1&subtype=0", OnvifParser.mediaUri(xml, "GetStreamUriResponse"))
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.mediaUri(SoapXml.parse(envelope("<trt:GetStreamUriResponse/>")), "GetStreamUriResponse") }
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.mediaUri(xml, "GetSnapshotUriResponse") }
        // An empty URI is none
        val empty = SoapXml.parse(envelope("<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri></tt:Uri></trt:MediaUri></trt:GetSnapshotUriResponse>"))
        assertEquals("Uri", expectThrows<OnvifError.InvalidResponse> { OnvifParser.mediaUri(empty, "GetSnapshotUriResponse") }.detail)
    }

    @Test
    fun `SOAP 1_2 faults, authentication faults and SOAP 1_1 faults`() {
        val notAuthorized = envelope(
            "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Sender</SOAP-ENV:Value><SOAP-ENV:Subcode><SOAP-ENV:Value>ter:NotAuthorized</SOAP-ENV:Value>" +
                "</SOAP-ENV:Subcode></SOAP-ENV:Code><SOAP-ENV:Reason><SOAP-ENV:Text xml:lang=\"en\">Sender not Authorized</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>",
        )
        assertEquals(OnvifError.Unauthorized, OnvifParser.fault(SoapXml.parse(notAuthorized)))
        // A fault with: a reason only
        assertEquals(
            OnvifError.Unauthorized,
            OnvifParser.fault(SoapXml.parse(envelope("<SOAP-ENV:Fault><SOAP-ENV:Reason>Sender not authorized</SOAP-ENV:Reason></SOAP-ENV:Fault>"))),
        )
        val other = envelope(
            "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Receiver</SOAP-ENV:Value><SOAP-ENV:Subcode><SOAP-ENV:Value>ter:ActionNotSupported</SOAP-ENV:Value>" +
                "</SOAP-ENV:Subcode></SOAP-ENV:Code><SOAP-ENV:Reason><SOAP-ENV:Text>Optional Action Not Implemented</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>",
        )
        assertEquals(
            OnvifError.Fault("SOAP-ENV:Receiver", "ter:ActionNotSupported", "Optional Action Not Implemented"),
            OnvifParser.fault(SoapXml.parse(other)),
        )
        val soap11 = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode>""" +
            """<faultstring>Bad profile</faultstring></s:Fault></s:Body></s:Envelope>"""
        assertEquals(OnvifError.Fault("s:Client", null, "Bad profile"), OnvifParser.fault(SoapXml.parse(soap11)))
        assertNull(OnvifParser.fault(SoapXml.parse(envelope("<tptz:StopResponse/>"))))
    }

    @Test
    fun `faults with a code only, an empty reason or an authentication reason`() {
        // A code that is itself an authentication subcode
        assertEquals(
            OnvifError.Unauthorized,
            OnvifParser.fault(SoapXml.parse(envelope("<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>ter:FailedAuthentication</SOAP-ENV:Value></SOAP-ENV:Code></SOAP-ENV:Fault>"))),
        )
        // SOAP 1.1 with an authentication reason
        assertEquals(
            OnvifError.Unauthorized,
            OnvifParser.fault(SoapXml.parse(envelope("<SOAP-ENV:Fault><faultcode>s:Client</faultcode><faultstring>Authority failure</faultstring></SOAP-ENV:Fault>"))),
        )
        // Nothing but the element: a fault without details, and an empty reason counts as none
        assertEquals(OnvifError.Fault(null, null, null), OnvifParser.fault(SoapXml.parse(envelope("<SOAP-ENV:Fault/>"))))
        assertEquals(
            OnvifError.Fault("SOAP-ENV:Receiver", null, null),
            OnvifParser.fault(
                SoapXml.parse(
                    envelope(
                        "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Receiver</SOAP-ENV:Value></SOAP-ENV:Code><SOAP-ENV:Reason><SOAP-ENV:Text></SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a clock with missing parts takes the start of the period, an impossible one none`() {
        val yearOnly =
            envelope(
                "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Date><tt:Year>2026</tt:Year></tt:Date></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>",
            )
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), OnvifParser.systemDateAndTime(SoapXml.parse(yearOnly)))
        val impossible = envelope(
            "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Date><tt:Year>2026</tt:Year><tt:Month>13</tt:Month><tt:Day>1</tt:Day></tt:Date>" +
                "<tt:Time><tt:Hour>0</tt:Hour><tt:Minute>0</tt:Minute><tt:Second>0</tt:Second></tt:Time></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>",
        )
        assertNull(OnvifParser.systemDateAndTime(SoapXml.parse(impossible)))
        val noYear =
            envelope(
                "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Date><tt:Year>soon</tt:Year></tt:Date></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>",
            )
        assertNull(OnvifParser.systemDateAndTime(SoapXml.parse(noYear)))
    }

    @Test
    fun `answers without their response element are refused`() {
        val empty = SoapXml.parse(envelope("<tds:Other/>"))
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.deviceInformation(empty) }
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.capabilities(empty, device) }
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.services(empty, device) }
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.profiles(empty) }
    }

    @Test
    fun `empty service addresses and services without a namespace or address`() {
        val capabilities = envelope(
            "<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr></tt:XAddr></tt:Media><tt:PTZ/></tds:Capabilities></tds:GetCapabilitiesResponse>",
        )
        // No usable Media address: the default next to the device service; no PTZ or events
        assertEquals(OnvifEndpoints("http://10.20.30.204/onvif/media_service", null, null), OnvifParser.capabilities(SoapXml.parse(capabilities), device))
        val services = envelope(
            "<tds:GetServicesResponse><tds:Service><tds:XAddr>http://10.20.30.204/onvif/other</tds:XAddr></tds:Service>" +
                "<tds:Service><tds:Namespace>http://www.onvif.org/ver20/ptz/wsdl</tds:Namespace></tds:Service></tds:GetServicesResponse>",
        )
        assertEquals(OnvifEndpoints("http://10.20.30.204/onvif/media_service", null, null), OnvifParser.services(SoapXml.parse(services), device))
    }

    @Test
    fun `a profile without token, name or encoder`() {
        val xml =
            envelope(
                "<trt:GetProfilesResponse><trt:Profiles><tt:Name></tt:Name><tt:VideoEncoderConfiguration><tt:Resolution><tt:Width>wide</tt:Width></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles><trt:Profiles/></trt:GetProfilesResponse>",
            )
        assertEquals(
            listOf(OnvifProfile("", "", 0, 0, "", hasPtz = false), OnvifProfile("", "", 0, 0, "", hasPtz = false)),
            OnvifParser.profiles(SoapXml.parse(xml)),
        )
    }

    @Test
    fun `addresses that cannot be moved to the camera's host stay as they are`() {
        // Not a URI, a reference without a host, a URL without a host
        assertEquals("http://bad host/onvif", OnvifUrls.onHost("http://bad host/onvif", device))
        assertEquals("http://10.0.0.9/onvif/media", OnvifUrls.onHost("http://10.0.0.9/onvif/media", "not a url"))
        assertEquals("urn:onvif:media", OnvifUrls.onHost("urn:onvif:media", device))
        // Port kept, no path
        assertEquals("http://10.20.30.204:8080", OnvifUrls.onHost("http://10.0.0.9:8080", device))
        // IPv6 hosts are bracketed once
        assertEquals("http://[fe80::1]:8000/onvif/device_service", OnvifUrls.deviceService("fe80::1", 8000))
        assertEquals("http://[fe80::1]/onvif/device_service", OnvifUrls.deviceService("[fe80::1]", 80))
    }

    @Test
    fun `what is not plain XML is refused`() {
        expectThrows<XmlParseException> { SoapXml.parse("") }
        expectThrows<XmlParseException> { SoapXml.parse("<html><body>Login</body>") }
        // No DTDs or entities (XXE)
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><r>&x;</r>"""
        assertEquals("doctype", expectThrows<XmlParseException> { SoapXml.parse(xxe) }.detail)
        // A byte order mark and leading blank lines are fine
        assertEquals("r", SoapXml.parse("\uFEFF\n  <r a:b=\"1\" xmlns:a=\"urn:a\"/>").name)
        assertEquals("1", SoapXml.parse("<r a:b=\"1\" xmlns:a=\"urn:a\"/>").attribute("b"))
        assertNull(SoapXml.parse("<r/>").attribute("b"))
        assertNull(SoapXml.parse("<r/>").path("a", "b"))
    }

    @Test
    fun `PTZ nodes with a zoom space have an optical zoom, with the absolute position's range`() {
        val zoom = OnvifParser.ptzZoomSupport(SoapXml.parse(OnvifFixtures.read("ptz-nodes-optical-zoom.xml")))
        assertEquals(PtzZoomSupport(true, 0.0, 1.0), zoom)
        // A pan/tilt camera listing a zoom space from 0 to 0 has none
        assertEquals(PtzZoomSupport.NONE, OnvifParser.ptzZoomSupport(SoapXml.parse(OnvifFixtures.read("ptz-nodes-pan-tilt-only.xml"))))
        // No zoom space at all, or no node
        val panTilt = "<tt:ContinuousPanTiltVelocitySpace><tt:URI>u</tt:URI></tt:ContinuousPanTiltVelocitySpace>"
        assertEquals(PtzZoomSupport.NONE, OnvifParser.ptzZoomSupport(SoapXml.parse(envelope(nodes(panTilt)))))
        assertEquals(PtzZoomSupport.NONE, OnvifParser.ptzZoomSupport(SoapXml.parse(envelope("<tptz:GetNodesResponse/>"))))
        // A zoom space without a range counts; an absolute range other than 0...1 scales the position
        val continuous = "<tt:ContinuousZoomVelocitySpace><tt:URI>u</tt:URI></tt:ContinuousZoomVelocitySpace>"
        assertEquals(PtzZoomSupport(true), OnvifParser.ptzZoomSupport(SoapXml.parse(envelope(nodes(panTilt + continuous)))))
        val absolute = "<tt:AbsoluteZoomPositionSpace><tt:URI>u</tt:URI><tt:XRange><tt:Min>1</tt:Min><tt:Max>30</tt:Max></tt:XRange></tt:AbsoluteZoomPositionSpace>"
        val scaled = OnvifParser.ptzZoomSupport(SoapXml.parse(envelope(nodes(absolute))))
        assertEquals(PtzZoomSupport(true, 1.0, 30.0), scaled)
        assertEquals(0.5, scaled.normalized(15.5), 1e-9)
        assertEquals(1.0, scaled.normalized(40.0), 1e-9)
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.ptzZoomSupport(SoapXml.parse(envelope("<tptz:GetStatusResponse/>"))) }
    }

    @Test
    fun `PTZ status gives the lens's zoom position`() {
        assertEquals(0.42, OnvifParser.ptzZoomPosition(SoapXml.parse(OnvifFixtures.read("ptz-status.xml")))!!, 1e-9)
        assertNull(OnvifParser.ptzZoomPosition(SoapXml.parse(envelope("<tptz:GetStatusResponse><tptz:PTZStatus/></tptz:GetStatusResponse>"))))
        expectThrows<OnvifError.InvalidResponse> { OnvifParser.ptzZoomPosition(SoapXml.parse(envelope("<tptz:GetNodesResponse/>"))) }
    }

    private fun nodes(spaces: String) =
        "<tptz:GetNodesResponse><tptz:PTZNode token=\"n\"><tt:SupportedPTZSpaces>$spaces</tt:SupportedPTZSpaces></tptz:PTZNode></tptz:GetNodesResponse>"

    @Test
    fun `a profile's audio output, Media1 in its Extension and Media2 in its Configurations`() {
        val media1 = OnvifParser.profiles(SoapXml.parse(OnvifFixtures.read("media1-profiles-audio-output.xml")))
        assertEquals(
            listOf(
                OnvifProfile("MediaProfile000", "MediaProfile_Channel1_MainStream", 3840, 2160, "H264", hasPtz = true, hasAudioOutput = true),
                OnvifProfile("MediaProfile001", "MediaProfile_Channel1_SubStream1", 704, 480, "H264"),
            ),
            media1,
        )
        val media2 = OnvifParser.profiles(SoapXml.parse(OnvifFixtures.read("media2-profiles-audio-output.xml")))
        assertEquals(
            listOf(
                OnvifProfile("Profile_1", "mainStream", 2560, 1440, "H264", hasAudioOutput = true),
                OnvifProfile("Profile_2", "subStream", 640, 360, "H265"),
            ),
            media2,
        )
        // A decoder alone (the output configured elsewhere) counts too
        val decoder = envelope("<trt:GetProfilesResponse><trt:Profiles token=\"P\"><tt:AudioDecoderConfiguration token=\"D\"/></trt:Profiles></trt:GetProfilesResponse>")
        assertTrue(OnvifParser.profiles(SoapXml.parse(decoder)).single().hasAudioOutput)
    }

    @Test
    fun `the device's speakers from GetCapabilities' DeviceIO`() {
        val none = envelope(
            "<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr>http://cam/media</tt:XAddr></tt:Media>" +
                "<tt:Extension><tt:DeviceIO><tt:AudioOutputs>0</tt:AudioOutputs></tt:DeviceIO></tt:Extension></tds:Capabilities></tds:GetCapabilitiesResponse>",
        )
        assertEquals(0, OnvifParser.capabilities(SoapXml.parse(none), "http://cam/onvif/device_service").audioOutputs)
        val unknown =
            envelope("<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr>http://cam/media</tt:XAddr></tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>")
        assertNull(OnvifParser.capabilities(SoapXml.parse(unknown), "http://cam/onvif/device_service").audioOutputs)
    }
}
