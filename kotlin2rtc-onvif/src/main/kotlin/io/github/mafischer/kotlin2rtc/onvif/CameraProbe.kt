package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.HttpHeaders
import io.github.mafischer.kotlin2rtc.core.HttpRequest
import io.github.mafischer.kotlin2rtc.core.HttpTransport
import io.github.mafischer.kotlin2rtc.core.RtspUrls
import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.core.XmlElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a camera tells about itself before any login: its WS-Discovery endpoint reference (a stable
 * `urn:uuid:`, unique per device) and the hardware of its onvif:// scopes. Either may be missing.
 */
public data class CameraFingerprint(val endpoint: String?, val hardware: String? = null) {
    public companion object {
        /** From a ProbeMatch: the parser's fallbacks (device URL for a missing reference, "Network Camera") do not count */
        public fun of(device: DiscoveredDevice): CameraFingerprint = CameraFingerprint(
            endpoint = device.endpoint.takeIf { it != device.deviceUrl && !it.startsWith("http", ignoreCase = true) },
            hardware = device.hardware.takeIf { it != WsDiscoveryMessages.DEFAULT_HARDWARE },
        )
    }
}

/**
 * Where a camera is expected: its ONVIF device service (empty when it has none) and its RTSP stream (the RTSP port is
 * probed when there is no device service). Only the paths and ports matter: the probe calls the checked address.
 */
public data class ProbeTarget(val deviceUrl: String, val streamUri: String = "")

/** How to reach a camera without its login: does it answer, and which device is it */
public interface CameraProbe {
    /** The camera answers at [address] right now (short timeout, nothing secret sent) */
    public suspend fun answers(address: InetAddress, camera: ProbeTarget): Boolean

    /** What the device at [address] says it is, without credentials; null when it says nothing */
    public suspend fun identify(address: InetAddress, camera: ProbeTarget): CameraFingerprint?
}

/**
 * Whether the device at a camera's address is that camera, decided before any credentials go to it: off the
 * home network the same private address may be another device.
 */
public object CameraIdentity {
    public enum class Trust {
        /** The endpoint reference recorded for this camera matches */
        VERIFIED,

        /** Nothing recorded yet, and the device is plausibly the camera: allowed, its reference is recorded once it plays */
        FIRST_USE,

        /** Another endpoint reference than the recorded one: a different device */
        MISMATCH,

        /** Cannot tell: the device gave no reference to compare, or nothing recorded and nothing to go by off the camera's subnet */
        UNVERIFIED,
    }

    /** `urn:uuid:ABC` and `uuid:abc` are the same reference */
    public fun normalize(endpoint: String?): String? =
        endpoint?.trim()?.lowercase()?.removePrefix("urn:")?.removePrefix("uuid:")?.takeIf { it.isNotEmpty() }

    /**
     * [recorded]: the reference the caller recorded for the camera (null: none yet). [seen]: what the device at the
     * address says now. Without a recorded reference, the first use is allowed on the camera's own subnet ([onSubnet]),
     * or elsewhere when the device's hardware scope names the [model] stored for the camera.
     */
    public fun trust(recorded: String?, seen: CameraFingerprint?, onSubnet: Boolean, model: String?): Trust {
        val endpoint = normalize(seen?.endpoint)
        if (recorded != null) {
            return when (endpoint) {
                null -> Trust.UNVERIFIED
                normalize(recorded) -> Trust.VERIFIED
                else -> Trust.MISMATCH
            }
        }
        if (onSubnet || sameModel(model, seen?.hardware)) return Trust.FIRST_USE
        return Trust.UNVERIFIED
    }

    /** "IP8M-2493EB" and "ip8m 2493E" name the same model (one a prefix of the other, at least 4 letters or digits) */
    public fun sameModel(stored: String?, hardware: String?): Boolean {
        val a = stored.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
        val b = hardware.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
        if (a.length < MIN_MODEL || b.length < MIN_MODEL) return false
        return a.startsWith(b) || b.startsWith(a)
    }

