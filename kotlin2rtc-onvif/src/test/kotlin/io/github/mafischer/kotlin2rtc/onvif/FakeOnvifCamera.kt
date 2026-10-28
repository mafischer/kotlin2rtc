package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.HttpRequest
import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.core.HttpTransport
import io.github.mafischer.kotlin2rtc.core.SoapXml
import java.io.IOException
import java.util.Base64

/**
 * A fake ONVIF camera at [HOST] for the tests: user [USERNAME] / password [PASSWORD] (checked through the WS-Security
 * digest, as a camera does), a main stream with PTZ (an optical zoom unless not [opticalZoom], the lens moving straight
 * to an end on a zoom move) and a speaker whose level can be set unless not [speakerLevel]. Other hosts do not answer.
 */
class FakeOnvifCamera(
    private val opticalZoom: Boolean = true,
    private val speakerLevel: Boolean = true,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    /** The speaker level last set, null until set */
    var outputLevel: Int? = null
        private set

    /** The lens's position (GetStatus, 0 ... 1) */
    var zoomLevel = 0.0

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        if (!request.url.startsWith("http://$HOST/")) throw IOException("no route to host")
        val text = request.body?.decodeToString().orEmpty()
        val body = SoapXml.parse(text).descendant("Body")?.children?.firstOrNull()?.name ?: return response(400, fault("ter:InvalidArgVal", "No body"))
        if (body == "GetSystemDateAndTime") return response(200, SYSTEM_DATE_AND_TIME)
        if (!authorized(text)) return response(400, fault("ter:NotAuthorized", "Sender not Authorized"))
        return when (body) {
            "ContinuousMove" -> {
                val zoom = SoapXml.parse(text).descendant("Zoom")?.attribute("x")?.toDoubleOrNull() ?: 0.0
                if (opticalZoom && zoom != 0.0) zoomLevel = if (zoom > 0) 1.0 else 0.0
                response(200, envelope("<tptz:ContinuousMoveResponse />"))
            }
            "Stop" -> {
                response(200, envelope("<tptz:StopResponse />"))
            }
            "GetNodes" -> {
                response(200, envelope(ptzNodes(opticalZoom)))
            }
            "GetStatus" -> {
                response(200, envelope(ptzStatus(zoomLevel)))
            }
            "GetAudioOutputConfigurations", "GetAudioOutputConfigurationOptions", "SetAudioOutputConfiguration" -> {
                speaker(body, text)
            }
            else -> {
                response(400, fault("ter:ActionNotSupported", "Not supported"))
            }
        }
    }

    private fun speaker(operation: String, text: String): HttpResponse {
        if (!speakerLevel) return response(400, fault("ter:ActionNotSupported", "Not supported"))
        return when (operation) {
            "GetAudioOutputConfigurations" -> {
                response(200, envelope(SPEAKER_CONFIGURATIONS))
            }
            "GetAudioOutputConfigurationOptions" -> {
                response(200, envelope(SPEAKER_OPTIONS))
            }
            else -> {
                outputLevel = SoapXml.parse(text).descendant("OutputLevel")?.text?.toIntOrNull()
                response(200, envelope("<trt:SetAudioOutputConfigurationResponse />"))
            }
        }
    }

    /** The UsernameToken of [USERNAME] whose digest matches [PASSWORD] */
    private fun authorized(text: String): Boolean {
        val token = runCatching { SoapXml.parse(text).descendant("UsernameToken") }.getOrNull() ?: return false
        val nonce = token.child("Nonce")?.text?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return false
        val created = token.child("Created")?.text ?: return false
        return token.child("Username")?.text == USERNAME && token.child("Password")?.text == OnvifSoap.passwordDigest(PASSWORD, created, nonce)
    }

    private fun response(status: Int, body: String) = HttpResponse(status, mapOf("Content-Type" to OnvifSoap.CONTENT_TYPE), body.encodeToByteArray())

    private fun fault(subcode: String, reason: String) = envelope(
        "<env:Fault><env:Code><env:Value>env:Sender</env:Value><env:Subcode><env:Value>$subcode</env:Value></env:Subcode></env:Code>" +
            "<env:Reason><env:Text xml:lang=\"en\">$reason</env:Text></env:Reason></env:Fault>",
    )

    companion object {
        const val HOST = "192.168.1.64"
        const val USERNAME = "admin"
        const val PASSWORD = "fake-camera-password"
        const val DEVICE_URL = "http://$HOST/onvif/device_service"
        const val MEDIA_URL = "http://$HOST/onvif/media_service"
        const val PTZ_URL = "http://$HOST/onvif/ptz_service"

        fun envelope(body: String) =
            """<?xml version="1.0" encoding="UTF-8"?><env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope" """ +
                """xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" """ +
                """xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" """ +
                """xmlns:ter="http://www.onvif.org/ver10/error"><env:Body>$body</env:Body></env:Envelope>"""

        private val SYSTEM_DATE_AND_TIME = envelope(
            "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Time><tt:Hour>12</tt:Hour><tt:Minute>0</tt:Minute>" +
                "<tt:Second>0</tt:Second></tt:Time><tt:Date><tt:Year>2026</tt:Year><tt:Month>10</tt:Month><tt:Day>25</tt:Day></tt:Date>" +
                "</tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>",
        )

        /** A PTZ node with pan and tilt, and a zoom when [zoom] (the spaces as cameras list them) */
        fun ptzNodes(zoom: Boolean): String {
            val space = { name: String, uri: String, axes: String -> "<tt:$name><tt:URI>$uri</tt:URI>$axes</tt:$name>" }
            val range = { axis: String, min: String -> "<tt:$axis><tt:Min>$min</tt:Min><tt:Max>1</tt:Max></tt:$axis>" }
            val panTilt = space(
                "ContinuousPanTiltVelocitySpace",
                "http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace",
                range("XRange", "-1") + range("YRange", "-1"),
            )
            val zoomSpaces = if (!zoom) {
                ""
            } else {
                space("AbsoluteZoomPositionSpace", "http://www.onvif.org/ver10/tptz/ZoomSpaces/PositionGenericSpace", range("XRange", "0")) +
                    space("ContinuousZoomVelocitySpace", "http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace", range("XRange", "-1"))
            }
            return "<tptz:GetNodesResponse><tptz:PTZNode token=\"PTZNODE0\"><tt:Name>PTZ</tt:Name>" +
                "<tt:SupportedPTZSpaces>$panTilt$zoomSpaces</tt:SupportedPTZSpaces><tt:MaximumNumberOfPresets>16</tt:MaximumNumberOfPresets>" +
                "<tt:HomeSupported>true</tt:HomeSupported></tptz:PTZNode></tptz:GetNodesResponse>"
        }

        fun ptzStatus(zoom: Double): String =
            "<tptz:GetStatusResponse><tptz:PTZStatus><tt:Position><tt:PanTilt x=\"0\" y=\"0\" /><tt:Zoom x=\"$zoom\" /></tt:Position>" +
                "<tt:MoveStatus><tt:PanTilt>IDLE</tt:PanTilt><tt:Zoom>IDLE</tt:Zoom></tt:MoveStatus><tt:UtcTime>2026-10-25T12:00:00Z</tt:UtcTime>" +
                "</tptz:PTZStatus></tptz:GetStatusResponse>"

        private const val SPEAKER_CONFIGURATIONS = "<trt:GetAudioOutputConfigurationsResponse><trt:Configurations token=\"AO0\">" +
            "<tt:Name>Speaker</tt:Name><tt:UseCount>1</tt:UseCount><tt:OutputToken>AudioOutput0</tt:OutputToken>" +
            "<tt:SendPrimacy>www.onvif.org/ver20/HalfDuplex/Auto</tt:SendPrimacy><tt:OutputLevel>80</tt:OutputLevel>" +
            "</trt:Configurations></trt:GetAudioOutputConfigurationsResponse>"

        private const val SPEAKER_OPTIONS = "<trt:GetAudioOutputConfigurationOptionsResponse><trt:Options>" +
            "<tt:OutputTokensAvailable>AudioOutput0</tt:OutputTokensAvailable><tt:OutputLevelRange><tt:Min>0</tt:Min><tt:Max>100</tt:Max>" +
            "</tt:OutputLevelRange></trt:Options></trt:GetAudioOutputConfigurationOptionsResponse>"
    }
}
