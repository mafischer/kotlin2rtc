package io.github.mafischer.kotlin2rtc.codec

/** AAC frames: 1024 samples per access unit (AAC-LC) */
public const val AAC_FRAME_SAMPLES: Int = 1024

/**
 * What an AAC backchannel asks for, from its SDP: AAC-LC at the media's rate and channels (the AudioSpecificConfig in
 * `config`, or LATM's StreamMuxConfig), and how access units are framed in RTP: RFC 3640 AU headers (`mpeg4-generic`,
 * AAC-hbr 13/3/3 bits or AAC-lbr 6/2/2 unless `sizelength`/`indexlength` say otherwise) or LATM (RFC 6416, `cpresent=0`).
 */
public data class AacFormat(
    public val sampleRate: Int,
    public val channels: Int,
    public val latm: Boolean,
    public val sizeLength: Int = 13,
    public val indexLength: Int = 3,
) {
    /** The largest access unit an AU header can describe (AAC-lbr: 63 bytes) */
    public val maxAccessUnit: Int get() = if (latm) Int.MAX_VALUE else (1 shl sizeLength) - 1

    /** Bits per second for the encoder: about 2 bits per sample per channel, less where AU headers limit the size */
    public val bitRate: Int
        get() {
            val wanted = (sampleRate * 2).coerceIn(MIN_BIT_RATE, MAX_BIT_RATE) * channels
            if (maxAccessUnit == Int.MAX_VALUE) return wanted
            val fits = (maxAccessUnit * 8L * sampleRate / AAC_FRAME_SAMPLES * HEADROOM).toInt()
            return minOf(wanted, fits)
        }

    public companion object {
        private const val MIN_BIT_RATE = 16_000
        private const val MAX_BIT_RATE = 64_000
        private const val HEADROOM = 0.8
        private const val AAC_LC = 2
        private val sampleRates = intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

        /** The format of an AAC [media], or null when kotlin2rtc cannot send it (not AAC-LC, in-band config, odd framing) */
        public fun of(media: BackchannelMedia): AacFormat? {
            val config = media.fmtp["config"]?.let(::hex) ?: return null
            val format = when (media.codec) {
                BackchannelCodec.MPEG4_GENERIC -> generic(media.fmtp, config)
                BackchannelCodec.MP4A_LATM -> latm(media.fmtp, config)
                else -> null
            } ?: return null
            return format.takeIf { it.sampleRate == media.clockRate && it.channels == media.channels }
        }

        private fun generic(fmtp: Map<String, String>, config: ByteArray): AacFormat? {
            val mode = fmtp["mode"]?.lowercase()
            val (size, index) = when (mode) {
                "aac-hbr" -> 13 to 3
                "aac-lbr" -> 6 to 2
                else -> return null
            }
            val sizeLength = fmtp["sizelength"]?.toIntOrNull() ?: size
            val indexLength = fmtp["indexlength"]?.toIntOrNull() ?: index
            if (sizeLength !in 1..16 || indexLength !in 0..8) return null
            val (rate, channels) = audioSpecificConfig(BitReader(config)) ?: return null
            return AacFormat(rate, channels, latm = false, sizeLength, indexLength)
        }

        private fun latm(fmtp: Map<String, String>, config: ByteArray): AacFormat? {
            if (fmtp["cpresent"] != "0") return null
            val bits = BitReader(config)
            // StreamMuxConfig, audioMuxVersion 0: one program, one layer, one frame per AudioMuxElement
            if (bits.read(1) != 0) return null
            bits.read(1)
            if (bits.read(6) != 0 || bits.read(4) != 0 || bits.read(3) != 0) return null
            val (rate, channels) = audioSpecificConfig(bits) ?: return null
            return AacFormat(rate, channels, latm = true)
        }

        /** AAC-LC's sample rate and channels, or null for another object type or channel layout */
        private fun audioSpecificConfig(bits: BitReader): Pair<Int, Int>? {
            if (bits.read(5) != AAC_LC) return null
            val index = bits.read(4)
            val rate = if (index == 15) bits.read(24) else sampleRates.getOrNull(index) ?: return null
            val channels = bits.read(4)
            return (rate to channels).takeIf { channels in 1..2 && !bits.overrun }
        }

        private fun hex(text: String): ByteArray? {
            if (text.length % 2 != 0) return null
            return runCatching { ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        private var position = 0
        var overrun = false
            private set

        public fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = position / 8
                val bit = if (byte < bytes.size) (bytes[byte].toInt() shr (7 - position % 8)) and 1 else 1.also { overrun = true }
                value = (value shl 1) or bit
                position++
            }
            return value
        }
    }
}

/** RTP payloads of AAC access units: one per packet (RFC 3640 with one AU header, or LATM's PayloadLengthInfo) */
public object AacPacketizer {
    /** [unit] as a payload, or null when it is too large for the AU header (AAC-lbr) */
    public fun payload(unit: ByteArray, format: AacFormat): ByteArray? {
        if (format.latm) {
            val lengthBytes = unit.size / 255 + 1
            val out = ByteArray(lengthBytes + unit.size)
            for (i in 0 until lengthBytes - 1) out[i] = 0xFF.toByte()
            out[lengthBytes - 1] = (unit.size % 255).toByte()
            unit.copyInto(out, lengthBytes)
            return out
        }
        if (unit.size > format.maxAccessUnit) return null
        val headerBits = format.sizeLength + format.indexLength
        val headerBytes = (headerBits + 7) / 8
        val out = ByteArray(2 + headerBytes + unit.size)
        out[0] = (headerBits shr 8).toByte()
        out[1] = headerBits.toByte()
        // AU-size, then AU-Index 0, left aligned and padded to a whole octet
        val header = unit.size.toLong() shl (headerBytes * 8 - format.sizeLength)
        for (i in 0 until headerBytes) out[2 + i] = (header shr (8 * (headerBytes - 1 - i))).toByte()
        unit.copyInto(out, 2 + headerBytes)
        return out
    }
}

/**
 * An AAC-LC encoder (a platform encoder: Android's MediaCodec, a JVM AAC library; fakes in tests): 16-bit
 * interleaved PCM in, raw access units out (no ADTS), as they become ready. Used on the audio pump's thread.
 */
public interface AacEncoder {
    public fun encode(pcm: ShortArray): List<ByteArray>

    public fun close()
}

/** Creates AAC encoders; null when the platform has none (plain JVM: kotlin2rtc ships no AAC encoder) */
public fun interface AacEncoderFactory {
    public fun create(sampleRate: Int, channels: Int, bitRate: Int): AacEncoder?

    public companion object {
        public val None: AacEncoderFactory = AacEncoderFactory { _, _, _ -> null }
    }
}
