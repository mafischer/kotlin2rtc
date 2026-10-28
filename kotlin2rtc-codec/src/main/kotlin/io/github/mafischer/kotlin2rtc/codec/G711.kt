package io.github.mafischer.kotlin2rtc.codec

import kotlin.math.roundToInt

/**
 * ITU-T G.711 companding (µ-law and A-law, 8 bits per sample), what ONVIF cameras take on their audio backchannel. The
 * segment tables of the reference implementation (Sun Microsystems' public-domain g711.c): 16-bit linear PCM in,
 * one byte out, and back for tests and fake cameras.
 */
public object G711 {
    private const val ULAW_BIAS = 0x84
    private const val ULAW_CLIP = 8159
    private val ulawSegmentEnds = intArrayOf(0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF)
    private val alawSegmentEnds = intArrayOf(0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF)

    /** One 16-bit sample as µ-law (PCMU) */
    public fun ulaw(pcm: Int): Int {
        var value = pcm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) shr 2
        val mask: Int
        if (value < 0) {
            value = -value
            mask = 0x7F
        } else {
            mask = 0xFF
        }
        if (value > ULAW_CLIP) value = ULAW_CLIP
        value += ULAW_BIAS shr 2
        val segment = segment(value, ulawSegmentEnds)
        if (segment >= 8) return 0x7F xor mask
        return ((segment shl 4) or ((value shr (segment + 1)) and 0x0F)) xor mask
    }

    /** One µ-law byte as 16-bit PCM */
    public fun ulawToLinear(code: Int): Int {
        val inverted = code.inv() and 0xFF
        var value = ((inverted and 0x0F) shl 3) + ULAW_BIAS
        value = value shl ((inverted and 0x70) shr 4)
        return if (inverted and 0x80 != 0) ULAW_BIAS - value else value - ULAW_BIAS
    }

    /** One 16-bit sample as A-law (PCMA) */
    public fun alaw(pcm: Int): Int {
        var value = pcm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) shr 3
        val mask: Int
        if (value >= 0) {
            mask = 0xD5
        } else {
            mask = 0x55
            value = -value - 1
        }
        val segment = segment(value, alawSegmentEnds)
        if (segment >= 8) return 0x7F xor mask
        val mantissa = if (segment < 2) (value shr 1) and 0x0F else (value shr segment) and 0x0F
        return ((segment shl 4) or mantissa) xor mask
    }

    /** One A-law byte as 16-bit PCM */
    public fun alawToLinear(code: Int): Int {
        val value = (code and 0xFF) xor 0x55
        var linear = (value and 0x0F) shl 4
        val segment = (value and 0x70) shr 4
        when (segment) {
            0 -> {
                linear += 8
            }
            1 -> {
                linear += 0x108
            }
            else -> {
                linear += 0x108
                linear = linear shl (segment - 1)
            }
        }
        return if (value and 0x80 != 0) linear else -linear
    }

    /** [count] float samples (-1..1, clamped) encoded with [codec] into [out] */
    public fun encode(codec: BackchannelCodec, samples: FloatArray, out: ByteArray, count: Int = samples.size) {
        for (i in 0 until count) {
            val pcm = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
            out[i] = (if (codec == BackchannelCodec.PCMU) ulaw(pcm) else alaw(pcm)).toByte()
        }
    }

    /** Encoded bytes back to 16-bit PCM (fake cameras and tests listen this way) */
    public fun decode(codec: BackchannelCodec, encoded: ByteArray): ShortArray =
        ShortArray(encoded.size) { (if (codec == BackchannelCodec.PCMU) ulawToLinear(encoded[it].toInt()) else alawToLinear(encoded[it].toInt())).toShort() }

    private fun segment(value: Int, ends: IntArray): Int {
        for (i in ends.indices) if (value <= ends[i]) return i
        return ends.size
    }
}
