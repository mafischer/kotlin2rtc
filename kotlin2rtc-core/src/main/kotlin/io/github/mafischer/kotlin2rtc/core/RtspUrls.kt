package io.github.mafischer.kotlin2rtc.core

import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** RTSP (and HTTP) URLs with and without the user info */
public object RtspUrls {
    /** The URL without its user info, and the credentials it carried (percent-decoded), if any */
    public fun split(url: String): Pair<String, Credentials?> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url to null
        val userInfo = uri.rawUserInfo ?: return url to null
        val stripped = url.replaceFirst("$userInfo@", "")
        val user = decode(userInfo.substringBefore(':'))
        val password = if (':' in userInfo) decode(userInfo.substringAfter(':')) else ""
        return stripped to Credentials(user, password).takeIf { user.isNotEmpty() }
    }

    /** [url] with [credentials] as percent-encoded user info (players decode it for RTSP Digest/Basic); as is without */
    public fun withCredentials(url: String, credentials: Credentials?): String {
        if (credentials == null || credentials.username.isEmpty()) return url
        val (bare, _) = split(url)
        val schemeEnd = bare.indexOf("://")
        if (schemeEnd < 0) return bare
        val userInfo = encode(credentials.username) + ":" + encode(credentials.password)
        return bare.substring(0, schemeEnd + 3) + userInfo + "@" + bare.substring(schemeEnd + 3)
    }

    /** [url] with [address] as its host (user info, port, path and query kept): called without a second lookup */
    public fun withHost(url: String, address: InetAddress): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = address.hostAddress?.substringBefore('%') ?: return url
        return buildString {
            append(uri.scheme).append("://")
            uri.rawUserInfo?.let { append(it).append('@') }
            append(if (':' in host) "[$host]" else host)
            if (uri.port >= 0) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
