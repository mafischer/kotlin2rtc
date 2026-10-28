package io.github.mafischer.kotlin2rtc.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/** The transport's address policy refused every address of the host: nothing was sent to it */
public class AddressNotAllowedException : IOException("address not allowed")

/**
 * Plain HTTP/1.1 to cameras on the local network (ONVIF's SOAP). Cameras speak SOAP over `http://` (few offer TLS);
 * this socket client sends cleartext only to an address [allows] accepts, typically [LocalNetwork.allowsCamera] or
 * [LocalNetwork.allows], so a misconfigured or hostile camera URL cannot make it talk cleartext to the internet. Every
 * other host is refused before connecting ([AddressNotAllowedException]). On Android it also works where the network
 * security config forbids cleartext for OkHttp and HttpURLConnection. The host is resolved once and the address that
 * passed the check is the one connected to (no second DNS lookup). One request per connection (`Connection: close`),
 * bodies up to [maxBodyBytes]; a cancelled call closes its socket at once.
 */
public class LanHttpTransport(
    /** Which resolved addresses may be called, e.g. `LocalNetwork::allowsCamera` (loopback in tests) */
    private val allows: (InetAddress) -> Boolean,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val connectTimeoutMillis: Int = 4_000,
    private val defaultTimeoutMillis: Int = 5_000,
    private val maxBodyBytes: Int = 1 shl 20,
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = coroutineScope {
        val uri = runCatching { URI(request.url) }.getOrNull()
        if (uri == null || !uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrEmpty()) throw IOException("unsupported url")
        val socket = Socket()
        // A cancelled call closes its socket, which ends a blocked connect or read at once
        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            withContext(Dispatchers.IO) { exchange(socket, uri, request) }
        } finally {
            watchdog.cancel()
            runCatching { socket.close() }
        }
    }

    private fun exchange(socket: Socket, uri: URI, request: HttpRequest): HttpResponse {
        val host = uri.host.removePrefix("[").removeSuffix("]")
        val address = resolve(host).firstOrNull(allows) ?: throw AddressNotAllowedException()
        val port = if (uri.port > 0) uri.port else 80
        val timeout = request.timeoutMillis?.toInt() ?: defaultTimeoutMillis
        socket.connect(InetSocketAddress(address, port), minOf(connectTimeoutMillis, timeout))
        socket.soTimeout = timeout
        val target = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val body = request.body ?: ByteArray(0)
        val head = buildString {
            append(request.method).append(' ').append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader(uri.host, uri.port)).append("\r\n")
            request.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
        return readResponse(socket.getInputStream())
    }

    private fun hostHeader(host: String, port: Int): String = if (port > 0 && port != 80) "$host:$port" else host

    /** Status line, headers, then the body by Content-Length, chunked, or until the camera closes the connection */
    internal fun readResponse(input: InputStream): HttpResponse {
        val stream = input.buffered()
        val statusLine = readLine(stream) ?: throw IOException("no response")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(stream) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                // Repeated headers (several WWW-Authenticate challenges): the first one wins
                if (headers.keys.none { it.equals(name, ignoreCase = true) }) headers[name] = value
            }
        }
        val header = { name: String -> headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }
        val body = when {
            header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> {
                readChunked(stream)
            }
            header("Content-Length") != null -> {
                val length = header("Content-Length")?.toIntOrNull() ?: throw IOException("bad length")
                if (length > maxBodyBytes) throw IOException(RESPONSE_TOO_LARGE)
                readExactly(stream, length)
            }
            else -> {
                readToEnd(stream)
            }
        }
        return HttpResponse(status, headers, body)
    }

    private fun readChunked(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(stream) ?: throw IOException("truncated chunk")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                // Trailers until the empty line
                while (!readLine(stream).isNullOrEmpty()) Unit
                break
            }
            if (out.size() + size > maxBodyBytes) throw IOException(RESPONSE_TOO_LARGE)
            out.write(readExactly(stream, size))
            readLine(stream)
        }
        return out.toByteArray()
    }

    private fun readExactly(stream: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = stream.read(bytes, read, length - read)
            if (count < 0) throw IOException("truncated body")
            read += count
        }
        return bytes
    }

    private fun readToEnd(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > maxBodyBytes) throw IOException(RESPONSE_TOO_LARGE)
        }
        return out.toByteArray()
    }

    /** One line without its CRLF (ISO-8859-1), or null at the end of the stream */
    private fun readLine(stream: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = stream.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            if (line.size() > MAX_LINE) throw IOException("header line too long")
            line.write(byte)
        }
        return line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }

    private companion object {
        const val MAX_LINE = 16 * 1024
        const val RESPONSE_TOO_LARGE = "response too large"
    }
}
