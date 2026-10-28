package io.github.mafischer.kotlin2rtc.core

import io.github.mafischer.kotlin2rtc.core.HttpRequest
import io.github.mafischer.kotlin2rtc.testing.expectThrows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The cleartext HTTP client of ONVIF: real sockets against a local server, this network's subnets only, the three
 * ways a camera ends its body, timeouts and limits.
 */
private const val SOAP_CONTENT_TYPE = "application/soap+xml; charset=utf-8"

class LanHttpTransportTest {
    private val server = MockWebServer()

    // The test seam: the local server is on loopback, which LocalNetwork never allows
    private val transport = LanHttpTransport({ it.isLoopbackAddress })

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun post(path: String = "/onvif/device_service", timeout: Long? = null) = HttpRequest(
        "POST",
        server.url(path).toString(),
        mapOf("Content-Type" to SOAP_CONTENT_TYPE),
        "<s:Envelope/>".encodeToByteArray(),
        timeout,
    )

    @Test
    fun `a SOAP request goes out as HTTP 1_1 and its answer comes back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", SOAP_CONTENT_TYPE).setBody("<ok/>"))
        val response = transport.send(post("/onvif/device_service?x=1"))
        assertEquals(200, response.status)
        assertEquals("<ok/>", response.text)
        assertEquals(SOAP_CONTENT_TYPE, response.header("content-type"))
        val recorded = server.takeRequest()
        assertEquals("POST /onvif/device_service?x=1 HTTP/1.1", recorded.requestLine)
        assertEquals("<s:Envelope/>", recorded.body.readUtf8())
        assertEquals(SOAP_CONTENT_TYPE, recorded.getHeader("Content-Type"))
        assertEquals("13", recorded.getHeader("Content-Length"))
        assertEquals("close", recorded.getHeader("Connection"))
        assertEquals("${server.hostName}:${server.port}", recorded.getHeader("Host"))
    }

    @Test
    fun `chunked bodies, error statuses and the first of repeated headers`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Digest realm=\"a\", nonce=\"1\"").addHeader("WWW-Authenticate", "Basic realm=\"a\"")
                .setChunkedBody("<fault>chunked answer</fault>", 5),
        )
        val response = transport.send(post())
        assertEquals(401, response.status)
        assertEquals("<fault>chunked answer</fault>", response.text)
        assertEquals("Digest realm=\"a\", nonce=\"1\"", response.header("WWW-Authenticate"))
    }

    @Test
    fun `a body without length ends with the connection`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\n\r\n<until-close/>"
        val response = transport.readResponse(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(200, response.status)
        assertEquals("<until-close/>", response.text)
        // A bare LF as line end (some embedded servers)
        assertEquals("x", transport.readResponse(ByteArrayInputStream("HTTP/1.0 500 Oops\nContent-Length: 1\n\nx".toByteArray())).text)
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream(ByteArray(0))) }
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream("garbage\r\n\r\n".toByteArray())) }
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort".toByteArray())) }
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n".toByteArray())) }
    }

    @Test
    fun `odd headers, lengths and chunks from embedded servers`() {
        // A header line without a name and one without a colon are skipped; headers end with the stream too
        val odd = transport.readResponse(ByteArrayInputStream("HTTP/1.1 204 No Content\r\n: nameless\r\nnot a header\r\nServer: cam".toByteArray()))
        assertEquals(204, odd.status)
        assertEquals(mapOf("Server" to "cam"), odd.headers)
        assertEquals(0, odd.body.size)
        // A status line without a status, and another transfer coding than chunked
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream("HTTP/1.1\r\n\r\n".toByteArray())) }
        assertEquals("ok", transport.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nTransfer-Encoding: identity\r\nContent-Length: 2\r\n\r\nok".toByteArray())).text)
        // A length that is not a number
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: many\r\n\r\nx".toByteArray())) }
        // A chunked body cut off before its last chunk, and the final chunk at the end of the stream
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n".toByteArray())) }
        assertEquals("ab", transport.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nTransfer-Encoding: Chunked\r\n\r\n2\r\nab\r\n0\r\n".toByteArray())).text)
        // A header line longer than any camera sends
        expectThrows<IOException> { transport.readResponse(ByteArrayInputStream(("HTTP/1.1 200 OK\r\nX: " + "a".repeat(17 * 1024) + "\r\n\r\n").toByteArray())) }
    }

    @Test
    fun `only http URLs with a host are called`() = runBlocking {
        for (url in listOf("https://10.20.30.204/onvif/device_service", "http:///onvif", "not a url at all", "file:///etc/hosts")) {
            expectThrows<IOException> { transport.send(HttpRequest("GET", url, emptyMap())) }
        }
        Unit
    }

    @Test
    fun `a GET without path or body asks for the root`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        val response = transport.send(HttpRequest("GET", "http://${server.hostName}:${server.port}", emptyMap()))
        assertEquals("ok", response.text)
        val recorded = server.takeRequest()
        assertEquals("GET / HTTP/1.1", recorded.requestLine)
        assertEquals("0", recorded.getHeader("Content-Length"))
    }

    @Test
    fun `answers larger than the limit are refused`() {
        val small = LanHttpTransport({ it.isLoopbackAddress }, maxBodyBytes = 4)
        expectThrows<IOException> { small.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n12345".toByteArray())) }
        expectThrows<IOException> { small.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n12345\r\n0\r\n\r\n".toByteArray())) }
        expectThrows<IOException> { small.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n123456789".toByteArray())) }
        assertEquals(
            "1234",
            small.readResponse(ByteArrayInputStream("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=1\r\n1234\r\n0\r\nTrailer: x\r\n\r\n".toByteArray())).text,
        )
    }

    @Test
    fun `a camera that does not answer in time is unreachable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        expectThrows<IOException> { transport.send(post(timeout = 300)) }
        Unit
    }

    @Test
    fun `cancelling a call closes its socket at once`() = runBlocking {
        // NO_RESPONSE, not a headers delay: the server's thread then blocks on a read that ends as soon as the client closes
        // the socket. A delay is an uninterruptible sleep that shutdown() waits for (at most 5 s), which failed under load.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = async(Dispatchers.Default) { runCatching { transport.send(post(timeout = 10_000)) } }
        server.takeRequest(5, TimeUnit.SECONDS)
        val started = System.currentTimeMillis()
        call.cancel()
        withTimeout(3_000) { call.join() }
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun `only addresses on this network get cleartext, checked on the one DNS answer`() = runBlocking {
        val network = LocalNetwork(listOf(LinkSubnet.parse("192.168.1.10/24")))
        val lookups = mutableListOf<String>()
        val app = LanHttpTransport(network::allows, resolve = { host ->
            lookups += host
            listOf(InetAddress.getByAddress(host, byteArrayOf(10, 3, 0, 204.toByte())))
        })
        // A private address of another subnet is refused before connecting
        expectThrows<AddressNotAllowedException> { app.send(HttpRequest("POST", "http://camera.lan/onvif/device_service")) }
        assertEquals(listOf("camera.lan"), lookups)
        val public = LanHttpTransport(network::allows, resolve = { listOf(InetAddress.getByAddress(it, byteArrayOf(8, 8, 8, 8))) })
        expectThrows<AddressNotAllowedException> { public.send(HttpRequest("POST", "http://camera.example.com/onvif/device_service")) }
        // Loopback is never this network
        expectThrows<AddressNotAllowedException> { LanHttpTransport(network::allows).send(post()) }
        expectThrows<IOException> { transport.send(HttpRequest("POST", "https://10.0.0.2/onvif/device_service")) }
        expectThrows<IOException> { transport.send(HttpRequest("POST", "not a url")) }
        Unit
    }
}
