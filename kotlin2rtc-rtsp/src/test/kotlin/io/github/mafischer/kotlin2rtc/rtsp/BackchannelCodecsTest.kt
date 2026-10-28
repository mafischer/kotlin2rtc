package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.AAC_FRAME_SAMPLES
import io.github.mafischer.kotlin2rtc.codec.AacEncoder
import io.github.mafischer.kotlin2rtc.codec.AacEncoderFactory
import io.github.mafischer.kotlin2rtc.codec.AacFormat
import io.github.mafischer.kotlin2rtc.codec.AacPacketizer
import io.github.mafischer.kotlin2rtc.codec.BackchannelCodec
import io.github.mafischer.kotlin2rtc.codec.BackchannelEncoder
import io.github.mafischer.kotlin2rtc.codec.BackchannelMedia
import io.github.mafischer.kotlin2rtc.codec.G711
import io.github.mafischer.kotlin2rtc.codec.G726
import io.github.mafischer.kotlin2rtc.codec.G726Format
import io.github.mafischer.kotlin2rtc.codec.G726Packing
import io.github.mafischer.kotlin2rtc.codec.RtpPacketizer
import io.github.mafischer.kotlin2rtc.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * the codecs besides G.711: the choice among what a camera offers, G.726 ADPCM at every rate and packing,
 * AAC formats from the SDP and their RTP framing (RFC 3640, RFC 6416), L16, and the encoder that sends them
 */
class BackchannelCodecsTest {
    private fun tone(count: Int, rate: Int = 8_000, frequency: Double = 440.0, amplitude: Double = 0.4) =
        IntArray(count) { (amplitude * 32_767 * sin(2 * PI * frequency * it / rate)).toInt() }

    /** Signal to noise ratio (dB) of [decoded] against [original] */
    private fun snr(original: IntArray, decoded: IntArray, from: Int): Double {
        var signal = 0.0
        var noise = 0.0
        for (i in from until original.size) {
            signal += original[i].toDouble() * original[i]
            val error = (original[i] - decoded[i]).toDouble()
            noise += error * error
        }
        return 10 * log10(signal / noise.coerceAtLeast(1.0))
    }

    @Test
    fun `G726 at every rate decodes close to what was encoded, better with more bits`() {
        val input = tone(8_000)
        val ratios = (2..5).map { bits ->
            val encoder = G726(bits)
            val decoder = G726(bits)
            val codes = IntArray(input.size) { encoder.encode(input[it]) }
            assertTrue(codes.all { it in 0 until (1 shl bits) })
            val decoded = IntArray(codes.size) { decoder.decode(codes[it]) }
            snr(input, decoded, from = 800)
        }
        // 16 kbit/s is rough, 40 kbit/s close to G.711
        assertTrue(ratios[0] > 5 && ratios[1] > 12 && ratios[2] > 18 && ratios[3] > 22, "SNR $ratios")
        assertEquals(ratios, ratios.sorted())
    }

    @Test
    fun `G726 is silent on silence and follows a step`() {
        val codec = G726(4)
        val decoder = G726(4)
        repeat(200) { decoder.decode(codec.encode(0)) }
        val quiet = IntArray(100) { decoder.decode(codec.encode(0)) }
        assertTrue(quiet.all { kotlin.math.abs(it) < 64 })
        // A loud square wave and full scale do not break it
        val loud = IntArray(800) { if (it / 20 % 2 == 0) 32_767 else -32_768 }
        val back = IntArray(loud.size) { decoder.decode(codec.encode(loud[it])) }
        assertTrue(back.drop(100).maxOf { it } > 16_000)
    }

