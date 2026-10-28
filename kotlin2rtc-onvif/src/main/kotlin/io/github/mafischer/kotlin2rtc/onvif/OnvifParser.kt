package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.core.XmlElement
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Reads the answers of cameras (namespace-tolerant, [SoapXml]); each throws [OnvifError] when the answer is not usable */
public object OnvifParser {
    private const val MEDIA_NAMESPACE = "http://www.onvif.org/ver10/media/wsdl"
    private const val PTZ_NAMESPACE = "http://www.onvif.org/ver20/ptz/wsdl"
    private const val EVENTS_NAMESPACE = "http://www.onvif.org/ver10/events/wsdl"

    /** Fault subcodes and reasons that mean the user name or password is wrong (ONVIF Core 5.11.2.1, and what cameras send) */
    private val authSubcodes = setOf("NotAuthorized", "FailedAuthentication", "InvalidSecurity", "FailedCheck")
    private val authReasons = listOf("not authorized", "not authorised", "unauthorized", "authentication", "authority failure")

    /**
     * The SOAP fault in [xml], or null. SOAP 1.2 (`Code/Value`, `Subcode/Value`, `Reason/Text`) and SOAP 1.1 (`faultcode`,
     * `faultstring`); an authentication fault is [OnvifError.Unauthorized].
     */
    public fun fault(xml: XmlElement): OnvifError? {
        val fault = xml.descendant("Fault") ?: return null
        val code = fault.path("Code", "Value")?.text ?: fault.child("faultcode")?.text
        val subcode = fault.path("Code", "Subcode", "Value")?.text
        val reason = fault.path("Reason", "Text")?.text ?: fault.child("faultstring")?.text ?: fault.child("Reason")?.text
        val localSubcode = subcode?.substringAfter(':')
        val localCode = code?.substringAfter(':')
        val authFault = localSubcode in authSubcodes || localCode in authSubcodes ||
            authReasons.any { reason?.contains(it, ignoreCase = true) == true }
        return if (authFault) OnvifError.Unauthorized else OnvifError.Fault(code, subcode, reason?.takeIf { it.isNotEmpty() })
    }

