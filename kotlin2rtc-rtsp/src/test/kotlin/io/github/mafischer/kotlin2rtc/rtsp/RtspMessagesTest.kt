package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.testing.expectThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** RTSP requests with the backchannel requirement, and what a camera sends back on the connection */
class RtspMessagesTest {
    private fun read(text: String): Rtsp.Incoming = read(text.encodeToByteArray())

    private fun read(bytes: ByteArray): Rtsp.Incoming {
        val input = ByteArrayInputStream(bytes)
        return Rtsp.read(input, input.read())
    }

    @Test
    fun `a request has its request line, CSeq, User-Agent, the headers in order, then the body`() {
        val describe = Rtsp.request("DESCRIBE", "rtsp://10.20.30.204/live", 2, listOf("Accept" to "application/sdp", "Require" to Rtsp.BACKCHANNEL_REQUIRE))
        assertEquals(
            "DESCRIBE rtsp://10.20.30.204/live RTSP/1.0\r\nCSeq: 2\r\nUser-Agent: kotlin2rtc\r\nAccept: application/sdp\r\n" +
                "Require: www.onvif.org/ver20/backchannel\r\n\r\n",
            describe,
        )
        val withBody = Rtsp.request("SET_PARAMETER", "rtsp://cam", 9, body = "é: 1\r\n")
        assertEquals("SET_PARAMETER rtsp://cam RTSP/1.0\r\nCSeq: 9\r\nUser-Agent: kotlin2rtc\r\nContent-Length: 7\r\n\r\né: 1\r\n", withBody)
        // A User-Agent of the caller's
        assertEquals("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: my-app/2\r\n\r\n", Rtsp.request("OPTIONS", "*", 1, userAgent = "my-app/2"))
    }

    @Test
    fun `a response with repeated headers, the session and its timeout, and a body`() {
        val incoming = read(
            "RTSP/1.0 401 Unauthorized\r\nCSeq: 3\r\nWWW-Authenticate: Digest realm=\"Login to cam\", nonce=\"abc\"\r\n" +
                "WWW-Authenticate: Basic realm=\"Login to cam\"\r\nSession: 1234ABCD;timeout=30\r\nContent-Length: 5\r\n\r\nhello",
        ) as Rtsp.Incoming.Response
        val response = incoming.response
        assertEquals(401, response.status)
        assertEquals("Unauthorized", response.reason)
        assertEquals(3, response.cseq)
        assertEquals(2, response.headers("www-authenticate").size)
        assertEquals("1234ABCD", response.session)
        assertEquals(30, response.sessionTimeoutSeconds)
        assertEquals("hello", response.body)
        val bare = (read("RTSP/1.0 200 OK\nSession: 99\n\n") as Rtsp.Incoming.Response).response
        assertEquals(RtspResponse.DEFAULT_SESSION_TIMEOUT_SECONDS, bare.sessionTimeoutSeconds)
        assertNull(bare.cseq)
        assertEquals("99", bare.session)
    }

    @Test
    fun `interleaved packets are skipped whole, a server's request is told apart`() {
        val packet = byteArrayOf('$'.code.toByte(), 1, 0, 3, 9, 9, 9)
        assertEquals(Rtsp.Incoming.Packet(1, 3), read(packet + "RTSP/1.0 200 OK\r\n\r\n".encodeToByteArray()))
        assertEquals(Rtsp.Incoming.Request, read("GET_PARAMETER rtsp://phone RTSP/1.0\r\nCSeq: 1\r\n\r\n"))
    }

    @Test
    fun `an ended or broken stream fails`() {
        expectThrows<IOException> { read(ByteArray(0)) }
        expectThrows<IOException> { read("RTSP/1.0 200 OK\r\nCSeq: 1\r\n") }
        expectThrows<IOException> { read(byteArrayOf('$'.code.toByte(), 0, 0, 10, 1)) }
        expectThrows<IOException> { read("RTSP/1.0 2xx OK\r\n\r\n") }
        expectThrows<IOException> { read("RTSP/1.0 200 OK\r\nContent-Length: 999999\r\n\r\n") }
    }

    @Test
    fun `the interleaved channels the camera chose`() {
        assertEquals(0 to 1, RtspBackchannelClient.interleavedChannels(null))
        assertEquals(0 to 1, RtspBackchannelClient.interleavedChannels("RTP/AVP/TCP;unicast"))
        assertEquals(2 to 3, RtspBackchannelClient.interleavedChannels("RTP/AVP/TCP;unicast;interleaved=2-3;ssrc=1234"))
        assertEquals(4 to 5, RtspBackchannelClient.interleavedChannels("RTP/AVP/TCP;interleaved=4"))
    }
}
