package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.HttpRequest
import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.element
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.envelope
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.operation
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.soap
import io.github.mafischer.kotlin2rtc.testing.RecordingTransport
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * the camera's speaker level over ONVIF: Media1's AudioOutputConfiguration, else
 * Media2's, scaled over OutputLevelRange, set with every field of the configuration kept
 */
class SpeakerLevelTest {
    private val device = "http://10.20.30.204/onvif/device_service"
    private val media = "http://10.20.30.204/onvif/media_service"

    private val configurations = "<trt:GetAudioOutputConfigurationsResponse><trt:Configurations token=\"000\"><tt:Name>AudioOutputConfig</tt:Name>" +
        "<tt:UseCount>3</tt:UseCount><tt:OutputToken>000</tt:OutputToken><tt:SendPrimacy>www.onvif.org/ver20/HalfDuplex/Auto</tt:SendPrimacy>" +
        "<tt:OutputLevel>80</tt:OutputLevel></trt:Configurations></trt:GetAudioOutputConfigurationsResponse>"
    private val options = "<trt:GetAudioOutputConfigurationOptionsResponse><trt:Options><tt:OutputTokensAvailable>000</tt:OutputTokensAvailable>" +
        "<tt:OutputLevelRange><tt:Min>1</tt:Min><tt:Max>11</tt:Max></tt:OutputLevelRange></trt:Options></trt:GetAudioOutputConfigurationOptionsResponse>"
    private val unsupported = "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Receiver</SOAP-ENV:Value><SOAP-ENV:Subcode>" +
        "<SOAP-ENV:Value>ter:ActionNotSupported</SOAP-ENV:Value></SOAP-ENV:Subcode></SOAP-ENV:Code><SOAP-ENV:Reason><SOAP-ENV:Text>no</SOAP-ENV:Text>" +
        "</SOAP-ENV:Reason></SOAP-ENV:Fault>"

    private fun level(answer: suspend (HttpRequest) -> HttpResponse): Pair<OnvifSpeakerLevel, RecordingTransport> {
        val transport = RecordingTransport(answer)
        return OnvifSpeakerLevel(OnvifClient(transport, device, OnvifCredentials("admin", "pw")), media) to transport
    }

    private fun clock() = soap(body = envelope("<tds:GetSystemDateAndTimeResponse />"))

    @Test
    fun `Media1 - the configuration, its range, then the level as a share of the range, every field kept`() = runTest {
        val (level, transport) = level { request ->
            when (request.operation) {
                "GetAudioOutputConfigurations" -> soap(body = envelope(configurations))
                "GetAudioOutputConfigurationOptions" -> soap(body = envelope(options))
                "SetAudioOutputConfiguration" -> soap(body = envelope("<trt:SetAudioOutputConfigurationResponse/>"))
                else -> clock()
            }
        }
        assertTrue(level.set(0.5f))
        val set = transport.requests.last()
        assertEquals(media, set.url)
        assertEquals("6", set.element("OutputLevel"))
        assertEquals("AudioOutputConfig", set.element("Name"))
        assertEquals("www.onvif.org/ver20/HalfDuplex/Auto", set.element("SendPrimacy"))
        assertEquals("true", set.element("ForcePersistence"))
        assertEquals("000", transport.requests.first { it.operation == "GetAudioOutputConfigurationOptions" }.element("ConfigurationToken"))
        assertTrue(level.set(1f))
        assertEquals("11", transport.requests.last().element("OutputLevel"))
        assertTrue(level.set(-1f))
        assertEquals("1", transport.requests.last().element("OutputLevel"))
        // Found once
        assertEquals(1, transport.requests.count { it.operation == "GetAudioOutputConfigurations" })
    }

