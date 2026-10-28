package io.github.mafischer.kotlin2rtc.testing

import io.github.mafischer.kotlin2rtc.core.HttpRequest
import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.core.HttpTransport
import java.io.IOException

// Shared fakes of the modules' tests: a recording HTTP transport with canned answers, the fixtures of real cameras

/** Runs [block] (suspend calls allowed: it is inlined) and returns the [E] it throws; fails when it throws nothing or something else */
public inline fun <reified E : Throwable> expectThrows(block: () -> Unit): E {
    val thrown = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }
    if (thrown is E) return thrown
    throw AssertionError("expected ${E::class.simpleName}, got ${thrown ?: "nothing"}", thrown)
}

/**
 * Records every request and answers from [handler] (default: 200, empty). Throwing an [IOException] from the handler
 * simulates no answer at all.
 */
public class RecordingTransport(public var handler: suspend (HttpRequest) -> HttpResponse = { HttpResponse(200) }) : HttpTransport {
    public val requests: MutableList<HttpRequest> = mutableListOf()

    public val last: HttpRequest get() = requests.last()

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return handler(request)
    }
}

/** The no-answer failure a transport throws when offline */
public class Offline : IOException("offline")

/**
 * Answers of real cameras (`src/testFixtures/resources/onvif`: captured from an Amcrest IP8M-2493E and a DoorBird D101S,
 * addresses and identifiers replaced; the `ptz-*` PTZ answers are written after the ONVIF PTZ specification and common
 * camera answers; the audio backchannel fixtures (`media1-`/`media2-profiles-audio-output.xml`, `backchannel-describe-*`,
 * `describe-without-backchannel.sdp`) after the ONVIF specification and documented firmware answers)
 */
public object Fixtures {
    public fun read(name: String): String =
        Fixtures::class.java.getResourceAsStream("/onvif/$name")?.bufferedReader()?.use { it.readText() } ?: error("fixture $name")
}