    private const val MIN_MODEL = 4
}

/**
 * The network [CameraProbe]. Answering: an unauthenticated ONVIF GetSystemDateAndTime to the camera's device service at
 * the checked address (any HTTP answer counts), or a TCP connect to its RTSP port when it has no device service; both
 * within [timeoutMillis]. Identity: a WS-Discovery Probe sent by unicast UDP to the address (port 3702; cameras answer it
 * with their endpoint reference and scopes, also across subnets and through NAT), else ONVIF GetEndpointReference.
 * Nothing secret is sent, and only to addresses [allows] accepts (e.g. `LocalNetwork::allowsCamera`; the [transport]
 * checks its own policy too).
 */
public class NetworkCameraProbe(
    private val transport: HttpTransport,
    private val allows: (InetAddress) -> Boolean,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    /** WS-Discovery's port (a local responder in tests) */
    private val discoveryPort: Int = WsDiscovery.PORT,
) : CameraProbe {
    override suspend fun answers(address: InetAddress, camera: ProbeTarget): Boolean {
        if (!allows(address)) return false
        if (camera.deviceUrl.isBlank()) return tcpProbe(address, rtspPort(camera.streamUri), timeoutMillis)
        val request = HttpRequest(
            "POST",
            RtspUrls.withHost(camera.deviceUrl, address),
            mapOf(HttpHeaders.CONTENT_TYPE to OnvifSoap.CONTENT_TYPE),
            OnvifSoap.envelope(OnvifSoap.GET_SYSTEM_DATE_AND_TIME).toByteArray(Charsets.UTF_8),
            timeoutMillis.toLong(),
        )
        return try {
            transport.send(request)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun identify(address: InetAddress, camera: ProbeTarget): CameraFingerprint? {
        if (!allows(address)) return null
        unicastDiscovery(address)?.let { return it }
        if (camera.deviceUrl.isBlank()) return null
        val request = HttpRequest(
            "POST",
            RtspUrls.withHost(camera.deviceUrl, address),
            mapOf(HttpHeaders.CONTENT_TYPE to OnvifSoap.CONTENT_TYPE),
            OnvifSoap.envelope(OnvifSoap.GET_ENDPOINT_REFERENCE).toByteArray(Charsets.UTF_8),
            timeoutMillis.toLong(),
        )
        return try {
            val response = transport.send(request)
            if (response.status !in 200..299) return null
            OnvifParser.endpointReference(SoapXml.parse(response.text))?.let { CameraFingerprint(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** The ProbeMatch of the device at [address] (sent twice: UDP may drop one), or null within the timeout */
    private suspend fun unicastDiscovery(address: InetAddress): CameraFingerprint? = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMillis
                val probe = WsDiscoveryMessages.probe().toByteArray(Charsets.UTF_8)
                val target = InetSocketAddress(address, discoveryPort)
                repeat(2) { socket.send(DatagramPacket(probe, probe.size, target)) }
                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(MAX_DATAGRAM)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    // Only the device asked counts
                    if (packet.address != address) continue
                    val device = WsDiscoveryMessages.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)).firstOrNull() ?: continue
                    return@withContext CameraFingerprint.of(device)
                }
                null
            }
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    public companion object {
        /** How long [answers] and [identify] wait */
        public const val DEFAULT_TIMEOUT_MILLIS: Int = 1_500
        private const val MAX_DATAGRAM = 64 * 1024
        private const val RTSP_PORT = 554

        /** The RTSP port of a stream URI (554 by default) */
        public fun rtspPort(streamUri: String): Int = runCatching { URI(streamUri).port }.getOrNull()?.takeIf { it > 0 } ?: RTSP_PORT

        /** A TCP connect: true when [address]:[port] accepts within [timeoutMillis] */
        public suspend fun tcpProbe(address: InetAddress, port: Int, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): Boolean = withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(address, port), timeoutMillis) }
                true
            } catch (e: IOException) {
                false
            }
        }
    }
}
