package io.github.mafischer.kotlin2rtc.codec

import io.github.mafischer.kotlin2rtc.core.Logger
import kotlin.math.roundToInt

/** One RTP payload: its bytes, the samples it covers, and the marker bit when the codec decides it (null: talkspurt rule) */
public class Payload(public val bytes: ByteArray, public val samples: Int, public val marker: Boolean? = null)

/** Turns 20 ms blocks of mono samples at the media's rate (-1..1) into RTP payloads, in the camera's codec */
public interface PayloadEncoder {
    public fun encode(block: FloatArray): List<Payload>

    public fun close(): Unit = Unit

    public companion object {
        /** The encoder of [media]'s codec; AAC through [aac] (none: nothing is sent, logged once to [logger]) */
        public fun of(media: BackchannelMedia, aac: AacEncoderFactory, logger: Logger = Logger.None): PayloadEncoder = when (media.codec) {
            BackchannelCodec.PCMU, BackchannelCodec.PCMA -> G711Payloads(media.codec)
            BackchannelCodec.G726 -> G726Payloads(G726Format.of(media.encoding) ?: G726Format(4, false))
            BackchannelCodec.L16 -> L16Payloads(media.channels)
            BackchannelCodec.MPEG4_GENERIC, BackchannelCodec.MP4A_LATM -> AacPayloads.of(media, aac, logger)
        }
    }
}

/** G.711: one byte per sample */
public class G711Payloads(private val codec: BackchannelCodec) : PayloadEncoder {
    override fun encode(block: FloatArray): List<Payload> {
        val bytes = ByteArray(block.size)
        G711.encode(codec, block, bytes)
        return listOf(Payload(bytes, block.size))
    }
}

/** G.726 ADPCM, 2 to 5 bits per sample, packed as the encoding name says */
public class G726Payloads(private val format: G726Format) : PayloadEncoder {
    private val codec = G726(format.bits)

    override fun encode(block: FloatArray): List<Payload> {
        val codes = IntArray(block.size) { codec.encode(pcm16(block[it])) }
        return listOf(Payload(G726Packing.pack(codes, format.bits, format.bigEndian), block.size))
    }
}

/** L16: 16-bit big-endian samples, mono copied to every channel */
public class L16Payloads(private val channels: Int) : PayloadEncoder {
    override fun encode(block: FloatArray): List<Payload> {
        val bytes = ByteArray(block.size * 2 * channels)
        var index = 0
        for (sample in block) {
            val pcm = pcm16(sample)
            repeat(channels) {
                bytes[index++] = (pcm shr 8).toByte()
                bytes[index++] = pcm.toByte()
            }
        }
        return listOf(Payload(bytes, block.size))
    }
}

/**
 * AAC: the blocks go to the AAC-LC encoder (mono copied to every channel), every access unit it returns is one packet
 * of 1024 samples with the marker bit (RFC 3640 3.2, RFC 6416: each packet completes its unit)
 */
public class AacPayloads(private val encoder: AacEncoder?, private val format: AacFormat, private val logger: Logger = Logger.None) : PayloadEncoder {
    override fun encode(block: FloatArray): List<Payload> {
        val aac = encoder ?: return emptyList()
        val pcm = ShortArray(block.size * format.channels)
        var index = 0
        for (sample in block) {
            val value = pcm16(sample).toShort()
            repeat(format.channels) { pcm[index++] = value }
        }
        return aac.encode(pcm).mapNotNull { unit ->
            AacPacketizer.payload(unit, format)?.let { Payload(it, AAC_FRAME_SAMPLES, marker = true) } ?: run {
                logger.info("AAC unit of ${unit.size} bytes dropped: too large for the AU header")
                null
            }
        }
    }

    override fun close() {
        encoder?.close()
    }

    public companion object {
        public fun of(media: BackchannelMedia, factory: AacEncoderFactory, logger: Logger = Logger.None): AacPayloads {
            val format = AacFormat.of(media) ?: AacFormat(media.clockRate, media.channels, latm = media.codec == BackchannelCodec.MP4A_LATM)
            val encoder = factory.create(format.sampleRate, format.channels, format.bitRate)
            if (encoder == null) logger.info("No AAC encoder: the backchannel stays silent")
            return AacPayloads(encoder, format, logger)
        }
    }
}

internal fun pcm16(sample: Float): Int = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
