package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.AddressNotAllowedException
import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.element
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.envelope
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.operation
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.soap
import io.github.mafischer.kotlin2rtc.testing.Offline
import io.github.mafischer.kotlin2rtc.testing.RecordingTransport
import io.github.mafischer.kotlin2rtc.testing.expectThrows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The ONVIF calls against a fake camera: what each call sends, the camera's clock in the
 * UsernameToken, HTTP Digest after a challenge (the real Amcrest one), and the errors: no answer, refused credentials,
 * SOAP faults, not ONVIF.
 */
class OnvifClientTest {
    private val device = "http://10.20.30.204/onvif/device_service"
    private val media = "http://10.20.30.204/onvif/media_service"
    private val credentials = OnvifCredentials("admin", "s3cret")
    private val now = Instant.parse("2026-10-27T00:50:00Z").toEpochMilli()

    private fun client(transport: RecordingTransport, credentials: OnvifCredentials? = this.credentials) = OnvifClient(transport, device, credentials, { now })

    @Test
    fun `device information is posted as SOAP with the security header`() = runTest {
        val transport = RecordingTransport {
            soap(
                body = envelope(
                    "<tds:GetDeviceInformationResponse><tds:Manufacturer>Amcrest</tds:Manufacturer><tds:Model>IP8M-2493E</tds:Model></tds:GetDeviceInformationResponse>",
                ),
            )
        }
        val info = client(transport).deviceInformation()
        assertEquals(OnvifDeviceInformation("Amcrest", "IP8M-2493E"), info)
        val request = transport.last
        assertEquals("POST", request.method)
        assertEquals(device, request.url)
        assertEquals("application/soap+xml; charset=utf-8", request.header("Content-Type"))
        assertEquals(4_000L, request.timeoutMillis)
        assertEquals("GetDeviceInformation", request.operation)
        assertEquals("admin", request.element("Username"))
        assertFalse(request.body!!.decodeToString().contains("s3cret"))
        // Our clock until the camera's is known
        assertEquals("2026-10-27T00:50:00.000Z", request.element("Created"))
    }

    @Test
    fun `the camera's clock sets the UsernameToken's timestamp`() = runTest {
        val transport = RecordingTransport { request ->
            when (request.operation) {
                "GetSystemDateAndTime" -> soap(body = OnvifFixtures.read("amcrest-system-date-and-time.xml"))
                else -> soap(body = envelope("<tptz:StopResponse/>"))
            }
        }
        val camera = client(transport)
        assertFalse(camera.clockSynced)
        assertEquals(Instant.parse("2026-10-27T00:51:47Z").toEpochMilli(), camera.syncClock())
        assertTrue(camera.clockSynced)
        assertEquals(107_000L, camera.clockOffsetMillis)
        // GetSystemDateAndTime is asked without credentials
        assertNull(transport.requests.first().element("Username"))
        camera.stop("http://10.20.30.204/onvif/ptz_service", "MainStream")
        assertEquals("2026-10-27T00:51:47.000Z", transport.last.element("Created"))
        assertEquals(3_000L, transport.last.timeoutMillis)
    }

    @Test
    fun `a camera without a clock answer keeps ours, an unreachable one throws`() = runTest {
        val refusing = RecordingTransport { soap(400, envelope("<SOAP-ENV:Fault><SOAP-ENV:Reason><SOAP-ENV:Text>Bad</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>")) }
        val camera = client(refusing)
        assertNull(camera.syncClock())
        assertEquals(0L, camera.clockOffsetMillis)
        assertNull(client(RecordingTransport { soap(body = envelope("<tds:GetSystemDateAndTimeResponse/>")) }).syncClock())
        expectThrows<OnvifError.Unreachable> { client(RecordingTransport { throw Offline() }).syncClock() }
        expectThrows<OnvifError.NotLocal> { client(RecordingTransport { throw AddressNotAllowedException() }).syncClock() }
    }

