package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.core.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Whether an IP camera takes audio on its backchannel, as last found out (a caller may keep it per camera and ask
 * again when [isStale]). Decided by the camera's RTSP answer to a DESCRIBE with the backchannel requirement
 * ([BackchannelSdp]); the ONVIF profile's audio output ([onvifAudioOutput]) only says a speaker exists, not that RTSP
 * can reach it with a codec kotlin2rtc sends.
 */
public data class BackchannelCapability(
    public val supported: Boolean,
    /** The codec chosen (PCMU, PCMA, G726-32, MPEG4-GENERIC...), as the camera asked */
    public val codec: String? = null,
    public val clockRate: Int? = null,
    /** Every encoding the camera's backchannel media listed (an AAC-only camera shows here why it is not supported) */
    public val offered: List<String> = emptyList(),
    /** The chosen ONVIF profile has an audio output (AudioOutputConfiguration / Media2 AudioOutput); null when unknown */
    public val onvifAudioOutput: Boolean? = null,
    /** When the DESCRIBE answered (epoch ms); 0 = never asked yet */
    public val checkedAt: Long = 0,
) {
    /** Asked again after a day (a firmware update, a speaker plugged in), and right away when never asked */
    public fun isStale(now: Long): Boolean = checkedAt <= 0 || now - checkedAt >= RECHECK_MILLIS

    public companion object {
        public const val RECHECK_MILLIS: Long = 24 * 60 * 60 * 1000L

        /** What a DESCRIBE showed, keeping what [previous] knew from ONVIF */
        public fun of(describe: SdpBackchannel, now: Long, previous: BackchannelCapability? = null): BackchannelCapability = BackchannelCapability(
            supported = describe.media != null,
            codec = describe.media?.label?.substringBefore('/'),
            clockRate = describe.media?.clockRate,
            offered = describe.offered,
            onvifAudioOutput = previous?.onvifAudioOutput,
            checkedAt = now,
        )

        /** The camera refused the backchannel requirement or has none */
        public fun unsupported(error: BackchannelError.Unsupported, now: Long, previous: BackchannelCapability? = null): BackchannelCapability =
            BackchannelCapability(false, offered = error.offered, onvifAudioOutput = previous?.onvifAudioOutput, checkedAt = now)
    }
}

/** The RTSP side of the backchannel: the capability check and the open link (an interface so callers can fake it) */
public interface BackchannelEngine {
    /** DESCRIBE with the backchannel requirement on [url] (the checked stream URL with the login); throws [BackchannelError] */
    public suspend fun probe(url: String): SdpBackchannel

    /** An open backchannel on [url]; throws [BackchannelError] */
    public suspend fun open(url: String): BackchannelLink

    /** Closes [link] off the main thread (TEARDOWN waits for the socket) */
    public fun closeLater(link: BackchannelLink) {
        Thread({ link.close() }, "kotlin2rtc-backchannel-close").apply { isDaemon = true }.start()
    }

    public companion object {
        /** No backchannel at all (tests, platforms without one): the camera seems not to answer */
        public val Unavailable: BackchannelEngine = object : BackchannelEngine {
            override suspend fun probe(url: String): SdpBackchannel = throw BackchannelError.Unreachable()

            override suspend fun open(url: String): BackchannelLink = throw BackchannelError.Unreachable()
        }
    }
}

/** The engine over [RtspBackchannelClient] on an IO thread, to addresses [allows] lets through (e.g. `LocalNetwork::allowsCamera`) */
public class RtspBackchannelEngine(
    private val allows: (InetAddress) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val logger: Logger = Logger.None,
) : BackchannelEngine {
    override suspend fun probe(url: String): SdpBackchannel = withContext(io) { RtspBackchannelClient(url, allows, logger = logger).describe() }

    override suspend fun open(url: String): BackchannelLink = withContext(io) { RtspBackchannelClient(url, allows, logger = logger).open() }
}
