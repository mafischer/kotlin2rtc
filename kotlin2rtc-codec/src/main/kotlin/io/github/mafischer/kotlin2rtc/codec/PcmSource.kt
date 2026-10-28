package io.github.mafischer.kotlin2rtc.codec

/**
 * Mono audio pulled block by block (-1..1 floats): what a backchannel plays besides the microphone (music, a file, a
 * synthesized sound). Called on the audio pump's thread, one 20 ms block at a time.
 */
public interface PcmSource {
    /** The sample rate of the blocks: a multiple of the camera's clock rate (48 kHz covers 8, 12, 16, 24 and 48 kHz) */
    public val sampleRate: Int get() = DEFAULT_SAMPLE_RATE

    /** Fills [out] with the next samples; false (and [out] untouched) when nothing plays */
    public fun render(out: FloatArray): Boolean

    public companion object {
        public const val DEFAULT_SAMPLE_RATE: Int = 48_000

        /** Plays nothing */
        public val Silence: PcmSource = object : PcmSource {
            override fun render(out: FloatArray): Boolean = false
        }
    }
}

/** A [PcmSource] over [samples] at [sampleRate], played once (the command line's `talk` plays a WAV file this way) */
public class BufferPcmSource(private val samples: FloatArray, override val sampleRate: Int = PcmSource.DEFAULT_SAMPLE_RATE) : PcmSource {
    @Volatile
    private var position = 0

    /** Every sample was played */
    public val isFinished: Boolean get() = position >= samples.size

    override fun render(out: FloatArray): Boolean {
        if (isFinished) return false
        val count = minOf(out.size, samples.size - position)
        samples.copyInto(out, 0, position, position + count)
        out.fill(0f, count)
        position += count
        return true
    }
}
