package io.github.mafischer.kotlin2rtc.rtsp

import io.github.mafischer.kotlin2rtc.codec.BackchannelMedia
import io.github.mafischer.kotlin2rtc.codec.Interleaved
import io.github.mafischer.kotlin2rtc.core.Credentials
import io.github.mafischer.kotlin2rtc.core.HttpDigest
import io.github.mafischer.kotlin2rtc.core.Logger
import io.github.mafischer.kotlin2rtc.core.RtspUrls
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/** Why the camera's backchannel cannot be used */
public sealed class BackchannelError(message: String) : Exception(message) {
    /** No answer, or the connection broke */
    public class Unreachable(cause: Throwable? = null) : BackchannelError("unreachable") {
        init {
            cause?.let { initCause(it) }
        }
    }

    /** The camera refused the login (it played the video with it, so rare) */
    public class Unauthorized : BackchannelError("unauthorized")

    /** The camera has no backchannel kotlin2rtc can send to; [offered] are the encodings it has (e.g. only AAC) */
    public class Unsupported(public val offered: List<String> = emptyList(), public val status: Int? = null) : BackchannelError("unsupported")

    /** The camera's speaker is taken (another client talks: 453 Not Enough Bandwidth, 503, 486) */
    public class Busy(public val status: Int) : BackchannelError("busy")

    /** Any other refusal */
    public class Failed(public val status: Int?) : BackchannelError("failed")
}

/** An open backchannel: RTP and RTCP go to the camera on the RTSP connection */
public interface BackchannelLink {
    public val media: BackchannelMedia

    /** One RTP packet; blocking (the audio pump's thread). Throws [IOException] when the connection is gone. */
    public fun sendRtp(packet: ByteArray)

    public fun sendRtcp(packet: ByteArray)

    /** The connection ended by itself (the camera closed it, the network went); called once, on another thread */
    public var onClosed: (() -> Unit)?

    /** TEARDOWN and close; blocking briefly (not on the main thread) */
    public fun close()
}

/**
 * The ONVIF audio backchannel over RTSP (ONVIF Streaming Specification 5.3, RFC 2326), a session of its own next to
 * any video player: DESCRIBE with `Require: www.onvif.org/ver20/backchannel` (the camera then lists its
 * `sendonly` audio media), SETUP of that media only, RTP/AVP/TCP interleaved on channels 0-1 (no UDP ports, works
 * where the video fell back to TCP), PLAY, then RTP from the client inside the connection. Digest authentication
 * ([HttpDigest]); Basic only when the camera offers nothing else (the caller should check that the device is the camera,
 * e.g. with the ONVIF module's CameraIdentity, before Basic sends the password). Keep-alive with GET_PARAMETER (OPTIONS for cameras that refuse it) at half the session
 * timeout; TEARDOWN on close.
 *
 * [url] carries the login as user info (memory only, never logged); [allows] is the local-network rule its host must
 * pass (`LocalNetwork.allowsCamera`). Blocking: call it on an IO thread.
 */