    @Test
    fun `G726 packing, RFC 3551 low bits first or AAL2 high bits first, and the formats`() {
        assertArrayEquals(byteArrayOf(0x21, 0x43), G726Packing.pack(intArrayOf(1, 2, 3, 4), 4, bigEndian = false))
        assertArrayEquals(byteArrayOf(0x12, 0x34), G726Packing.pack(intArrayOf(1, 2, 3, 4), 4, bigEndian = true))
        // 3 bits: 8 codewords in 3 octets
        val codes = intArrayOf(1, 2, 3, 4, 5, 6, 7, 0)
        for (bits in 2..5) {
            val values = codes.map { it and ((1 shl bits) - 1) }.toIntArray()
            for (bigEndian in listOf(false, true)) {
                val packed = G726Packing.pack(values, bits, bigEndian)
                assertEquals(bits, packed.size)
                assertArrayEquals(values, G726Packing.unpack(packed, bits, bigEndian))
            }
        }
        assertEquals(0b10_01_00_11.toByte(), G726Packing.pack(intArrayOf(3, 0, 1, 2), 2, bigEndian = false)[0])
        assertEquals(G726Format(4, false), G726Format.of("G726-32"))
        assertEquals(G726Format(5, true), G726Format.of("aal2-g726-40"))
        assertEquals(G726Format(2, false), G726Format.of("G726-16"))
        assertEquals(G726Format(3, false), G726Format.of("G726-24"))
        assertNull(G726Format.of("G726-48"))
        assertNull(G726Format.of("G726"))
    }

    @Test
    fun `the best codec a camera offers wins, G711, then AAC, then G726 with the most bits, then L16`() {
        val multi = BackchannelSdp.parse(Fixtures.read("backchannel-describe-multi-codec.sdp"), "rtsp://192.168.1.30/")
        assertEquals(BackchannelCodec.MP4A_LATM, multi.media?.codec)
        assertEquals(103, multi.media?.payloadType)
        assertEquals(listOf("L16", "G726-16", "AAL2-G726-40", "MP4A-LATM", "G726-32"), multi.offered)
        val noAac = Fixtures.read("backchannel-describe-multi-codec.sdp").replace("cpresent=0", "cpresent=1")
        val g726 = BackchannelSdp.parse(noAac, "rtsp://192.168.1.30/").media!!
        assertEquals("AAL2-G726-40", g726.encoding)
        assertEquals("AAL2-G726-40/8000", g726.label)
        val l16Only = "m=audio 0 RTP/AVP 100\na=rtpmap:100 L16/16000/1\na=sendonly\n"
        assertEquals(BackchannelCodec.L16, BackchannelSdp.parse(l16Only, "rtsp://cam").media?.codec)
        // G.711 beats everything; G.726 only at 8 kHz; L16 at 44.1 kHz cannot be reached from 48 kHz
        val withG711 = "m=audio 0 RTP/AVP 97 0\na=rtpmap:97 G726-40/8000\na=sendonly\n"
        assertEquals(BackchannelCodec.PCMU, BackchannelSdp.parse(withG711, "rtsp://cam").media?.codec)
        assertNull(BackchannelSdp.parse("m=audio 0 RTP/AVP 97\na=rtpmap:97 G726-32/16000\na=sendonly\n", "rtsp://cam").media)
        assertNull(BackchannelSdp.parse("m=audio 0 RTP/AVP 97\na=rtpmap:97 L16/44100/2\na=sendonly\n", "rtsp://cam").media)
        assertNull(BackchannelSdp.parse("m=audio 0 RTP/AVP 97\na=rtpmap:97 L16/16000/6\na=sendonly\n", "rtsp://cam").media)
    }

