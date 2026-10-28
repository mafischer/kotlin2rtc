package io.github.mafischer.kotlin2rtc.cli

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RIFF WAVE files: 8-bit unsigned or 16-bit signed PCM, any rate and channel count, read as mono at 48 kHz */
internal object Wav {
    const val RATE = 48_000

    /** The samples (-1..1) of [bytes], channels averaged, resampled linearly to [RATE]; throws [IllegalArgumentException] */
    fun read(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not RIFF WAVE" }
        var position = 12
        var format: Format? = null
        while (position + 8 <= bytes.size) {
            val id = tag(bytes, position)
            val size = buffer.getInt(position + 4)
            val body = position + 8
            require(size >= 0 && body + size <= bytes.size) { "truncated chunk $id" }
            when (id) {
                "fmt " -> format = format(buffer, body, size)
                "data" -> return resample(samples(buffer, body, size, format ?: throw IllegalArgumentException("data before fmt")), format.rate)
            }
            position = body + size + (size and 1)
        }
        throw IllegalArgumentException("no data chunk")
    }

    private class Format(val channels: Int, val rate: Int, val bits: Int)

    private fun format(buffer: ByteBuffer, at: Int, size: Int): Format {
        require(size >= 16) { "short fmt chunk" }
        val audioFormat = buffer.getShort(at).toInt() and 0xFFFF
        // 1 = PCM; 0xFFFE = WAVE_FORMAT_EXTENSIBLE (PCM in practice for these sizes)
        require(audioFormat == 1 || audioFormat == 0xFFFE) { "not PCM (format $audioFormat)" }
        val channels = buffer.getShort(at + 2).toInt()
        val rate = buffer.getInt(at + 4)
        val bits = buffer.getShort(at + 14).toInt()
        require(channels in 1..8 && rate in 1_000..192_000 && (bits == 8 || bits == 16)) { "unsupported: $channels channels, $rate Hz, $bits bits" }
        return Format(channels, rate, bits)
    }

    private fun samples(buffer: ByteBuffer, at: Int, size: Int, format: Format): FloatArray {
        val frameBytes = format.channels * format.bits / 8
        val frames = size / frameBytes
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (channel in 0 until format.channels) {
                val offset = at + frame * frameBytes + channel * format.bits / 8
                sum += if (format.bits == 16) buffer.getShort(offset) / 32_768f else ((buffer.get(offset).toInt() and 0xFF) - 128) / 128f
            }
            sum / format.channels
        }
    }

    /** Linear interpolation to [RATE] (enough for speech and a sample; the backchannel low-passes again) */
    internal fun resample(input: FloatArray, rate: Int): FloatArray {
        if (rate == RATE || input.isEmpty()) return input
        val count = (input.size.toLong() * RATE / rate).toInt()
        return FloatArray(count) { index ->
            val position = index.toDouble() * rate / RATE
            val left = position.toInt().coerceAtMost(input.size - 1)
            val right = (left + 1).coerceAtMost(input.size - 1)
            val fraction = (position - left).toFloat()
            input[left] * (1 - fraction) + input[right] * fraction
        }
    }

    private fun tag(bytes: ByteArray, at: Int): String = String(bytes, at, 4, Charsets.US_ASCII)
}
