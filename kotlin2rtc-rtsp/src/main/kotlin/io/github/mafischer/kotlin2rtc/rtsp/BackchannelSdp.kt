package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.AacFormat
import io.github.mafischer.kotlin2rtc.codec.BackchannelCodec
import io.github.mafischer.kotlin2rtc.codec.BackchannelMedia
import io.github.mafischer.kotlin2rtc.codec.G726Format

/** What a DESCRIBE with the backchannel requirement showed */
public data class SdpBackchannel(
    /** The backchannel to use, or null when there is none kotlin2rtc can send to */
    public val media: BackchannelMedia?,
    /** The encodings of every `sendonly` audio media (e.g. `MPEG4-GENERIC`, `G726-32`): why [media] is null */
    public val offered: List<String>,
    /** The session's aggregate control URL (PLAY and TEARDOWN go there) */
    public val aggregate: String = "",
)

/**
 * Reads an ONVIF camera's SDP (ONVIF Streaming Specification 5.3, "Back channel connection"): the backchannel is an
 * audio media the camera marks `a=sendonly` (the client sends, the camera receives); the camera's own streams are
 * `recvonly` or carry no direction. Its payload types come from the `m=` line, named by `a=rtpmap` or by the static
 * RTP/AVP table (0 = PCMU/8000, 8 = PCMA/8000). Of every type kotlin2rtc can send ([usable]), the best codec wins
 * ([BackchannelCodec] order, G.726 by bit rate), the camera's order among equals.
 */
public object BackchannelSdp {
    private val usableRates = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

    /**
     * The backchannel in [sdp]; relative control URLs are taken against [base] (the DESCRIBE's Content-Base or
     * Content-Location, else the request URL), as RTSP clients do: appended after a slash
     */
    public fun parse(sdp: String, base: String): SdpBackchannel {
        val lines = sdp.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val firstMedia = lines.indexOfFirst { it.startsWith("m=") }
        val session = if (firstMedia < 0) lines else lines.subList(0, firstMedia)
        val aggregate = session.attribute("control")?.let { resolve(it, base) } ?: base
        val offered = mutableListOf<String>()
        val candidates = mutableListOf<BackchannelMedia>()
        for (section in mediaSections(lines)) {
            val media = section.first().removePrefix("m=").split(' ').filter { it.isNotEmpty() }
            if (media.firstOrNull() != "audio" || !section.isSendOnly()) continue
            val rtpmaps = section.attributes("rtpmap").mapNotNull(::rtpmap).toMap()
            val fmtps = section.attributes("fmtp").mapNotNull(::fmtp).toMap()
            val control = section.attribute("control")?.let { resolve(it, aggregate) } ?: aggregate
            for (type in media.drop(3).mapNotNull { it.toIntOrNull() }) {
                val format = rtpmaps[type] ?: staticFormat(type)
                offered += format?.name ?: type.toString()
                format?.let { usable(control, type, it, fmtps[type].orEmpty()) }?.let(candidates::add)
            }
        }
        return SdpBackchannel(candidates.minByOrNull(::rank), offered.distinct(), aggregate)
    }

    private data class Format(val name: String, val rate: Int?, val channels: Int)

    /** A payload type kotlin2rtc can send, or null (unknown codec, an unusable rate, AAC that is not AAC-LC...) */
    private fun usable(control: String, type: Int, format: Format, fmtp: Map<String, String>): BackchannelMedia? {
        val codec = BackchannelCodec.fromEncoding(format.name) ?: return null
        val rate = format.rate?.takeIf { it in usableRates } ?: return null
        if (format.channels !in 1..2) return null
        val media = BackchannelMedia(control, codec, type, rate, format.name, format.channels, fmtp)
        return media.takeIf { codecFits(it) }
    }

    private fun codecFits(media: BackchannelMedia): Boolean = when (media.codec) {
        BackchannelCodec.PCMU, BackchannelCodec.PCMA -> media.channels == 1
        BackchannelCodec.MPEG4_GENERIC, BackchannelCodec.MP4A_LATM -> AacFormat.of(media) != null
        BackchannelCodec.G726 -> media.clockRate == 8_000 && media.channels == 1 && G726Format.of(media.encoding) != null
        BackchannelCodec.L16 -> true
    }

    /** G.711 first (µ-law and A-law alike: the camera's order), then AAC, G.726 (more bits first), L16 */
    private fun rank(media: BackchannelMedia): Int {
        val g726 = if (media.codec == BackchannelCodec.G726) G726Format.of(media.encoding)?.let { 5 - it.bits } ?: 0 else 0
        val group = if (media.codec.isG711) 0 else media.codec.ordinal - 1
        return group * 10 + g726
    }

    /** The media sections, each starting with its `m=` line */
    private fun mediaSections(lines: List<String>): List<List<String>> {
        val sections = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (line.startsWith("m=")) sections += mutableListOf(line) else sections.lastOrNull()?.add(line)
        }
        return sections
    }

    /** The section says it only sends from the client's side (`a=sendonly`; `a=sendrecv`, `recvonly` and `inactive` do not) */
    private fun List<String>.isSendOnly(): Boolean = any { it.equals("a=sendonly", ignoreCase = true) }

    private fun List<String>.attribute(name: String): String? = attributes(name).firstOrNull()

    private fun List<String>.attributes(name: String): List<String> =
        filter { it.startsWith("a=$name:", ignoreCase = true) }.map { it.substring(name.length + 3).trim() }

    /** `97 PCMU/16000[/1]` → 97 to (PCMU, 16000, 1) */
    private fun rtpmap(value: String): Pair<Int, Format>? {
        val type = value.substringBefore(' ').toIntOrNull() ?: return null
        val parts = value.substringAfter(' ', "").trim().split('/')
        val name = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return type to Format(name, parts.getOrNull(1)?.toIntOrNull(), parts.getOrNull(2)?.toIntOrNull() ?: 1)
    }

    /** `96 streamtype=5; mode=AAC-hbr; config=1408` → 96 to its parameters, names lower case */
    private fun fmtp(value: String): Pair<Int, Map<String, String>>? {
        val type = value.substringBefore(' ').toIntOrNull() ?: return null
        val params = value.substringAfter(' ', "").split(';').map { it.trim() }.filter { '=' in it }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
        return type to params
    }

    /** RFC 3551's static payload types kotlin2rtc knows */
    private fun staticFormat(type: Int): Format? = when (type) {
        0 -> Format("PCMU", 8_000, 1)
        8 -> Format("PCMA", 8_000, 1)
        else -> null
    }

    /** An absolute control as is, `*` the base, anything else appended to the base after a slash */
    public fun resolve(control: String, base: String): String = when {
        control == "*" -> base
        control.contains("://") -> control
        base.endsWith("/") -> base + control.removePrefix("/")
        else -> "$base/${control.removePrefix("/")}"
    }
}
