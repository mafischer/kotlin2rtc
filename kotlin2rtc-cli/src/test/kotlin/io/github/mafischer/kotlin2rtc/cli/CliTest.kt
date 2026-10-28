package io.github.mafischer.kotlin2rtc.cli

import io.github.mafischer.kotlin2rtc.core.Credentials
import io.github.mafischer.kotlin2rtc.testing.FakeRtspCamera
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The command line against local fakes only (loopback): usage, credentials, WAV files, probe and talk */
class CliTest {
    private val output = ByteArrayOutputStream()
    private val camera = FakeRtspCamera()
    private val onvif = MockWebServer()

    @TempDir
    lateinit var dir: File

    // The fakes listen on loopback, which the real command never contacts
    private fun cli(credentials: Credentials? = Credentials(camera.username, camera.password)) =
        Cli(PrintStream(output, true), allows = { it.isLoopbackAddress }, prompt = { credentials })

    private val printed: String get() = output.toString()

    @AfterEach
    fun tearDown() {
        camera.close()
        onvif.shutdown()
    }

    @Test
    fun `usage for unknown commands and missing arguments`() {
        assertEquals(Exit.USAGE, cli().run(emptyList()))
        assertEquals(Exit.USAGE, cli().run(listOf("fly")))
        assertEquals(Exit.USAGE, cli().run(listOf("talk", "rtsp://127.0.0.1/x")))
        assertEquals(Exit.USAGE, cli().run(listOf("probe", "127.0.0.1", "--credentials")))
        assertTrue(printed.contains("Usage:"))
    }

    @Test
    fun `credentials come from a file or the prompt, never from the URL`() {
        assertEquals(Credentials("admin", "pa:ss"), readCredentials(File(dir, "two").apply { writeText("admin\npa:ss\n") }))
        assertEquals(Credentials("admin", "secret"), readCredentials(File(dir, "one").apply { writeText("admin:secret\r\n") }))
        assertEquals(Credentials("admin", ""), readCredentials(File(dir, "user").apply { writeText("admin") }))
        assertNull(readCredentials(File(dir, "empty").apply { writeText("\n") }))
        val wav = File(dir, "a.wav").apply { writeBytes(wav(8_000, 1, ShortArray(80))) }
        assertEquals(Exit.USAGE, cli().run(listOf("talk", camera.urlWith(), wav.path)))
        assertTrue(printed.contains("Refused: credentials in the URL"))
        assertEquals(0, camera.connections.get())
        // No credentials at all
        assertEquals(Exit.USAGE, cli(credentials = null).run(listOf("talk", camera.url, wav.path)))
    }

    @Test
    fun `WAV files are read as mono at 48 kHz`() {
        val stereo = wav(48_000, 2, shortArrayOf(16_384, 0, -16_384, -16_384))
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), Wav.read(stereo), 1e-4f)
        val upsampled = Wav.read(wav(8_000, 1, ShortArray(800) { 8_192 }))
        assertEquals(4_800, upsampled.size)
        assertTrue(upsampled.all { it == 0.25f })
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 1f), Wav.resample(floatArrayOf(0f, 1f), 24_000), 1e-6f)
        assertThrows<IllegalArgumentException> { Wav.read("not a wav file".toByteArray()) }
        assertThrows<IllegalArgumentException> { Wav.read(wav(8_000, 1, ShortArray(4), bits = 24)) }
        assertEquals(Exit.USAGE, cli().run(listOf("talk", camera.url, File(dir, "bad.wav").apply { writeText("x") }.path)))
    }

    @Test
    fun `talk plays a WAV file on the camera's backchannel`() {
        val wav = File(dir, "tone.wav").apply { writeBytes(wav(16_000, 1, ShortArray(1_600) { (8_000 * kotlin.math.sin(it / 5.0)).toInt().toShort() })) }
        val credentials = File(dir, "login").apply { writeText("${camera.username}\n${camera.password}\n") }
        assertEquals(Exit.OK, cli(credentials = null).run(listOf("talk", camera.url, wav.path, "--credentials", credentials.path)))
        assertTrue(camera.awaitTeardown())
        // 100 ms of audio: at least 5 packets of 20 ms
        assertTrue(camera.frames.count { it.first == 0 } >= 5)
        assertTrue(printed.contains("Talking: PCMU/8000"))
        assertTrue(printed.contains("Done"))
    }

    @Test
    fun `talk reports a refused backchannel`() {
        camera.setupStatus = 453
        val wav = File(dir, "a.wav").apply { writeBytes(wav(8_000, 1, ShortArray(80))) }
        assertEquals(Exit.FAILED, cli().run(listOf("talk", camera.url, wav.path)))
        assertTrue(printed.contains("Backchannel failed: busy"))
    }

    @Test
    fun `probe shows the device, its profiles and the backchannel`() {
        onvif.dispatcher = FakeOnvif(rtsp = camera.url)
        onvif.start()
        assertEquals(Exit.OK, cli().run(listOf("probe", "127.0.0.1:${onvif.port}")))
        assertTrue(printed.contains("Device: Acme Cam-1 firmware 1.2"), printed)
        assertTrue(printed.contains("Profile main: Main H264 1920x1080, audio output"), printed)
        assertTrue(printed.contains("Stream: ${camera.url}"), printed)
        assertTrue(printed.contains("Backchannel: PCMU/8000 (offered PCMU, PCMA)"), printed)
    }

    @Test
    fun `probe reports a camera that refuses the login`() {
        onvif.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(401)
        }
        onvif.start()
        assertEquals(Exit.FAILED, cli().run(listOf("probe", "127.0.0.1:${onvif.port}")))
        assertTrue(printed.contains("ONVIF failed: unauthorized"))
    }

    /** A minimal ONVIF device on loopback whose stream is [rtsp] */
    private class FakeOnvif(private val rtsp: String) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            val answer = when {
                "GetSystemDateAndTime" in body -> {
                    "<tds:GetSystemDateAndTimeResponse/>"
                }
                "GetDeviceInformation" in body -> {
                    "<tds:GetDeviceInformationResponse><tds:Manufacturer>Acme</tds:Manufacturer>" +
                        "<tds:Model>Cam-1</tds:Model><tds:FirmwareVersion>1.2</tds:FirmwareVersion></tds:GetDeviceInformationResponse>"
                }
                "GetCapabilities" in body -> {
                    "<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr>http://${request.requestUrl!!.host}:" +
                        "${request.requestUrl!!.port}/onvif/media_service</tt:XAddr></tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>"
                }
                "GetProfiles" in body -> {
                    "<trt:GetProfilesResponse><trt:Profiles token=\"main\"><tt:Name>Main</tt:Name><tt:VideoEncoderConfiguration>" +
                        "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>" +
                        "</tt:VideoEncoderConfiguration><tt:AudioOutputConfiguration token=\"ao\"/></trt:Profiles></trt:GetProfilesResponse>"
                }
                "GetStreamUri" in body -> {
                    "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>${rtsp.replace("&", "&amp;")}</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"
                }
                else -> {
                    return MockResponse().setResponseCode(400)
                }
            }
            return MockResponse().setHeader("Content-Type", "application/soap+xml").setBody(
                "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:tt=\"http://www.onvif.org/ver10/schema\" " +
                    "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"><s:Body>$answer</s:Body></s:Envelope>",
            )
        }
    }

    /** A PCM WAV file */
    private fun wav(rate: Int, channels: Int, samples: ShortArray, bits: Int = 16): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
            putShort((channels * 2).toShort()).putShort(bits.toShort())
            put("data".toByteArray()).putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }
}
