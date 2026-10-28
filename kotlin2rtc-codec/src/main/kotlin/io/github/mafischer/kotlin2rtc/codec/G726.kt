package io.github.mafischer.kotlin2rtc.codec

import kotlin.math.abs

/** A G.726 flavour: bits per sample (2..5 = 16/24/32/40 kbit/s) and how the codewords are packed */
public data class G726Format(val bits: Int, val bigEndian: Boolean) {
    public companion object {
        /** `G726-32` (RFC 3551: first codeword in the low bits), `AAL2-G726-32` (ITU I.366.2: high bits first) */
        public fun of(encoding: String): G726Format? {
            val upper = encoding.uppercase()
            val aal2 = upper.startsWith("AAL2-")
            val rate = upper.removePrefix("AAL2-").removePrefix("G726-").toIntOrNull() ?: return null
            val bits = when (rate) {
                16 -> 2
                24 -> 3
                32 -> 4
                40 -> 5
                else -> return null
            }
            return G726Format(bits, aal2)
        }
    }
}

/**
 * ITU-T G.726 ADPCM at 8 kHz, one state per direction: the adaptive quantizer, the two-pole six-zero predictor and the
 * speed control of the reference implementation (Sun Microsystems' public-domain g72x.c: G.721 for 32 kbit/s, G.723
 * for 24 and 40; the 16 kbit/s tables of G.726 Annex). [encode] turns a 16-bit sample into a [bits]-bit codeword,
 * [decode] back (fake cameras and the tests listen with it). Not thread-safe.
 */
public class G726(public val bits: Int) {
    private val tables = Tables.of(bits)
    private var yl = 34_816
    private var yu = 544
    private var dms = 0
    private var dml = 0
    private var ap = 0
    private val a = IntArray(2)
    private val b = IntArray(6)
    private val pk = IntArray(2)
    private val dq = IntArray(6) { 32 }
    private val sr = intArrayOf(32, 32)
    private var td = 0

