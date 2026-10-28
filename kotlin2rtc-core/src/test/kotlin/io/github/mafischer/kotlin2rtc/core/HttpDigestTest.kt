package io.github.mafischer.kotlin2rtc.core

import io.github.mafischer.kotlin2rtc.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.SecureRandom

/** HTTP Digest for cameras that answer ONVIF with a challenge: RFC 2617's example and the Amcrest challenge */
class HttpDigestTest {
    @Test
    fun `the RFC 2617 example gives its published response`() {
        val challenge = HttpDigest.parseChallenge(
            """Digest realm="testrealm@host.com", qop="auth,auth-int", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41"""",
        )!!
        assertEquals("auth", challenge.qop)
        val header = HttpDigest.authorization(challenge, Credentials("Mufasa", "Circle Of Life"), "GET", "/dir/index.html", "0a4f113b")
        assertTrue(header.contains("response=\"6629fae49393a05397450978507c4ef1\""), header)
        assertTrue(header.startsWith("Digest username=\"Mufasa\", realm=\"testrealm@host.com\""))
        assertTrue(header.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
        assertTrue(header.contains("uri=\"/dir/index.html\""))
    }

    @Test
    fun `the Amcrest camera's challenge is read`() {
        val challenge = HttpDigest.parseChallenge(Fixtures.read("amcrest-digest-challenge.txt").trim())!!
        assertEquals(HttpDigest.Challenge("Login to AMC0000000000000000", "b252aWYtZGlnZXN0OjY4MDgxOTY2NzMw", "auth", "", null), challenge)
        val header = HttpDigest.authorization(challenge, Credentials("admin", "pw"), "POST", "/onvif/device_service", "abc", nonceCount = 2)
        val ha1 = md5("admin:Login to AMC0000000000000000:pw")
        val ha2 = md5("POST:/onvif/device_service")
        assertTrue(header.contains("response=\"${md5("$ha1:b252aWYtZGlnZXN0OjY4MDgxOTY2NzMw:00000002:abc:auth:$ha2")}\""))
        assertTrue(header.contains("nc=00000002"))
    }

    @Test
    fun `the algorithm is echoed only when the camera named it (TP-Link Tapo refuses an unasked algorithm=MD5)`() {
        // A Tapo C210 (firmware 1.2.7) answers 401 to the right response when the header adds algorithm=MD5
        val tapo = HttpDigest.parseChallenge("Digest realm=\"TP-LINK IP-Camera\", nonce=\"fb93f59432fa328235d29ba4a23cb7fa\"")!!
        assertNull(tapo.algorithm)
        val header = HttpDigest.authorization(tapo, Credentials("u", "p"), "DESCRIBE", "rtsp://cam/stream1", "c")
        assertTrue(!header.contains("algorithm"), header)
        assertTrue(header.contains("response=\"${md5("${md5("u:TP-LINK IP-Camera:p")}:fb93f59432fa328235d29ba4a23cb7fa:${md5("DESCRIBE:rtsp://cam/stream1")}")}\""))

        val named = HttpDigest.parseChallenge("Digest realm=\"r\", nonce=\"n\", algorithm=\"MD5\"")!!
        assertEquals("MD5", named.algorithm)
        assertTrue(HttpDigest.authorization(named, Credentials("u", "p"), "GET", "/", "c").endsWith(", algorithm=MD5"))
    }

    @Test
    fun `without qop and with MD5-sess`() {
        val plain = HttpDigest.parseChallenge("Digest realm=r, nonce=n")!!
        assertNull(plain.qop)
        val header = HttpDigest.authorization(plain, Credentials("u", "p"), "POST", "/x", "c")
        assertTrue(header.contains("response=\"${md5("${md5("u:r:p")}:n:${md5("POST:/x")}")}\""))
        assertTrue(!header.contains("qop="))

        val session = HttpDigest.parseChallenge("Digest realm=\"r\", nonce=\"n\", qop=\"auth\", algorithm=MD5-sess")!!
        val sessionHeader = HttpDigest.authorization(session, Credentials("u", "p"), "POST", "/x", "c")
        val ha1 = md5("${md5("u:r:p")}:n:c")
        assertTrue(sessionHeader.contains("response=\"${md5("$ha1:n:00000001:c:auth:${md5("POST:/x")}")}\""))
        assertTrue(sessionHeader.endsWith("algorithm=MD5-sess"))
    }

    @Test
    fun `basic, empty or incomplete challenges are not digest challenges`() {
        assertNull(HttpDigest.parseChallenge(null))
        assertNull(HttpDigest.parseChallenge("Basic realm=\"cam\""))
        assertNull(HttpDigest.parseChallenge("Digest realm=\"cam\""))
        assertNull(HttpDigest.parseChallenge("Digest nonce=\"n\""))
        // Only auth-int offered: no qop the client can use
        assertNull(HttpDigest.parseChallenge("Digest realm=r, nonce=n, qop=\"auth-int\"")!!.qop)
    }

    @Test
    fun `quoted values keep commas and escapes, user names are quoted`() {
        assertEquals(mapOf("realm" to "a, \"b\"", "nonce" to "n"), HttpDigest.parameters(" realm=\"a, \\\"b\\\"\", nonce=n"))
        val header = HttpDigest.authorization(HttpDigest.Challenge("r", "n"), Credentials("we\"ird", "p"), "POST", "/", "c")
        assertTrue(header.startsWith("Digest username=\"we\\\"ird\""))
        assertEquals(32, HttpDigest.cnonce(SecureRandom()).length)
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
