package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.BackchannelCodec
import io.github.mafischer.kotlin2rtc.codec.BackchannelEncoder
import io.github.mafischer.kotlin2rtc.codec.BackchannelMedia
import io.github.mafischer.kotlin2rtc.codec.G711
import io.github.mafischer.kotlin2rtc.codec.Rtcp
import io.github.mafischer.kotlin2rtc.testing.FakeRtspCamera
import io.github.mafischer.kotlin2rtc.testing.expectThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * the ONVIF backchannel over RTSP end to end on real TCP against [FakeRtspCamera]: the requests and their
 * headers, Digest, SETUP of the backchannel only with TCP interleaving, RTP and RTCP arriving on the camera's channels as
 * G.711 it decodes, keep-alive, TEARDOWN, and every refusal.
 */
class RtspBackchannelClientTest {
    private val camera = FakeRtspCamera()

    // The test seam: the fake camera is on loopback, which LocalNetwork never allows
    private fun client(url: String = camera.urlWith(), keepAliveMillis: Long = 5_000) =
        RtspBackchannelClient(url, { it.isLoopbackAddress }, minKeepAliveMillis = keepAliveMillis)

    @AfterEach
    fun tearDown() {
        camera.close()
    }

    @Test
    fun `open asks for the backchannel, logs in with Digest, sets up the backchannel only, plays, and the camera hears G711`() {
        val link = client().open()
        assertEquals(BackchannelMedia("${camera.url}/trackID=5", BackchannelCodec.PCMU, 0, 8_000), link.media)
        assertEquals(listOf("DESCRIBE", "DESCRIBE", "SETUP", "PLAY"), camera.methods())
        val (challenged, describe, setup, play) = camera.requests
        // The first DESCRIBE without a login, answered 401; then Digest (the camera also offered Basic)
        assertNull(challenged.header("authorization"))
        assertTrue(describe.header("authorization")!!.startsWith("Digest username=\"admin\""))
        assertEquals("application/sdp", describe.header("accept"))
        // The ONVIF feature tag on every request of the session, the CSeq counting up, never the login in a URL
        for (request in camera.requests) {
            assertEquals(Rtsp.BACKCHANNEL_REQUIRE, request.header("require"))
            assertEquals(Rtsp.USER_AGENT, request.header("user-agent"))
            assertFalse(request.url.contains("secret"))
        }
        assertEquals(listOf("1", "2", "3", "4"), camera.requests.map { it.header("cseq") })
        assertEquals(camera.url, describe.url)
        assertEquals("${camera.url}/trackID=5", setup.url)
        assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1", setup.header("transport"))
        // PLAY on the aggregate control (`*`: the Content-Base), in the session
        assertEquals("${camera.url}/", play.url)
        assertEquals(FakeRtspCamera.SESSION, play.header("session"))

        val encoder = BackchannelEncoder(link.media)
        val tone = FloatArray(960) { (0.5 * kotlin.math.sin(2 * Math.PI * 440 * it / 48_000)).toFloat() }
        repeat(3) { link.sendRtp(encoder.packet(tone, null)) }
        link.sendRtcp(Rtcp.senderReport(encoder.rtp, System.currentTimeMillis(), "test"))
        val packets = camera.awaitFrames(channel = 0, count = 3)
        assertEquals(172, packets[0].size)
        assertEquals(0, packets[0][1].toInt() and 0x7F)
        // The camera decodes what it got: a tone around half scale (G.711 is lossy, the filter settles in the first packet)
        val pcm = G711.decode(BackchannelCodec.PCMU, packets[2].copyOfRange(12, 172))
        val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak in 13_000..19_000, "peak $peak")
        assertEquals(200, camera.awaitFrames(channel = 1, count = 1)[0][1].toInt() and 0xFF)

