package io.github.mafischer.kotlin2rtc.core

/** A camera login (ONVIF user, RTSP user). Never logged: [toString] leaves the password out. */
public data class Credentials(val username: String, val password: String) {
    override fun toString(): String = "Credentials(username=$username, password=***)"
}