    @Test
    fun `a Digest challenge is answered once with an Authorization header`() = runTest {
        val challenge = OnvifFixtures.read("amcrest-digest-challenge.txt").trim()
        val transport = RecordingTransport { request ->
            if (request.header("Authorization") == null) {
                HttpResponse(401, mapOf("WWW-Authenticate" to challenge))
            } else {
                soap(body = envelope("<tds:GetDeviceInformationResponse><tds:Model>IP8M-2493E</tds:Model></tds:GetDeviceInformationResponse>"))
            }
        }
        assertEquals("IP8M-2493E", client(transport).deviceInformation().model)
        assertEquals(2, transport.requests.size)
        val authorization = transport.last.header("Authorization")!!
        assertTrue(authorization.startsWith("Digest username=\"admin\", realm=\"Login to AMC0000000000000000\""))
        assertTrue(authorization.contains("uri=\"/onvif/device_service\""))
        assertTrue(authorization.contains("nc=00000001"))
        // A fresh UsernameToken in the second request too
        assertTrue(transport.requests[0].element("Nonce") != transport.requests[1].element("Nonce"))
    }

    @Test
    fun `refused credentials are Unauthorized, with or without a challenge`() = runTest {
        val challenge = OnvifFixtures.read("amcrest-digest-challenge.txt").trim()
        val alwaysRefused = RecordingTransport { HttpResponse(401, mapOf("WWW-Authenticate" to challenge)) }
        assertEquals(OnvifError.Unauthorized, expectThrows<OnvifError> { client(alwaysRefused).deviceInformation() })
        // Tried once with Digest, not more
        assertEquals(2, alwaysRefused.requests.size)
        // Without credentials there is nothing to answer the challenge with
        val anonymous = RecordingTransport { HttpResponse(401, mapOf("WWW-Authenticate" to challenge)) }
        assertEquals(OnvifError.Unauthorized, expectThrows<OnvifError> { client(anonymous, null).deviceInformation() })
        assertEquals(1, anonymous.requests.size)
        // The SOAP way: a NotAuthorized fault (status 400 or 500)
        val fault = RecordingTransport {
            soap(
                400,
                envelope(
                    "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Sender</SOAP-ENV:Value><SOAP-ENV:Subcode><SOAP-ENV:Value>ter:NotAuthorized</SOAP-ENV:Value></SOAP-ENV:Subcode></SOAP-ENV:Code></SOAP-ENV:Fault>",
                ),
            )
        }
        assertEquals(OnvifError.Unauthorized, expectThrows<OnvifError> { client(fault).deviceInformation() })
        // A Basic challenge is not answered
        val basic = RecordingTransport { HttpResponse(401, mapOf("WWW-Authenticate" to "Basic realm=\"cam\"")) }
        assertEquals(OnvifError.Unauthorized, expectThrows<OnvifError> { client(basic).deviceInformation() })
        assertEquals(1, basic.requests.size)
        assertEquals(OnvifError.Unauthorized, expectThrows<OnvifError> { client(RecordingTransport { HttpResponse(403) }).deviceInformation() })
    }

    @Test
    fun `faults, other errors and answers that are not ONVIF`() = runTest {
        val fault = RecordingTransport {
            soap(
                500,
                envelope(
                    "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Receiver</SOAP-ENV:Value></SOAP-ENV:Code><SOAP-ENV:Reason><SOAP-ENV:Text>Busy</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>",
                ),
            )
        }
        assertEquals(OnvifError.Fault("SOAP-ENV:Receiver", null, "Busy"), expectThrows<OnvifError.Fault> { client(fault).profiles(media) })
        assertEquals(
            OnvifError.Http(404),
            expectThrows<OnvifError.Http> {
                client(RecordingTransport { HttpResponse(404, body = "<html/>".encodeToByteArray()) }).deviceInformation()
            },
        )
        expectThrows<OnvifError.InvalidResponse> { client(RecordingTransport { HttpResponse(200, body = "OK".encodeToByteArray()) }).deviceInformation() }
        val offline = expectThrows<OnvifError.Unreachable> { client(RecordingTransport { throw Offline() }).deviceInformation() }
        assertTrue(offline.cause is Offline)
    }

