package io.github.mafischer.kotlin2rtc.codec

import io.github.mafischer.kotlin2rtc.core.Logger

/**
 * 20 ms for the camera: the sound ([soundRate], 48 kHz by default, decimated) plus the microphone (decimated from its rate), clamped,
 * encoded in the codec the camera's SDP asked for ([PayloadEncoder]), in RTP: one packet per block (G.711, G.726, L16)
 * or one per AAC frame when the encoder has one. Silence when neither plays: the stream stays continuous while the
 * backchannel is open (no gaps for the camera's jitter buffer).
 */
public class BackchannelEncoder(
    public val media: BackchannelMedia,
    public val rtp: RtpPacketizer = RtpPacketizer.random(media.payloadType),
    aac: AacEncoderFactory = AacEncoderFactory.None,
    /** The rate of the sound blocks given to [packets]: a multiple of the camera's clock rate */
    public val soundRate: Int = PcmSource.DEFAULT_SAMPLE_RATE,
    logger: Logger = Logger.None,
) {
    /** Samples per packet at the camera's rate (20 ms) */
    public val samples: Int = media.samplesPerPacket
    private val soundDecimator = Decimator(soundRate / media.clockRate)
    private var micDecimator: Decimator? = null
    private val mix = FloatArray(samples)
    private val decimated = FloatArray(samples)
    private val payloads = PayloadEncoder.of(media, aac, logger)

    /** The samples of one microphone block at [rate] (null: no microphone) */
    public fun microphoneRate(rate: Int?) {
        micDecimator = rate?.let { Decimator(it / media.clockRate) }
    }

    /** The microphone rates that decimate to the camera's rate, preferred first (no resampling at all first) */
    public val microphoneRates: List<Int> get() = listOf(media.clockRate, 16_000, 48_000).filter { it >= media.clockRate && it % media.clockRate == 0 }.distinct()

    /** The packets of [sound] ([soundRate], 20 ms) and [microphone] (its rate, 20 ms), either may be null; none while AAC fills a frame */
    public fun packets(sound: FloatArray?, microphone: FloatArray?): List<ByteArray> {
        mix.fill(0f)
        if (sound != null) add(soundDecimator, sound, 1f)
        val mic = micDecimator
        if (microphone != null && mic != null) add(mic, microphone, MICROPHONE_GAIN)
        for (i in mix.indices) mix[i] = mix[i].coerceIn(-1f, 1f)
        return payloads.encode(mix).map { rtp.packet(it.bytes, it.samples, it.marker) }
    }

    /** One packet (the block codecs: G.711, G.726, L16) */
    public fun packet(sound: FloatArray?, microphone: FloatArray?): ByteArray = packets(sound, microphone).single()

    /** Frees the codec (the AAC encoder) */
    public fun close(): Unit = payloads.close()

    private fun add(decimator: Decimator, input: FloatArray, gain: Float) {
        val count = decimator.process(input, input.size, decimated)
        for (i in 0 until minOf(count, samples)) mix[i] += decimated[i] * gain
    }

    public companion object {
        /** The microphone as recorded (the voice-communication source has its own gain control) */
        public const val MICROPHONE_GAIN: Float = 1f
    }
}
