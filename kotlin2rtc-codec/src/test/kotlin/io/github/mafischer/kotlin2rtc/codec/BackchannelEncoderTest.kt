package io.github.mafischer.kotlin2rtc.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What goes to the camera: sound at 48 kHz and the microphone, low-passed and decimated to the camera's 8 kHz, mixed,
 * G.711 in RTP every 20 ms
 */
class BackchannelEncoderTest {
    private fun sine(frequency: Double, rate: Int, count: Int, offset: Int = 0, amplitude: Double = 0.5) =
        FloatArray(count) { (amplitude * sin(2 * PI * frequency * (offset + it) / rate)).toFloat() }

    private fun rms(samples: FloatArray, from: Int = 0) = sqrt(samples.drop(from).sumOf { it.toDouble() * it } / (samples.size - from))

    private fun decimate(decimator: Decimator, input: FloatArray): FloatArray {
        val out = FloatArray(input.size / decimator.factor)
        decimator.process(input, input.size, out)
        return out
    }

    @Test
    fun `the decimator keeps the voice band and removes what would alias`() {
        // 1 kHz passes at full level, a constant too
        val voice = decimate(Decimator(6), sine(1_000.0, 48_000, 4_800))
        assertEquals(0.5 / sqrt(2.0), rms(voice, from = 100), 0.01)
        val dc = decimate(Decimator(6), FloatArray(960) { 0.25f })
        assertEquals(0.25f, dc.last(), 0.001f)
        // 6 kHz (above 4 kHz, the output's Nyquist) would fold to 2 kHz: at least 40 dB down
        val high = decimate(Decimator(6), sine(6_000.0, 48_000, 4_800))
        assertTrue(rms(high, from = 100) < 0.5 / sqrt(2.0) / 100)
    }

    @Test
    fun `blocks join without a seam, and a factor of 1 passes through`() {
        val signal = sine(700.0, 48_000, 1_920)
        val whole = decimate(Decimator(6), signal)
        val inBlocks = Decimator(6)
        val first = decimate(inBlocks, signal.copyOfRange(0, 960))
        val second = decimate(inBlocks, signal.copyOfRange(960, 1_920))
        assertArrayEquals(whole, first + second, 1e-6f)
        val same = FloatArray(160) { it / 160f }
        assertArrayEquals(same, decimate(Decimator(1), same), 0f)
    }

    @Test
    fun `a packet is 20 ms of the camera codec with silence, sound, microphone and both`() {
        val encoder = BackchannelEncoder(BackchannelMedia("rtsp://cam/back", BackchannelCodec.PCMA, 8, 8_000), RtpPacketizer(8, 7, 0, 0))
        val silence = encoder.packet(null, null)
        assertEquals(12 + 160, silence.size)
        assertTrue(silence.drop(12).all { it == 0xD5.toByte() })
        assertEquals(listOf(8_000, 16_000, 48_000), encoder.microphoneRates)

        // Microphone at 16 kHz: decimated by 2
        encoder.microphoneRate(16_000)
        var packet = ByteArray(0)
        repeat(5) { packet = encoder.packet(null, sine(500.0, 16_000, 320, offset = it * 320)) }
        val voice = G711.decode(BackchannelCodec.PCMA, packet.copyOfRange(12, packet.size))
        assertEquals(0.5 / sqrt(2.0), sqrt(voice.sumOf { (it / 32_768.0) * (it / 32_768.0) } / voice.size), 0.03)

        // Sound and microphone add up
        encoder.microphoneRate(8_000)
        repeat(5) { packet = encoder.packet(FloatArray(960) { 0.2f }, FloatArray(160) { 0.3f }) }
        val sum = G711.decode(BackchannelCodec.PCMA, packet.copyOfRange(12, packet.size))
        assertTrue(sum.all { abs(it / 32_768.0 - 0.5) < 0.02 })
        // Without a microphone rate its samples are ignored
        encoder.microphoneRate(null)
        repeat(5) { packet = encoder.packet(null, FloatArray(160) { 0.3f }) }
        assertTrue(packet.drop(12).all { it == 0xD5.toByte() })
        // Microphone rates that are no multiple of the camera's are not offered
        assertEquals(listOf(16_000, 48_000), BackchannelEncoder(BackchannelMedia("x", BackchannelCodec.PCMU, 0, 16_000)).microphoneRates)
    }

    @Test
    fun `a buffer source plays once, then says nothing plays`() {
        val source = BufferPcmSource(FloatArray(1_500) { 0.25f })
        val block = FloatArray(960)
        assertEquals(48_000, source.sampleRate)
        assertTrue(source.render(block))
        assertTrue(block.all { it == 0.25f })
        assertTrue(source.render(block))
        assertEquals(0.25f, block[539])
        assertEquals(0f, block[540])
        assertTrue(source.isFinished)
        assertFalse(source.render(block))
        assertFalse(PcmSource.Silence.render(block))
        assertEquals(PcmSource.DEFAULT_SAMPLE_RATE, PcmSource.Silence.sampleRate)
    }

    @Test
    fun `sound at another rate is decimated from that rate`() {
        val encoder = BackchannelEncoder(BackchannelMedia("rtsp://cam/back", BackchannelCodec.PCMU, 0, 8_000), RtpPacketizer(0, 7, 0, 0), soundRate = 16_000)
        var packet = ByteArray(0)
        repeat(5) { packet = encoder.packet(FloatArray(320) { 0.3f }, null) }
        val pcm = G711.decode(BackchannelCodec.PCMU, packet.copyOfRange(12, packet.size))
        assertTrue(pcm.all { abs(it / 32_768.0 - 0.3) < 0.02 })
    }
}
