package io.github.mafischer.kotlin2rtc.onvif

import io.github.mafischer.kotlin2rtc.core.Credentials
import io.github.mafischer.kotlin2rtc.core.HttpResponse
import io.github.mafischer.kotlin2rtc.core.HttpTransport
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.element
import io.github.mafischer.kotlin2rtc.onvif.OnvifFixtures.operation
import io.github.mafischer.kotlin2rtc.testing.RecordingTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pan, tilt and zoom of one camera: in order, the clock once, refusals reported; the optical zoom probe */
@OptIn(ExperimentalCoroutinesApi::class)
class PtzSessionTest {
    private val errors = mutableListOf<OnvifError>()

    private fun session(transport: HttpTransport, scope: CoroutineScope, enabled: () -> Boolean = { true }): PtzSession {
        val client = OnvifClient(transport, FakeOnvifCamera.DEVICE_URL, Credentials(FakeOnvifCamera.USERNAME, FakeOnvifCamera.PASSWORD))
        return PtzSession(client, FakeOnvifCamera.PTZ_URL, "MainStream", scope, onError = { errors += it }, enabled = enabled)
    }

    private fun TestScope.session(transport: HttpTransport, enabled: () -> Boolean = { true }) = session(transport, this as CoroutineScope, enabled)

    @Test
    fun `holding moves, releasing stops, in that order, the clock read once`() = runTest {
        val camera = FakeOnvifCamera()
        val gate = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            // The first move answers late: the stop must still come after it
            if (request.operation == "ContinuousMove" && !gate.isCompleted) gate.await()
            camera.send(request)
        }
        val ptz = session(transport)
        ptz.move(PtzDirection.LEFT)
        ptz.stop()
        runCurrent()
        assertEquals(listOf("GetSystemDateAndTime", "ContinuousMove"), transport.requests.map { it.operation })
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("GetSystemDateAndTime", "ContinuousMove", "Stop"), transport.requests.map { it.operation })
        assertEquals("MainStream", transport.last.element("ProfileToken"))
        assertTrue(transport.requests[1].body!!.decodeToString().contains("<tt:PanTilt x=\"-0.5\" y=\"0.0\""))

        ptz.move(PtzDirection.ZOOM_IN)
        testScheduler.advanceUntilIdle()
        assertTrue(transport.last.body!!.decodeToString().contains("<tt:Zoom x=\"0.5\""))
        // No second clock request
        assertEquals(1, transport.requests.count { it.operation == "GetSystemDateAndTime" })
        assertEquals(emptyList<OnvifError>(), errors)
    }

    @Test
    fun `a session that is not enabled sends nothing`() = runTest {
        val transport = RecordingTransport { FakeOnvifCamera().send(it) }
        var enabled = false
        val ptz = session(transport) { enabled }
        ptz.move(PtzDirection.LEFT)
        ptz.stop()
        testScheduler.advanceUntilIdle()
        assertTrue(transport.requests.isEmpty())
        enabled = true
        ptz.move(PtzDirection.LEFT)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("GetSystemDateAndTime", "ContinuousMove"), transport.requests.map { it.operation })
    }

    @Test
    fun `a nudge moves, waits and stops`() = runTest {
        val transport = RecordingTransport { FakeOnvifCamera().send(it) }
        val ptz = session(transport)
        ptz.nudge(PtzDirection.UP)
        runCurrent()
        assertEquals("ContinuousMove", transport.last.operation)
        testScheduler.advanceUntilIdle()
        assertEquals("Stop", transport.last.operation)
        assertEquals(NUDGE_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun `refused commands are reported, a reset cancels and forgets`() = runTest {
        val transport = RecordingTransport { request -> if (request.operation == "GetSystemDateAndTime") FakeOnvifCamera().send(request) else HttpResponse(500) }
        val ptz = session(transport)
        ptz.move(PtzDirection.RIGHT)
        ptz.stop()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(OnvifError.Http(500), OnvifError.Http(500)), errors)

        ptz.reset()
        ptz.stop()
        testScheduler.advanceUntilIdle()
        // The client keeps the camera's clock
        assertEquals(1, transport.requests.count { it.operation == "GetSystemDateAndTime" })
        assertEquals(3, errors.size)
    }

    @Test
    fun `the session learns once whether the camera zooms optically and where its lens is`() = runTest {
        val camera = FakeOnvifCamera()
        val transport = RecordingTransport { camera.send(it) }
        val ptz = session(transport)
        assertEquals(OpticalZoom.UNKNOWN, ptz.opticalZoom.value)
        ptz.probeZoom()
        ptz.probeZoom()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("GetSystemDateAndTime", "GetNodes", "GetStatus"), transport.requests.map { it.operation })
        assertEquals(OpticalZoom(true, 0.0), ptz.opticalZoom.value)
        assertTrue(ptz.opticalZoom.value.atWidest)

        // The lens zooms in (the fake's lens is fast): the next read finds it at its closest
        ptz.move(PtzDirection.ZOOM_IN)
        ptz.stop()
        ptz.refreshZoom()
        testScheduler.advanceUntilIdle()
        assertEquals("GetStatus", transport.last.operation)
        assertTrue(ptz.opticalZoom.value.atClosest)
        assertEquals(1, transport.requests.count { it.operation == "GetNodes" })
        assertEquals(emptyList<OnvifError>(), errors)
    }

    @Test
    fun `a pan and tilt camera has no optical zoom, and is never asked where its lens is`() = runTest {
        val transport = RecordingTransport { FakeOnvifCamera(opticalZoom = false).send(it) }
        val ptz = session(transport)
        ptz.probeZoom()
        testScheduler.advanceUntilIdle()
        assertEquals(OpticalZoom.NONE, ptz.opticalZoom.value)
        ptz.refreshZoom()
        testScheduler.advanceUntilIdle()
        assertEquals(0, transport.requests.count { it.operation == "GetStatus" })
    }

    @Test
    fun `a camera refusing GetNodes stays unknown, quietly, and is asked again next time`() = runTest {
        val transport = RecordingTransport { request ->
            if (request.operation == "GetNodes") HttpResponse(500) else FakeOnvifCamera().send(request)
        }
        val ptz = session(transport)
        ptz.probeZoom()
        testScheduler.advanceUntilIdle()
        assertEquals(OpticalZoom.UNKNOWN, ptz.opticalZoom.value)
        assertEquals(emptyList<OnvifError>(), errors)
        ptz.probeZoom()
        testScheduler.advanceUntilIdle()
        assertEquals(2, transport.requests.count { it.operation == "GetNodes" })
        ptz.reset()
        assertEquals(OpticalZoom.UNKNOWN, ptz.opticalZoom.value)
    }

    @Test
    fun `a session that was not enabled probes once it is`() = runTest {
        val transport = RecordingTransport { FakeOnvifCamera().send(it) }
        var enabled = false
        val ptz = session(transport) { enabled }
        ptz.probeZoom()
        testScheduler.advanceUntilIdle()
        assertTrue(transport.requests.isEmpty())
        enabled = true
        ptz.probeZoom()
        testScheduler.advanceUntilIdle()
        assertEquals(OpticalZoom(true, 0.0), ptz.opticalZoom.value)
    }

    @Test
    fun `the speaker level goes over the configuration's range`() = runTest {
        val camera = FakeOnvifCamera()
        val client = OnvifClient(camera, FakeOnvifCamera.DEVICE_URL, Credentials(FakeOnvifCamera.USERNAME, FakeOnvifCamera.PASSWORD))
        val level = OnvifSpeakerLevel(client, FakeOnvifCamera.MEDIA_URL)
        assertTrue(level.set(0.25f))
        assertEquals(25, camera.outputLevel)
        val none = OnvifSpeakerLevel(OnvifClient(FakeOnvifCamera(speakerLevel = false), FakeOnvifCamera.DEVICE_URL, null), FakeOnvifCamera.MEDIA_URL)
        assertEquals(false, none.set(0.5f))
    }
}
