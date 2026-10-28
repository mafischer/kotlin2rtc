package io.github.mafischer.kotlin2rtc.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** G.711 as the reference tables (ITU-T G.711, Sun's g711.c) compute it */
class G711Test {
    @Test
    fun `silence and full scale in mu-law`() {
        assertEquals(0xFF, G711.ulaw(0))
        assertEquals(0, G711.ulawToLinear(0xFF))
        assertEquals(-32124, G711.ulawToLinear(0x00))
        assertEquals(32124, G711.ulawToLinear(0x80))
        // Clipped beyond the largest segment
        assertEquals(0x80, G711.ulaw(Short.MAX_VALUE.toInt()))
        assertEquals(0x00, G711.ulaw(Short.MIN_VALUE.toInt()))
        assertEquals(0x80, G711.ulaw(100_000))
    }

    @Test
    fun `silence and full scale in A-law`() {
        assertEquals(0xD5, G711.alaw(0))
        assertEquals(8, G711.alawToLinear(0xD5))
        assertEquals(-8, G711.alawToLinear(0x55))
        assertEquals(32256, G711.alawToLinear(0xAA))
        assertEquals(-32256, G711.alawToLinear(0x2A))
        assertEquals(0xAA, G711.alaw(Short.MAX_VALUE.toInt()))
        assertEquals(0x2A, G711.alaw(Short.MIN_VALUE.toInt()))
    }

    @Test
    fun `every code decodes to a level that encodes to the same code`() {
        for (code in 0..255) {
            assertEquals(code, G711.alaw(G711.alawToLinear(code)), "A-law $code")
            // 0x7F is µ-law's negative zero: it decodes to 0, which is 0xFF
            if (code != 0x7F) assertEquals(code, G711.ulaw(G711.ulawToLinear(code)), "µ-law $code")
        }
    }

    @Test
    fun `the quantization error stays within the segment's half step`() {
        var sample = Short.MIN_VALUE.toInt()
        while (sample <= Short.MAX_VALUE) {
            val bound = abs(sample) / 16 + 16
            assertTrue(abs(G711.ulawToLinear(G711.ulaw(sample)) - sample) <= bound, "µ-law $sample")
            assertTrue(abs(G711.alawToLinear(G711.alaw(sample)) - sample) <= bound, "A-law $sample")
            sample += 7
        }
    }

    @Test
    fun `codes grow with the level`() {
        val levels = (-32_000..32_000 step 500).toList()
        val ulaw = levels.map { G711.ulawToLinear(G711.ulaw(it)) }
        val alaw = levels.map { G711.alawToLinear(G711.alaw(it)) }
        assertEquals(ulaw.sorted(), ulaw)
        assertEquals(alaw.sorted(), alaw)
    }

    @Test
    fun `float blocks are encoded with the codec the camera asked for, clamped`() {
        val samples = floatArrayOf(0f, 1f, -1f, 2f, -0.5f)
        val ulaw = ByteArray(samples.size)
        G711.encode(BackchannelCodec.PCMU, samples, ulaw)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00, 0x80.toByte(), G711.ulaw(-16_384).toByte()), ulaw)
        val alaw = ByteArray(samples.size)
        G711.encode(BackchannelCodec.PCMA, samples, alaw)
        assertEquals(0xD5.toByte(), alaw[0])
        val decoded = G711.decode(BackchannelCodec.PCMA, alaw)
        assertEquals(5, decoded.size)
        assertTrue(abs(decoded[4] + 16_384) < 600)
    }
}