        link.close()
        assertTrue(camera.awaitTeardown())
        val teardown = camera.awaitRequest("TEARDOWN")
        assertEquals("${camera.url}/", teardown.url)
        assertEquals(FakeRtspCamera.SESSION, teardown.header("session"))
        assertNotNull(teardown.header("authorization"))
        // Closing again does nothing
        link.close()
    }

    @Test
    fun `the engine runs the client off the caller's thread and closes links in the background`() = kotlinx.coroutines.runBlocking {
        val engine = RtspBackchannelEngine({ it.isLoopbackAddress })
        assertEquals(BackchannelCodec.PCMU, engine.probe(camera.urlWith()).media?.codec)
        val link = engine.open(camera.urlWith())
        engine.closeLater(link)
        assertTrue(camera.awaitTeardown())
        expectThrows<BackchannelError.Unreachable> { kotlinx.coroutines.runBlocking { BackchannelEngine.Unavailable.probe(camera.url) } }
        expectThrows<BackchannelError.Unreachable> { kotlinx.coroutines.runBlocking { BackchannelEngine.Unavailable.open(camera.url) } }
        Unit
    }

    @Test
    fun `the capability check is a DESCRIBE only`() {
        val sdp = client().describe()
        assertEquals(BackchannelCodec.PCMU, sdp.media?.codec)
        assertEquals(listOf("DESCRIBE", "DESCRIBE"), camera.methods())
    }

    @Test
    fun `a camera that does not know the feature tag (551) or describes no usable backchannel`() {
        camera.describeStatus = 551
        val unknown = expectThrows<BackchannelError.Unsupported> { client().describe() }
        assertEquals(551, unknown.status)
        camera.describeStatus = 200
        camera.sdpWithBackchannel = "v=0\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=sendonly\r\n"
        assertNull(client().describe().media)
        val aacOnly = expectThrows<BackchannelError.Unsupported> { client().open() }
        assertEquals(listOf("MPEG4-GENERIC"), aacOnly.offered)
    }

    @Test
    fun `another failure of the DESCRIBE`() {
        camera.describeStatus = 404
        assertEquals(404, expectThrows<BackchannelError.Failed> { client().describe() }.status)
    }

    @Test
    fun `a speaker in use (453) and a refused SETUP`() {
        camera.setupStatus = 453
        assertEquals(453, expectThrows<BackchannelError.Busy> { client().open() }.status)
        camera.setupStatus = 461
        expectThrows<BackchannelError.Unsupported> { client().open() }
        camera.setupStatus = 500
        assertEquals(500, expectThrows<BackchannelError.Failed> { client().open() }.status)
    }

    @Test
    fun `a wrong password is refused, no login at all too`() {
        expectThrows<BackchannelError.Unauthorized> { client(camera.urlWith(pass = "wrong")).open() }
        expectThrows<BackchannelError.Unauthorized> { client(camera.url).describe() }
    }

    @Test
    fun `nothing is sent to an address the local network does not allow, or where nothing listens`() {
        expectThrows<BackchannelError.Unreachable> { RtspBackchannelClient(camera.urlWith(), { false }).describe() }
        assertEquals(0, camera.connections.get())
        val closedPort = ServerSocket(0).use { it.localPort }
        expectThrows<BackchannelError.Unreachable> { client("rtsp://admin:secret@127.0.0.1:$closedPort/live").open() }
        expectThrows<BackchannelError.Failed> { client("not a url") }
    }

    @Test
    fun `a camera that ends the session is reported once, and sending fails`() {
        camera.closeAfterPlay = true
        val link = client().open()
        val closed = CountDownLatch(1)
        link.onClosed = { closed.countDown() }
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        expectThrows<java.io.IOException> { link.sendRtp(ByteArray(20)) }
        link.close()
    }

    @Test
    fun `keep-alives in the session, OPTIONS for a camera that refuses GET_PARAMETER`() {
        camera.sessionTimeout = 1
        camera.keepAliveStatus = 405
        val link = client(keepAliveMillis = 100).open()
        val get = camera.awaitRequest("GET_PARAMETER")
        assertEquals(FakeRtspCamera.SESSION, get.header("session"))
        val options = camera.awaitRequest("OPTIONS")
        assertEquals("${camera.url}/", options.url)
        link.close()
    }
}
