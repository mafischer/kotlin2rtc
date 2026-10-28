package io.github.mafischer.kotlin2rtc.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** RTP packets (RFC 3550), the RTCP sender report and RTSP interleaving of the backchannel */
class RtpTest {
    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

    private fun ByteArray.u16(index: Int) = (u8(index) shl 8) or u8(index + 1)

    private fun ByteArray.u32(index: Int) = (u16(index).toLong() shl 16 or u16(index + 2).toLong()).toInt()

    @Test
    fun `the header has version 2, payload type, sequence, timestamp, SSRC, then the payload`() {
        val rtp = RtpPacketizer(payloadType = 8, ssrc = 0x12345678, initialSequence = 0xFFFE, initialTimestamp = -160)
        val payload = ByteArray(160) { it.toByte() }
        val first = rtp.packet(payload, 160)
        assertEquals(12 + 160, first.size)
        assertEquals(0x80, first.u8(0))
        // Marker on the first packet of a talkspurt
        assertEquals(0x80 or 8, first.u8(1))
        assertEquals(0xFFFE, first.u16(2))
        assertEquals(-160, first.u32(4))
        assertEquals(0x12345678, first.u32(8))
        assertArrayEquals(payload, first.copyOfRange(12, first.size))

        val second = rtp.packet(payload, 160)
        assertEquals(8, second.u8(1))
        assertEquals(0xFFFF, second.u16(2))
        assertEquals(0, second.u32(4))
        // Both wrap
        val third = rtp.packet(payload, 160)
        assertEquals(0, third.u16(2))
        assertEquals(160, third.u32(4))
        assertEquals(3, rtp.packetCount)
        assertEquals(480, rtp.octetCount)
    }

    @Test
    fun `a gap moves the clock on and marks the next packet`() {
        val rtp = RtpPacketizer(0, 1, 10, 1_000)
        rtp.packet(ByteArray(160), 160)
        rtp.skip(800)
        val next = rtp.packet(ByteArray(160), 160)
        assertEquals(0x80, next.u8(1) and 0x80)
        assertEquals(11, next.u16(2))
        assertEquals(1_000 + 160 + 800, next.u32(4))
    }

    @Test
    fun `random packetizers differ`() {
        val a = RtpPacketizer.random(0)
        val b = RtpPacketizer.random(0)
        assertNotEquals(a.ssrc, b.ssrc)
    }

    @Test
    fun `the sender report and its CNAME in one compound packet`() {
        val rtp = RtpPacketizer(0, 0x0BADCAFE, 0, 5_000)
        repeat(3) { rtp.packet(ByteArray(160), 160) }
        val report = Rtcp.senderReport(rtp, 1_793_102_400_500L, "kotlin2rtc@test")
        assertEquals(0x80, report.u8(0))
        assertEquals(200, report.u8(1))
        assertEquals(6, report.u16(2))
        assertEquals(0x0BADCAFE, report.u32(4))
        assertEquals((1_793_102_400L + 2_208_988_800L).toInt(), report.u32(8))
        // Half a second in NTP's 32-bit fraction
        assertEquals(0x80000000.toInt(), report.u32(12))
        assertEquals(5_000 + 480, report.u32(16))
        assertEquals(3, report.u32(20))
        assertEquals(480, report.u32(24))
        // SDES: one chunk, padded to 32 bits, length in words minus one
        val sdes = report.copyOfRange(28, report.size)
        assertEquals(0x81, sdes.u8(0))
        assertEquals(202, sdes.u8(1))
        assertEquals(sdes.size / 4 - 1, sdes.u16(2))
        assertEquals(0, sdes.size % 4)
        assertEquals(0x0BADCAFE, sdes.u32(4))
        assertEquals(1, sdes.u8(8))
        assertEquals(15, sdes.u8(9))
        assertEquals("kotlin2rtc@test", String(sdes, 10, 15))
        assertEquals(0, sdes.u8(25))
    }

    @Test
    fun `interleaved frames carry the channel and the length`() {
        val frame = Interleaved.frame(1, ByteArray(300) { 7 })
        assertEquals('$'.code, frame.u8(0))
        assertEquals(1, frame.u8(1))
        assertEquals(300, frame.u16(2))
        assertEquals(304, frame.size)
        assertEquals(7, frame.u8(303))
    }
}
