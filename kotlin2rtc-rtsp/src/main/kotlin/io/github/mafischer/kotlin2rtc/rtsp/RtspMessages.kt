package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.Interleaved
import java.io.IOException
import java.io.InputStream

/** An RTSP response (RFC 2326 7); header names lower case, every value of a repeated header kept */
public data class RtspResponse(val status: Int, val reason: String, val headers: Map<String, List<String>>, val body: String = "") {
    public fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    public fun headers(name: String): List<String> = headers[name.lowercase()].orEmpty()

    public val cseq: Int? get() = header("cseq")?.trim()?.toIntOrNull()

    /** `Session: 12345678;timeout=60` → the id */
    public val session: String? get() = header("session")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

    /** The session's timeout in seconds (RFC 2326 12.37; 60 when not given) */
    public val sessionTimeoutSeconds: Int
        get() = header("session")?.split(';')?.drop(1)?.map { it.trim() }
            ?.firstOrNull { it.startsWith("timeout=", ignoreCase = true) }?.substringAfter('=')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: DEFAULT_SESSION_TIMEOUT_SECONDS

    public companion object {
        public const val DEFAULT_SESSION_TIMEOUT_SECONDS: Int = 60
    }
}

/** RTSP/1.0 requests and the reader of what a camera sends on the connection */
public object Rtsp {
    /** ONVIF Streaming Specification 5.3.1: the feature tag that asks the camera for its audio backchannel */
    public const val BACKCHANNEL_REQUIRE: String = "www.onvif.org/ver20/backchannel"
    public const val USER_AGENT: String = "kotlin2rtc"
    private const val MAX_HEADER_LINES = 64
    private const val MAX_LINE = 8_192
    private const val MAX_BODY = 64 * 1024

    /** A request: the request line, CSeq, User-Agent ([userAgent]), then [headers] in order, then the body */
    public fun request(
        method: String,
        url: String,
        cseq: Int,
        headers: List<Pair<String, String>> = emptyList(),
        body: String = "",
        userAgent: String = USER_AGENT,
    ): String = buildString {
        append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
        append("CSeq: ").append(cseq).append("\r\n")
        append("User-Agent: ").append(userAgent).append("\r\n")
        for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
        if (body.isNotEmpty()) append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
        append("\r\n")
        append(body)
    }

    /** Something the camera sent: a response, or an interleaved packet on [channel] */
    public sealed interface Incoming {
        public data class Response(val response: RtspResponse) : Incoming

        public data class Packet(val channel: Int, val size: Int) : Incoming

        /** A request from the camera (rare: a server's GET_PARAMETER keep-alive); ignored */
        public data object Request : Incoming
    }

    /**
     * The next message on [input], [first] being its first byte (already read): an interleaved frame (`$`, skipped), a
     * response, or a request from the server. Throws [IOException] at the end of the stream or on garbage.
     */
    public fun read(input: InputStream, first: Int): Incoming {
        if (first < 0) throw IOException(CONNECTION_CLOSED)
        if (first == Interleaved.MAGIC) {
            val channel = input.read()
            val size = (input.read() shl 8) or input.read()
            if (channel < 0 || size < 0) throw IOException(CONNECTION_CLOSED)
            skipFully(input, size)
            return Incoming.Packet(channel, size)
        }
        val statusLine = (first.toChar() + readLine(input)).trim()
        val headers = readHeaders(input)
        val length = headers["content-length"]?.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) throw IOException("body too large")
        val body = if (length > 0) String(readFully(input, length), Charsets.UTF_8) else ""
        if (!statusLine.startsWith("RTSP/")) return Incoming.Request
        val parts = statusLine.split(' ', limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("not an RTSP response")
        return Incoming.Response(RtspResponse(status, parts.getOrNull(2).orEmpty(), headers, body))
    }

    private fun readHeaders(input: InputStream): Map<String, List<String>> {
        val headers = linkedMapOf<String, MutableList<String>>()
        repeat(MAX_HEADER_LINES) {
            val line = readLine(input)
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } += line.substring(colon + 1).trim()
        }
        throw IOException("too many headers")
    }

    /** One line without its CR LF */
    private fun readLine(input: InputStream): String {
        val builder = StringBuilder()
        while (builder.length < MAX_LINE) {
            val byte = input.read()
            if (byte < 0) throw IOException(CONNECTION_CLOSED)
            if (byte == '\n'.code) return builder.toString().removeSuffix("\r")
            builder.append(byte.toChar())
        }
        throw IOException("line too long")
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(buffer, read, size - read)
            if (count < 0) throw IOException(CONNECTION_CLOSED)
            read += count
        }
        return buffer
    }

    private fun skipFully(input: InputStream, size: Int) {
        readFully(input, size)
    }
}

private const val CONNECTION_CLOSED = "connection closed"
