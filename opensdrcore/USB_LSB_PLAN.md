# USB / LSB Plan

This document describes the lowest-risk path for adding real `USB` and `LSB` support to `opensdrcore`.

Historical design notes: SSB support is now implemented. The state and rollout
below describe the original proposal, not the current release's feature set.

## Current State

Today the AM pipeline supports:

- envelope AM demodulation
- synchronous AM demodulation
- a sideband-focus preprocessing step

That sideband focus is not yet a true SSB demodulator. It biases IQ toward one sideband before AM demodulation, but it does not implement a full product-detector SSB path.

## Goal

Add real:

- `LSB`
- `USB`

without breaking the current AM path and without forcing immediate client UI changes.

## Recommended Rollout

### Phase 1: Native DSP support in `opensdrcore`

- Introduce explicit SSB demod modes in the native pipeline.
- Keep AM modes unchanged.
- Reuse the existing tuned/narrowband filtering stages where possible.
- Replace `apply_sideband_focus()` as the main mechanism for SSB playback with a proper product-detector path.

Expected implementation shape:

- new native demod mode values for `USB` and `LSB`
- local oscillator / carrier reinsertion path for SSB
- audio output derived from one selected sideband after complex mixing

### Phase 2: Kotlin bridge and controller wiring

- Extend `DirectRtlSdrNativeBridge` config plumbing.
- Extend `DirectRtlSdrController` setting sanitization and defaults.
- Preserve current AM defaults for existing clients.

### Phase 3: IPC contract

Only if the client needs separate user-visible controls:

- extend `radioapi.HostReceiverSettings`
- extend `radioapi.AmHostStatus`

If `USB/LSB` can be represented through existing `demodMode` + `sidebandMode`, avoid IPC churn in the first iteration.

### Phase 4: Host sample UI

- expose the new modes in `rtlsdrhost/MainActivity`
- verify that the host can exercise `USB` and `LSB` without client changes

### Phase 5: Client integrations

- only after host/core behavior is verified
- expose user-facing mode selection if product requirements justify it

## Suggested DSP Shape

Minimum viable SSB path:

1. keep channel filtering and decimation
2. add a selectable BFO/product-detector step
3. select upper or lower sideband by frequency shift sign
4. run existing audio conditioning stages after demod

This should be implemented as a distinct branch, not as extra heuristics inside envelope AM.

## API Impact

Preferred first step:

- no change to service binding
- no new message ids
- no new client workflow

Possible later change:

- explicit modulation enum instead of overloaded `demodMode`

That can wait until more than AM/SSB exists.

## Risks

- regressions in current AM demod if branches are not isolated cleanly
- confusion between current sideband-focus behavior and true SSB
- overloading `demodMode` too far before introducing a clearer modulation model

## Acceptance Criteria

- AM envelope behavior remains unchanged
- synchronous AM remains unchanged
- `USB` and `LSB` produce intelligible audio on appropriate signals
- no regression in startup, buffering, or host IPC state flow