    @Test
    fun `AAC formats from the SDP, AAC-hbr with its AudioSpecificConfig, AAC-lbr, LATM with its StreamMuxConfig`() {
        val hbr = BackchannelSdp.parse(Fixtures.read("backchannel-describe-aac-hbr.sdp"), "rtsp://192.168.1.21/").media!!
        assertEquals(BackchannelCodec.MPEG4_GENERIC, hbr.codec)
        assertEquals("rtsp://192.168.1.21/h264Preview_01_main/backchannel", hbr.control)
        assertEquals(AacFormat(16_000, 1, latm = false, 13, 3), AacFormat.of(hbr))
        assertEquals(32_000, AacFormat.of(hbr)!!.bitRate)
        val lbr = hbr.copy(fmtp = mapOf("mode" to "AAC-lbr", "config" to "1408"))
        val lbrFormat = AacFormat.of(lbr)!!
        assertEquals(AacFormat(16_000, 1, latm = false, 6, 2), lbrFormat)
        assertEquals(63, lbrFormat.maxAccessUnit)
        // Small enough that a unit fits in 63 bytes
        assertTrue(lbrFormat.bitRate < 63 * 8 * 16_000 / 1024)
        val latm = BackchannelSdp.parse(Fixtures.read("backchannel-describe-multi-codec.sdp"), "rtsp://cam/").media!!
        assertEquals(AacFormat(16_000, 1, latm = true), AacFormat.of(latm))
        // Not AAC-LC (HE-AAC, object type 5), another rate than the rtpmap's, no mode or config, in-band config
        assertNull(AacFormat.of(hbr.copy(fmtp = hbr.fmtp + ("config" to "2B08"))))
        assertNull(AacFormat.of(hbr.copy(clockRate = 8_000)))
        assertNull(AacFormat.of(hbr.copy(fmtp = mapOf("config" to "1408"))))
        assertNull(AacFormat.of(hbr.copy(fmtp = mapOf("mode" to "AAC-hbr"))))
        assertNull(AacFormat.of(hbr.copy(fmtp = hbr.fmtp + ("config" to "14"))))
        assertNull(AacFormat.of(hbr.copy(fmtp = hbr.fmtp + ("config" to "zz08"))))
        assertNull(AacFormat.of(latm.copy(fmtp = latm.fmtp - "cpresent")))
        assertNull(AacFormat.of(latm.copy(fmtp = latm.fmtp + ("config" to "C0002810"))))
        assertNull(AacFormat.of(hbr.copy(codec = BackchannelCodec.L16)))
        // Explicit sample rate (index 15) and stereo
        val explicit = hbr.copy(clockRate = 24_000, channels = 2, fmtp = hbr.fmtp + ("config" to "17802EE010"))
        assertEquals(AacFormat(24_000, 2, latm = false, 13, 3), AacFormat.of(explicit))
    }

