package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.AddressNotAllowedException
import io.github.mafischer.kotlin2rtc.core.Clock
import io.github.mafischer.kotlin2rtc.core.HttpDigest
import io.github.mafischer.kotlin2rtc.core.HttpHeaders
import io.github.mafischer.kotlin2rtc.core.HttpRequest
import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.core.HttpTransport
import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.core.XmlElement
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import kotlin.coroutines.cancellation.CancellationException

/**
 * One ONVIF camera (the device service at [deviceUrl]): device information, capabilities and services, media
 * profiles, stream and snapshot URIs, PTZ, with the camera's clock for the WS-Security timestamp (GetSystemDateAndTime),
 * GetServices when GetCapabilities is refused, GetSnapshotUri, HTTP Digest when the camera asks for it, and distinct errors
 * for no answer, refused credentials and SOAP faults ([OnvifError]).
 *
 * Every authenticated request carries a UsernameToken (password digest, fresh nonce); a `401` with a Digest challenge
 * is answered once with an `Authorization` header (Amcrest/Dahua do that). The [transport] is typically
 * [io.github.mafischer.kotlin2rtc.core.LanHttpTransport] (cleartext to local addresses only); fakes in tests.
 */
public class OnvifClient(
    private val transport: HttpTransport,
    public val deviceUrl: String,
    private val credentials: OnvifCredentials?,
    private val now: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The camera's clock minus ours, for the UsernameToken's Created (cameras refuse timestamps far off their clock) */
    public var clockOffsetMillis: Long = 0
        private set

    /** [syncClock] ran (successfully or not): it is not asked again */
    public var clockSynced: Boolean = false
        private set

    private var nonceCount = 0

    /**
     * GetSystemDateAndTime, unauthenticated (allowed by the ONVIF spec before any login): the camera's UTC clock sets
     * [clockOffsetMillis]. A camera that does not answer it keeps our clock.
     */
    public suspend fun syncClock(): Long? {
        clockSynced = true
        val xml = try {
            call(deviceUrl, OnvifSoap.GET_SYSTEM_DATE_AND_TIME, authenticated = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnvifError.Unreachable) {
            throw e
        } catch (e: OnvifError.NotLocal) {
            throw e
        } catch (e: OnvifError) {
            return null
        }
        val camera = OnvifParser.systemDateAndTime(xml) ?: return null
        clockOffsetMillis = camera - now.millis()
        return camera
    }

    public suspend fun deviceInformation(): OnvifDeviceInformation = OnvifParser.deviceInformation(call(deviceUrl, OnvifSoap.GET_DEVICE_INFORMATION))

    public suspend fun capabilities(): OnvifEndpoints = OnvifParser.capabilities(call(deviceUrl, OnvifSoap.GET_CAPABILITIES), deviceUrl)

    public suspend fun services(): OnvifEndpoints = OnvifParser.services(call(deviceUrl, OnvifSoap.GET_SERVICES), deviceUrl)

    /** GetCapabilities, else GetServices (cameras of ONVIF 2.x profiles may answer only one of them) */
    public suspend fun endpoints(): OnvifEndpoints = try {
        capabilities()
    } catch (e: OnvifError.Fault) {
        services()
    } catch (e: OnvifError.Http) {
        services()
    } catch (e: OnvifError.InvalidResponse) {
        services()
    }

    public suspend fun profiles(mediaUrl: String): List<OnvifProfile> = OnvifParser.profiles(call(mediaUrl, OnvifSoap.GET_PROFILES))

    /**
     * GetStreamUri (RTP-Unicast over RTSP) of the profile, on the host the camera was reached at ([OnvifUrls.onHost]).
     * Returned as the camera sent it: credentials are added only for playback, in memory ([io.github.mafischer.kotlin2rtc.core.RtspUrls.withCredentials]).
     */
    public suspend fun streamUri(mediaUrl: String, profileToken: String): String =
        OnvifUrls.onHost(OnvifParser.mediaUri(call(mediaUrl, OnvifSoap.getStreamUri(profileToken)), "GetStreamUriResponse"), deviceUrl)

    /** GetSnapshotUri: a JPEG of the profile (HTTP, usually behind Digest), or null when the camera has none */
    public suspend fun snapshotUri(mediaUrl: String, profileToken: String): String? = try {
        OnvifUrls.onHost(OnvifParser.mediaUri(call(mediaUrl, OnvifSoap.getSnapshotUri(profileToken)), "GetSnapshotUriResponse"), deviceUrl)
    } catch (e: OnvifError.Fault) {
        null
    } catch (e: OnvifError.InvalidResponse) {
        null
    }

    /** PTZ ContinuousMove: the camera moves until [stop] (a shorter timeout: a press must feel immediate) */
    public suspend fun continuousMove(ptzUrl: String, profileToken: String, velocity: PtzVelocity) {
        call(ptzUrl, OnvifSoap.continuousMove(profileToken, velocity), timeoutMillis = PTZ_TIMEOUT_MILLIS)
    }

    /** PTZ GetNodes: whether the camera has an optical zoom */
    public suspend fun ptzZoomSupport(ptzUrl: String): PtzZoomSupport =
        OnvifParser.ptzZoomSupport(call(ptzUrl, OnvifSoap.GET_NODES, timeoutMillis = PTZ_TIMEOUT_MILLIS))

    /** PTZ GetStatus: the zoom position, or null when the camera reports none */
    public suspend fun ptzZoomPosition(ptzUrl: String, profileToken: String): Double? =
        OnvifParser.ptzZoomPosition(call(ptzUrl, OnvifSoap.getStatus(profileToken), timeoutMillis = PTZ_TIMEOUT_MILLIS))

    /** PTZ Stop of pan, tilt and zoom */
    public suspend fun stop(ptzUrl: String, profileToken: String) {
        call(ptzUrl, OnvifSoap.stop(profileToken), timeoutMillis = PTZ_TIMEOUT_MILLIS)
    }

    /** Sends the SOAP [body] (one operation, e.g. `<tds:GetDeviceInformation />`) to the service at [url], with the
     * WS-Security header unless not [authenticated], answering a Digest challenge once; returns the parsed answer.
     * Throws [OnvifError]. The building block of every other call, public for operations this client has no method for. */
    public suspend fun call(url: String, body: String, authenticated: Boolean = true, timeoutMillis: Long = TIMEOUT_MILLIS): XmlElement {
        val user = credentials.takeIf { authenticated && it != null && it.username.isNotEmpty() }
        val envelope = OnvifSoap.envelope(body, user?.let { securityHeader(it) }).toByteArray(Charsets.UTF_8)
        val headers = mapOf(HttpHeaders.CONTENT_TYPE to OnvifSoap.CONTENT_TYPE)
        var response = send(HttpRequest("POST", url, headers, envelope, timeoutMillis))
        if (response.status == 401 && user != null) {
            val challenge = HttpDigest.parseChallenge(response.header(HttpHeaders.WWW_AUTHENTICATE))
            if (challenge != null) {
                nonceCount += 1
                val authorization = HttpDigest.authorization(challenge, user, "POST", requestTarget(url), HttpDigest.cnonce(random), nonceCount)
                // A fresh UsernameToken too: some cameras check both
                val again = OnvifSoap.envelope(body, securityHeader(user)).toByteArray(Charsets.UTF_8)
                response = send(HttpRequest("POST", url, headers + (HttpHeaders.AUTHORIZATION to authorization), again, timeoutMillis))
            }
        }
        return answer(response)
    }

    private fun securityHeader(user: OnvifCredentials): String {
        val created = OnvifSoap.created(now.millis() + clockOffsetMillis)
        return OnvifSoap.securityHeader(user, created, OnvifSoap.nonce(random))
    }

    private suspend fun send(request: HttpRequest): HttpResponse = try {
        transport.send(request)
    } catch (e: AddressNotAllowedException) {
        throw OnvifError.NotLocal
    } catch (e: IOException) {
        throw OnvifError.Unreachable(e)
    }

    /** A fault (any status) first, then 401, other errors, and the body */
    private fun answer(response: HttpResponse): XmlElement {
        val xml = runCatching { SoapXml.parse(response.text) }.getOrNull()
        xml?.let(OnvifParser::fault)?.let { throw it }
        if (response.status == 401 || response.status == 403) throw OnvifError.Unauthorized
        if (response.status !in 200..299) throw OnvifError.Http(response.status)
        return xml ?: throw OnvifError.InvalidResponse("xml")
    }

    private fun requestTarget(url: String): String {
        val uri = URI(url)
        return (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    public companion object {
        public const val TIMEOUT_MILLIS: Long = 4_000L
        public const val PTZ_TIMEOUT_MILLIS: Long = 3_000L
    }
}
