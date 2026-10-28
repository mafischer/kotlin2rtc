package io.github.mafischer.kotlin2rtc.codec

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A low-pass FIR filter and decimator by an integer [factor] (windowed sinc, Blackman window, cutoff at 0.45 of the
 * output rate, 32 taps per output phase: at 8 kHz the stop band starts below 4.3 kHz): 48 kHz sound (music, a file) and the
 * microphone down to the camera's 8 kHz without aliasing. Keeps its history across blocks, so consecutive blocks join
 * without a seam. One instance per stream, one thread.
 */
public class Decimator(public val factor: Int, tapsPerPhase: Int = TAPS_PER_PHASE) {
    private val taps: FloatArray = if (factor == 1) floatArrayOf(1f) else design(factor * tapsPerPhase + 1, CUTOFF / factor)
    private var history = FloatArray(taps.size - 1)
    private var work = FloatArray(0)

    /** Filters [count] samples of [input] (a multiple of [factor]) into [out]; returns the number written (count / factor) */
    public fun process(input: FloatArray, count: Int, out: FloatArray): Int {
        require(count % factor == 0) { "block of $count is not a multiple of $factor" }
        if (factor == 1) {
            input.copyInto(out, 0, 0, count)
            return count
        }
        val length = history.size + count
        if (work.size < length) work = FloatArray(length)
        history.copyInto(work)
        input.copyInto(work, history.size, 0, count)
        var written = 0
        var position = history.size + factor - 1
        while (position < length) {
            var sum = 0f
            for (k in taps.indices) sum += taps[k] * work[position - k]
            out[written++] = sum
            position += factor
        }
        history = work.copyOfRange(length - history.size, length)
        return written
    }

    public companion object {
        public const val TAPS_PER_PHASE: Int = 32

        /** Of the output's Nyquist frequency: 3.6 kHz at 8 kHz, the telephone band G.711 carries */
        private const val CUTOFF = 0.9 / 2

        /** [count] taps of a low-pass at [cutoff] (of the input rate), unity gain at DC */
        public fun design(count: Int, cutoff: Double): FloatArray {
            val middle = (count - 1) / 2.0
            val taps = DoubleArray(count) { n ->
                val x = n - middle
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val window = 0.42 - 0.5 * cos(2 * PI * n / (count - 1)) + 0.08 * cos(4 * PI * n / (count - 1))
                sinc * window
            }
            val sum = taps.sum()
            return FloatArray(count) { (taps[it] / sum).toFloat() }
        }
    }
}
