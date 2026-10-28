package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.core.XmlElement
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * The SOAP requests of the ONVIF client: the SOAP 1.2 envelope and the WS-Security UsernameToken (PasswordDigest,
 * checked against the ONVIF specification's example vector). Values that come from the user or the camera (user name, profile token) are XML-escaped.
 */
public object OnvifSoap {
    public const val CONTENT_TYPE: String = "application/soap+xml; charset=utf-8"

    private const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
    private const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
    private const val PASSWORD_DIGEST = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
    private const val BASE64_BINARY = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary"
    private const val PAN_TILT_SPACE = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace"
    private const val ZOOM_SPACE = "http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace"

    private val createdFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** WS-Security PasswordDigest: Base64(SHA-1(nonce + created + password)) */
    public fun passwordDigest(password: String, created: String, nonce: ByteArray): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha1.digest())
    }

    /** The UsernameToken's timestamp: UTC with milliseconds, as JavaScript's toISOString */
    public fun created(epochMillis: Long): String = createdFormat.format(Instant.ofEpochMilli(epochMillis))

    /** A fresh 16-byte nonce for every request (replay protection) */
    public fun nonce(random: SecureRandom): ByteArray = ByteArray(16).also(random::nextBytes)

    /** The WS-Security header: the password itself is never in it, only its digest */
    public fun securityHeader(credentials: OnvifCredentials, created: String, nonce: ByteArray): String {
        val digest = passwordDigest(credentials.password, created, nonce)
        val nonceText = Base64.getEncoder().encodeToString(nonce)
        return """
            <s:Header>
              <Security s:mustUnderstand="1" xmlns="$WSSE">
                <UsernameToken>
                  <Username>${SoapXml.escape(credentials.username)}</Username>
                  <Password Type="$PASSWORD_DIGEST">$digest</Password>
                  <Nonce EncodingType="$BASE64_BINARY">$nonceText</Nonce>
                  <Created xmlns="$WSU">$created</Created>
                </UsernameToken>
              </Security>
            </s:Header>
            """.trimIndent()
    }

    /** The envelope around [body]; with [security] (a [securityHeader]) when the camera has a user */
    public fun envelope(body: String, security: String? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tt=\"http://www.onvif.org/ver10/schema\" ")
        append("xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" ")
        append("xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\">\n")
        if (security != null) append(security).append('\n')
        append("<s:Body>\n").append(body).append("\n</s:Body>\n</s:Envelope>")
    }

    // Bodies

    public const val GET_SYSTEM_DATE_AND_TIME: String = "<tds:GetSystemDateAndTime />"
    public const val GET_DEVICE_INFORMATION: String = "<tds:GetDeviceInformation />"

    /** Unauthenticated (PRE_AUTH in ONVIF Core): the device's endpoint reference, optional (Amcrest/Dahua do not implement it) */
    public const val GET_ENDPOINT_REFERENCE: String = "<tds:GetEndpointReference />"
    public const val GET_CAPABILITIES: String = "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>"
    public const val GET_SERVICES: String = "<tds:GetServices><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices>"
    public const val GET_PROFILES: String = "<trt:GetProfiles />"

    public fun getStreamUri(profileToken: String): String =
        """
        <trt:GetStreamUri>
          <trt:StreamSetup>
            <tt:Stream>RTP-Unicast</tt:Stream>
            <tt:Transport>
              <tt:Protocol>RTSP</tt:Protocol>
            </tt:Transport>
          </trt:StreamSetup>
          <trt:ProfileToken>${SoapXml.escape(profileToken)}</trt:ProfileToken>
        </trt:GetStreamUri>
        """.trimIndent()

    public fun getSnapshotUri(profileToken: String): String =
        "<trt:GetSnapshotUri><trt:ProfileToken>${SoapXml.escape(profileToken)}</trt:ProfileToken></trt:GetSnapshotUri>"

    public fun continuousMove(profileToken: String, velocity: PtzVelocity): String =
        """
        <tptz:ContinuousMove>
          <tptz:ProfileToken>${SoapXml.escape(profileToken)}</tptz:ProfileToken>
          <tptz:Velocity>
            <tt:PanTilt x="${velocity.pan}" y="${velocity.tilt}" space="$PAN_TILT_SPACE" />
            <tt:Zoom x="${velocity.zoom}" space="$ZOOM_SPACE" />
          </tptz:Velocity>
        </tptz:ContinuousMove>
        """.trimIndent()

    /** The PTZ nodes and the spaces they support (whether the camera has an optical zoom) */
    public const val GET_NODES: String = "<tptz:GetNodes />"

    /** Where the camera points and how far it is zoomed */
    public fun getStatus(profileToken: String): String =
        "<tptz:GetStatus><tptz:ProfileToken>${SoapXml.escape(profileToken)}</tptz:ProfileToken></tptz:GetStatus>"

    public fun stop(profileToken: String): String =
        """
        <tptz:Stop>
          <tptz:ProfileToken>${SoapXml.escape(profileToken)}</tptz:ProfileToken>
          <tptz:PanTilt>true</tptz:PanTilt>
          <tptz:Zoom>true</tptz:Zoom>
        </tptz:Stop>
        """.trimIndent()
}
