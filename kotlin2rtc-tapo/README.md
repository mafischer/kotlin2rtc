# kotlin2rtc-tapo (planned)

Two-way audio for TP-Link Tapo cameras through their private protocol. **No code yet**: the implementation is being
built and tested inside the application this library was extracted from, and will be ported here once it works
against real cameras. This module exists so the layout and the dependency direction (`core`, `codec`) are settled.

## Why a private protocol

Tapo cameras speak ONVIF and RTSP only after **Third-Party Compatibility** is turned on in the Tapo app (Me → Tapo Lab
→ Third-Party Compatibility) and a camera account is created (camera settings → Advanced Settings → Camera Account).
Even then they offer no ONVIF Profile T backchannel: RTSP gives video and the camera's microphone, nothing goes back to
the speaker. The Tapo app talks through the camera's own streaming service instead.

## What the module will do

Following the protocol as documented by [go2rtc](https://github.com/AlexxIT/go2rtc) (`pkg/tapo`) and
[pytapo](https://github.com/JurajNyiri/pytapo) (see [THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md)):

- **Connection**: HTTP on **port 8800** of the camera (`/stream`), a long-lived multipart connection carrying JSON
  requests and media in both directions.
- **Authentication**: HTTP Digest (the core module's `HttpDigest`) with user `admin` and, as the password, a hash of
  the TP-Link cloud account password (MD5, or SHA-256 on newer firmware, upper-case hex), not the camera account of
  Third-Party Compatibility. Newer firmware encrypts the session (key exchange with a nonce, AES); both variants are
  needed.
- **Talk session**: a JSON request opening the talk channel (half or full duplex, "aec" mode), answered with a session
  id that the audio parts carry.
- **Audio**: G.711 A-law (PCMA) at 8 kHz, packed in **MPEG-TS** (PES in 188-byte TS packets with PAT/PMT), sent as
  multipart parts of the session. The encoder side reuses `kotlin2rtc-codec` (`G711`, `Decimator`, `BackchannelEncoder`
  style 20 ms blocks); the MPEG-TS muxer will live here.
- **API shape**: the same `BackchannelLink`-like surface as the RTSP backchannel, so callers can switch between an ONVIF
  camera and a Tapo camera without changing their audio pipeline.

## Notes for users

- The cloud password hash is a secret equivalent to the password for this protocol: keep it on the device, never log
  it (the library will treat it like `Credentials`).
- Cameras on firmware that removed local access, or with Third-Party Compatibility off, cannot be reached locally.
