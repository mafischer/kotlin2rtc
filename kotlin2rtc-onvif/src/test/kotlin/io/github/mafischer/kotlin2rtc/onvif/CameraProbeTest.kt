package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.testing.RecordingTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * the probe without credentials (ONVIF GetSystemDateAndTime, the RTSP port without a device service), the
 * identity by unicast WS-Discovery or GetEndpointReference, and the trust rules that decide whether the login may go
 * to the device at a camera's address.
 */
class CameraProbeTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private fun camera(deviceUrl: String = "http://10.20.30.204/onvif/device_service", stream: String = "rtsp://10.20.30.204:554/live") = ProbeTarget(
        deviceUrl = deviceUrl,
        streamUri = stream,
    )

    @Test
    fun `the trust rules`() {
        val home = CameraFingerprint("urn:uuid:11111111-2222", "IP8M-2493E")
        // Recorded: only the same reference
        assertEquals(CameraIdentity.Trust.VERIFIED, CameraIdentity.trust("11111111-2222", home, onSubnet = false, model = null))
        assertEquals(CameraIdentity.Trust.MISMATCH, CameraIdentity.trust("other", home, onSubnet = true, model = "IP8M-2493E"))
        assertEquals(CameraIdentity.Trust.UNVERIFIED, CameraIdentity.trust("11111111-2222", null, onSubnet = true, model = null))
        assertEquals(CameraIdentity.Trust.UNVERIFIED, CameraIdentity.trust("11111111-2222", CameraFingerprint(null, "IP8M-2493E"), onSubnet = true, model = null))
        // Not recorded: the camera's subnet, or the stored model
        assertEquals(CameraIdentity.Trust.FIRST_USE, CameraIdentity.trust(null, null, onSubnet = true, model = null))
        assertEquals(CameraIdentity.Trust.FIRST_USE, CameraIdentity.trust(null, home, onSubnet = false, model = "IP8M-2493EB"))
        assertEquals(CameraIdentity.Trust.UNVERIFIED, CameraIdentity.trust(null, home, onSubnet = false, model = "D101S"))
        assertEquals(CameraIdentity.Trust.UNVERIFIED, CameraIdentity.trust(null, home, onSubnet = false, model = null))
        assertEquals(CameraIdentity.Trust.UNVERIFIED, CameraIdentity.trust(null, null, onSubnet = false, model = "IP8M-2493EB"))
    }

    @Test
    fun `references and models compare loosely, discovery fallbacks do not count`() {
        assertEquals("abc-1", CameraIdentity.normalize(" urn:uuid:ABC-1 "))
        assertEquals("abc-1", CameraIdentity.normalize("uuid:abc-1"))
        assertNull(CameraIdentity.normalize(""))
        assertNull(CameraIdentity.normalize(null))
        assertTrue(CameraIdentity.sameModel("IP8M-2493EB", "ip8m 2493e"))
        assertTrue(CameraIdentity.sameModel("IP8M-2493E", "IP8M-2493E"))
        assertFalse(CameraIdentity.sameModel("IP8M-2493E", "IP5M-1173E"))
        // Too short to mean anything
        assertFalse(CameraIdentity.sameModel("IPC", "IPC"))
        assertFalse(CameraIdentity.sameModel(null, "IP8M-2493E"))
        val parsed = WsDiscoveryMessages.parse(OnvifFixtures.read("amcrest-probe-match.xml")).single()
        assertEquals(CameraFingerprint("uuid:11111111-2222-4333-8444-555555555555", "IP8M-2493E"), CameraFingerprint.of(parsed))
        val bare =
            DiscoveredDevice("http://10.0.0.5/onvif/device_service", "ONVIF Camera", WsDiscoveryMessages.DEFAULT_HARDWARE, "10.0.0.5", 80, "http://10.0.0.5/onvif/device_service")
        assertEquals(CameraFingerprint(null, null), CameraFingerprint.of(bare))
    }

    @Test
    fun `answering is an unauthenticated GetSystemDateAndTime at the checked address`() = runBlocking {
        val transport = RecordingTransport { OnvifFixtures.soap(body = OnvifFixtures.read("amcrest-system-date-and-time.xml")) }
        val probe = NetworkCameraProbe(transport, allows = { true })
        assertTrue(probe.answers(InetAddress.getByName("10.20.30.204"), camera(deviceUrl = "http://driveway.lan/onvif/device_service")))
        val request = transport.last
        assertEquals("http://10.20.30.204/onvif/device_service", request.url)
        assertEquals(NetworkCameraProbe.DEFAULT_TIMEOUT_MILLIS.toLong(), request.timeoutMillis)
        val body = request.body!!.decodeToString()
        assertTrue(body.contains("GetSystemDateAndTime"))
        // No credentials of any kind
        assertFalse(body.contains("UsernameToken"))
        assertTrue(request.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
        // Any HTTP answer counts (a 401 is a camera too); no answer does not
        transport.handler = { HttpResponse(401, emptyMap(), ByteArray(0)) }
        assertTrue(probe.answers(InetAddress.getByName("10.20.30.204"), camera()))
        transport.handler = { throw IOException("timeout") }
        assertFalse(probe.answers(InetAddress.getByName("10.20.30.204"), camera()))
    }

    @Test
    fun `addresses that are not allowed are never called`() = runBlocking {
        val transport = RecordingTransport()
        val probe = NetworkCameraProbe(transport, allows = { false })
        assertFalse(probe.answers(InetAddress.getByName("8.8.8.8"), camera()))
        assertNull(probe.identify(InetAddress.getByName("8.8.8.8"), camera()))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `without a device service the RTSP port is tried`() = runBlocking {
        val server = ServerSocket(0, 1, loopback)
        val probe = NetworkCameraProbe(RecordingTransport(), allows = { true })
        assertTrue(probe.answers(loopback, camera(deviceUrl = "", stream = "rtsp://cam:${server.localPort}/live")))
        val port = server.localPort
        server.close()
        assertFalse(probe.answers(loopback, camera(deviceUrl = "", stream = "rtsp://cam:$port/live")))
        assertEquals(554, NetworkCameraProbe.rtspPort("rtsp://cam/live"))
        assertEquals(8554, NetworkCameraProbe.rtspPort("rtsp://cam:8554/live"))
    }

    @Test
    fun `identity from a unicast WS-Discovery probe`() = runBlocking {
        DatagramSocket(0, loopback).use { responder ->
            val answer = OnvifFixtures.read("amcrest-probe-match.xml").toByteArray()
            val worker = thread {
                val packet = DatagramPacket(ByteArray(65_536), 65_536)
                responder.receive(packet)
                responder.send(DatagramPacket(answer, answer.size, packet.socketAddress))
            }
            val transport = RecordingTransport()
            val probe = NetworkCameraProbe(transport, allows = { true }, discoveryPort = responder.localPort)
            assertEquals(CameraFingerprint("uuid:11111111-2222-4333-8444-555555555555", "IP8M-2493E"), probe.identify(loopback, camera()))
            worker.join()
            // HTTP was not needed
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun `identity from GetEndpointReference when discovery is silent, else none`() = runBlocking {
        // A bound socket that never answers, held for the whole test: probing a port this test had closed again reached
        // whatever bound it next, e.g. WsDiscoveryTest's responder in a parallel test JVM, which then answered these
        // probes instead of its own (its discovery found nothing)
        DatagramSocket(0, loopback).use { silent ->
            val transport = RecordingTransport {
                OnvifFixtures.soap(body = OnvifFixtures.envelope("<tds:GetEndpointReferenceResponse><tds:GUID>urn:uuid:doorbird-1</tds:GUID></tds:GetEndpointReferenceResponse>"))
            }
            val probe = NetworkCameraProbe(transport, allows = { true }, timeoutMillis = 200, discoveryPort = silent.localPort)
            assertEquals(CameraFingerprint("urn:uuid:doorbird-1"), probe.identify(loopback, camera(deviceUrl = "http://cam/onvif/device_service")))
            assertTrue(transport.last.body!!.decodeToString().contains("GetEndpointReference"))
            assertEquals("http://127.0.0.1/onvif/device_service", transport.last.url)
            // Amcrest: not implemented (a fault), and failures: nothing to tell
            transport.handler =
                {
                    OnvifFixtures.soap(
                        500,
                        OnvifFixtures.envelope("<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Sender</SOAP-ENV:Value></SOAP-ENV:Code></SOAP-ENV:Fault>"),
                    )
                }
            assertNull(probe.identify(loopback, camera()))
            transport.handler = { throw IOException("timeout") }
            assertNull(probe.identify(loopback, camera()))
            assertNull(probe.identify(loopback, camera(deviceUrl = "")))
        }
    }
}
