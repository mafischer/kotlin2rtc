package io.github.mafischer.kotlin2rtc.core

import java.io.IOException

/** Header names the clients use */
public object HttpHeaders {
    public const val CONTENT_TYPE: String = "Content-Type"
    public const val AUTHORIZATION: String = "Authorization"
    public const val WWW_AUTHENTICATE: String = "WWW-Authenticate"
}

/** One HTTP request, independent of the client library ([LanHttpTransport], OkHttp, fakes in tests) */
public data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** Connect and read timeout of this request; the transport's default when null */
    val timeoutMillis: Long? = null,
) {
    /** The header [name], whatever its case */
    public fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun equals(other: Any?): Boolean = other is HttpRequest && method == other.method && url == other.url &&
        headers == other.headers && body.contentEquals(other.body) && timeoutMillis == other.timeoutMillis

    override fun hashCode(): Int = 31 * (31 * method.hashCode() + url.hashCode()) + headers.hashCode()
}

/** An HTTP response: status, headers (the first of repeated ones) and body */
public class HttpResponse(public val status: Int, public val headers: Map<String, String> = emptyMap(), public val body: ByteArray = ByteArray(0)) {
    /** The header [name], whatever its case */
    public fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** The body as UTF-8 */
    public val text: String get() = body.decodeToString()
}

/** Sends one request. Throws [IOException] when there is no answer at all (refused address, offline, DNS, timeout). */
public fun interface HttpTransport {
    public suspend fun send(request: HttpRequest): HttpResponse
}
