# Compatibility Notes

## Package And Service

Current Android application ID (device installation and Google Play):

```text
dev.rafaldot.opensdr
```

The internal namespace remains `org.opensdr.amhost`. For explicit intents, use
the application ID above as the package and the fully qualified component name:

- Service: `org.opensdr.amhost.AmHostService`
- Activity: `org.opensdr.amhost.MainActivity`

These names are exposed by `RtlSdrIpcContract.SERVICE_PACKAGE`,
`SERVICE_CLASS_NAME` and `MAIN_ACTIVITY_CLASS_NAME`. Do not derive class names
from the application ID.

Current bound service action:

```text
org.opensdr.radioapi.action.BIND_AM_HOST
```

Current binding permission:

```text
org.opensdr.radioapi.permission.BIND_AM_HOST
```

The permission is signature-protected. A client app must be signed compatibly or otherwise distributed in a way that satisfies Android permission rules for the intended deployment.

### Pre-release Package Change

Earlier test builds used `org.opensdr.amhost` as their application ID. The
Google Play build uses `dev.rafaldot.opensdr` and is a separate installation, not
an update of the old package. Preferences and tuning history are not migrated.
Uninstall the old test Host before testing the new Host/client pair, and grant
USB permission again when requested. Update client apps together with the Host;
old clients still target the old application ID. The binding action and
signature permission remain unchanged.

## IPC Contract

The IPC contract lives in:

```text
radioapi/src/main/java/org/opensdr/radioapi/
```

Important files:

- `RtlSdrIpcContract.kt`
- `AmHostModels.kt`

Client applications should use `radioapi` and should not call `opensdrcore` directly.

The shared API is licensed under MIT; see `radioapi/LICENSE`. Clients may be
open-source or proprietary, including paid applications, while preserving the
API notice. This does not change the signature permission above or the GPL
terms for the Host/core implementation. Do not copy Host or native receiver
code into a client on the assumption that the API's MIT license covers it.

## Versioning Policy

Partial listening-control updates have two additive commands:

- `MSG_UPDATE_LISTENING_CONTROLS` (11), advertised by `KEY_LISTENING_CONTROLS_SUPPORTED`:
  PPM, Bias-T, gain, squelch and optional speech filter; Hz correction is unchanged.
- `MSG_UPDATE_CALIBRATED_LISTENING_CONTROLS` (12), advertised by
  `KEY_CALIBRATED_LISTENING_CONTROLS_SUPPORTED`: Hz correction, PPM, Bias-T,
  squelch and optional speech filter; gain is unchanged. This capability also
  guarantees `KEY_PRESERVE_FREQUENCY_CORRECTION` support in profile selection (9).

These flags are in the reply Bundle; the status Parcelable layout is unchanged.
Clients must check the matching capability before writes or calibration-preserving
profile selection. Ask for a Host update if it is absent or unconfirmed; do not
emulate partial writes using stale full settings or fall back from 12 to 11.
Existing full-settings clients can continue using message 8. Optional expected
settings/profile Bundle keys allow expert editors to reject concurrent changes.

Pre-1.0 compatibility policy:

- Message IDs and parcelable fields may still change.
- New fields should be added in a backward-compatible way where practical.
- Removing or reinterpreting existing fields should require a documented compatibility note.
- Client integrations should be tested with the corresponding Host release.

Recommended future policy:

- Add explicit `radioapi` version metadata.
- Add host capability flags to `AmHostStatus`.
- Keep deprecated IPC fields for at least one public release before removal.

## Client Integration Checklist

- Query Android package visibility for `dev.rafaldot.opensdr` if targeting modern Android.
- Bind with `org.opensdr.radioapi.action.BIND_AM_HOST`.
- Use `Messenger` IPC messages from `RtlSdrIpcContract`.
- Treat `AmHostStatus` as canonical for receiver state.
- Handle unavailable host, missing USB device, missing USB permission, pending connection, and streaming failure.
- Do not assume that tuning implies audio streaming.
- Do not assume that a connected session means a USB device is still physically attached.

## Known Limitations

- Current hardware path is RTL-SDR focused.
- The standalone receiver UI does not implement every feature of a desktop SDR suite.
- Radio reception quality depends on antenna, gain, local RF environment, Android USB behavior, and device performance.