    /** A 16-bit linear sample as a codeword */
    public fun encode(pcm: Int): Int {
        val sl = pcm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()) shr 2
        val sezi = predictorZero()
        val sez = sezi shr 1
        val se = (sezi + predictorPole()) shr 1
        val y = stepSize()
        val code = quantize(sl - se, y)
        step(code, y, se, sez)
        return code
    }

    /** A codeword as a 16-bit linear sample */
    public fun decode(code: Int): Int {
        val masked = code and ((1 shl bits) - 1)
        val sezi = predictorZero()
        val sez = sezi shr 1
        val se = (sezi + predictorPole()) shr 1
        val y = stepSize()
        return step(masked, y, se, sez) shl 2
    }

    /** The reconstruction and the state update of a codeword; the reconstructed signal */
    private fun step(code: Int, y: Int, se: Int, sez: Int): Int {
        val dqValue = reconstruct(code and (1 shl (bits - 1)) != 0, tables.dqln[code], y)
        val srValue = if (dqValue < 0) se - (dqValue and 0x3FFF) else se + dqValue
        update(y, tables.wi[code], tables.fi[code], dqValue, srValue, srValue + sez - se)
        return srValue
    }

    private fun quantize(d: Int, y: Int): Int {
        val dqm = abs(d)
        val exp = quan(dqm shr 1, POWER2)
        val mant = ((dqm shl 7) shr exp) and 0x7F
        val dln = (exp shl 7) + mant - (y shr 2)
        val i = quan(dln, tables.quant)
        val size = tables.quant.size
        return when {
            d < 0 -> (size shl 1) + 1 - i
            // The odd-level quantizers code a positive zero as the negative one; 16 kbit/s has no zero level
            i == 0 && bits != 2 -> (size shl 1) + 1
            else -> i
        }
    }

    private fun reconstruct(negative: Boolean, dqln: Int, y: Int): Int {
        val dql = dqln + (y shr 2)
        if (dql < 0) return if (negative) -0x8000 else 0
        val dex = (dql shr 7) and 15
        val dqt = 128 + (dql and 127)
        val value = (dqt shl 7) shr (14 - dex)
        return if (negative) value - 0x8000 else value
    }

    private fun predictorZero(): Int = (0 until 6).sumOf { fmult(b[it] shr 2, dq[it]) }

    private fun predictorPole(): Int = fmult(a[1] shr 2, sr[1]) + fmult(a[0] shr 2, sr[0])

    private fun stepSize(): Int {
        if (ap >= 256) return yu
        var y = yl shr 6
        val dif = yu - y
        val al = ap shr 2
        if (dif > 0) {
            y += (dif * al) shr 6
        } else if (dif < 0) {
            y += (dif * al + 0x3F) shr 6
        }
        return y
    }

    private fun update(y: Int, wi: Int, fi: Int, dqValue: Int, srValue: Int, dqsez: Int) {
        val pk0 = if (dqsez < 0) 1 else 0
        val mag = dqValue and 0x7FFF
        val tr = transition(mag)
        yu = (y + ((wi - y) shr 5)).coerceIn(544, 5120)
        yl += yu + ((-yl) shr 6)
        var a2p = 0
        if (tr) {
            a.fill(0)
            b.fill(0)
        } else {
            a2p = updatePoles(pk0, dqsez)
            updateZeros(dqValue)
        }
        for (i in 5 downTo 1) dq[i] = dq[i - 1]
        dq[0] = floatFormat(dqValue, mag)
        sr[1] = sr[0]
        sr[0] = floatFormat(srValue)
        pk[1] = pk[0]
        pk[0] = pk0
        td = if (!tr && a2p < -11_776) 1 else 0
        dms += (fi - dms) shr 5
        dml += ((fi shl 2) - dml) shr 7
        ap = when {
            tr -> 256
            y < 1536 || td == 1 || abs((dms shl 2) - dml) >= (dml shr 3) -> ap + ((0x200 - ap) shr 4)
            else -> ap + ((-ap) shr 4)
        }
    }

    /** A tone transition (G.726 4.2.8): the predictor starts over */
    private fun transition(mag: Int): Boolean {
        if (td == 0) return false
        val ylint = yl shr 15
        val ylfrac = (yl shr 10) and 0x1F
        val thr1 = (32 + ylfrac) shl ylint
        val thr2 = if (ylint > 9) 31 shl 10 else thr1
        val dqthr = (thr2 + (thr2 shr 1)) shr 1
        return mag > dqthr
    }

    private fun updatePoles(pk0: Int, dqsez: Int): Int {
        val pks1 = pk0 xor pk[0]
        var a2p = a[1] - (a[1] shr 7)
        if (dqsez != 0) {
            val fa1 = if (pks1 != 0) a[0] else -a[0]
            a2p += when {
                fa1 < -8191 -> -0x100
                fa1 > 8191 -> 0xFF
                else -> fa1 shr 5
            }
            a2p = leakA2(a2p, signsDiffer = pk0 xor pk[1] != 0)
        }
        a[1] = a2p
        a[0] -= a[0] shr 8
        if (dqsez != 0) a[0] += if (pks1 == 0) 192 else -192
        val a1ul = 15_360 - a2p
        a[0] = a[0].coerceIn(-a1ul, a1ul)
        return a2p
    }

    /** The second pole's step toward the partial signal's sign, within its limits */
    private fun leakA2(a2p: Int, signsDiffer: Boolean): Int = if (signsDiffer) {
        when {
            a2p <= -12_160 -> -12_288
            a2p >= 12_416 -> 12_288
            else -> a2p - 0x80
        }
    } else {
        when {
            a2p <= -12_416 -> -12_288
            a2p >= 12_160 -> 12_288
            else -> a2p + 0x80
        }
    }

    private fun updateZeros(dqValue: Int) {
        for (i in 0 until 6) {
            b[i] -= if (bits == 5) b[i] shr 9 else b[i] shr 8
            if (dqValue and 0x7FFF != 0) b[i] += if ((dqValue xor dq[i]) >= 0) 128 else -128
        }
    }

    public companion object {
        private val POWER2 = intArrayOf(1, 2, 4, 8, 0x10, 0x20, 0x40, 0x80, 0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000)

        /** The index of the first entry above [value], or the table's size */
        private fun quan(value: Int, table: IntArray): Int {
            for (i in table.indices) if (value < table[i]) return i
            return table.size
        }

        /** The predictor's multiply in the reference's floating format */
        private fun fmult(an: Int, srn: Int): Int {
            val anmag = if (an > 0) an else (-an) and 0x1FFF
            val anexp = quan(anmag, POWER2) - 6
            val anmant = when {
                anmag == 0 -> 32
                anexp >= 0 -> anmag shr anexp
                else -> anmag shl -anexp
            }
            val wanexp = anexp + ((srn shr 6) and 0xF) - 13
            val wanmant = (anmant * (srn and 0x3F) + 0x30) shr 4
            val result = if (wanexp >= 0) (wanmant shl wanexp) and 0x7FFF else wanmant shr -wanexp
            return if ((an xor srn) < 0) -result else result
        }

        /** A signed value in the 11-bit floating format the predictor keeps (0xFC20 is -0) */
        private fun floatFormat(value: Int, magnitude: Int = abs(value)): Int {
            if (value == 0 || magnitude == 0) return if (value >= 0) 0x20 else NEGATIVE_ZERO
            if (value <= -32_768) return NEGATIVE_ZERO
            val exp = quan(magnitude, POWER2)
            val format = (exp shl 6) + ((magnitude shl 6) shr exp)
            return if (value >= 0) format else format - 0x400
        }

        /** 0xFC20 as the reference's signed short */
        private const val NEGATIVE_ZERO = 0xFC20 - 0x10000
    }

    /** The quantizer, inverse quantizer, scale-factor and speed-control tables of one bit rate */
    private class Tables(val quant: IntArray, val dqln: IntArray, val wi: IntArray, val fi: IntArray) {
        public companion object {
            public fun of(bits: Int): Tables = when (bits) {
                2 -> Tables(intArrayOf(261), intArrayOf(116, 365, 365, 116), intArrayOf(-704, 14_048, 14_048, -704), intArrayOf(0, 0xE00, 0xE00, 0))
                3 -> Tables(
                    intArrayOf(8, 218, 331),
                    intArrayOf(-2048, 135, 273, 373, 373, 273, 135, -2048),
                    intArrayOf(-128, 960, 4384, 18_624, 18_624, 4384, 960, -128),
                    intArrayOf(0, 0x200, 0x400, 0xE00, 0xE00, 0x400, 0x200, 0),
                )
                4 -> Tables(
                    intArrayOf(-124, 80, 178, 246, 300, 349, 400),
                    intArrayOf(-2048, 4, 135, 213, 273, 323, 373, 425, 425, 373, 323, 273, 213, 135, 4, -2048),
                    intArrayOf(-12, 18, 41, 64, 112, 198, 355, 1122, 1122, 355, 198, 112, 64, 41, 18, -12).map { it shl 5 }.toIntArray(),
                    intArrayOf(0, 0, 0, 0x200, 0x200, 0x200, 0x600, 0xE00, 0xE00, 0x600, 0x200, 0x200, 0x200, 0, 0, 0),
                )
                5 -> Tables(
                    intArrayOf(-122, -16, 68, 139, 198, 250, 298, 339, 378, 413, 445, 475, 502, 528, 553),
                    intArrayOf(
                        -2048,
                        -66,
                        28,
                        104,
                        169,
                        224,
                        274,
                        318,
                        358,
                        395,
                        429,
                        459,
                        488,
                        514,
                        539,
                        566,
                        566,
                        539,
                        514,
                        488,
                        459,
                        429,
                        395,
                        358,
                        318,
                        274,
                        224,
                        169,
                        104,
                        28,
                        -66,
                        -2048,
                    ),
                    intArrayOf(
                        448,
                        448,
                        768,
                        1248,
                        1280,
                        1312,
                        1856,
                        3200,
                        4512,
                        5728,
                        7008,
                        8960,
                        11_456,
                        14_080,
                        16_928,
                        22_272,
                        22_272,
                        16_928,
                        14_080,
                        11_456,
                        8960,
                        7008,
                        5728,
                        4512,
                        3200,
                        1856,
                        1312,
                        1280,
                        1248,
                        768,
                        448,
                        448,
                    ),
                    intArrayOf(
                        0,
                        0,
                        0,
                        0,
                        0,
                        0x200,
                        0x200,
                        0x200,
                        0x200,
                        0x200,
                        0x400,
                        0x600,
                        0x800,
                        0xA00,
                        0xC00,
                        0xC00,
                        0xC00,
                        0xC00,
                        0xA00,
                        0x800,
                        0x600,
                        0x400,
                        0x200,
                        0x200,
                        0x200,
                        0x200,
                        0x200,
                        0,
                        0,
                        0,
                        0,
                        0,
                    ),
                )
                else -> throw IllegalArgumentException("G.726 has 2 to 5 bits per sample, not $bits")
            }
        }
    }
}

