package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.Logger
import io.github.mafischer.kotlin2rtc.core.SoapXml
import io.github.mafischer.kotlin2rtc.core.XmlElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** An IP camera speaker's level, as a fraction 0..1 of its range */
public fun interface SpeakerLevel {
    /** Sets the camera's speaker to [fraction] of its range; false when the camera has no level or refused it */
    public suspend fun set(fraction: Float): Boolean

    public companion object {
        /** No camera level (cameras without ONVIF): the caller scales the sound it sends instead */
        public val None: SpeakerLevel = SpeakerLevel { false }
    }
}

/** An ONVIF AudioOutputConfiguration (Media1 and Media2 share the `tt` type) */
public data class AudioOutputConfiguration(
    public val token: String,
    public val name: String,
    public val useCount: Int,
    public val outputToken: String,
    public val sendPrimacy: String?,
    public val outputLevel: Int,
)

/** The ONVIF answers and requests of a speaker's level */
public object SpeakerLevelSoap {
    public const val MEDIA2_NAMESPACE: String = "http://www.onvif.org/ver20/media/wsdl"

    /** Media1 (`trt`, on the media service) or Media2 (`tr2`, on its own service) */
    private fun prefix(media2: Boolean): String = if (media2) "tr2" else "trt"

    private fun declaration(media2: Boolean): String = if (media2) " xmlns:tr2=\"$MEDIA2_NAMESPACE\"" else ""

    public fun getConfigurations(media2: Boolean): String = "<${prefix(media2)}:GetAudioOutputConfigurations${declaration(media2)} />"

    public fun getOptions(media2: Boolean, token: String): String {
        val p = prefix(media2)
        return "<$p:GetAudioOutputConfigurationOptions${declaration(media2)}><$p:ConfigurationToken>${SoapXml.escape(token)}</$p:ConfigurationToken>" +
            "</$p:GetAudioOutputConfigurationOptions>"
    }

    /** SetAudioOutputConfiguration with every field of [configuration] (Media1 also asks whether to persist it: yes) */
    public fun set(media2: Boolean, configuration: AudioOutputConfiguration): String {
        val p = prefix(media2)
        val primacy = configuration.sendPrimacy?.let { "<tt:SendPrimacy>${SoapXml.escape(it)}</tt:SendPrimacy>" }.orEmpty()
        val persistence = if (media2) "" else "<trt:ForcePersistence>true</trt:ForcePersistence>"
        return "<$p:SetAudioOutputConfiguration${declaration(media2)}><$p:Configuration token=\"${SoapXml.escape(configuration.token)}\">" +
            "<tt:Name>${SoapXml.escape(configuration.name)}</tt:Name><tt:UseCount>${configuration.useCount}</tt:UseCount>" +
            "<tt:OutputToken>${SoapXml.escape(configuration.outputToken)}</tt:OutputToken>$primacy" +
            "<tt:OutputLevel>${configuration.outputLevel}</tt:OutputLevel></$p:Configuration>$persistence</$p:SetAudioOutputConfiguration>"
    }

    /** GetAudioOutputConfigurations (either version): every configuration with a token */
    public fun configurations(xml: XmlElement): List<AudioOutputConfiguration> {
        val response = xml.descendant("GetAudioOutputConfigurationsResponse") ?: throw OnvifError.InvalidResponse("GetAudioOutputConfigurationsResponse")
        return response.children("Configurations").mapNotNull { configuration ->
            val token = configuration.attribute("token")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AudioOutputConfiguration(
                token = token,
                name = configuration.child("Name")?.text.orEmpty().ifEmpty { token },
                useCount = configuration.child("UseCount")?.text?.toIntOrNull() ?: 1,
                outputToken = configuration.child("OutputToken")?.text.orEmpty(),
                sendPrimacy = configuration.child("SendPrimacy")?.text?.takeIf { it.isNotEmpty() },
                outputLevel = configuration.child("OutputLevel")?.text?.toIntOrNull() ?: 0,
            )
        }
    }

