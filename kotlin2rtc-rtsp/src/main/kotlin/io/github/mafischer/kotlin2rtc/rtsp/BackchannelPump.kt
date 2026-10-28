package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.AacEncoderFactory
import io.github.mafischer.kotlin2rtc.codec.BackchannelEncoder
import io.github.mafischer.kotlin2rtc.codec.BackchannelMedia
import io.github.mafischer.kotlin2rtc.codec.PcmSource
import io.github.mafischer.kotlin2rtc.codec.Rtcp
import io.github.mafischer.kotlin2rtc.core.Clock
import io.github.mafischer.kotlin2rtc.core.Logger
import java.io.IOException

/**
 * A microphone for push to talk on an IP camera (a raw recorder: the samples go to the camera in its codec). The
 * platform supplies it (Android's AudioRecord, javax.sound on a desktop); tests use fakes. Used from the audio pump's
 * thread only.
 */
public interface TalkMicrophone {
    /** Opens at the first of [rates] the device records at; the rate, or null when the microphone cannot be opened */
    public fun open(rates: List<Int>): Int?

    /** Fills [out] with the next samples (-1..1), blocking until they are recorded; false when recording failed */
    public fun read(out: FloatArray): Boolean

    public fun close()

    /** No microphone */
    public object None : TalkMicrophone {
        override fun open(rates: List<Int>): Int? = null

        override fun read(out: FloatArray): Boolean = false

        override fun close(): Unit = Unit
    }
}

/** What the audio pump reports (on its thread) */
public enum class BackchannelAudioEvent {
    /** The microphone could not be opened or stopped recording: talking ends */
    MICROPHONE_UNAVAILABLE,

    /** Writing to the camera failed: the link is gone */
    LINK_FAILED,
}

/** The running audio of one open backchannel */
public interface BackchannelAudio {
    /** The microphone is sent (push to talk on); set from any thread */
    public var talking: Boolean

    public fun start()

    /** Stops sending (does not close the link); returns at once */
    public fun stop()
}

/** Creates the audio pump of an open link */
public fun interface BackchannelAudioFactory {
    public fun create(link: BackchannelLink, sounds: PcmSource, events: (BackchannelAudioEvent) -> Unit): BackchannelAudio

    public companion object {
        /** Without a microphone: [PcmSource] sound only */
        public val WithoutMicrophone: BackchannelAudioFactory = BackchannelAudioFactory { link, sounds, events -> BackchannelPump(link, sounds, TalkMicrophone.None, events) }
    }
}

/**
 * The backchannel's audio thread: every 20 ms one packet ([BackchannelEncoder]) to the camera, paced by the microphone
 * while someone talks (its reads block for 20 ms of audio) and by the clock otherwise; an RTCP sender report every 5
 * s. [sounds] plays besides the microphone. Opens the microphone when talking starts and closes it when it ends, on this thread.
 */
public class BackchannelPump(
    private val link: BackchannelLink,
    private val sounds: PcmSource,
    private val microphone: TalkMicrophone,
    private val events: (BackchannelAudioEvent) -> Unit,
    aac: AacEncoderFactory = AacEncoderFactory.None,
    /** A monotonic clock in nanoseconds (pacing) */
    private val nanoTime: () -> Long = System::nanoTime,
    /** The wall clock of the RTCP sender reports */
    private val wallClock: Clock = Clock.System,
    /** The RTCP CNAME of this sender */
    private val cname: String = CNAME,
    logger: Logger = Logger.None,
) : BackchannelAudio {
    @Volatile
    override var talking: Boolean = false

    @Volatile
    private var running = false
    private var thread: Thread? = null
    internal val encoder = BackchannelEncoder(link.media, aac = aac, soundRate = sounds.sampleRate, logger = logger)
    private val soundBlock = FloatArray(sounds.sampleRate / BackchannelMedia.PACKETS_PER_SECOND)
    private var micBlock: FloatArray? = null
    private var micOpen = false
    private var micFailed = false
    private var lastReport = Long.MIN_VALUE

    override fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "kotlin2rtc-backchannel-audio").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        var deadline = nanoTime()
        try {
            while (running) {
                val paced = step()
                if (paced) {
                    deadline = nanoTime()
                    continue
                }
                deadline += FRAME_NANOS
                val wait = deadline - nanoTime()
                if (wait > 0) Thread.sleep(wait / NANOS_PER_MILLI, (wait % NANOS_PER_MILLI).toInt())
                // Far behind (the thread was starved): start again from now, never send a burst
                if (nanoTime() - deadline > MAX_LAG_NANOS) deadline = nanoTime()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            if (running) events(BackchannelAudioEvent.LINK_FAILED)
        } finally {
            closeMicrophone()
            encoder.close()
        }
    }

    /**
     * One packet: the microphone's block when talking (its read paced it: true), the sound's, encoded and sent.
     * Throws [IOException] when the link is gone.
     */
    internal fun step(): Boolean {
        val microphoneBlock = if (talking && !micFailed) {
            readMicrophone()
        } else {
            closeMicrophone()
            if (!talking) micFailed = false
            null
        }
        val sound = if (sounds.render(soundBlock)) soundBlock else null
        for (packet in encoder.packets(sound, microphoneBlock)) link.sendRtp(packet)
        val now = wallClock.millis()
        if (lastReport == Long.MIN_VALUE || now - lastReport >= RTCP_INTERVAL_MILLIS) {
            lastReport = now
            link.sendRtcp(Rtcp.senderReport(encoder.rtp, now, cname))
        }
        return microphoneBlock != null
    }

    private fun readMicrophone(): FloatArray? {
        if (!micOpen) {
            val rate = microphone.open(encoder.microphoneRates)
            if (rate == null) {
                microphoneFailed()
                return null
            }
            micOpen = true
            encoder.microphoneRate(rate)
            micBlock = FloatArray(rate / BackchannelMedia.PACKETS_PER_SECOND)
        }
        val block = micBlock ?: return null
        if (!microphone.read(block)) {
            microphoneFailed()
            return null
        }
        return block
    }

    private fun microphoneFailed() {
        closeMicrophone()
        micFailed = true
        events(BackchannelAudioEvent.MICROPHONE_UNAVAILABLE)
    }

    private fun closeMicrophone() {
        if (!micOpen) return
        micOpen = false
        encoder.microphoneRate(null)
        microphone.close()
    }

    public companion object {
        public const val CNAME: String = "kotlin2rtc"
        public const val RTCP_INTERVAL_MILLIS: Long = 5_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val FRAME_NANOS = 1_000_000_000L / BackchannelMedia.PACKETS_PER_SECOND
        private const val MAX_LAG_NANOS = 200_000_000L
    }
}
