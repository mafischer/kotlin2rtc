package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.Clock
import io.github.mafischer.kotlin2rtc.core.InetAddresses
import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.core.XmlElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/** Finds the cameras on this network; empty when none answered in time */
public fun interface DeviceDiscovery {
    public suspend fun discover(timeoutMillis: Long): List<DiscoveredDevice>
}

/**
 * A hook around discovery: Android drops multicast packets for apps that hold no `WifiManager.MulticastLock` (needs
 * CHANGE_WIFI_MULTICAST_STATE), so an Android caller supplies one that acquires and releases it; held only while a probe
 * runs. [None] (the default) on the JVM.
 */
public interface MulticastLock {
    public fun acquire()

    public fun release()

    public object None : MulticastLock {
        override fun acquire(): Unit = Unit

        override fun release(): Unit = Unit
    }
}

/** The WS-Discovery Probe and its ProbeMatches  */
public object WsDiscoveryMessages {
    /** A Probe for NetworkVideoTransmitter (every ONVIF camera) */
    public fun probe(messageId: String = "urn:uuid:${UUID.randomUUID()}"): String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
          <e:Header>
            <w:MessageID>$messageId</w:MessageID>
            <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
            <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
          </e:Header>
          <e:Body>
            <d:Probe>
              <d:Types>dn:NetworkVideoTransmitter</d:Types>
            </d:Probe>
          </e:Body>
        </e:Envelope>
        """.trimIndent()

    /**
     * The cameras in a ProbeMatches answer: the first HTTP address of XAddrs (IPv4 preferred), the name and hardware
     * from the onvif:// scopes (URL-decoded; "ONVIF Camera" / "Network Camera" without), port 80 by default. Not XML or
     * no match: empty.
     */
    public fun parse(text: String): List<DiscoveredDevice> {
        val xml = runCatching { SoapXml.parse(text) }.getOrNull() ?: return emptyList()
        return xml.descendants("ProbeMatch").mapNotNull { match ->
            val addresses = match.child("XAddrs")?.text?.split(Regex("\\s+")).orEmpty()
                .mapNotNull { address -> runCatching { URI(address) }.getOrNull()?.takeIf { it.scheme.equals("http", true) && it.host != null } }
            val uri = addresses.firstOrNull { !it.host.contains(':') && !it.host.startsWith("[") } ?: addresses.firstOrNull() ?: return@mapNotNull null
            val scopes = match.child("Scopes")?.text?.split(Regex("\\s+")).orEmpty()
            val scope = { kind: String ->
                scopes.firstOrNull { it.contains("/$kind/") }?.substringAfter("/$kind/")?.let(::decode)?.takeIf { it.isNotBlank() }
            }
            DiscoveredDevice(
                endpoint = match.path("EndpointReference", "Address")?.text?.takeIf { it.isNotEmpty() } ?: uri.toString(),
                name = scope("name") ?: DEFAULT_NAME,
                hardware = scope("hardware") ?: DEFAULT_HARDWARE,
                host = uri.host.removePrefix("[").removeSuffix("]"),
                port = if (uri.port > 0) uri.port else 80,
                deviceUrl = uri.toString(),
            )
        }
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    public const val DEFAULT_NAME: String = "ONVIF Camera"
    public const val DEFAULT_HARDWARE: String = "Network Camera"
}

/**
 * WS-Discovery over UDP: the Probe to 239.255.255.250:3702 (sent twice, UDP may drop one), then every ProbeMatch until
 * the timeout, one entry per device. The multicast lock is held meanwhile. [target] is a unicast responder in tests.
 * Answers from addresses [accept] refuses (e.g. `LocalNetwork::allows`: this device's subnets) are ignored, by sender
 * and by address. [now] is a monotonic clock (a wall-clock step must not stretch or cut the window).
 */
public class WsDiscovery(
    private val lock: MulticastLock = MulticastLock.None,
    private val accept: (InetAddress) -> Boolean,
    private val target: InetSocketAddress = InetSocketAddress(InetAddress.getByName(MULTICAST_ADDRESS), PORT),
    private val now: Clock = Clock.Monotonic,
) : DeviceDiscovery {
    override suspend fun discover(timeoutMillis: Long): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredDevice>()
        lock.acquire()
        try {
            MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                // A port of its own: with SO_REUSEADDR another socket could share the ephemeral port and take the answers
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(0))
                // Cameras answer on the local link only
                socket.timeToLive = 1
                val probe = WsDiscoveryMessages.probe().toByteArray(Charsets.UTF_8)
                repeat(2) { socket.send(DatagramPacket(probe, probe.size, target)) }
                val deadline = now.millis() + timeoutMillis
                val buffer = ByteArray(MAX_DATAGRAM)
                while (true) {
                    val remaining = deadline - now.millis()
                    if (remaining <= 0) break
                    ensureActive()
                    // Never wait past the deadline
                    socket.soTimeout = remaining.coerceAtMost(RECEIVE_SLICE_MILLIS.toLong()).toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    if (!accept(packet.address)) continue
                    WsDiscoveryMessages.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                        .filter { device -> InetAddresses.literal(device.host)?.let(accept) != false }
                        .forEach { device -> found.putIfAbsent(device.endpoint, device) }
                }
            }
        } finally {
            lock.release()
        }
        found.values.distinctBy { it.deviceUrl }
    }

    public companion object {
        public const val MULTICAST_ADDRESS: String = "239.255.255.250"
        public const val PORT: Int = 3702
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 3_000L
        private const val RECEIVE_SLICE_MILLIS = 250
        private const val MAX_DATAGRAM = 64 * 1024
    }
}
