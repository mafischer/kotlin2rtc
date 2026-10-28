package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.SoapXml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * The SOAP the client sends: the WS-Security password digest with fixed vectors
 * (the ONVIF specification's example and two more), the envelope and the
 * bodies of each operation, and escaping of values from users and cameras.
 */
class OnvifSoapTest {
    private val credentials = OnvifCredentials("admin", "s3cret")

    @Test
    fun `the password digest matches the ONVIF specification example and fixed vectors`() {
        // ONVIF Application Programmer's Guide 6.1.1.3: the published nonce, timestamp and password
        val nonce = Base64.getDecoder().decode("LKqI6G/AikKCQrN0zqZFlg==")
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", OnvifSoap.passwordDigest("userpassword", "2010-09-16T07:50:45Z", nonce))
        // Every input changes it
        assertNotEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", OnvifSoap.passwordDigest("userpassword", "2010-09-16T07:50:46Z", nonce))
        // Bytes 1...16, testPassword123, 2026-10-25T00:00:00Z
        val counting = ByteArray(16) { (it + 1).toByte() }
        assertEquals("8lkwDHT3BpaNBg/R7KnO4Matd8U=", OnvifSoap.passwordDigest("testPassword123", "2026-10-25T00:00:00Z", counting))
        // An ASCII nonce: the value pinned so other clients can compare
        assertEquals("swccRiGHBCrWDQjq3N6/BkBRh4Q=", OnvifSoap.passwordDigest("testpassword", "2026-10-25T00:00:00Z", "16bytesnonce1234".toByteArray()))
    }

    @Test
    fun `the security header carries a digest of its own nonce and timestamp, never the password`() {
        val nonce = OnvifSoap.nonce(SecureRandom())
        val created = OnvifSoap.created(1_761_436_800_123)
        assertEquals("2025-10-26T00:00:00.123Z", created)
        val header = OnvifSoap.securityHeader(credentials, created, nonce)
        val envelope = OnvifSoap.envelope(OnvifSoap.GET_DEVICE_INFORMATION, header)
        assertFalse(envelope.contains("s3cret"))
        val xml = SoapXml.parse(envelope)
        assertEquals("admin", xml.descendant("Username")?.text)
        assertEquals(Base64.getEncoder().encodeToString(nonce), xml.descendant("Nonce")?.text)
        assertEquals(created, xml.descendant("Created")?.text)
        assertEquals(OnvifSoap.passwordDigest("s3cret", created, nonce), xml.descendant("Password")?.text)
        assertTrue(envelope.contains("<Security s:mustUnderstand=\"1\""))
        assertTrue(envelope.contains("#PasswordDigest\">"))
        // The exact text other ONVIF clients send
        assertTrue(envelope.contains("<Username>admin</Username>"))
        assertTrue(envelope.contains("xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""))
        assertTrue(envelope.contains("<tds:GetDeviceInformation />"))
        // A fresh nonce for every request
        assertFalse(OnvifSoap.nonce(SecureRandom()).contentEquals(nonce))
        assertEquals(16, nonce.size)
    }

    @Test
    fun `without credentials there is no security header`() {
        val envelope = OnvifSoap.envelope(OnvifSoap.GET_PROFILES)
        assertFalse(envelope.contains("<s:Header>"))
        assertFalse(envelope.contains("UsernameToken"))
        assertTrue(envelope.contains("<s:Body>\n<trt:GetProfiles />\n</s:Body>"))
        assertEquals("GetProfiles", SoapXml.parse(envelope).descendant("Body")?.children?.single()?.name)
    }

    @Test
    fun `the bodies ask what ONVIF clients ask`() {
        val stream = OnvifSoap.getStreamUri("MainStream")
        assertTrue(stream.contains("<tt:Stream>RTP-Unicast</tt:Stream>"))
        assertTrue(stream.contains("<tt:Protocol>RTSP</tt:Protocol>"))
        assertTrue(stream.contains("<trt:ProfileToken>MainStream</trt:ProfileToken>"))
        assertTrue(OnvifSoap.getSnapshotUri("Sub").contains("<trt:GetSnapshotUri><trt:ProfileToken>Sub</trt:ProfileToken>"))
        assertTrue(OnvifSoap.GET_CAPABILITIES.contains("<tds:Category>All</tds:Category>"))
        val move = OnvifSoap.continuousMove("MainStream", PtzVelocity(pan = 0.5, tilt = -0.25))
        assertTrue(move.contains("<tt:PanTilt x=\"0.5\" y=\"-0.25\""))
        assertTrue(move.contains("<tt:Zoom x=\"0.0\""))
        assertTrue(move.contains("VelocityGenericSpace"))
        val stop = OnvifSoap.stop("MainStream")
        assertTrue(stop.contains("<tptz:Stop>"))
        assertTrue(stop.contains("<tptz:PanTilt>true</tptz:PanTilt>"))
        assertTrue(stop.contains("<tptz:Zoom>true</tptz:Zoom>"))
        // Every envelope is well-formed XML
        listOf(stream, move, stop, OnvifSoap.GET_SERVICES, OnvifSoap.GET_SYSTEM_DATE_AND_TIME).forEach { SoapXml.parse(OnvifSoap.envelope(it)) }
    }

    @Test
    fun `user names and profile tokens are escaped`() {
        val header = OnvifSoap.securityHeader(OnvifCredentials("a<b>&\"c'", "pw"), "2026-10-25T00:00:00.000Z", ByteArray(16))
        assertTrue(header.contains("<Username>a&lt;b&gt;&amp;&quot;c&apos;</Username>"))
        val body = OnvifSoap.getStreamUri("</trt:ProfileToken><evil/>")
        assertEquals("</trt:ProfileToken><evil/>", SoapXml.parse(OnvifSoap.envelope(body)).descendant("ProfileToken")?.text)
        assertEquals("a<b>&\"c'", SoapXml.parse(OnvifSoap.envelope("<x/>", header)).descendant("Username")?.text)
    }

    @Test
    fun `credentials never print their password`() {
        assertEquals("Credentials(username=admin, password=***)", credentials.toString())
    }
}