    @Test
    fun `Media2 when the media service does not know the call, 0 to 100 without a range`() = runTest {
        val media2 = "http://10.20.30.204/onvif/media2_service"
        val services = "<tds:GetServicesResponse><tds:Service><tds:Namespace>http://www.onvif.org/ver20/media/wsdl</tds:Namespace>" +
            "<tds:XAddr>http://192.168.0.5/onvif/media2_service</tds:XAddr></tds:Service></tds:GetServicesResponse>"
        val (level, transport) = level { request ->
            when {
                request.url == media -> soap(400, envelope(unsupported))
                request.operation == "GetServices" -> soap(body = envelope(services))
                request.operation == "GetAudioOutputConfigurations" -> soap(
                    body = envelope(configurations.replace("trt:", "tr2:").replaceFirst("Response>", "Response xmlns:tr2=\"http://www.onvif.org/ver20/media/wsdl\">")),
                )
                request.operation == "GetAudioOutputConfigurationOptions" -> soap(400, envelope(unsupported))
                request.operation == "SetAudioOutputConfiguration" -> soap(
                    body = envelope("<tr2:SetAudioOutputConfigurationResponse xmlns:tr2=\"http://www.onvif.org/ver20/media/wsdl\"/>"),
                )
                else -> clock()
            }
        }
        assertTrue(level.set(0.25f))
        val set = transport.requests.last()
        // The service on the camera's host, in the Media2 namespace, no ForcePersistence
        assertEquals(media2, set.url)
        assertEquals("25", set.element("OutputLevel"))
        assertTrue(set.body!!.decodeToString().contains("xmlns:tr2=\"http://www.onvif.org/ver20/media/wsdl\""))
        assertNull(set.element("ForcePersistence"))
    }

    @Test
    fun `a camera without a level, or that refuses it, is not asked again, one that does not answer is`() = runTest {
        val (none, noneTransport) = level { request ->
            when (request.operation) {
                "GetAudioOutputConfigurations" -> soap(body = envelope("<trt:GetAudioOutputConfigurationsResponse/>"))
                "GetServices" -> soap(body = envelope("<tds:GetServicesResponse/>"))
                else -> clock()
            }
        }
        assertFalse(none.set(0.5f))
        val asked = noneTransport.requests.size
        assertFalse(none.set(0.5f))
        assertEquals(asked, noneTransport.requests.size)

        val (refusing, _) = level { request ->
            when (request.operation) {
                "GetAudioOutputConfigurations" -> soap(body = envelope(configurations))
                "GetAudioOutputConfigurationOptions" -> soap(body = envelope(options))
                "SetAudioOutputConfiguration" -> soap(400, envelope(unsupported))
                else -> clock()
            }
        }
        assertFalse(refusing.set(0.5f))
        assertFalse(refusing.set(0.5f))

        var online = false
        val (silent, _) = level { request ->
            if (!online) throw IOException("no route")
            when (request.operation) {
                "GetAudioOutputConfigurations" -> soap(body = envelope(configurations))
                "GetAudioOutputConfigurationOptions" -> soap(body = envelope(options))
                "SetAudioOutputConfiguration" -> soap(body = envelope("<trt:SetAudioOutputConfigurationResponse/>"))
                else -> clock()
            }
        }
        assertFalse(silent.set(0.5f))
        online = true
        assertTrue(silent.set(0.5f))
        assertFalse(SpeakerLevel.None.set(1f))
    }

    @Test
    fun `the requests and answers, escaped, and configurations without a token are skipped`() {
        val configuration = AudioOutputConfiguration("a<b", "N&", 2, "out", null, 40)
        val body = SpeakerLevelSoap.set(media2 = false, configuration)
        assertTrue(body.contains("token=\"a&lt;b\""))
        assertTrue(body.contains("<tt:Name>N&amp;</tt:Name>"))
        assertFalse(body.contains("SendPrimacy"))
        assertEquals("<trt:GetAudioOutputConfigurations />", SpeakerLevelSoap.getConfigurations(media2 = false))
        val parsed = SpeakerLevelSoap.configurations(
            SoapXml.parse(
                envelope(
                    "<trt:GetAudioOutputConfigurationsResponse><trt:Configurations><tt:Name>x</tt:Name></trt:Configurations>" +
                        "<trt:Configurations token=\"t\"/></trt:GetAudioOutputConfigurationsResponse>",
                ),
            ),
        )
        assertEquals(listOf(AudioOutputConfiguration("t", "t", 1, "", null, 0)), parsed)
        assertNull(
            SpeakerLevelSoap.levelRange(SoapXml.parse(envelope("<trt:Options><tt:OutputLevelRange><tt:Min>5</tt:Min><tt:Max>5</tt:Max></tt:OutputLevelRange></trt:Options>"))),
        )
        assertNull(SpeakerLevelSoap.levelRange(SoapXml.parse(envelope("<trt:Options/>"))))
        assertEquals(1..11, SpeakerLevelSoap.levelRange(SoapXml.parse(envelope(options))))
    }
}
