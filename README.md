# OpenSDR Radio Host

OpenSDR Radio Host is a standalone open-source RTL-SDR receiver for Android,
with local audio playback, live spectrum and waterfall.

This source package contains the complete standalone Android receiver, its IPC
API and the receiver core. No separate client application is required to build
or use it.

## Project Links

- [Source repository](https://github.com/rafal-dot/opensdr-app/)
- [Website](https://opensdr.app/)
- [Privacy policy](https://opensdr.app/privacy.html)
- [Support contact on Google Play](https://play.google.com/store/apps/details?id=dev.rafaldot.opensdr)
- [Bug reports](https://github.com/rafal-dot/opensdr-app/issues)

For a bug report, include the app version, phone model, Android version, receiver
model and steps to reproduce it. Diagnostics may contain tuned frequencies and
USB/device identifiers. Review them before posting; GitHub issues are public.
For private support or privacy inquiries, open the Google Play listing's
**App support** section and use the developer contact details there. Do not put
personal information in public issues or store reviews.

## Modules

- `radioapi`
  MIT-licensed Android IPC contract used by clients and the host service.
- `opensdrcore`
  Host-side RTL-SDR USB, native bridge, DSP, demodulation, metrics, and audio pipeline.
- `rtlsdrhost`
  Android application and exported bound service that exposes the receiver through `radioapi`.
- `third_party/libusb`
  Bundled libusb source used by the native Android bridge.
- `third_party/rtl-sdr-android`
  Bundled RTL-SDR source used by the native receiver path.

## Runtime Architecture

The intended integration boundary is:

`Client app -> radioapi Messenger IPC -> OpenSDR Radio Host -> opensdrcore -> RTL-SDR USB device -> local AudioTrack playback`

OpenSDR Radio Host owns USB permission, receiver session setup, demodulation, metrics, and local audio playback. Client applications should communicate through `radioapi`; they should not depend directly on `opensdrcore`.

The receiver keeps about 20 seconds of spectrum/waterfall history in memory
while audio is running. Rotating the phone or returning to the Host restores
that history. Retunes keep earlier sections with their own frequency/span
labels instead of erasing the waterfall. History is not saved to disk and does
not survive termination of the Host process.

## Supported Hardware

Current supported hardware path:

- RTL-SDR compatible USB receivers exposed through the bundled rtl-sdr/libusb native stack.

Other SDR families may require different vendor SDKs or native backends and are not part of this source package.

## Build

See `BUILDING.md`.

Short command:

```bash
./gradlew --no-daemon :rtlsdrhost:assembleDebug
```

## Licensing

See `LICENSE.md` and `THIRD_PARTY_NOTICES.md`.

The Host and receiver core are GPL-2.0-or-later. The shared `radioapi` module
is MIT-licensed so independently developed open-source or proprietary clients
can include it, preserving its notice. See `radioapi/LICENSE`. This does not
grant a permissive license to the Host implementation or native dependencies.

OpenSDR Radio Host is distributed as source because it includes GPL-covered RTL-SDR native code. Preserve upstream license texts and notices when redistributing binaries or modified source packages.

## Safety And Legal Notice

OpenSDR Radio Host is a general-purpose radio receiver for hobby listening and experimentation. It is not a safety-critical, navigation or operational decision-making system.

Check local laws before receiving, recording or sharing any radio communications or signals. These activities may be permitted, restricted or prohibited depending on your location, the radio service and your authorization. The ability to receive a signal does not imply legal permission to listen to, record or share it.

## Compatibility

See `COMPATIBILITY.md`.

The IPC surface is intentionally small, but it is not yet a long-term stable public API unless a release explicitly states otherwise.

## Developer Notes

- `DSP_ARCHITECTURE.md` explains the native receiver pipeline, threading model, and metrics.
- `AM_PROFILES.md` documents the JSON profile format used to tune the low-CPU DSP path.
- `DIAGNOSTIC_RECORDING.md` describes APK-only raw IQ / WAV capture for receiver debugging.
- `SOURCE_PACKAGE_MANIFEST.md` describes the source layout and release archives.