public class RtspBackchannelClient(
    private val url: String,
    private val allows: (InetAddress) -> Boolean,
    private val random: SecureRandom = SecureRandom(),
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    /** The shortest keep-alive interval (tests shorten it) */
    private val minKeepAliveMillis: Long = MIN_KEEP_ALIVE_MILLIS,
    /** The User-Agent header of every request */
    private val userAgent: String = Rtsp.USER_AGENT,
    private val logger: Logger = Logger.None,
) {
    private val requestUrl: String
    private val credentials: Credentials?
    private val host: String
    private val port: Int

    init {
        val (bare, login) = RtspUrls.split(url)
        requestUrl = bare
        credentials = login
        val uri = runCatching { URI(bare) }.getOrNull() ?: throw BackchannelError.Failed(null)
        host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: throw BackchannelError.Failed(null)
        port = if (uri.port > 0) uri.port else DEFAULT_PORT
    }

    /** DESCRIBE with the backchannel requirement only: does the camera have one kotlin2rtc can use (capability check) */
    public fun describe(): SdpBackchannel {
        val connection = connect()
        try {
            return describe(connection)
        } finally {
            connection.closeQuietly()
        }
    }

    /** DESCRIBE, SETUP of the backchannel, PLAY: the open link */
    public fun open(): BackchannelLink {
        val connection = connect()
        try {
            val sdp = describe(connection)
            val media = sdp.media ?: throw BackchannelError.Unsupported(sdp.offered)
            val setup = connection.exchange(
                "SETUP",
                media.control,
                listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1", "Require" to Rtsp.BACKCHANNEL_REQUIRE),
            )
            when {
                setup.status in BUSY -> throw BackchannelError.Busy(setup.status)
                setup.status == UNSUPPORTED_TRANSPORT -> throw BackchannelError.Unsupported(sdp.offered, setup.status)
                setup.status != OK -> throw BackchannelError.Failed(setup.status)
            }
            val session = setup.session ?: throw BackchannelError.Failed(setup.status)
            val channels = interleavedChannels(setup.header("transport"))
            val aggregate = sdp.aggregate.ifEmpty { requestUrl }
            val play = connection.exchange(
                "PLAY",
                aggregate,
                listOf("Session" to session, "Range" to "npt=0.000-", "Require" to Rtsp.BACKCHANNEL_REQUIRE),
            )
            when {
                play.status in BUSY -> throw BackchannelError.Busy(play.status)
                play.status != OK -> throw BackchannelError.Failed(play.status)
            }
            logger.info("backchannel open: ${media.codec.encoding}/${media.clockRate}")
            return OpenLink(connection, media, session, aggregate, channels, setup.sessionTimeoutSeconds)
        } catch (e: BackchannelError) {
            connection.closeQuietly()
            throw e
        } catch (e: IOException) {
            connection.closeQuietly()
            throw BackchannelError.Unreachable(e)
        }
    }

    private fun describe(connection: Connection): SdpBackchannel {
        val response = try {
            connection.exchange("DESCRIBE", requestUrl, listOf("Accept" to "application/sdp", "Require" to Rtsp.BACKCHANNEL_REQUIRE))
        } catch (e: IOException) {
            throw BackchannelError.Unreachable(e)
        }
        when (response.status) {
            OK -> Unit
            UNAUTHORIZED -> throw BackchannelError.Unauthorized()
            // 551 Option not supported: the camera does not know the feature tag (it names it in Unsupported)
            OPTION_NOT_SUPPORTED -> throw BackchannelError.Unsupported(status = response.status)
            else -> throw if (response.header("unsupported") != null) BackchannelError.Unsupported(status = response.status) else BackchannelError.Failed(response.status)
        }
        val base = response.header("content-base") ?: response.header("content-location") ?: requestUrl
        return BackchannelSdp.parse(response.body, base)
    }

    private fun connect(): Connection {
        val address = try {
            InetAddress.getAllByName(host).firstOrNull() ?: throw BackchannelError.Unreachable()
        } catch (e: IOException) {
            throw BackchannelError.Unreachable(e)
        }
        // Plain RTSP goes to the camera's local network only (as LanHttpTransport for ONVIF)
        if (!allows(address)) throw BackchannelError.Unreachable()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw BackchannelError.Unreachable(e)
        }
        return Connection(socket)
    }

    /** One RTSP connection: requests with CSeq and the login, responses matched by CSeq */
    private inner class Connection(val socket: Socket) {
        val input: InputStream = socket.getInputStream().buffered()
        private val output: OutputStream = socket.getOutputStream()
        private val writeLock = Any()
        private var cseq = 0
        private var digest: HttpDigest.Challenge? = null
        private var basic = false
        private var nonceCount = 0

        /** Writes a request now (the keep-alive and TEARDOWN do not wait for the answer); its CSeq */
        public fun send(method: String, uri: String, headers: List<Pair<String, String>>): Int = synchronized(writeLock) {
            cseq += 1
            val request = Rtsp.request(method, uri, cseq, headers + listOfNotNull(authorization(method, uri)?.let { "Authorization" to it }), userAgent = userAgent)
            output.write(request.encodeToByteArray())
            output.flush()
            cseq
        }

        public fun write(bytes: ByteArray) = synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }

        /** A request and its response (interleaved data before it is skipped); a 401 is answered once with the login */
        public fun exchange(method: String, uri: String, headers: List<Pair<String, String>>): RtspResponse {
            var response = await(send(method, uri, headers))
            if (response.status == UNAUTHORIZED && credentials != null && digest == null && !basic) {
                if (!authenticate(response.headers("www-authenticate"))) return response
                response = await(send(method, uri, headers))
            } else if (response.status == UNAUTHORIZED && credentials != null && digest != null) {
                // A stale nonce: the camera's new challenge, once
                val renewed = response.headers("www-authenticate").firstNotNullOfOrNull(HttpDigest::parseChallenge)
                if (renewed != null && renewed.nonce != digest?.nonce) {
                    digest = renewed
                    nonceCount = 0
                    response = await(send(method, uri, headers))
                }
            }
            return response
        }

        private fun await(sequence: Int): RtspResponse {
            while (true) {
                val incoming = try {
                    Rtsp.read(input, input.read())
                } catch (e: SocketTimeoutException) {
                    throw IOException("no answer", e)
                }
                if (incoming is Rtsp.Incoming.Response && (incoming.response.cseq == null || incoming.response.cseq == sequence)) return incoming.response
            }
        }

        /** Digest when offered, else Basic */
        private fun authenticate(challenges: List<String>): Boolean {
            digest = challenges.firstNotNullOfOrNull(HttpDigest::parseChallenge)
            if (digest != null) return true
            basic = challenges.any { it.trim().startsWith("Basic", ignoreCase = true) }
            return basic
        }

        private fun authorization(method: String, uri: String): String? {
            val login = credentials ?: return null
            digest?.let { challenge ->
                nonceCount += 1
                return HttpDigest.authorization(challenge, login, method, uri, HttpDigest.cnonce(random), nonceCount)
            }
            if (!basic) return null
            return "Basic " + Base64.getEncoder().encodeToString("${login.username}:${login.password}".encodeToByteArray())
        }

        public fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    /** The playing backchannel: keep-alives, the camera's messages read and dropped, TEARDOWN on close */
    private inner class OpenLink(
        private val connection: Connection,
        override val media: BackchannelMedia,
        private val session: String,
        private val aggregate: String,
        private val channels: Pair<Int, Int>,
        timeoutSeconds: Int,
    ) : BackchannelLink {
        /** Set after the link dropped already (a camera that ends the session at once): called then */
        override var onClosed: (() -> Unit)? = null
            set(value) {
                field = value
                if (dropped) report()
            }

        @Volatile
        private var closed = false

        @Volatile
        private var dropped = false
        private val reported = AtomicBoolean(false)

        @Volatile
        private var keepAliveWithOptions = false
        private val keepAliveMillis = (timeoutSeconds * 1000L / 2).coerceIn(minKeepAliveMillis, MAX_KEEP_ALIVE_MILLIS)
        private val reader = Thread(::readLoop, "kotlin2rtc-backchannel-rtsp").apply { isDaemon = true }
        private val keepAlive = Thread(::keepAliveLoop, "kotlin2rtc-backchannel-keepalive").apply { isDaemon = true }

        init {
            connection.socket.soTimeout = 0
            reader.start()
            keepAlive.start()
        }

        override fun sendRtp(packet: ByteArray) = connection.write(Interleaved.frame(channels.first, packet))

        override fun sendRtcp(packet: ByteArray) = connection.write(Interleaved.frame(channels.second, packet))

        override fun close() {
            if (closed) return
            closed = true
            keepAlive.interrupt()
            runCatching { connection.send("TEARDOWN", aggregate, listOf("Session" to session)) }
            connection.closeQuietly()
            logger.info("backchannel closed")
        }

        private fun readLoop() {
            try {
                while (!closed) {
                    val incoming = Rtsp.read(connection.input, connection.input.read())
                    // After PLAY only keep-alives are answered: a camera that refuses GET_PARAMETER gets OPTIONS from now on
                    if (incoming is Rtsp.Incoming.Response && incoming.response.status >= BAD_REQUEST) keepAliveWithOptions = true
                }
            } catch (e: IOException) {
                ended()
            }
        }

        private fun keepAliveLoop() {
            try {
                while (!closed) {
                    Thread.sleep(keepAliveMillis)
                    if (closed) return
                    val method = if (keepAliveWithOptions) "OPTIONS" else "GET_PARAMETER"
                    connection.send(method, aggregate, listOf("Session" to session))
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: IOException) {
                ended()
            }
        }

        private fun ended() {
            if (closed) return
            closed = true
            keepAlive.interrupt()
            connection.closeQuietly()
            logger.info("backchannel dropped")
            dropped = true
            report()
        }

        private fun report() {
            val callback = onClosed ?: return
            if (reported.compareAndSet(false, true)) callback()
        }
    }

    public companion object {
        public const val DEFAULT_PORT: Int = 554
        public const val CONNECT_TIMEOUT_MILLIS: Int = 3_000
        public const val READ_TIMEOUT_MILLIS: Int = 5_000
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val UNSUPPORTED_TRANSPORT = 461
        private const val OPTION_NOT_SUPPORTED = 551

        /** 453 Not Enough Bandwidth, 486 Busy Here, 503 Service Unavailable: the speaker is taken */
        private val BUSY = setOf(453, 486, 503)
        private const val MIN_KEEP_ALIVE_MILLIS = 5_000L
        private const val MAX_KEEP_ALIVE_MILLIS = 30_000L

        /** `...;interleaved=2-3` → 2 to 3; 0 to 1 when the camera says nothing */
        public fun interleavedChannels(transport: String?): Pair<Int, Int> {
            val value = transport?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("interleaved=", ignoreCase = true) }?.substringAfter('=')
            val rtp = value?.substringBefore('-')?.trim()?.toIntOrNull() ?: return 0 to 1
            val rtcp = value.substringAfter('-', "").trim().toIntOrNull() ?: (rtp + 1)
            return rtp to rtcp
        }
    }
}
