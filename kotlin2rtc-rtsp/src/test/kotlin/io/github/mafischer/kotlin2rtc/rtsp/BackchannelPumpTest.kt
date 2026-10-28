package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.BackchannelCodec
import io.github.mafischer.kotlin2rtc.codec.BackchannelMedia
import io.github.mafischer.kotlin2rtc.codec.G711
import io.github.mafischer.kotlin2rtc.codec.PcmSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** The audio pump: one packet every 20 ms of sound and microphone, the microphone opened and closed on its thread, RTCP, failures */
class BackchannelPumpTest {
    private fun sine(frequency: Double, rate: Int, count: Int, offset: Int = 0, amplitude: Double = 0.5) =
        FloatArray(count) { (amplitude * sin(2 * PI * frequency * (offset + it) / rate)).toFloat() }

    /** A 440 Hz tone at 48 kHz that never ends */
    private class ToneSource : PcmSource {
        private var position = 0

        override fun render(out: FloatArray): Boolean {
            for (i in out.indices) out[i] = (0.3 * sin(2 * PI * 440 * (position + i) / 48_000)).toFloat()
            position += out.size
            return true
        }
    }

    /** The microphone of the tests: a 300 Hz tone at [rate], or nothing ([opens] fails) */
    private class FakeMicrophone(private val rate: Int? = 8_000, var failReads: Boolean = false) : TalkMicrophone {
        val asked = mutableListOf<List<Int>>()
        var open = false
        var closes = 0
        private var position = 0

        override fun open(rates: List<Int>): Int? {
            asked += rates
            open = rate != null
            return rate
        }

        override fun read(out: FloatArray): Boolean {
            if (failReads) return false
            for (i in out.indices) out[i] = (0.5 * sin(2 * PI * 300 * (position + i) / (rate ?: 8_000))).toFloat()
            position += out.size
            return true
        }

        override fun close() {
            open = false
            closes += 1
        }
    }

    private class RecordingLink(override val media: BackchannelMedia = BackchannelMedia("x", BackchannelCodec.PCMU, 0, 8_000)) : BackchannelLink {
        override var onClosed: (() -> Unit)? = null
        val rtp = CopyOnWriteArrayList<ByteArray>()
        val rtcp = CopyOnWriteArrayList<ByteArray>()
        var broken = false

        override fun sendRtp(packet: ByteArray) {
            if (broken) throw IOException("gone")
            rtp += packet
        }

        override fun sendRtcp(packet: ByteArray) {
            rtcp += packet
        }

        override fun close() = Unit
    }

    private fun level(packet: ByteArray): Double {
        val pcm = G711.decode(BackchannelCodec.PCMU, packet.copyOfRange(12, packet.size))
        return sqrt(pcm.sumOf { (it / 32_768.0) * (it / 32_768.0) } / pcm.size)
    }

    @Test
    fun `the pump sends the microphone while talking, opening and closing it on its thread`() {
        val link = RecordingLink()
        val microphone = FakeMicrophone()
        val events = mutableListOf<BackchannelAudioEvent>()
        var wall = 0L
        val pump = BackchannelPump(link, PcmSource.Silence, microphone, events::add, wallClock = { wall })
        assertFalse(pump.step(), "not talking: paced by the clock")
        assertEquals(0.0, level(link.rtp.last()), 0.0)
        // An RTCP report with the first packet, then every 5 s
        assertEquals(1, link.rtcp.size)
        wall = 4_000
        pump.step()
        assertEquals(1, link.rtcp.size)
        wall = 5_000
        pump.step()
        assertEquals(2, link.rtcp.size)

        pump.talking = true
        repeat(3) { assertTrue(pump.step(), "the microphone paces") }
        assertEquals(listOf(listOf(8_000, 16_000, 48_000)), microphone.asked)
        assertTrue(microphone.open)
        assertTrue(level(link.rtp.last()) > 0.2)
        pump.talking = false
        pump.step()
        assertFalse(microphone.open)
        assertEquals(1, microphone.closes)
        assertEquals(listOf<BackchannelAudioEvent>(), events)
        // Consecutive sequence numbers throughout
        val sequences = link.rtp.map { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }
        assertEquals(sequences.indices.map { (sequences[0] + it) and 0xFFFF }, sequences)
    }

    @Test
    fun `a microphone that cannot be opened or stops is reported once, the sound goes on`() {
        val link = RecordingLink()
        val events = mutableListOf<BackchannelAudioEvent>()
        val sounds = ToneSource()
        val pump = BackchannelPump(link, sounds, FakeMicrophone(rate = null), events::add)
        pump.talking = true
        assertFalse(pump.step())
        assertFalse(pump.step())
        assertEquals(listOf(BackchannelAudioEvent.MICROPHONE_UNAVAILABLE), events)
        repeat(80) { pump.step() }
        assertTrue(level(link.rtp.last()) > 0.01)

        val failing = FakeMicrophone()
        val second = BackchannelPump(RecordingLink(), PcmSource.Silence, failing, events::add)
        second.talking = true
        assertTrue(second.step())
        failing.failReads = true
        assertFalse(second.step())
        assertEquals(2, events.size)
        assertFalse(failing.open)
    }

    @Test
    fun `the pump thread sends a packet every 20 ms and reports a broken link`() {
        val link = RecordingLink()
        val events = CopyOnWriteArrayList<BackchannelAudioEvent>()
        val pump = BackchannelPump(link, PcmSource.Silence, TalkMicrophone.None, { events += it })
        pump.start()
        pump.start()
        val start = System.nanoTime()
        while (link.rtp.size < 10 && System.nanoTime() - start < 3_000_000_000L) Thread.sleep(5)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertTrue(link.rtp.size >= 10 && elapsedMillis >= 150, "10 packets in $elapsedMillis ms")
        link.broken = true
        val broke = System.nanoTime()
        while (events.isEmpty() && System.nanoTime() - broke < 3_000_000_000L) Thread.sleep(5)
        assertEquals(listOf(BackchannelAudioEvent.LINK_FAILED), events.toList())
        pump.stop()

        // Stopped: nothing more, and no event
        val quiet = RecordingLink()
        val stopped = BackchannelPump(quiet, PcmSource.Silence, TalkMicrophone.None, { events += it })
        stopped.start()
        stopped.stop()
        Thread.sleep(100)
        val sent = quiet.rtp.size
        Thread.sleep(100)
        assertEquals(sent, quiet.rtp.size)
        assertEquals(1, events.size)
    }

    @Test
    fun `without a microphone factory the pump sends sounds only`() {
        val link = RecordingLink()
        val audio = BackchannelAudioFactory.WithoutMicrophone.create(link, PcmSource.Silence) {}
        audio.talking = true
        assertTrue(audio is BackchannelPump)
        assertFalse((audio as BackchannelPump).step())
    }
}
