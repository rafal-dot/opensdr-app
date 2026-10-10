# Diagnostic IQ and Audio Recording

This is an internal testing tool, not a feature of the Google Play release.
It records the receiver already running in OpenSDR, including sessions controlled
by another client. Starting/stopping capture never tunes the receiver, applies
saved settings, starts reception, or stops playback.

## Build and Install

```powershell
.\gradlew.bat --no-parallel :rtlsdrhost:assembleDiagnostic
```

`rtlsdrhost/build/outputs/apk/diagnostic/opensdr-diagnostic.apk` is optimized like
release, uses debug signing, and keeps the existing application ID. Debug APKs
also include recording. Do not install diagnostic and production builds side by
side: they represent the same receiver. Android requires matching signatures to
update an installed app; replacing a Play-signed installation can require
uninstalling it. External clients protected by the signature IPC permission must
use the same signing certificate as this host. The diagnostic variant is not
marked debuggable; use debug for debugger attachment.

Production APKs and AABs use only `src/production` no-op integration boundaries.
Neither Kotlin recorder sources nor native capture/JNI sources are compiled into
release. Gradle rejects debug/diagnostic bundle packaging, and release assemble /
bundle tasks inspect the resulting DEX/native libraries for recorder markers.
The normal store command remains `:rtlsdrhost:bundleRelease`.

## Record

1. Start the receiver, either here or through a connected client.
2. Scroll below Diagnostics to **Diagnostic recording**.
3. Select **Choose folder**, create/select e.g. `Documents/OpenSDR/Recordings`,
   and grant access using the Android folder picker. Use local phone storage;
   cloud document providers are rejected. No microphone or all-files permission
   is requested. The chosen folder grant is remembered.
4. Press **Start recording**. Return to the client if desired; capture belongs
   to the foreground receiver service, not its Activity.
5. Use **Mark problem** when an artifact is heard. It creates a timestamped
   event, not an audio modification. Press **Stop recording** and wait for the
   saved message before copying files to a computer.

Files are user-owned documents and are not automatically removed when the app is
uninstalled. Use the system file manager/USB to copy or remove them. There is no
automatic upload. Check local rules before recording radio communications.

## Clear Recordings

After **Stop recording**, wait until saving finishes, then use **Clear recording
folder**. The button is also available while idle for clearing older recordings.
Two confirmation steps show the selected folder: **Continue**, then **Delete
everything**. Back/Cancel or dismissing the dialog does not delete anything.

This permanently removes **all files and subfolders** in that folder, not only
recognized recording files. The selected folder itself remains available for
the next recording. Always choose a dedicated recordings folder; the cleanup
refuses storage volume roots. Copy anything needed elsewhere before confirming.

Deletion runs in the background and shares the recorder's process-wide storage
lock. It cannot overlap capture, draining or finalization, even if the UI status
is stale or the service is recreated. New capture and folder selection are
disabled during cleanup. The receiver can keep playing throughout. A completion
message reports deleted file/subfolder counts. Permission or I/O failures report
partial progress; already deleted data cannot be restored. The folder is fully
enumerated before the first deletion, so an enumeration failure leaves it intact.

## Session Files

Each session has a timestamp and random suffix to prevent collisions:

- `input-001.sigmf-data`: byte-exact, interleaved unsigned 8-bit I then Q (`cu8`),
  directly after a successful USB read and **before all software DSP**. Hardware
  tuner/ADC filtering and gain have already happened and cannot be undone.
- `input-001.sigmf-meta`: SigMF JSON, sample rate, center frequency, device,
  settings and sample-indexed configuration observations, including whether the
  selected frequency is a channel designator and its expected carrier offsets.
  Additional diagnostic
  fields use the `opensdr:` namespace. See <https://sigmf.org/>.
- `audio-001.wav`: 48 kHz mono signed 16-bit little-endian PCM accepted by
  `AudioTrack`, **after playback resampling**. Partial writes are recorded only
  for the samples actually accepted. It includes squelch and starvation fill,
  but not the microphone, Android mixer effects, Bluetooth or speaker output.
- `events.jsonl`: each IQ/audio block's monotonic time and session-relative
  sample counters, accepted audio's queued output frames, native produced-PCM
  counter, user markers, control transitions, DSP metrics (up to 10 Hz), and
  periodic playback/receiver status observations.
- `session.json`: format/build identification, progress, segment counts, timing
  caveats, and final `complete` / `incomplete` status and reason.

IQ and audio files rotate independently after 600 seconds of their nominal
sample count. They are not padded, repeated or dropped at rotation. At 240 ksps
an IQ chunk is 288 MB and an audio chunk 57.6 MB. Approximate total rates are
34.56 MB/min at 240 ksps, 63.36 MB/min at 480 ksps and 120.96 MB/min at 960 ksps,
plus small metadata/event logs. This version records **one** audio track; the
earlier optional pre-playback WAV proposal is not enabled.

## Timing and Failure Handling

Never align IQ and WAV merely by filename or assume one IQ second equals one
second of wall-clock delivery. USB blocks arrive in bursts; playback buffers and
the adaptive resampler introduce delay. Audio captured initially can originate
from IQ received before capture began. Packet times use `CLOCK_MONOTONIC` /
`System.nanoTime`; they describe observation, not exact antenna arrival or DAC
presentation. Use the counters, queue estimates and event times together.
The native produced counter is session-relative and may include an in-flight
DSP block whose input predates capture. Internal DSP filter/AGC state is not
serialized, so offline replay needs warm-up and is not initially bit-identical.

`control_begin` and subsequent `receiver_state` bracket a control transition.
USB buffering can straddle a retune; metadata frequency changes are observations,
not guarantees that every sample at that boundary has settled. A sample-rate
change or receiver stop ends capture; begin a fresh recording after restarting.
Raw USB has no hardware sequence numbers, so absence of reported USB failures
does not prove there were no upstream lost samples.

Producers copy only into a bounded, preallocated 24 MiB native RAM queue. This
holds over 10 seconds at 960 ksps with audio. A background writer owns SAF/file
I/O, buffers output, and flushes/checkpoints roughly every three seconds. No
disk access occurs under the receiver/audio locks. Capture is off by default
and does not resume automatically on app launch.

Queue overflow stops capture at the first packet that cannot be stored; it does
not block radio or silently stitch across missing data. I/O failure, revoked
access or less than 128 MiB available space reports an error while leaving radio
alone. Stop drains the queue and finalizes WAV sizes/metadata. A killed process,
power loss or failing filesystem can still leave incomplete files; periodic WAV
headers are recovery checkpoints, not a guarantee of durable storage. Check
`session.json` before treating a capture as complete.

## Verification

```bash
./gradlew --no-daemon --no-parallel :opensdrcore:testDebugUnitTest :rtlsdrhost:testDebugUnitTest
cmake -S opensdrcore/src/main/cpp -B build/dsp-tests
cmake --build build/dsp-tests
ctest --test-dir build/dsp-tests --output-on-failure
```

Before relying on a device capture, check local-folder permissions, Start/Stop,
marking, background playback, tuning transitions, USB removal, low-space errors,
10-minute rotation, both cleanup confirmations/cancellation, disabled cleanup
during recording/finalization, and reopening WAV/IQ in desktop analysis tools. Compare USB
failure/drop and playback cadence counters with recording off and on. Android
filesystem throughput and vendor foreground-service behavior require a real
device test; host tests cannot establish those guarantees.