    /** GetAudioOutputConfigurationOptions: `OutputLevelRange` Min..Max, or null when the camera gave none */
    public fun levelRange(xml: XmlElement): IntRange? {
        val range = xml.descendant("OutputLevelRange") ?: return null
        val min = range.child("Min")?.text?.toIntOrNull() ?: return null
        val max = range.child("Max")?.text?.toIntOrNull() ?: return null
        return (min..max).takeIf { max > min }
    }

    /** GetServices: the Media2 service, on the camera's host */
    public fun media2Url(xml: XmlElement, deviceUrl: String): String? =
        xml.descendant("GetServicesResponse")?.descendants("Service")
            ?.firstOrNull { it.child("Namespace")?.text == MEDIA2_NAMESPACE }
            ?.child("XAddr")?.text?.takeIf { it.isNotEmpty() }?.let { OnvifUrls.onHost(it, deviceUrl) }
}

/**
 * the camera's speaker level through ONVIF: its first AudioOutputConfiguration (Media1 on the media service,
 * else Media2 on the service GetServices names), scaled over the range GetAudioOutputConfigurationOptions gives
 * (0..100 when it gives none), set with SetAudioOutputConfiguration. Found once; a camera without one, or that refuses
 * the change, stays at "no level" (the caller then scales the samples it sends). Not answering is tried again next time.
 */
public class OnvifSpeakerLevel(private val client: OnvifClient, private val mediaUrl: String, private val logger: Logger = Logger.None) : SpeakerLevel {
    private class Target(val url: String, val media2: Boolean, val configuration: AudioOutputConfiguration, val range: IntRange)

    private var target: Target? = null
    private var unavailable = false

    override suspend fun set(fraction: Float): Boolean {
        if (unavailable) return false
        return try {
            val found = located() ?: run {
                unavailable = true
                return false
            }
            val level = found.range.first + (fraction.coerceIn(0f, 1f) * (found.range.last - found.range.first)).roundToInt()
            client.call(found.url, SpeakerLevelSoap.set(found.media2, found.configuration.copy(outputLevel = level)))
            logger.info("IP camera speaker level $level of ${found.range}")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnvifError.Unreachable) {
            false
        } catch (e: OnvifError) {
            logger.info("IP camera speaker level refused: ${e.message}")
            unavailable = true
            false
        }
    }

    /** The target found before, else looked for now (and kept when found) */
    private suspend fun located(): Target? = target ?: find()?.also { target = it }

    private suspend fun find(): Target? {
        if (!client.clockSynced) client.syncClock()
        return locate(mediaUrl, media2 = false) ?: media2()?.let { locate(it, media2 = true) }
    }

    private suspend fun media2(): String? = try {
        SpeakerLevelSoap.media2Url(client.call(client.deviceUrl, OnvifSoap.GET_SERVICES), client.deviceUrl)
    } catch (e: OnvifError.Fault) {
        null
    } catch (e: OnvifError.InvalidResponse) {
        null
    }

    /** The configuration and range at [url]; null when that service has none or does not know the call */
    private suspend fun locate(url: String, media2: Boolean): Target? {
        val configuration = try {
            SpeakerLevelSoap.configurations(client.call(url, SpeakerLevelSoap.getConfigurations(media2))).firstOrNull()
        } catch (e: OnvifError.Fault) {
            null
        } catch (e: OnvifError.Http) {
            null
        } catch (e: OnvifError.InvalidResponse) {
            null
        } ?: return null
        val range = try {
            SpeakerLevelSoap.levelRange(client.call(url, SpeakerLevelSoap.getOptions(media2, configuration.token)))
        } catch (e: OnvifError.Fault) {
            null
        } catch (e: OnvifError.InvalidResponse) {
            null
        }
        return Target(url, media2, configuration, range ?: DEFAULT_RANGE)
    }

    public companion object {
        public val DEFAULT_RANGE: IntRange = 0..100
    }
}
