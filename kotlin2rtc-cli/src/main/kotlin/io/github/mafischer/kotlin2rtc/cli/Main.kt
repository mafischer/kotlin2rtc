package io.github.mafischer.kotlin2rtc.cli

import io.github.mafischer.kotlin2rtc.codec.BufferPcmSource
import io.github.mafischer.kotlin2rtc.core.Credentials
import io.github.mafischer.kotlin2rtc.core.LanHttpTransport
import io.github.mafischer.kotlin2rtc.core.LinkSubnet
import io.github.mafischer.kotlin2rtc.core.LocalNetwork
import io.github.mafischer.kotlin2rtc.core.Logger
import io.github.mafischer.kotlin2rtc.core.RtspUrls
import io.github.mafischer.kotlin2rtc.onvif.OnvifClient
import io.github.mafischer.kotlin2rtc.onvif.OnvifError
import io.github.mafischer.kotlin2rtc.onvif.OnvifUrls
import io.github.mafischer.kotlin2rtc.onvif.WsDiscovery
import io.github.mafischer.kotlin2rtc.rtsp.BackchannelError
import io.github.mafischer.kotlin2rtc.rtsp.BackchannelPump
import io.github.mafischer.kotlin2rtc.rtsp.RtspBackchannelClient
import io.github.mafischer.kotlin2rtc.rtsp.TalkMicrophone
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintStream
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.system.exitProcess

private const val USAGE = """kotlin2rtc: talk to IP cameras on this network

Usage:
  kotlin2rtc discover [--timeout <ms>]
  kotlin2rtc probe <host[:port]> [--credentials <file>]
  kotlin2rtc talk <rtsp-url> <file.wav> [--credentials <file>]

Credentials are never taken from the command line (they would end up in the shell history and the process list):
they are asked for on the terminal, or read from a file given with --credentials (first line the user name, second
line the password; or one line user:password). An rtsp-url with user info is refused.
Only private addresses and this machine's subnets are contacted (cleartext protocols never go to the internet)."""

/** Exit codes */
internal object Exit {
    const val OK = 0
    const val USAGE = 2
    const val FAILED = 1
}

/**
 * The command line: [allows] is which addresses may be contacted (this machine's subnets and private ranges; loopback in
 * tests), [prompt] asks for credentials when no file is given.
 */
