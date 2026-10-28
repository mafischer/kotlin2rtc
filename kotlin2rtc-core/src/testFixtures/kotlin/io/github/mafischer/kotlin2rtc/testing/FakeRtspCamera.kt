package io.github.mafischer.kotlin2rtc.testing

import io.github.mafischer.kotlin2rtc.core.HttpDigest
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An ONVIF camera's RTSP server on loopback for the backchannel client's tests (real TCP): Digest login (no qop, as
 * Dahua/Amcrest answer RTSP), DESCRIBE that lists the `sendonly` audio only when asked with the backchannel requirement,
 * SETUP with TCP interleaving, PLAY, keep-alives, TEARDOWN, and every interleaved frame the client sends.
 */
public class FakeRtspCamera(
    public val username: String = "admin",
    public val password: String = "secret",
) : Closeable {
    public data class Request(val method: String, val url: String, val headers: Map<String, String>) {
        public fun header(name: String): String? = headers[name.lowercase()]
    }

    public var sdpWithBackchannel: String = DEFAULT_SDP
    public var describeStatus = 200
    public var setupStatus = 200
    public var keepAliveStatus = 200
    public var closeAfterPlay = false
    public var sessionTimeout = 60

    private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    public val port: Int get() = server.localPort
    public val url: String get() = "rtsp://127.0.0.1:$port/cam/realmonitor?channel=1&subtype=0"

    public fun urlWith(user: String = username, pass: String = password) = "rtsp://$user:$pass@127.0.0.1:$port/cam/realmonitor?channel=1&subtype=0"

    public val requests = CopyOnWriteArrayList<Request>()
    public val frames = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    public val connections = AtomicInteger()
    public val tornDown = CountDownLatch(1)
    private val sockets = CopyOnWriteArrayList<Socket>()

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    return@Thread
                }
                connections.incrementAndGet()
                sockets += socket
                Thread({ serve(socket) }, "fake-rtsp-camera").apply { isDaemon = true }.start()
            }
        }, "fake-rtsp-accept").apply { isDaemon = true }.start()
    }

    public fun methods(): List<String> = requests.map { it.method }

    /** Waits until [count] frames arrived on [channel] */
    public fun awaitFrames(channel: Int, count: Int, timeoutMillis: Long = 3_000): List<ByteArray> {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            val got = frames.filter { it.first == channel }.map { it.second }
            if (got.size >= count) return got
            Thread.sleep(10)
        }
        error("only ${frames.count { it.first == channel }} frames on channel $channel")
    }

    public fun awaitRequest(method: String, timeoutMillis: Long = 3_000): Request {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            requests.lastOrNull { it.method == method }?.let { return it }
            Thread.sleep(10)
        }
        error("no $method; got ${methods()}")
    }

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()
            while (true) {
                val first = input.read()
                if (first < 0) return
                if (first == '$'.code) {
                    val channel = input.read()
                    val size = (input.read() shl 8) or input.read()
                    frames += channel to input.readExactly(size)
                    continue
                }
                val request = readRequest(first, input)
                requests += request
                if (!respond(request, output)) {
                    socket.close()
                    return
                }
            }
        } catch (e: IOException) {
            return
        }
    }

    /** Answers; false: close the connection */
    private fun respond(request: Request, output: OutputStream): Boolean {
        val cseq = request.header("cseq").orEmpty()
        if (!authorized(request)) {
            output.reply("401 Unauthorized", cseq, listOf("WWW-Authenticate" to "Digest realm=\"$REALM\", nonce=\"$NONCE\"", "WWW-Authenticate" to "Basic realm=\"$REALM\""))
            return true
        }
        when (request.method) {
            "DESCRIBE" -> {
                if (describeStatus != 200) {
                    val extra = if (describeStatus == 551) listOf("Unsupported" to BACKCHANNEL_REQUIRE) else emptyList()
                    output.reply("$describeStatus Refused", cseq, extra)
                    return true
                }
                // As a camera does: the backchannel only for a client that asks for it
                val asked = request.header("require") == BACKCHANNEL_REQUIRE
                val sdp = if (asked) sdpWithBackchannel else sdpWithBackchannel.substringBefore("m=audio 0 RTP/AVP 0 8")
                output.reply("200 OK", cseq, listOf("Content-Base" to "$url/", "Content-Type" to "application/sdp"), sdp)
            }
            "SETUP" -> {
                if (setupStatus != 200) {
                    output.reply("$setupStatus Refused", cseq)
                    return true
                }
                output.reply("200 OK", cseq, listOf("Session" to "$SESSION;timeout=$sessionTimeout", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
            }
            "PLAY" -> {
                output.reply("200 OK", cseq, listOf("Session" to SESSION))
                return !closeAfterPlay
            }
            "GET_PARAMETER" -> {
                output.reply(if (keepAliveStatus == 200) "200 OK" else "$keepAliveStatus Not Allowed", cseq, listOf("Session" to SESSION))
            }
            "OPTIONS" -> {
                output.reply("200 OK", cseq, listOf("Public" to "DESCRIBE, SETUP, PLAY, TEARDOWN, OPTIONS"))
            }
            "TEARDOWN" -> {
                output.reply("200 OK", cseq, listOf("Session" to SESSION))
                tornDown.countDown()
                return false
            }
            else -> {
                output.reply("501 Not Implemented", cseq)
            }
        }
        return true
    }

    /** The Digest answer of [username]/[password] for this request (RFC 2617 without qop) */
    private fun authorized(request: Request): Boolean {
        val header = request.header("authorization") ?: return false
        if (!header.startsWith("Digest ")) return false
        val params = HttpDigest.parameters(header.removePrefix("Digest "))
        if (params["username"] != username || params["nonce"] != NONCE || params["uri"] != request.url) return false
        val ha1 = md5("$username:$REALM:$password")
        val ha2 = md5("${request.method}:${request.url}")
        return params["response"] == md5("$ha1:$NONCE:$ha2")
    }

    private fun readRequest(first: Int, input: InputStream): Request {
        val line = first.toChar() + input.readLine()
        val parts = line.split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = input.readLine()
            if (header.isEmpty()) break
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
        }
        headers["content-length"]?.toIntOrNull()?.let { input.readExactly(it) }
        return Request(parts[0], parts.getOrElse(1) { "" }, headers)
    }

    private fun InputStream.readLine(): String {
        val builder = StringBuilder()
        while (true) {
            val byte = read()
            if (byte < 0) throw IOException("closed")
            if (byte == '\n'.code) return builder.toString().removeSuffix("\r")
            builder.append(byte.toChar())
        }
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = read(buffer, read, size - read)
            if (count < 0) throw IOException("closed")
            read += count
        }
        return buffer
    }

    private fun OutputStream.reply(status: String, cseq: String, headers: List<Pair<String, String>> = emptyList(), body: String = "") {
        val text = buildString {
            append("RTSP/1.0 ").append(status).append("\r\nCSeq: ").append(cseq).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            if (body.isNotEmpty()) append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
            append("\r\n").append(body)
        }
        synchronized(this) {
            write(text.encodeToByteArray())
            flush()
        }
    }

    /** The camera goes away: every connection breaks */
    public fun dropConnections() {
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        runCatching { server.close() }
        dropConnections()
    }

    public fun awaitTeardown(): Boolean = tornDown.await(3, TimeUnit.SECONDS)

    public companion object {
        /** The ONVIF backchannel feature tag (the RTSP module's Rtsp.BACKCHANNEL_REQUIRE) */
        public const val BACKCHANNEL_REQUIRE = "www.onvif.org/ver20/backchannel"
        public const val REALM = "Login to 0000000000000"
        public const val NONCE = "a6ba8c1e6e25c8ad4e2b3f8e2a8d1c2b"
        public const val SESSION = "4D3C2B1A"
        val DEFAULT_SDP = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=Media Server\r\nt=0 0\r\na=control:*\r\n" +
            "m=video 0 RTP/AVP 96\r\na=control:trackID=0\r\na=rtpmap:96 H264/90000\r\na=recvonly\r\n" +
            "m=audio 0 RTP/AVP 0 8\r\na=control:trackID=5\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=sendonly\r\n"

        private fun md5(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
