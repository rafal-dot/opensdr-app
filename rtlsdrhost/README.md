# OpenSDR Radio Host

`rtlsdrhost` is the standalone Android app for OpenSDR's direct RTL-SDR receiver path.

Public source: [rafal-dot/opensdr-app](https://github.com/rafal-dot/opensdr-app/).
Website: [opensdr.app](https://opensdr.app/).
Report bugs through [GitHub issues](https://github.com/rafal-dot/opensdr-app/issues);
review diagnostics for private information before posting.
For private support and privacy inquiries, use the developer contact in
**App support** on the [Google Play listing](https://play.google.com/store/apps/details?id=dev.rafaldot.opensdr).

It has two responsibilities:

1. Expose a bound IPC service that external clients can control.
2. Provide receiver controls, audio playback, spectrum and waterfall locally.

It should not contain client-specific workflows or duplicate business rules owned by external clients.

## Modules

- `rtlsdrhost`
  Android application module with:
  - `AmHostService`: exported bound/started foreground service implementing the IPC contract from `radioapi`
  - `MainActivity`: standalone receiver UI
  - `ReceiverServiceLifetime` / `ReceiverForegroundNotification`: playback lifetime, notification and wake lock owned by `AmHostService`

- `radioapi`
  Shared IPC contract and parcelable models used by both the host and the client.

- `opensdrcore`
  Shared host-side SDR implementation module with:
  - `DirectRtlSdrController`: direct USB + native receiver pipeline control
  - `DirectRtlSdrNativeBridge`: JNI bridge to the native library
  - `AmProfileStore`: packaged AM profile loading
  - native RTL-SDR / DSP sources

## Current Data Path

The old single-call JNI path is no longer used.

Current flow:

1. `AmHostService` receives IPC commands via `Messenger`.
2. `opensdrcore.DirectRtlSdrController` configures the device and receiver pipeline.
3. Native code starts a continuous receiver stream with a PCM ring buffer.
4. Kotlin pulls PCM blocks from the native ring buffer.
5. Kotlin performs the final lightweight software resampling before writing to `AudioTrack`.
6. Status and diagnostics are returned to clients through `AmHostStatus`.

This means the host now relies on the native streaming bridge:

- `startAmStream`
- `readAmStreamPcm`
- `amStreamMetrics`
- `stopAmStream`

and does not rely on the previous one-shot `processAmIq` bridge anymore.

The `AmHostService` and `*AmStream*` names are kept for IPC and native bridge compatibility, even though the host now exposes additional modulation modes.

## Background Playback Lifetime

The service owning the USB/audio controller is also the started `mediaPlayback`
foreground service. Closing or losing the last client connection does not stop
an active listening session. A separate notification-only service cannot provide
this guarantee for a bound controller service.

Playback start acquires a partial wake lock and shows the persistent notification.
Stop, USB detach, native stream failure and service destruction release foreground
resources. An idle service is bound-only. `START_NOT_STICKY` avoids restarting an
empty notification and wake lock after process death; playback must be started by
the user again in that case.

Client implementations should not treat a single missing/invalid reply as a
power-off event. Preserve the binding and last known receiver state until a
disconnect is confirmed, and correlate replies so a delayed status cannot
complete a later command. This does not require changing the wire contract.

### Device Regression Checks

1. Start audio from a compatible client; leave it running with the screen locked
   for at least 30 minutes, then reopen the client. Playback must remain active.
2. Start audio from the Host UI and close that UI; playback must survive the last
   client unbinding. Reopening the UI must reconnect to the same session.
3. Stop from the notification with no UI open. Both audio and notification must
   stop; reopening the UI must not restart audio automatically.
4. Unplug USB during playback and while idle. The next status must show no receiver;
   there must be no orphan notification or wake lock.
5. Briefly stall/reconnect the client during playback, then issue Stop/Start. A late
   status must not overwrite the newer command's state or reset the radio session.

Android/OEM process termination and USB power loss can still interrupt reception.
For an unexplained stop, capture Host lifecycle logs, client IPC diagnostics and
the direct RTL trace, including any `Audio writer stopped` / `Native USB stream ended` entry.

## Standalone App

`MainActivity` is intentionally simple:

- bind to the host service
- expose one power button for the full `USB -> connect -> tune -> start audio` sequence
- use the same button for `stop audio -> disconnect`
- show the latest status payload
- render a small live spectrum and a local waterfall history from host status updates

Additional controls should use the same IPC surface. Keep client-specific workflows outside this app.

### Viewing A Session Started By Another Client

Opening or refreshing the Host UI is read-only: it requests status and displays
the active receiver's frequency, modulation and squelch. This applies to an
already connected session as well as streaming audio, and subsequent changes
from another client update the controls. No saved UI preferences are sent to the receiver
on binding, status updates or reconnecting.

Standalone preferences are drafts while the receiver is idle. They are applied
when the user explicitly presses Power On or Tune. Choosing a history entry only
fills the draft controls; it does not immediately change the active modulation
or squelch. Manual editing remains available: modulation and squelch callbacks
change only their respective settings, and Tune/step controls apply the draft.
Unchanged status polling does not erase text being typed. An external retune
replaces old drafts with the new receiver target. Explicit Refresh discards drafts
and restores the current receiver values without applying any settings.

The frequency input shows the logical channel selected by the active client. The FFT axis
uses the hardware IQ center (search center plus the zero-IF avoidance offset and
frequency correction); these can differ for airband channel designators.
Spectrum display settings remain local and do not affect receiver settings.

Clients should follow external PPM, gain, Bias-T and squelch edits after startup
without overwriting unsent local changes. The partial settings command merges only
those controls on the service thread and cannot overwrite Host DSP experiments.
Per-frequency squelch history should use the actually tuned frequency as its key.
Clients may apply their saved profile when starting a new app session; ordinary
listening-control updates do not reapply a profile.

The status field `receiverSettingsError` distinguishes a failed configuration from
accepted settings. A failure invalidates the core's incremental-update baseline:
even an identical retry re-applies hardware and pipeline settings. Requested
preferences remain saved for a retry/reconnect, but are not presented as confirmed
while this error is set. Both applications must use the same `radioapi` build.

Receiver limits live in `radioapi/ReceiverSettingRules.kt` and are shared with
client controls and core sanitization. Manual/custom opening delay is 100-500 ms;
Auto uses 0-60 ms opening and 500-1000 ms closing when its threshold is nonzero.
These reflect existing native timing restrictions rather than new DSP tuning.

Device check: start AM reception from a client with a different frequency/modulation
saved in the Host history, then open Host and Refresh. Verify that reception is
uninterrupted, controls match the client and no `UPDATE_SETTINGS` or `TUNE` is sent by
the Host UI. Change frequency/squelch in the client, return to Host (or use split
screen) and verify the updated controls and spectrum. Also test standalone Power
On, choosing history followed by Tune, and editing squelch with Auto or SQ off
initially active.

Also check the reverse direction: change modulation and squelch in Host, then
change only PPM through the client's partial-control command. The Host changes
must remain active. Enter PPM
outside -200..200 and verify both panels show the accepted limit after applying.
Exercise unplug/replug during settings changes and confirm errors are visible and
an identical retry is not skipped as already applied.

## Design Rules

- Keep `rtlsdrhost` minimal and host-focused.
- Keep cross-process types only in `radioapi`.
- Keep SDR execution logic in `opensdrcore`.
- Avoid parallel APIs for the same transport path.
- Remove obsolete JNI/Kotlin wrappers when the transport model changes.
- Prefer documentation in this file when the host contract or data path changes.

## What To Update When The Contract Changes

If the IPC or streaming model changes, update all of:

- `radioapi/src/main/java/org/opensdr/radioapi/RtlSdrIpcContract.kt`
- `radioapi/src/main/java/org/opensdr/radioapi/AmHostModels.kt`
- `opensdrcore`
- `rtlsdrhost/src/main/java/org/opensdr/amhost/AmHostService.kt`
- `rtlsdrhost/src/main/java/org/opensdr/amhost/MainActivity.kt`
- this file

## Expert Settings And Diagnostics

- **AM speech filter (250-2500 Hz)** is enabled by default in Expert receiver
  settings / Edit active settings. Apply saves the choice; it survives restarts,
  Tune, squelch edits and profile changes. Only envelope/synchronous AM is filtered.
  Compatible clients can control the same value using partial listening updates.
- **Expert receiver settings** is collapsed by default. It exposes all JSON profiles,
  AM bandwidth, sample rate, extra Hz offset, calibration, audio boost, optional DSP
  and squelch timings. There is no automatic Apply when opening the card.
- **Edit active settings** creates a draft from the current receiver, not history.
  Apply is explicit. Concurrent changes require Reload; the service also checks the
  baseline before writing, including profile identity. Sample-rate edits need a new
  receiver connection. Native safety limits still apply.
- **Diagnostics** contains RF/AF/clipping, carrier offset, USB and PCM/FIFO/cadence
  counters, full active settings and trace. Share report opens Android's chooser;
  Clear trace only clears diagnostics, not playback.
- Client applications can open this card using MainActivity's
  `RtlSdrIpcContract.KEY_SHOW_DIAGNOSTICS` extra. This is a viewing action, never a
  tune, profile selection or audio-start command.

Both APKs need updating for the partial-control command. Older full-settings clients
can still use the existing IPC messages and unchanged Parcelable models.