    /** GetSystemDateAndTime: the camera's UTC clock (epoch millis), or null when it sent none */
    public fun systemDateAndTime(xml: XmlElement): Long? {
        val utc = xml.descendant("UTCDateTime") ?: return null
        val number = { path: Array<String> -> utc.path(*path)?.text?.toIntOrNull() }
        val year = number(arrayOf("Date", "Year")) ?: return null
        return runCatching {
            LocalDateTime.of(
                year,
                number(arrayOf("Date", "Month")) ?: 1,
                number(arrayOf("Date", "Day")) ?: 1,
                number(arrayOf("Time", "Hour")) ?: 0,
                number(arrayOf("Time", "Minute")) ?: 0,
                number(arrayOf("Time", "Second")) ?: 0,
            ).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    /** GetEndpointReference: the device's GUID (its WS-Discovery endpoint reference), or null */
    public fun endpointReference(xml: XmlElement): String? =
        xml.descendant("GetEndpointReferenceResponse")?.descendant("GUID")?.text?.trim()?.takeIf { it.isNotEmpty() }

    public fun deviceInformation(xml: XmlElement): OnvifDeviceInformation {
        val response = xml.descendant("GetDeviceInformationResponse") ?: throw OnvifError.InvalidResponse("GetDeviceInformationResponse")
        val field = { name: String -> response.descendant(name)?.text.orEmpty() }
        return OnvifDeviceInformation(field("Manufacturer"), field("Model"), field("FirmwareVersion"), field("SerialNumber"), field("HardwareId"))
    }

    /**
     * GetCapabilities: the Media, PTZ and Events services. Without a Media service, the default path next to the device
     * service. Service hosts are those of [deviceUrl] ([OnvifUrls.onHost]).
     */
    public fun capabilities(xml: XmlElement, deviceUrl: String): OnvifEndpoints {
        val capabilities = xml.descendant("Capabilities") ?: throw OnvifError.InvalidResponse("Capabilities")
        val address = { category: String -> capabilities.descendant(category)?.child("XAddr")?.text?.takeIf { it.isNotEmpty() } }
        // Extension/DeviceIO/AudioOutputs (the Amcrest IP8M-2493E says 1)
        val audioOutputs = capabilities.descendant("DeviceIO")?.child("AudioOutputs")?.text?.trim()?.toIntOrNull()
        return endpoints(deviceUrl, address("Media"), address("PTZ"), address("Events")).copy(audioOutputs = audioOutputs)
    }

    /** GetServices: the same services by namespace (cameras that do not answer GetCapabilities) */
    public fun services(xml: XmlElement, deviceUrl: String): OnvifEndpoints {
        val response = xml.descendant("GetServicesResponse") ?: throw OnvifError.InvalidResponse("GetServicesResponse")
        val byNamespace = response.descendants("Service").associate { it.child("Namespace")?.text.orEmpty() to it.child("XAddr")?.text }
        return endpoints(deviceUrl, byNamespace[MEDIA_NAMESPACE], byNamespace[PTZ_NAMESPACE], byNamespace[EVENTS_NAMESPACE])
    }

    private fun endpoints(deviceUrl: String, media: String?, ptz: String?, events: String?): OnvifEndpoints = OnvifEndpoints(
        mediaUrl = media?.let { OnvifUrls.onHost(it, deviceUrl) } ?: OnvifUrls.sibling(deviceUrl, "media_service"),
        ptzUrl = ptz?.let { OnvifUrls.onHost(it, deviceUrl) },
        eventsUrl = events?.let { OnvifUrls.onHost(it, deviceUrl) },
    )

    /**
     * GetProfiles (Media1, and Media2's with `Configurations`): every profile with its token, name (the token without
     * one), resolution, encoding, PTZ and audio output
     */
    public fun profiles(xml: XmlElement): List<OnvifProfile> {
        val response = xml.descendant("GetProfilesResponse") ?: throw OnvifError.InvalidResponse("GetProfilesResponse")
        return response.children("Profiles").map { profile ->
            val token = profile.attribute("token").orEmpty()
            val configurations = profile.child("Configurations")
            val encoder = profile.child("VideoEncoderConfiguration") ?: configurations?.child("VideoEncoder")
            OnvifProfile(
                token = token,
                name = profile.child("Name")?.text?.takeIf { it.isNotEmpty() } ?: token,
                width = encoder?.path("Resolution", "Width")?.text?.toIntOrNull() ?: 0,
                height = encoder?.path("Resolution", "Height")?.text?.toIntOrNull() ?: 0,
                encoding = encoder?.child("Encoding")?.text.orEmpty(),
                hasPtz = profile.child("PTZConfiguration") != null || configurations?.child("PTZ") != null,
                hasAudioOutput = hasAudioOutput(profile),
            )
        }
    }

    /**
     * PTZ GetNodes: optical zoom when a node's SupportedPTZSpaces has a zoom space (continuous velocity, absolute
     * position or relative translation) whose XRange is not empty (pan/tilt-only cameras may list one from 0 to 0). The
     * range of the absolute position space, when listed, scales GetStatus's position.
     */
    public fun ptzZoomSupport(xml: XmlElement): PtzZoomSupport {
        val response = xml.descendant("GetNodesResponse") ?: throw OnvifError.InvalidResponse("GetNodesResponse")
        for (node in response.descendants("PTZNode")) {
            val spaces = node.child("SupportedPTZSpaces") ?: continue
            if (zoomSpaces.flatMap { spaces.children(it) }.none { it.hasRange() }) continue
            val absolute = spaces.children("AbsoluteZoomPositionSpace").firstOrNull { it.hasRange() }
            val min = absolute?.path("XRange", "Min")?.text?.toDoubleOrNull() ?: 0.0
            val max = absolute?.path("XRange", "Max")?.text?.toDoubleOrNull() ?: 1.0
            return if (max > min) PtzZoomSupport(true, min, max) else PtzZoomSupport(true)
        }
        return PtzZoomSupport.NONE
    }

    private val zoomSpaces = listOf("ContinuousZoomVelocitySpace", "AbsoluteZoomPositionSpace", "RelativeZoomTranslationSpace")

    /** A space without a readable XRange counts; one whose Min is not below its Max moves nothing */
    private fun XmlElement.hasRange(): Boolean {
        val range = child("XRange") ?: return true
        val min = range.child("Min")?.text?.toDoubleOrNull() ?: return true
        val max = range.child("Max")?.text?.toDoubleOrNull() ?: return true
        return max > min
    }

    /** PTZ GetStatus: the zoom position (`PTZStatus/Position/Zoom@x`), or null when the camera sent none */
    public fun ptzZoomPosition(xml: XmlElement): Double? {
        val response = xml.descendant("GetStatusResponse") ?: throw OnvifError.InvalidResponse("GetStatusResponse")
        return response.descendant("Position")?.child("Zoom")?.attribute("x")?.toDoubleOrNull()
    }

    /** Media1 keeps the audio output in the profile's Extension, Media2 in its Configurations */
    private fun hasAudioOutput(profile: XmlElement): Boolean {
        val media1 = listOf("AudioOutputConfiguration", "AudioDecoderConfiguration").any { profile.child(it) != null || profile.child("Extension")?.child(it) != null }
        val media2 = profile.child("Configurations")?.let { it.child("AudioOutput") != null || it.child("AudioDecoder") != null } == true
        return media1 || media2
    }

    /** GetStreamUri / GetSnapshotUri: the `MediaUri/Uri` (entities already decoded by the parser) */
    public fun mediaUri(xml: XmlElement, response: String): String {
        val answer = xml.descendant(response) ?: throw OnvifError.InvalidResponse(response)
        return answer.descendant("Uri")?.text?.takeIf { it.isNotEmpty() } ?: throw OnvifError.InvalidResponse("Uri")
    }
}

/** URLs that cameras hand out */
public object OnvifUrls {
    /**
     * [url] with the host of [reference]: many cameras report service and stream addresses with an address of another
     * interface (or 0.0.0.0, or their address before DHCP), while the user reached them at [reference]'s host. The port
     * and path the camera reported are kept.
     */
    public fun onHost(url: String, reference: String): String {
        val target = runCatching { URI(url) }.getOrNull() ?: return url
        val host = runCatching { URI(reference).host }.getOrNull() ?: return url
        val current = target.host ?: return url
        if (current.equals(host, ignoreCase = true)) return url
        return buildString {
            append(target.scheme).append("://")
            target.rawUserInfo?.let { append(it).append('@') }
            append(host)
            if (target.port >= 0) append(':').append(target.port)
            append(target.rawPath.orEmpty())
            target.rawQuery?.let { append('?').append(it) }
        }
    }

    /** The service [name] next to the device service (`http://cam/onvif/device_service` → `http://cam/onvif/media_service`) */
    public fun sibling(deviceUrl: String, name: String): String {
        val withoutQuery = deviceUrl.substringBefore('?')
        val slash = withoutQuery.lastIndexOf('/')
        val schemeEnd = withoutQuery.indexOf("://") + 3
        return if (slash >= schemeEnd) withoutQuery.substring(0, slash + 1) + name else "$withoutQuery/$name"
    }

    /** The device service of a camera at [host]:[port] */
    public fun deviceService(host: String, port: Int): String {
        val bracketed = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (port == 80) "http://$bracketed/onvif/device_service" else "http://$bracketed:$port/onvif/device_service"
    }
}
