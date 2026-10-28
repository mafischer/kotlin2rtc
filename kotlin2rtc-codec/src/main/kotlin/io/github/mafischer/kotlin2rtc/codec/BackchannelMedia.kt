package io.github.mafischer.kotlin2rtc.codec

/**
 * The audio codecs kotlin2rtc sends on a camera's backchannel, best first: G.711 (every ONVIF camera with a speaker takes
 * it, no encoder delay), AAC (RFC 3640 `mpeg4-generic` AAC-hbr/AAC-lbr, RFC 6416 `MP4A-LATM`; through an [AacEncoder] the platform
 * supplies), G.726 ADPCM (16/24/32/40 kbit/s, RFC 3551 packing or the AAL2 variant), then L16 linear PCM.
 */
public enum class BackchannelCodec(public val encoding: String) {
    /** µ-law */
    PCMU("PCMU"),

    /** A-law */
    PCMA("PCMA"),

    /** AAC in RFC 3640 access units (`mpeg4-generic`, mode AAC-hbr or AAC-lbr) */
    MPEG4_GENERIC("MPEG4-GENERIC"),

    /** AAC in LATM (RFC 6416 `MP4A-LATM`), configuration out of band (`cpresent=0`) */
    MP4A_LATM("MP4A-LATM"),

    /** G.726 ADPCM; the bit rate and packing come from the encoding name (`G726-32`, `AAL2-G726-24`) */
    G726("G726"),

    /** 16-bit linear PCM, network byte order */
    L16("L16"),
    ;

    public val isG711: Boolean get() = this == PCMU || this == PCMA

    public companion object {
        /** The codec of an rtpmap encoding name (`G726-32` and `AAL2-G726-32` are G.726), or null */
        public fun fromEncoding(name: String?): BackchannelCodec? {
            val upper = name?.uppercase() ?: return null
            return when {
                upper.startsWith("G726-") || upper.startsWith("AAL2-G726-") -> G726
                else -> entries.firstOrNull { it.encoding == upper }
            }
        }
    }
}

/** The camera's backchannel as its SDP describes it: where to SETUP it and how to send */
public data class BackchannelMedia(
    /** The media's control URL (absolute, without user info) */
    public val control: String,
    public val codec: BackchannelCodec,
    public val payloadType: Int,
    /** RTP clock rate = sample rate (a divisor of 48 000 from 8 kHz) */
    public val clockRate: Int,
    /** The rtpmap encoding name as the camera wrote it (`G726-32`, `mpeg4-generic`) */
    public val encoding: String = codec.encoding,
    public val channels: Int = 1,
    /** The `a=fmtp` parameters, names lower case (`mode`, `config`, `sizelength`, `cpresent`...) */
    public val fmtp: Map<String, String> = emptyMap(),
) {
    /** Samples per 20 ms block (G.711, G.726 and L16 send one packet per block; AAC one per 1024-sample frame) */
    public val samplesPerPacket: Int get() = clockRate / PACKETS_PER_SECOND

    /** `codec/rate` (with G.726's bit rate), for logs and the remembered capability */
    public val label: String get() = "${if (codec == BackchannelCodec.G726) encoding.uppercase() else codec.encoding}/$clockRate"

    public companion object {
        public const val PACKETS_PER_SECOND: Int = 50
    }
}
