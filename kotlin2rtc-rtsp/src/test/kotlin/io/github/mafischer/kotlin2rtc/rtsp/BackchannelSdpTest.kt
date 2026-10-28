package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.BackchannelCodec
import io.github.mafischer.kotlin2rtc.codec.BackchannelMedia
import io.github.mafischer.kotlin2rtc.codec.G711
import io.github.mafischer.kotlin2rtc.codec.G726
import io.github.mafischer.kotlin2rtc.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * the backchannel in a camera's SDP (ONVIF Streaming 5.3): the `sendonly` audio media, its G.711 type and
 * control URL. Fixtures in kotlin2rtc-core/src/testFixtures/resources/onvif.
 */
class BackchannelSdpTest {
    @Test
    fun `a Dahua or Amcrest camera's backchannel, its control under a Content-Base with a query`() {
        // RTSP lines end in CR LF (Content-Length counts them), whatever the checkout did to the fixture's line ends
        val text = Fixtures.read("backchannel-describe-dahua.txt").replace("\r\n", "\n").replace("\n", "\r\n")
        val response = text.encodeToByteArray().inputStream()
        val incoming = Rtsp.read(response, response.read()) as Rtsp.Incoming.Response
        val base = incoming.response.header("Content-Base")!!
        val sdp = BackchannelSdp.parse(incoming.response.body, base)
        assertEquals(
            BackchannelMedia(
                "rtsp://10.20.30.204:554/cam/realmonitor?channel=1&subtype=0&unicast=true&proto=Onvif/trackID=5",
                BackchannelCodec.PCMU,
                0,
                8_000,
            ),
            sdp.media,
        )
        // Its other offer and the session's aggregate control (`*`: the base)
        assertEquals(listOf("PCMU", "PCMA"), sdp.offered)
        assertEquals(base, sdp.aggregate)
        assertEquals(160, sdp.media!!.samplesPerPacket)
    }

    @Test
    fun `a camera whose backchannel takes AAC only has none for this app, and says what it has`() {
        val sdp = BackchannelSdp.parse(Fixtures.read("backchannel-describe-aac-only.sdp"), "rtsp://192.168.1.20/stream1")
        assertNull(sdp.media)
        assertEquals(listOf("MPEG4-GENERIC"), sdp.offered)
        assertEquals("rtsp://192.168.1.20/stream1", sdp.aggregate)
    }

    @Test
    fun `a camera without a backchannel (its audio is recvonly)`() {
        val sdp = BackchannelSdp.parse(Fixtures.read("describe-without-backchannel.sdp"), "rtsp://192.168.1.30/Streaming/Channels/101")
        assertNull(sdp.media)
        assertEquals(emptyList<String>(), sdp.offered)
    }

    @Test
    fun `the first G711 type in the camera's order, by rtpmap or the static table`() {
        val sdp =
            """
            v=0
            m=audio 0 RTP/AVP 97 8 0
            a=rtpmap:97 G726-32/8000
            a=control:rtsp://cam/back
            a=sendonly
            """.trimIndent()
        val parsed = BackchannelSdp.parse(sdp, "rtsp://cam/live")
        assertEquals(BackchannelMedia("rtsp://cam/back", BackchannelCodec.PCMA, 8, 8_000), parsed.media)
        assertEquals(listOf("G726-32", "PCMA", "PCMU"), parsed.offered)
        // Without a session control, PLAY goes to the request URL
        assertEquals("rtsp://cam/live", parsed.aggregate)
    }

    @Test
    fun `dynamic G711 types at 16 kHz work, rates that are not a divisor of 48 kHz do not`() {
        val wide = "m=audio 0 RTP/AVP 96\na=rtpmap:96 pcmu/16000/1\na=control:back\na=sendonly\n"
        assertEquals(BackchannelMedia("rtsp://cam/live/back", BackchannelCodec.PCMU, 96, 16_000, "pcmu"), BackchannelSdp.parse(wide, "rtsp://cam/live").media)
        val odd = "m=audio 0 RTP/AVP 96\na=rtpmap:96 PCMU/11025\na=sendonly\n"
        assertNull(BackchannelSdp.parse(odd, "rtsp://cam/live").media)
        val unknownStatic = "m=audio 0 RTP/AVP 3\na=sendonly\n"
        assertEquals(listOf("3"), BackchannelSdp.parse(unknownStatic, "rtsp://cam/live").offered)
    }

    @Test
    fun `only a sendonly audio media is a backchannel`() {
        val sdp = "v=0\na=sendonly\nm=audio 0 RTP/AVP 0\na=sendrecv\nm=video 0 RTP/AVP 0\na=sendonly\nm=audio 0 RTP/AVP 8\na=inactive\n"
        assertNull(BackchannelSdp.parse(sdp, "rtsp://cam").media)
        // The first backchannel counts; a second one is listed only
        val two = "m=audio 0 RTP/AVP 0\na=control:a\na=sendonly\nm=audio 0 RTP/AVP 8\na=control:b\na=sendonly\n"
        val parsed = BackchannelSdp.parse(two, "rtsp://cam/")
        assertEquals("rtsp://cam/a", parsed.media?.control)
        assertEquals(listOf("PCMU", "PCMA"), parsed.offered)
    }

    @Test
    fun `control URLs are resolved as RTSP clients do`() {
        assertEquals("rtsp://cam/live", BackchannelSdp.resolve("*", "rtsp://cam/live"))
        assertEquals("rtsp://other/back", BackchannelSdp.resolve("rtsp://other/back", "rtsp://cam/live"))
        assertEquals("rtsp://cam/live/trackID=5", BackchannelSdp.resolve("trackID=5", "rtsp://cam/live"))
        assertEquals("rtsp://cam/live/trackID=5", BackchannelSdp.resolve("/trackID=5", "rtsp://cam/live/"))
        assertEquals("rtsp://cam/x?a=1/trackID=5", BackchannelSdp.resolve("trackID=5", "rtsp://cam/x?a=1/"))
    }
}