    @Test
    fun `AAC units in RTP, one AU header (RFC 3640) or LATM's length bytes (RFC 6416)`() {
        val unit = ByteArray(300) { it.toByte() }
        val hbr = AacPacketizer.payload(unit, AacFormat(16_000, 1, latm = false, 13, 3))!!
        // AU-headers-length 16 bits, AU-size 300 in 13 bits, index 0
        assertArrayEquals(byteArrayOf(0x00, 0x10, (300 shr 5).toByte(), ((300 shl 3) and 0xFF).toByte()), hbr.copyOfRange(0, 4))
        assertArrayEquals(unit, hbr.copyOfRange(4, hbr.size))
        val lbrFormat = AacFormat(16_000, 1, latm = false, 6, 2)
        val small = AacPacketizer.payload(ByteArray(40), lbrFormat)!!
        assertArrayEquals(byteArrayOf(0x00, 0x08, (40 shl 2).toByte()), small.copyOfRange(0, 3))
        assertNull(AacPacketizer.payload(ByteArray(64), lbrFormat))
        val latm = AacPacketizer.payload(unit, AacFormat(16_000, 1, latm = true))!!
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 45), latm.copyOfRange(0, 2))
        assertEquals(302, latm.size)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0), AacPacketizer.payload(ByteArray(255), AacFormat(16_000, 1, latm = true))!!.copyOfRange(0, 2))
    }

    /** One fake access unit per 1024 samples per channel, its first byte the channel count */
    private class FakeAac(val channels: Int) : AacEncoder {
        var buffered = 0
        var closed = false

        override fun encode(pcm: ShortArray): List<ByteArray> {
            buffered += pcm.size / channels
            val units = mutableListOf<ByteArray>()
            while (buffered >= AAC_FRAME_SAMPLES) {
                buffered -= AAC_FRAME_SAMPLES
                units += byteArrayOf(channels.toByte(), 1, 2, 3)
            }
            return units
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the encoder sends AAC units with the marker and 1024-sample timestamps`() {
        var created: FakeAac? = null
        val factory = AacEncoderFactory { rate, channels, bitRate ->
            assertEquals(16_000, rate)
            assertEquals(32_000, bitRate)
            FakeAac(channels).also { created = it }
        }
        val media = BackchannelSdp.parse(Fixtures.read("backchannel-describe-aac-hbr.sdp"), "rtsp://cam/").media!!
        val encoder = BackchannelEncoder(media, RtpPacketizer(97, 1, 0, 0), factory)
        // 320 samples per 20 ms block at 16 kHz: a unit every 3.2 blocks
        val packets = (0 until 16).flatMap { encoder.packets(FloatArray(960), null) }
        assertEquals(5, packets.size)
        packets.forEachIndexed { index, packet ->
            assertEquals(0x80 or 97, packet[1].toInt() and 0xFF)
            val timestamp = ((packet[4].toInt() and 0xFF) shl 24) or ((packet[5].toInt() and 0xFF) shl 16) or ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
            assertEquals(index * 1024, timestamp)
            assertEquals(12 + 4 + 4, packet.size)
        }
        encoder.close()
        assertTrue(created!!.closed)
        // Without an encoder nothing is sent
        assertTrue(BackchannelEncoder(media).packets(FloatArray(960), null).isEmpty())
        val latm = BackchannelSdp.parse(Fixtures.read("backchannel-describe-multi-codec.sdp"), "rtsp://cam/").media!!
        val latmEncoder = BackchannelEncoder(latm, RtpPacketizer(103, 1, 0, 0), factory)
        val latmPackets = (0 until 4).flatMap { latmEncoder.packets(FloatArray(960), null) }
        assertEquals(1, latmPackets.size)
        assertEquals(4, latmPackets[0][12].toInt())
    }

    @Test
    fun `stereo AAC gets the mono samples on both channels`() {
        val media = BackchannelMedia("rtsp://cam/back", BackchannelCodec.MPEG4_GENERIC, 97, 16_000, "mpeg4-generic", 2, mapOf("mode" to "AAC-hbr", "config" to "1410"))
        val seen = mutableListOf<ShortArray>()
        val factory = AacEncoderFactory { _, channels, _ ->
            assertEquals(2, channels)
            object : AacEncoder {
                override fun encode(pcm: ShortArray): List<ByteArray> = emptyList<ByteArray>().also { seen += pcm }

                override fun close() = Unit
            }
        }
        BackchannelEncoder(media, RtpPacketizer(97, 1, 0, 0), factory).packets(FloatArray(960) { 0.5f }, null)
        assertEquals(640, seen.single().size)
        assertEquals(seen.single()[0], seen.single()[1])
    }

    @Test
    fun `G726 and L16 packets from the encoder`() {
        val g726 = BackchannelMedia("rtsp://cam/back", BackchannelCodec.G726, 97, 8_000, "G726-24")
        val tone = FloatArray(960) { (0.4 * sin(2 * PI * 440 * it / 48_000)).toFloat() }
        assertEquals(12 + 60, BackchannelEncoder(g726).packet(tone, null).size)
        assertEquals(12 + 100, BackchannelEncoder(g726.copy(encoding = "G726-40")).packet(tone, null).size)
        val l16 = BackchannelMedia("rtsp://cam/back", BackchannelCodec.L16, 100, 16_000, "L16", 2)
        val packet = BackchannelEncoder(l16, RtpPacketizer(100, 1, 0, 0)).packet(FloatArray(960) { 0.5f }, null)
        assertEquals(12 + 320 * 2 * 2, packet.size)
        // Big-endian, both channels; past the filter's start the value is half scale
        val last = packet.size - 4
        val left = ((packet[last].toInt() shl 8) or (packet[last + 1].toInt() and 0xFF)).toShort()
        val right = ((packet[last + 2].toInt() shl 8) or (packet[last + 3].toInt() and 0xFF)).toShort()
        assertEquals(left, right)
        assertEquals(16_384.0, left.toDouble(), 200.0)
    }
}
