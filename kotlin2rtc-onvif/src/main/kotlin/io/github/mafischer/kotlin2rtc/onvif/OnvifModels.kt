package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.Credentials

/** The camera's ONVIF user (the core [Credentials]). Never logged: its toString leaves the password out. */
public typealias OnvifCredentials = Credentials

/** GetDeviceInformation; a field the camera leaves out is empty */
public data class OnvifDeviceInformation(
    public val manufacturer: String = "",
    public val model: String = "",
    public val firmwareVersion: String = "",
    public val serialNumber: String = "",
    public val hardwareId: String = "",
) {
    /** "Amcrest IP8M-2493E", or null when the camera named neither */
    public val displayName: String? get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { null }
}

/**
 * The services a camera offers (GetCapabilities, or GetServices on cameras without it); [audioOutputs] is the device's
 * number of audio outputs (speakers) when GetCapabilities' DeviceIO says, else null
 */
public data class OnvifEndpoints(val mediaUrl: String, val ptzUrl: String? = null, val eventsUrl: String? = null, val audioOutputs: Int? = null)

/** A media profile (GetProfiles): one stream of the camera, e.g. main and sub stream */
public data class OnvifProfile(
    public val token: String,
    public val name: String,
    public val width: Int = 0,
    public val height: Int = 0,
    /** H264, H265, JPEG, ... or empty */
    public val encoding: String = "",
    /** The profile has a PTZ configuration: ContinuousMove and Stop work with its token */
    public val hasPtz: Boolean = false,
    /**
     * the profile has an audio output (Media1 `Extension/AudioOutputConfiguration` or
     * `AudioDecoderConfiguration`, Media2 `Configurations/AudioOutput` or `AudioDecoder`): the camera has a speaker this
     * profile can reach. Whether RTSP takes G.711 there is the backchannel DESCRIBE's answer.
     */
    public val hasAudioOutput: Boolean = false,
) {
    public val hasVideo: Boolean get() = width > 0 && height > 0
}

/** A WS-Discovery ProbeMatch: a camera on this network */
public data class DiscoveredDevice(
    /** The device's endpoint reference (a stable `urn:uuid:`), or its device URL when it sent none */
    public val endpoint: String,
    public val name: String,
    public val hardware: String,
    public val host: String,
    public val port: Int,
    /** The device service (`http://<host>/onvif/device_service`) */
    public val deviceUrl: String,
)

/**
 * PTZ GetNodes: whether the camera zooms optically (a node lists a zoom space whose range is not empty) and the range of
 * its absolute zoom position (GetStatus): 0 ... 1 in the generic space unless the node says otherwise
 */
public data class PtzZoomSupport(val optical: Boolean, val min: Double = 0.0, val max: Double = 1.0) {
    /** A GetStatus zoom position as 0 (widest) ... 1 (closest) */
    public fun normalized(position: Double): Double = if (max > min) ((position - min) / (max - min)).coerceIn(0.0, 1.0) else 0.0

    public companion object {
        public val NONE: PtzZoomSupport = PtzZoomSupport(optical = false)
    }
}

/** A pan/tilt/zoom velocity in the generic space, each -1.0 ... 1.0 */
public data class PtzVelocity(val pan: Double = 0.0, val tilt: Double = 0.0, val zoom: Double = 0.0)

/** Why an ONVIF call failed; each case is its own class, for its own message */
public sealed class OnvifError(message: String) : Exception(message) {
    /** No answer: the camera is off, the address is wrong, or this device is on another network */
    public class Unreachable(cause: Throwable? = null) : OnvifError("unreachable") {
        init {
            cause?.let { initCause(it) }
        }
    }

    /** The transport refused the address (its address policy, [io.github.mafischer.kotlin2rtc.core.AddressNotAllowedException]): nothing was sent */
    public data object NotLocal : OnvifError("not_local") {
        private fun readResolve(): Any = NotLocal
    }

    /** HTTP 401 (after HTTP Digest, when offered) or a NotAuthorized SOAP fault: wrong user name or password */
    public data object Unauthorized : OnvifError("unauthorized") {
        private fun readResolve(): Any = Unauthorized
    }

    /** Any other SOAP fault; [reason] is the camera's text */
    public data class Fault(val code: String?, val subcode: String?, val reason: String?) : OnvifError("fault")

    /** An error status without a SOAP fault */
    public data class Http(val status: Int) : OnvifError("http_$status")

    /** Not XML, or not the answer that was asked for */
    public data class InvalidResponse(val detail: String) : OnvifError("invalid_response")
}