    @Test
    fun `endpoints come from GetCapabilities, else from GetServices`() = runTest {
        val capabilities = RecordingTransport { soap(body = OnvifFixtures.read("amcrest-capabilities.xml")) }
        assertEquals(media, client(capabilities).endpoints().mediaUrl)
        assertEquals(listOf("GetCapabilities"), capabilities.requests.map { it.operation })

        val servicesOnly = RecordingTransport { request ->
            when (request.operation) {
                "GetServices" -> soap(body = OnvifFixtures.read("amcrest-services.xml"))
                else -> soap(400, envelope("<SOAP-ENV:Fault><SOAP-ENV:Reason><SOAP-ENV:Text>Action not supported</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>"))
            }
        }
        assertEquals(OnvifEndpoints(media, null, "http://10.20.30.204/onvif/event_service"), client(servicesOnly).endpoints())
        assertEquals(listOf("GetCapabilities", "GetServices"), servicesOnly.requests.map { it.operation })
        assertEquals("false", servicesOnly.last.element("IncludeCapability"))

        // A camera that refuses the credentials does not get a second call
        val refused = RecordingTransport { HttpResponse(401) }
        expectThrows<OnvifError.Unauthorized> { client(refused).endpoints() }
        assertEquals(1, refused.requests.size)
        for (answer in listOf(HttpResponse(404), soap(body = envelope("<x/>")))) {
            val fallback = RecordingTransport { request -> if (request.operation == "GetServices") soap(body = OnvifFixtures.read("amcrest-services.xml")) else answer }
            assertEquals(media, client(fallback).endpoints().mediaUrl)
        }
    }

    @Test
    fun `the stream URI is on the camera's host, unescaped, without added credentials`() = runTest {
        val transport = RecordingTransport {
            soap(
                body = envelope(
                    "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://0.0.0.0:554/cam/realmonitor?channel=1&amp;subtype=0</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>",
                ),
            )
        }
        assertEquals("rtsp://10.20.30.204:554/cam/realmonitor?channel=1&subtype=0", client(transport).streamUri(media, "MainStream"))
        assertEquals(media, transport.last.url)
        assertEquals("MainStream", transport.last.element("ProfileToken"))
        assertEquals("RTP-Unicast", transport.last.element("Stream"))
    }

    @Test
    fun `the snapshot URI, or null when the camera has none`() = runTest {
        val transport = RecordingTransport {
            soap(body = envelope("<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>http://10.20.30.204/cgi-bin/snapshot.cgi</tt:Uri></trt:MediaUri></trt:GetSnapshotUriResponse>"))
        }
        assertEquals("http://10.20.30.204/cgi-bin/snapshot.cgi", client(transport).snapshotUri(media, "MainStream"))
        val none = RecordingTransport { soap(400, envelope("<SOAP-ENV:Fault><SOAP-ENV:Reason><SOAP-ENV:Text>No snapshot</SOAP-ENV:Text></SOAP-ENV:Reason></SOAP-ENV:Fault>")) }
        assertNull(client(none).snapshotUri(media, "MainStream"))
        assertNull(client(RecordingTransport { soap(body = envelope("<x/>")) }).snapshotUri(media, "MainStream"))
    }

    @Test
    fun `continuous move sends the velocities with the shorter timeout`() = runTest {
        val transport = RecordingTransport { soap(body = envelope("<tptz:ContinuousMoveResponse />")) }
        client(transport).continuousMove("http://10.20.30.204/onvif/ptz_service", "MainStream", PtzVelocity(pan = 0.5, tilt = -0.25))
        val body = transport.last.body!!.decodeToString()
        assertEquals("http://10.20.30.204/onvif/ptz_service", transport.last.url)
        assertEquals(3_000L, transport.last.timeoutMillis)
        assertTrue(body.contains("<tt:PanTilt x=\"0.5\" y=\"-0.25\""))
        assertTrue(body.contains("<tt:Zoom x=\"0.0\""))
        expectThrows<OnvifError.Http> { client(RecordingTransport { HttpResponse(500) }).continuousMove(device, "p", PtzVelocity(zoom = 1.0)) }
    }

    @Test
    fun `a user without a name sends no security header`() = runTest {
        val transport = RecordingTransport { soap(body = envelope("<trt:GetProfilesResponse/>")) }
        assertTrue(client(transport, OnvifCredentials("", "x")).profiles(media).isEmpty())
        assertFalse(transport.last.body!!.decodeToString().contains("UsernameToken"))
    }
}