internal class Cli(
    private val out: PrintStream = System.out,
    private val allows: (InetAddress) -> Boolean = machineNetwork()::allowsCamera,
    private val prompt: () -> Credentials? = ::promptCredentials,
    private val logger: Logger = Logger.None,
) {
    fun run(args: List<String>): Int {
        val (positional, options) = parse(args) ?: return usage()
        return when (positional.firstOrNull()) {
            "discover" -> discover(options["timeout"]?.toLongOrNull() ?: WsDiscovery.DEFAULT_TIMEOUT_MILLIS)
            "probe" -> positional.getOrNull(1)?.let { probe(it, credentials(options) ?: return Exit.USAGE) } ?: usage()
            "talk" -> if (positional.size == 3) talk(positional[1], File(positional[2]), options) else usage()
            else -> usage()
        }
    }

    private fun usage(): Int {
        out.println(USAGE)
        return Exit.USAGE
    }

    /** Positional arguments and `--name value` options; null for an option without a value */
    private fun parse(args: List<String>): Pair<List<String>, Map<String, String>>? {
        val positional = mutableListOf<String>()
        val options = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg.startsWith("--")) {
                options[arg.removePrefix("--")] = args.getOrNull(index + 1) ?: return null
                index += 2
            } else {
                positional += arg
                index += 1
            }
        }
        return positional to options
    }

    private fun credentials(options: Map<String, String>): Credentials? {
        val file = options["credentials"]
        val credentials = if (file != null) readCredentials(File(file)) else prompt()
        if (credentials == null) out.println("No credentials")
        return credentials
    }

    fun discover(timeoutMillis: Long): Int = runBlocking {
        val devices = WsDiscovery(accept = allows).discover(timeoutMillis)
        if (devices.isEmpty()) out.println("No ONVIF device answered within $timeoutMillis ms")
        for (device in devices) out.println("${device.name} (${device.hardware}) ${device.deviceUrl} ${device.endpoint}")
        Exit.OK
    }

    fun probe(target: String, credentials: Credentials): Int = runBlocking {
        val host = target.substringBeforeLast(':').takeIf { ':' in target && !target.endsWith("]") } ?: target
        val port = target.substringAfterLast(':', "").toIntOrNull()?.takeIf { ':' in target && !target.endsWith("]") } ?: 80
        val client = OnvifClient(LanHttpTransport(allows), OnvifUrls.deviceService(host, port), credentials)
        try {
            client.syncClock()?.let { out.println("Camera clock offset: ${client.clockOffsetMillis} ms") }
            val info = client.deviceInformation()
            out.println("Device: ${info.displayName ?: "unnamed"} firmware ${info.firmwareVersion.ifEmpty { "?" }}")
            val endpoints = client.endpoints()
            out.println("Media: ${endpoints.mediaUrl}")
            endpoints.ptzUrl?.let { out.println("PTZ: $it") }
            endpoints.audioOutputs?.let { out.println("Audio outputs: $it") }
            val profiles = client.profiles(endpoints.mediaUrl)
            for (profile in profiles) {
                val audio = if (profile.hasAudioOutput) ", audio output" else ""
                val ptz = if (profile.hasPtz) ", PTZ" else ""
                out.println("Profile ${profile.token}: ${profile.name} ${profile.encoding} ${profile.width}x${profile.height}$ptz$audio")
            }
            val profile = profiles.firstOrNull { it.hasVideo } ?: profiles.firstOrNull() ?: return@runBlocking Exit.OK
            val stream = client.streamUri(endpoints.mediaUrl, profile.token)
            out.println("Stream: $stream")
            backchannel(RtspUrls.withCredentials(stream, credentials))
            Exit.OK
        } catch (e: OnvifError) {
            out.println("ONVIF failed: ${e.message}")
            Exit.FAILED
        }
    }

    /** The RTSP backchannel check: a DESCRIBE with the ONVIF backchannel requirement */
    private fun backchannel(url: String) {
        try {
            val sdp = RtspBackchannelClient(url, allows, logger = logger).describe()
            val media = sdp.media
            if (media != null) {
                out.println("Backchannel: ${media.label} (offered ${sdp.offered.joinToString()})")
            } else {
                out.println("Backchannel: none usable (offered ${sdp.offered.joinToString().ifEmpty { "nothing" }})")
            }
        } catch (e: BackchannelError) {
            out.println("Backchannel: ${e.message}")
        }
    }

    fun talk(url: String, wav: File, options: Map<String, String>): Int {
        if (RtspUrls.split(url).second != null) {
            out.println("Refused: credentials in the URL. Use the prompt or --credentials <file>.")
            return Exit.USAGE
        }
        val samples = try {
            Wav.read(wav.readBytes())
        } catch (e: IllegalArgumentException) {
            out.println("Not a usable WAV file: ${e.message}")
            return Exit.USAGE
        }
        val credentials = credentials(options) ?: return Exit.USAGE
        val link = try {
            RtspBackchannelClient(RtspUrls.withCredentials(url, credentials), allows, logger = logger).open()
        } catch (e: BackchannelError) {
            out.println("Backchannel failed: ${e.message}")
            return Exit.FAILED
        }
        out.println("Talking: ${link.media.label}, ${samples.size / Wav.RATE} s")
        val source = BufferPcmSource(samples)
        var failed = false
        val pump = BackchannelPump(link, source, TalkMicrophone.None, { failed = true }, logger = logger)
        pump.start()
        while (!source.isFinished && !failed) Thread.sleep(POLL_MILLIS)
        // Let the camera play what it buffered
        Thread.sleep(TAIL_MILLIS)
        pump.stop()
        link.close()
        out.println(if (failed) "The camera closed the backchannel" else "Done")
        return if (failed) Exit.FAILED else Exit.OK
    }

    private companion object {
        const val POLL_MILLIS = 20L
        const val TAIL_MILLIS = 300L
    }
}

/** Credentials from a file: two lines (user, password) or one line `user:password`; null when empty */
internal fun readCredentials(file: File): Credentials? {
    val lines = file.readLines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    return when {
        lines.size >= 2 -> Credentials(lines[0], lines[1])
        lines.size == 1 && ':' in lines[0] -> Credentials(lines[0].substringBefore(':'), lines[0].substringAfter(':'))
        lines.size == 1 -> Credentials(lines[0], "")
        else -> null
    }
}

/** Asks on the terminal (the password without echo); null without a terminal */
private fun promptCredentials(): Credentials? {
    val console = System.console() ?: return null
    val user = console.readLine("User: ")?.takeIf { it.isNotEmpty() } ?: return null
    val password = console.readPassword("Password: ") ?: return null
    return Credentials(user, String(password))
}

/** This machine's subnets (every interface that is up), for the cleartext address rule */
private fun machineNetwork(): LocalNetwork {
    val subnets = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }.map { LinkSubnet(it.address, it.networkPrefixLength.toInt()) }
    }.getOrDefault(emptyList())
    return LocalNetwork(subnets)
}

public fun main(args: Array<String>) {
    exitProcess(Cli(logger = if (System.getenv("KOTLIN2RTC_DEBUG") != null) Logger.stderr(Logger.Level.DEBUG) else Logger.None).run(args.toList()))
}
