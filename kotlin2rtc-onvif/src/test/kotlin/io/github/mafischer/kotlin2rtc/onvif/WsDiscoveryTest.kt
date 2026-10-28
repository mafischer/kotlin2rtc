package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.SoapXml
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * WS-Discovery: the Probe, the ProbeMatches of real cameras (Amcrest, DoorBird) and
 * a minimal one, and a probe over UDP to a responder on this machine (the multicast lock held meanwhile).
 */
class WsDiscoveryTest {
    @Test
    fun `the probe asks for NetworkVideoTransmitter with a fresh message id`() {
        val probe = WsDiscoveryMessages.probe("urn:uuid:test")
        assertTrue(probe.contains("<d:Types>dn:NetworkVideoTransmitter</d:Types>"))
        assertTrue(probe.contains("http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe"))
        assertTrue(probe.contains("<w:MessageID>urn:uuid:test</w:MessageID>"))
        assertTrue(WsDiscoveryMessages.probe() != WsDiscoveryMessages.probe())
        assertEquals("Probe", SoapXml.parse(probe).descendant("Body")?.children?.single()?.name)
    }

    @Test
    fun `the real cameras' answers`() {
        assertEquals(
            listOf(
                DiscoveredDevice(
                    "uuid:11111111-2222-4333-8444-555555555555",
                    "Amcrest",
                    "IP8M-2493E",
                    "10.20.30.204",
                    80,
                    "http://10.20.30.204/onvif/device_service",
                ),
            ),
            WsDiscoveryMessages.parse(OnvifFixtures.read("amcrest-probe-match.xml")),
        )
        assertEquals(
            listOf(
                DiscoveredDevice(
                    "urn:uuid:66666666-7777-4888-8999-aaaaaaaaaaaa",
                    "DoorBird_D101S",
                    "IntercomDoorBirdOne",
                    "10.20.30.185",
                    80,
                    "http://10.20.30.185/onvif/device_service",
                ),
            ),
            WsDiscoveryMessages.parse(OnvifFixtures.read("doorbird-probe-match.xml")),
        )
    }

    @Test
    fun `a minimal answer, several addresses, defaults and junk`() {
        val minimal =
            """
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
              <e:Body><d:ProbeMatches><d:ProbeMatch>
                <d:Types>dn:NetworkVideoTransmitter</d:Types>
                <d:Scopes>onvif://www.onvif.org/name/Amcrest%20Nursery onvif://www.onvif.org/hardware/IP4M-1051</d:Scopes>
                <d:XAddrs>http://10.20.30.204/onvif/device_service</d:XAddrs>
              </d:ProbeMatch></d:ProbeMatches></e:Body>
            </e:Envelope>
            """.trimIndent()
        val device = WsDiscoveryMessages.parse(minimal).single()
        assertEquals("10.20.30.204", device.host)
        assertEquals(80, device.port)
        assertEquals("Amcrest Nursery", device.name)
        assertEquals("IP4M-1051", device.hardware)
        assertEquals("http://10.20.30.204/onvif/device_service", device.deviceUrl)
        // Without an endpoint reference the device URL identifies it
        assertEquals("http://10.20.30.204/onvif/device_service", device.endpoint)

        val several = minimal.replace(
            "<d:XAddrs>http://10.20.30.204/onvif/device_service</d:XAddrs>",
            "<d:XAddrs>http://[fe80::1]:8080/onvif/device_service http://192.168.1.9:8080/onvif/device_service</d:XAddrs>",
        ).replace(Regex("<d:Scopes>.*</d:Scopes>"), "")
        val ipv4 = WsDiscoveryMessages.parse(several).single()
        assertEquals("192.168.1.9", ipv4.host)
        assertEquals(8080, ipv4.port)
        assertEquals(WsDiscoveryMessages.DEFAULT_NAME, ipv4.name)
        assertEquals(WsDiscoveryMessages.DEFAULT_HARDWARE, ipv4.hardware)
        val onlyV6 = minimal.replace("http://10.20.30.204/onvif/device_service", "http://[fe80::1]/onvif/device_service")
        assertEquals("fe80::1", WsDiscoveryMessages.parse(onlyV6).single().host)

        assertTrue(WsDiscoveryMessages.parse("not xml").isEmpty())
        assertTrue(WsDiscoveryMessages.parse(minimal.replace(Regex("<d:XAddrs>.*</d:XAddrs>"), "")).isEmpty())
        assertTrue(WsDiscoveryMessages.parse(minimal.replace("http://10.20.30.204", "https://10.20.30.204")).isEmpty())
    }