/** G.726 codewords in octets: RFC 3551 (first codeword in the least significant bits) or AAL2 (most significant first) */
public object G726Packing {
    public fun pack(codes: IntArray, bits: Int, bigEndian: Boolean): ByteArray {
        val out = ByteArray((codes.size * bits + 7) / 8)
        var accumulator = 0
        var filled = 0
        var index = 0
        for (code in codes) {
            val value = code and ((1 shl bits) - 1)
            accumulator = if (bigEndian) (accumulator shl bits) or value else accumulator or (value shl filled)
            filled += bits
            while (filled >= 8) {
                out[index++] = if (bigEndian) (accumulator shr (filled - 8)).toByte() else accumulator.toByte()
                filled -= 8
                accumulator = if (bigEndian) accumulator and ((1 shl filled) - 1) else accumulator ushr 8
            }
        }
        if (filled > 0) out[index] = if (bigEndian) (accumulator shl (8 - filled)).toByte() else accumulator.toByte()
        return out
    }

    public fun unpack(bytes: ByteArray, bits: Int, bigEndian: Boolean): IntArray {
        val count = bytes.size * 8 / bits
        val codes = IntArray(count)
        var accumulator = 0
        var filled = 0
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            accumulator = if (bigEndian) (accumulator shl 8) or value else accumulator or (value shl filled)
            filled += 8
            while (filled >= bits && index < count) {
                if (bigEndian) {
                    codes[index++] = (accumulator shr (filled - bits)) and ((1 shl bits) - 1)
                    filled -= bits
                    accumulator = accumulator and ((1 shl filled) - 1)
                } else {
                    codes[index++] = accumulator and ((1 shl bits) - 1)
                    accumulator = accumulator ushr bits
                    filled -= bits
                }
            }
        }
        return codes
    }
}
