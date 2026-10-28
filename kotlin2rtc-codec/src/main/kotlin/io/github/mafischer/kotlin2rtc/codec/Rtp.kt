package io.github.mafischer.kotlin2rtc.codec

import java.security.SecureRandom

/**
 * RTP packets of the backchannel (RFC 3550, RFC 3551 audio): version 2, no padding, extension or CSRC, the codec's
 * payload type, one 20 ms frame per packet. The SSRC, first sequence number and first timestamp are random; the
 * sequence number counts packets, the timestamp samples (both wrap). The first packet of a talkspurt has the marker bit.
 */
public class RtpPacketizer(
    private val payloadType: Int,
    public val ssrc: Int,
    initialSequence: Int,
    initialTimestamp: Int,
) {
    private var sequence = initialSequence and 0xFFFF
    private var timestamp = initialTimestamp

    /** Packets and payload bytes sent so far (for the RTCP sender report) */
    public var packetCount: Long = 0L
        private set
    public var octetCount: Long = 0L
        private set

    /** The timestamp the next packet carries */
    public val nextTimestamp: Int get() = timestamp
    private var marker = true

    /**
     * One RTP packet with [payload] ([samples] samples of audio); [marker] as given, else on the first packet of a
     * talkspurt
     */
    public fun packet(payload: ByteArray, samples: Int, marker: Boolean? = null): ByteArray {
        val packet = ByteArray(HEADER_BYTES + payload.size)
        packet[0] = (VERSION shl 6).toByte()
        packet[1] = ((if (marker ?: this.marker) 0x80 else 0) or (payloadType and 0x7F)).toByte()
        packet[2] = (sequence shr 8).toByte()
        packet[3] = sequence.toByte()
        writeInt(packet, 4, timestamp)
        writeInt(packet, 8, ssrc)
        payload.copyInto(packet, HEADER_BYTES)
        this.marker = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samples
        packetCount += 1
        octetCount += payload.size
        return packet
    }

    /** Nothing was sent for [samples] samples: the clock moves on and the next packet starts a talkspurt */
    public fun skip(samples: Int) {
        timestamp += samples
        marker = true
    }

    public companion object {
        public const val VERSION: Int = 2
        public const val HEADER_BYTES: Int = 12

        /** A packetizer with RFC 3550's random SSRC, sequence number and timestamp */
        public fun random(payloadType: Int, random: SecureRandom = SecureRandom()): RtpPacketizer =
            RtpPacketizer(payloadType, random.nextInt(), random.nextInt(0x10000), random.nextInt())

        internal fun writeInt(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }
    }
}

/** RTCP for the backchannel's sender (RFC 3550 6.4.1 and 6.5): a compound sender report + SDES CNAME, every few seconds */
public object Rtcp {
    private const val SENDER_REPORT = 200
    private const val SOURCE_DESCRIPTION = 202
    private const val CNAME = 1

    /** Seconds from 1900 (NTP) to 1970 (Unix) */
    private const val NTP_EPOCH_OFFSET = 2_208_988_800L

    /** A compound RTCP packet: SR of [rtp]'s stream at [wallClockMillis], then SDES with [cname] */
    public fun senderReport(rtp: RtpPacketizer, wallClockMillis: Long, cname: String): ByteArray {
        val report = ByteArray(28)
        report[0] = (RtpPacketizer.VERSION shl 6).toByte()
        report[1] = SENDER_REPORT.toByte()
        report[3] = 6
        RtpPacketizer.writeInt(report, 4, rtp.ssrc)
        val seconds = wallClockMillis / 1000 + NTP_EPOCH_OFFSET
        val fraction = (wallClockMillis % 1000) * (1L shl 32) / 1000
        RtpPacketizer.writeInt(report, 8, seconds.toInt())
        RtpPacketizer.writeInt(report, 12, fraction.toInt())
        RtpPacketizer.writeInt(report, 16, rtp.nextTimestamp)
        RtpPacketizer.writeInt(report, 20, rtp.packetCount.toInt())
        RtpPacketizer.writeInt(report, 24, rtp.octetCount.toInt())
        val name = cname.encodeToByteArray().take(255).toByteArray()
        // SSRC + CNAME item + end of list, padded to 32 bits
        val itemBytes = 4 + 2 + name.size + 1
        val padded = (itemBytes + 3) / 4 * 4
        val sdes = ByteArray(4 + padded)
        sdes[0] = ((RtpPacketizer.VERSION shl 6) or 1).toByte()
        sdes[1] = SOURCE_DESCRIPTION.toByte()
        val words = padded / 4
        sdes[2] = (words shr 8).toByte()
        sdes[3] = words.toByte()
        RtpPacketizer.writeInt(sdes, 4, rtp.ssrc)
        sdes[8] = CNAME.toByte()
        sdes[9] = name.size.toByte()
        name.copyInto(sdes, 10)
        return report + sdes
    }
}

/** RTP and RTCP inside the RTSP TCP connection (RFC 2326 10.12): `$`, the channel, a 16-bit length, the packet */
public object Interleaved {
    public const val MAGIC: Int = '$'.code

    public fun frame(channel: Int, packet: ByteArray): ByteArray {
        val frame = ByteArray(4 + packet.size)
        frame[0] = MAGIC.toByte()
        frame[1] = channel.toByte()
        frame[2] = (packet.size shr 8).toByte()
        frame[3] = packet.size.toByte()
        packet.copyInto(frame, 4)
        return frame
    }
}
