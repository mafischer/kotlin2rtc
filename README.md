# kotlin2rtc

A Kotlin/JVM library for talking to IP cameras: find them with WS-Discovery, read and control them over ONVIF
(device information, media profiles, stream and snapshot URIs, PTZ, speaker level) and send audio to their speaker
over the ONVIF Profile T RTSP backchannel (G.711, G.726, AAC, L16 in RTP).

Think of it as the Kotlin counterpart of the camera-protocol side of [go2rtc](https://github.com/AlexxIT/go2rtc), as an
embeddable library rather than a server. It is pure Kotlin/JVM (JDK 17 bytecode, no Android dependency), so it runs
on Android and on any JVM. Video playback is out of scope: hand the RTSP URL to a player (Media3/ExoPlayer, FFmpeg,
GStreamer...).

> **Status: pre-1.0.** The API may change between 0.x releases. The code comes from a shipping Android app, where it
> talks to real cameras, and is covered by unit tests against recorded camera answers and local fakes.

## Modules

| Module | What it does | Depends on |
|--------|--------------|------------|
| `kotlin2rtc-core` | `HttpTransport` and `LanHttpTransport` (cleartext HTTP/1.1 only to addresses you allow), `LocalNetwork` and `LinkSubnet` (which addresses count as local), HTTP Digest (RFC 7616/2617), the namespace-tolerant SOAP XML reader (`SoapXml`, no DTDs), `RtspUrls`, `Credentials`, and the `Logger` and `Clock` hooks | kotlinx-coroutines-core |
| `kotlin2rtc-codec` | G.711 µ-law/A-law, G.726 ADPCM (16/24/32/40 kbit/s, RFC 3551 and AAL2 packing), AAC RTP framing (RFC 3640 AAC-hbr/lbr, RFC 6416 LATM) around an `AacEncoder` you supply, L16, RTP/RTCP packetization, RTSP interleaving, a decimating FIR filter, `BackchannelEncoder` (sound + microphone to RTP) | core |
| `kotlin2rtc-onvif` | WS-Discovery (`WsDiscovery`), the ONVIF client (`OnvifClient`: WS-Security UsernameToken with the camera's clock, Digest fallback), parser and models, PTZ (`PtzSession`, optical zoom detection), speaker level (`OnvifSpeakerLevel`, SetAudioOutputConfiguration), the credential-free camera probe (`NetworkCameraProbe`, `CameraIdentity`) | core |
| `kotlin2rtc-rtsp` | The ONVIF Profile T audio backchannel: `RtspBackchannelClient` (DESCRIBE with `Require: www.onvif.org/ver20/backchannel`, SETUP, PLAY, RTP over TCP interleaved, keep-alive, TEARDOWN), `BackchannelSdp`, `BackchannelEngine`, the 20 ms audio pump `BackchannelPump` | core, codec |
| `kotlin2rtc-tapo` | *Planned:* the TP-Link Tapo private-protocol backchannel. No code yet, see [its README](kotlin2rtc-tapo/README.md) | core, codec |
| `kotlin2rtc-cli` | A small command line sample: `discover`, `probe`, `talk` (not published) | onvif, rtsp |

Group `io.github.mafischer`, artifacts named like the modules, packages `io.github.mafischer.kotlin2rtc.<module>`.
Not on Maven Central yet (version `0.1.0-SNAPSHOT`); see "Using it" below.

## Supported protocols

- **WS-Discovery** Probe/ProbeMatches for `NetworkVideoTransmitter` on 239.255.255.250:3702, and unicast probes of one
  address (`NetworkCameraProbe.identify`).
- **ONVIF Profile S**: GetSystemDateAndTime, GetDeviceInformation, GetCapabilities with GetServices as fallback,
  GetProfiles (Media1, and Media2 profiles with `Configurations`), GetStreamUri, GetSnapshotUri, GetEndpointReference;
  PTZ ContinuousMove, Stop, GetNodes, GetStatus. Authentication: WS-Security UsernameToken (PasswordDigest, timestamp
  from the camera's clock) and HTTP Digest (MD5, MD5-sess, qop auth) when the camera answers 401. SOAP 1.2 and 1.1
  faults; authentication faults are told apart (`OnvifError.Unauthorized`).
- **ONVIF Profile T audio backchannel** (ONVIF Streaming Specification 5.3) over RTSP/1.0 with Digest or Basic, RTP and
  RTCP interleaved in the RTSP TCP connection. Codecs, best first: PCMU/PCMA (8 to 48 kHz), AAC-LC (`mpeg4-generic`
  AAC-hbr/AAC-lbr, `MP4A-LATM` with `cpresent=0`; needs an `AacEncoder`), G.726-16/24/32/40 (RFC 3551 or AAL2), L16.
- **Speaker level**: GetAudioOutputConfigurations / GetAudioOutputConfigurationOptions / SetAudioOutputConfiguration
  (Media1, else Media2).

## Camera notes

- **Amcrest / Dahua**: answer ONVIF with an HTTP Digest challenge (handled), and offer a PCMU 8 kHz backchannel after
  `Require: www.onvif.org/ver20/backchannel` (Profile T). Their RTSP Digest has no qop (handled).
- **Cameras whose backchannel is AAC only**: `BackchannelSdp` reports what they `offered`; sending needs an
  `AacEncoder` (Android's MediaCodec, or a JVM encoder). kotlin2rtc ships no AAC encoder, so without one the
  backchannel stays silent.
- **Cameras without a backchannel** answer `551 Option not supported` or list no `sendonly` audio:
  `BackchannelError.Unsupported`.
- **Many cameras report service and stream URLs with another address** (another interface, `0.0.0.0`): the client
  rewrites them to the host you reached the camera at (`OnvifUrls.onHost`).
- **TP-Link Tapo**: ONVIF and RTSP work only after turning on **Third-Party Compatibility** in the Tapo app (Me → Tapo
  Lab → Third-Party Compatibility) and creating a camera account (camera settings → Advanced Settings → Camera
  Account); use that account, not the TP-Link ID. Tapo cameras do not offer the ONVIF backchannel: talking to them needs
  their private protocol, planned in `kotlin2rtc-tapo`.

## Quick start

Discovery and ONVIF (suspend functions; run them in a coroutine):

```kotlin
import io.github.mafischer.kotlin2rtc.core.*
import io.github.mafischer.kotlin2rtc.onvif.*

// Which addresses cleartext camera protocols may reach: this device's subnets (on Android from LinkProperties),
// plus private ranges for allowsCamera. Nothing else is contacted.
val network = LocalNetwork(listOf(LinkSubnet.parse("192.168.1.23/24")))

val devices = WsDiscovery(accept = network::allows).discover(timeoutMillis = 3_000)

val credentials = Credentials("admin", readPasswordSomehow())
val client = OnvifClient(LanHttpTransport(network::allowsCamera), devices.first().deviceUrl, credentials)
client.syncClock() // the camera's clock for the WS-Security timestamp
val info = client.deviceInformation() // manufacturer, model, firmware...
val endpoints = client.endpoints() // media, PTZ, events services
val profile = client.profiles(endpoints.mediaUrl).first { it.hasVideo }
val streamUri = client.streamUri(endpoints.mediaUrl, profile.token) // rtsp://... for your player
```

PTZ (commands run in order on your scope):

```kotlin
val ptz = PtzSession(client, endpoints.ptzUrl!!, profile.token, scope, onError = { println("refused: $it") })
ptz.move(PtzDirection.LEFT) // while the button is held
ptz.stop() // on release
ptz.probeZoom() // ptz.opticalZoom: StateFlow<OpticalZoom>
```

Talking to the camera's speaker (blocking calls: use an IO thread):

```kotlin
import io.github.mafischer.kotlin2rtc.codec.BufferPcmSource
import io.github.mafischer.kotlin2rtc.rtsp.*

val url = RtspUrls.withCredentials(streamUri, credentials) // in memory only, never logged
val link = RtspBackchannelClient(url, network::allowsCamera).open() // throws BackchannelError
println("camera takes ${link.media.label}") // e.g. PCMU/8000

// 48 kHz mono floats; the pump decimates and encodes to the camera's codec, one RTP packet every 20 ms
val pump = BackchannelPump(link, BufferPcmSource(samples48k), microphone, events = { println(it) })
pump.start()
pump.talking = true // push to talk: the TalkMicrophone you supply is mixed in
// ...
pump.stop()
link.close() // TEARDOWN
```

Speaker volume: `OnvifSpeakerLevel(client, endpoints.mediaUrl).set(0.6f)`.

Logging is off by default; pass a `Logger` (e.g. `Logger.stderr()`) to the classes that take one. Credentials are
never logged (`Credentials.toString()` hides the password).

## Command line sample

```
./gradlew :kotlin2rtc-cli:installDist
kotlin2rtc-cli/build/install/kotlin2rtc/bin/kotlin2rtc discover
kotlin2rtc-cli/build/install/kotlin2rtc/bin/kotlin2rtc probe 192.168.1.64 --credentials ~/.camera-login
kotlin2rtc-cli/build/install/kotlin2rtc/bin/kotlin2rtc talk rtsp://192.168.1.64:554/stream hello.wav
```

Credentials are asked for on the terminal or read from a file (`--credentials`: user name and password on two lines,
or `user:password`), never taken from arguments; URLs with user info are refused. Only this machine's subnets and
private addresses are contacted.

## Building and testing

Requires a JDK 21 (the bytecode targets JDK 17).

```
./gradlew build            # compile, tests, ktlint, ABI check, aggregated Kover report (build/reports/kover)
./gradlew test             # tests only
./gradlew ktlintFormat     # fix formatting
./gradlew updateKotlinAbi  # after an intended public API change (api/*.api)
```

The tests need no camera and no network: recorded answers of real cameras (`kotlin2rtc-core/src/testFixtures/resources/onvif`,
addresses and identifiers replaced), an in-process fake RTSP camera on loopback and fake transports.

## Using it

Releases are published as files of [GitHub Releases](https://github.com/mafischer/kotlin2rtc/releases) (jars, sources,
javadoc, POM and Gradle Module Metadata, which carries each module's dependencies, plus `SHA256SUMS`). Gradle resolves
them anonymously through an Ivy repository; the dependencies (kotlinx-coroutines, the Kotlin stdlib) come from Maven
Central:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        ivy {
            name = "kotlin2rtc"
            url = uri("https://github.com/mafischer/kotlin2rtc/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier])(.[ext])") }
            metadataSources { gradleMetadata() }
            content { includeGroup("io.github.mafischer") }
        }
    }
}

// build.gradle.kts
dependencies {
    implementation("io.github.mafischer:kotlin2rtc-onvif:0.1.0")
    implementation("io.github.mafischer:kotlin2rtc-rtsp:0.1.0") // brings kotlin2rtc-codec and -core with it
}
```

Pin what you download: Gradle's [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
(`./gradlew --write-verification-metadata sha256 help`) or dependency locking, and compare with the release's `SHA256SUMS`.

To work on kotlin2rtc and an app together, use a composite build instead: `includeBuild("../kotlin2rtc")` in the app's
`settings.gradle.kts` (the version then comes from this checkout).

**Releasing:** push a tag `vX.Y.Z` on `main`; `.github/workflows/release.yml` builds, tests and creates the release with
its files.

## Acknowledgements

kotlin2rtc follows the protocol work of [go2rtc](https://github.com/AlexxIT/go2rtc) (MIT, Alexey Khit) and
[pytapo](https://github.com/JurajNyiri/pytapo) (MIT, Juraj Nyíri); their licences are reproduced in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). G.711 and G.726 follow Sun Microsystems' public-domain reference
implementation.

## License

[ISC](LICENSE), Copyright (c) 2026 Michael Fischer. Issues and pull requests:
https://github.com/mafischer/kotlin2rtc/issues
