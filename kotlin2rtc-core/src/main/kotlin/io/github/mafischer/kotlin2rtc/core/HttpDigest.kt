package io.github.mafischer.kotlin2rtc.core

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * HTTP Digest authentication (RFC 7616, MD5 and MD5-sess, qop `auth` or none). Many cameras (Amcrest/Dahua among them)
 * answer ONVIF and RTSP calls with `401` and a Digest challenge instead of checking the WS-Security header: the client then sends
 * the request again with an `Authorization` header.
 */
public object HttpDigest {
    /** A `WWW-Authenticate: Digest ...` challenge */
    public data class Challenge(
        val realm: String,
        val nonce: String,
        val qop: String? = null,
        val opaque: String? = null,
        /**
         * The algorithm the camera named; null when it named none (MD5 is then used). Echoed in the answer only when
         * named: some cameras (e.g. TP-Link Tapo) refuse an answer carrying `algorithm=MD5` they did not ask for.
         */
        val algorithm: String? = null,
    )

    /** The Digest challenge in a `WWW-Authenticate` value, or null (Basic, or a Digest without realm or nonce) */
    public fun parseChallenge(header: String?): Challenge? {
        if (header == null) return null
        val trimmed = header.trim()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        val params = parameters(trimmed.substring("Digest".length))
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        // "auth" when offered (possibly next to auth-int, which a SOAP client does not need)
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.let { options -> if ("auth" in options) "auth" else null }
        return Challenge(realm, nonce, qop, params["opaque"], params["algorithm"])
    }

    /** The parameters of a challenge or an Authorization value: comma-separated `name=value` / `name="quoted, value"` pairs, names lower-cased */
    public fun parameters(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = skipSeparators(text, 0)
        while (index < text.length) {
            val equals = text.indexOf('=', index)
            if (equals < 0) break
            val name = text.substring(index, equals).trim().lowercase()
            val (value, next) = readValue(text, equals + 1)
            if (name.isNotEmpty()) result[name] = value
            index = skipSeparators(text, next)
        }
        return result
    }

    /** The index of the first character from [from] on that is neither a comma nor white space */
    private fun skipSeparators(text: String, from: Int): Int {
        var index = from
        while (index < text.length && (text[index] == ',' || text[index].isWhitespace())) index++
        return index
    }

    /** The value starting at [from] (quoted, or up to the next comma) and the index after it */
    private fun readValue(text: String, from: Int): Pair<String, Int> {
        if (from < text.length && text[from] == '"') return readQuoted(text, from + 1)
        val end = text.indexOf(',', from).let { if (it < 0) text.length else it }
        return text.substring(from, end).trim() to end
    }

    /** A quoted value from [from] (after the opening quote) with its escapes resolved, and the index after the closing quote */
    private fun readQuoted(text: String, from: Int): Pair<String, Int> {
        val builder = StringBuilder()
        var index = from
        while (index < text.length && text[index] != '"') {
            if (text[index] == '\\' && index + 1 < text.length) index++
            builder.append(text[index])
            index++
        }
        return builder.toString() to index + 1
    }

    /** The `Authorization` value answering [challenge] for [method] on [uri] (the request target, path and query) */
    public fun authorization(
        challenge: Challenge,
        credentials: Credentials,
        method: String,
        uri: String,
        cnonce: String,
        nonceCount: Int = 1,
    ): String {
        val nc = "%08x".format(nonceCount)
        var ha1 = md5("${credentials.username}:${challenge.realm}:${credentials.password}")
        if (challenge.algorithm?.lowercase() == "md5-sess") ha1 = md5("$ha1:${challenge.nonce}:$cnonce")
        val ha2 = md5("$method:$uri")
        val response = if (challenge.qop != null) {
            md5("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:$ha2")
        } else {
            md5("$ha1:${challenge.nonce}:$ha2")
        }
        return buildString {
            append("Digest username=\"").append(quote(credentials.username)).append('"')
            append(", realm=\"").append(quote(challenge.realm)).append('"')
            append(", nonce=\"").append(quote(challenge.nonce)).append('"')
            append(", uri=\"").append(quote(uri)).append('"')
            if (challenge.qop != null) append(", qop=").append(challenge.qop).append(", nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"')
            append(", response=\"").append(response).append('"')
            challenge.opaque?.let { append(", opaque=\"").append(quote(it)).append('"') }
            challenge.algorithm?.let { append(", algorithm=").append(it) }
        }
    }

    /** A client nonce: 16 random bytes in hex */
    public fun cnonce(random: SecureRandom): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun quote(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
