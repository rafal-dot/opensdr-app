# OpenSDR Radio API

`radioapi` is the thin Android IPC contract shared between:

- compatible client applications
- `rtlsdrhost` as host

It must stay transport-focused. Do not move SDR execution logic, JNI code, USB handling, or client business rules into this module.

## License And Client Integration

The project-owned code, build configuration and documentation in this module
are licensed under [MIT](LICENSE), separately from the GPL-licensed Host and
receiver core. Open-source and proprietary clients, including paid applications,
may include this API under the MIT terms. Preserve its copyright and license
notice; the module bundles the full text in
`src/main/assets/licenses/radioapi-MIT.txt` for inclusion in Android applications.
That file must stay identical to `LICENSE`.

Clients use the API to send commands and receive status through Android
Messenger IPC. They do not link or copy the Host, DSP or native receiver
libraries. This MIT grant covers only this module, not those implementations or
third-party dependencies. Earlier GPL grants for previously distributed copies
are not revoked.

The official Host service requires a compatibly signed client. MIT licensing
does not bypass Android signature permissions; see the Host's compatibility
documentation before distributing a new client.

## Scope

This module contains:

- service binding constants
- message ids
- bundle key names
- parcelable request and status models

This module does not contain:

- DSP code
- host controller code
- client UI logic
- aviation data logic

## Compatibility Rules

- Additive changes are preferred.
- Existing message ids and bundle keys should remain stable.
- `AmHostStatus` is the host's latest snapshot, not a command result enum.
- `HostReceiverSettings` describes desired host configuration, not guaranteed applied state.
- New modulation support should extend this contract only when the client actually needs new knobs or new status fields.

## Current Commands

- `MSG_GET_STATUS`
  Request the latest full host snapshot.
- `MSG_REQUEST_USB_PERMISSION`
  Ask the host to start the Android USB permission flow.
- `MSG_CONNECT`
  Open and configure the RTL-SDR transport/session.
- `MSG_DISCONNECT`
  Tear down the session and release the device.
- `MSG_START_AUDIO`
  Start receiver streaming/playback pipeline.
- `MSG_STOP_AUDIO`
  Stop receiver streaming/playback pipeline.
- `MSG_TUNE`
  Retune to a frequency in Hz.
- `MSG_UPDATE_SETTINGS`
  Push full receiver settings to the host. Optional `KEY_EXPECTED_SETTINGS` and
  `KEY_EXPECTED_PROFILE_ID` guard explicit expert edits against concurrent changes.
  A mismatch returns `receiverSettingsError` without changing the receiver.
- `MSG_UPDATE_LISTENING_CONTROLS`
  Atomically merge only PPM, Bias-T, gain mode/value, squelch mode/level and the
  optional AM speech filter from `HostReceiverSettings` under `KEY_SETTINGS`.
  All other fields are ignored.
  Profile-owned DSP, bandwidth, timing and modulation remain unchanged.
- `MSG_UPDATE_CALIBRATED_LISTENING_CONTROLS`
  Atomically merge frequency correction in Hz, PPM, Bias-T, squelch mode/level
  and the optional AM speech filter from the same `KEY_SETTINGS` payload.
  Unlike message 11, this command preserves Host gain mode/value. All other
  expert settings are also retained. Explicit zero Hz clears the correction.
- `MSG_SELECT_AM_PROFILE`
  Select a built-in AM profile and optionally apply its defaults.
  `KEY_PRESERVE_FREQUENCY_CORRECTION = true` keeps the current Hz correction
  even if the profile specifies a different default. Absent/false retains the
  original behavior. PPM, Bias-T, gain, SQ level and the speech filter remain
  independent of profile defaults in either case.
- `MSG_CLEAR_TRACE_LOG`
  Clear host-side trace diagnostics.

## Status Model

Replies advertise `KEY_LISTENING_CONTROLS_SUPPORTED = true` for message 11 and
`KEY_CALIBRATED_LISTENING_CONTROLS_SUPPORTED = true` for message 12 and
calibration-preserving profile selection. Check the appropriate capability before
sending the command (including profile selection with preservation enabled).
An absent or unconfirmed capability must not trigger a full settings write or a
fallback to message 11: these have different calibration/gain semantics. Ask for
a Host update instead. Parcelable layouts and old message IDs are unchanged.
Test clients with the corresponding Host release.

Host MainActivity accepts `KEY_SHOW_DIAGNOSTICS` as an optional boolean extra.
Opening it displays diagnostics without tuning or applying receiver settings.

### Optional AM Speech Filter

Use `Bundle.putHostReceiverSettings` / `readHostReceiverSettings` for both
`KEY_SETTINGS` and `KEY_EXPECTED_SETTINGS`, and `putHostStatus` / `readHostStatus`
for replies. They carry `amSpeechFilterEnabled` as a separate nullable Boolean
Bundle field (`<payload-key>.am_speech_filter_enabled`), while `@IgnoredOnParcel`
keeps the existing positional Parcelable layout unchanged.

- In a request, null/missing means preserve the receiver's saved value.
- In status, null/missing means the Host does not advertise filter support.
- False is an explicit disabled choice, not an omitted/default value.
- An older Host safely ignores the extension. Clients must not display it as
  applied on that Host and should ask for a Host update to enable this option.
- Profile selection and older clients' full settings writes preserve this value.

`AmHostStatus` intentionally mixes:

- transport/device state
- currently applied receiver settings
- live runtime metrics
- diagnostics text

That is convenient for a small host/client system, but future protocol evolution should keep the distinction clear in documentation and naming.

The `AmHost*` model names and `BIND_AM_HOST` action are stable compatibility names. The user-visible host application name is OpenSDR Radio Host because the transport now supports more than AM.