    @Test
    fun `junk addresses and scopes, blank names and empty endpoint references`() {
        val match = { xaddrs: String, scopes: String, endpoint: String ->
            """
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing">
              <e:Body><d:ProbeMatches><d:ProbeMatch>
                <a:EndpointReference><a:Address>$endpoint</a:Address></a:EndpointReference>
                <d:Scopes>$scopes</d:Scopes>
                <d:XAddrs>$xaddrs</d:XAddrs>
              </d:ProbeMatch></d:ProbeMatches></e:Body>
            </e:Envelope>
            """.trimIndent()
        }
        // Not URIs, a URL without a host, then the usable one; a name that is blank once decoded; a malformed escape kept as sent
        val device = WsDiscoveryMessages.parse(
            match(
                "http://bad^host/onvif http:/onvif/device_service http://10.20.30.204:8000/onvif/device_service",
                "onvif://www.onvif.org/name/%20 onvif://www.onvif.org/hardware/IP%ZZ onvif://www.onvif.org/location/home",
                "",
            ),
        ).single()
        assertEquals("10.20.30.204", device.host)
        assertEquals(8000, device.port)
        assertEquals(WsDiscoveryMessages.DEFAULT_NAME, device.name)
        assertEquals("IP%ZZ", device.hardware)
        // An empty endpoint reference: the device URL identifies the camera
        assertEquals("http://10.20.30.204:8000/onvif/device_service", device.endpoint)

        val referenced = WsDiscoveryMessages.parse(match("http://10.20.30.205/onvif/device_service", "", "urn:uuid:cam-205")).single()
        assertEquals("urn:uuid:cam-205", referenced.endpoint)
        assertEquals(WsDiscoveryMessages.DEFAULT_HARDWARE, referenced.hardware)
    }

    /*
     * Hermetic and timing-free: the window is closed by the test's clock once the second round's last answer was read,
     * not by wall time (a stalled responder thread or a GC pause longer than the window emptied the result). The
     * responder answers every probe it gets until it is closed, so a stray datagram on its port (another test JVM
     * probing a port it no longer owns) cannot use up the rounds meant for this probe. A guard of 30 s turns a lost
     * datagram into a failure instead of a hang.
     */
    @Test
    fun `a probe over UDP collects every camera once, holding the multicast lock`() = runBlocking {
        val answers = listOf("garbage", OnvifFixtures.read("amcrest-probe-match.xml"), OnvifFixtures.read("doorbird-probe-match.xml"))
        val probes = ConcurrentLinkedQueue<Pair<SocketAddress, String>>()
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { responder ->
            val worker = thread {
                val buffer = ByteArray(65_535)
                // Until the socket is closed: every probe is answered by both cameras (after some junk), the second round repeats them
                runCatching {
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        responder.receive(packet)
                        probes += packet.socketAddress to String(packet.data, 0, packet.length)
                        for (answer in answers) {
                            val bytes = answer.toByteArray()
                            responder.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                        }
                    }
                }
            }
            // The DoorBird answers last in each round: once its address was checked twice, both rounds were read
            val doorBirdChecks = AtomicInteger()
            val accept = { address: InetAddress ->
                if (address.hostAddress == "10.20.30.185") doorBirdChecks.incrementAndGet()
                address.isLoopbackAddress || address.hostAddress == "10.20.30.204"
            }
            val guard = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            val clock = { if (doorBirdChecks.get() >= 2 || System.nanoTime() > guard) Long.MAX_VALUE / 2 else 0L }
            val lock = RecordingLock()
            val found = WsDiscovery(lock, accept, InetSocketAddress(InetAddress.getLoopbackAddress(), responder.localPort), clock).discover(1_000)
            responder.close()
            worker.join()

            assertEquals(2, doorBirdChecks.get(), "both rounds answered within the guard")
            // The DoorBird at 10.20.30.185 is outside what this probe accepts: ignored
            assertEquals(listOf("10.20.30.204"), found.map { it.host })
            // The probe was sent twice from one socket (a stray sender, if any, is another key)
            assertTrue(probes.groupBy({ it.first }, { it.second }).values.any { sent -> sent.size == 2 && sent.all { it.contains("NetworkVideoTransmitter") } })
            assertEquals(listOf("acquire", "release"), lock.calls)
        }
    }

    @Test
    fun `nobody answering is an empty list after the timeout`() = runBlocking {
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { silent ->
            val lock = RecordingLock()
            val started = System.nanoTime()
            val found = WsDiscovery(lock, accept = { true }, target = InetSocketAddress(InetAddress.getLoopbackAddress(), silent.localPort)).discover(300)
            assertTrue(found.isEmpty())
            // The default clock is monotonic, like System.nanoTime
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 300)
            assertEquals(listOf("acquire", "release"), lock.calls)
        }
        MulticastLock.None.acquire()
        MulticastLock.None.release()
    }

    @Test
    fun `the window ends on the injected clock, whatever arrives`() = runBlocking {
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { silent ->
            // 0 when the deadline is set, then past it: one look at the socket at most, no real waiting for the timeout
            val ticks = ArrayDeque(listOf(0L, 0L))
            val clock = { ticks.removeFirstOrNull() ?: 60_000L }
            val started = System.nanoTime()
            val found = WsDiscovery(accept = { true }, target = InetSocketAddress(InetAddress.getLoopbackAddress(), silent.localPort), now = clock).discover(60_000)
            assertTrue(found.isEmpty())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 60_000)
        }
    }

    private class RecordingLock : MulticastLock {
        val calls = mutableListOf<String>()

        override fun acquire() {
            calls += "acquire"
        }

        override fun release() {
            calls += "release"
        }
    }
}
