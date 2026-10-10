# OpenSDR Core

`opensdrcore` is the shared SDR implementation module used by `rtlsdrhost`.

It is intentionally not a client API module. Clients should talk to the host through `radioapi`, not depend on this module directly.

The core depends on `radioapi` for shared receiver-setting limits. Desired settings
are persisted separately from the confirmed configuration tracked by
`ReceiverConfigurationApplication`. Partial native failures invalidate confirmation,
remain visible in `receiverSettingsError`, and force a full retry even if the next
request contains identical values. Disconnect clears that session's confirmation.

## Responsibilities

- direct USB device handling for RTL-SDR hardware
- receiver session configuration
- AM profile loading from packaged assets
- JNI bridge to native DSP
- AM stream lifecycle
- host-facing controller state and diagnostics

## Contents

- `org.opensdr.sdrcore.DirectRtlSdrController`
  Main host-side controller for USB, pipeline setup, tuning, and audio lifecycle.
- `org.opensdr.sdrcore.DirectRtlSdrNativeBridge`
  Kotlin bridge to the native library.
- `org.opensdr.sdrcore.AmProfileStore`
  Loader for built-in AM profiles from `src/main/assets/am_profiles`.
- `org.opensdr.sdrcore.DirectRtlSdrForegroundController`
  Thin callback interface that lets the host provide app-specific foreground-service behavior without moving that code into the library.
- `src/main/cpp`
  Native RTL-SDR + AM DSP implementation.

## Boundaries

This module should contain:

- SDR execution logic
- host-side runtime state
- DSP and JNI code

This module should not contain:

- client UI logic
- aviation data access
- IPC parcelables or service message ids
- app-specific Android entry points

## Native Library

The native library is built as `opensdr_rtlsdr`.

JNI symbols are bound to `org.opensdr.sdrcore.DirectRtlSdrNativeBridge`.

## Portable DSP Tests

No Android device is needed to run the native regression tests:

```sh
cmake -S opensdrcore/src/main/cpp -B /tmp/opensdr-dsp-tests -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build /tmp/opensdr-dsp-tests
ctest --test-dir /tmp/opensdr-dsp-tests --output-on-failure
```

Use a separate build directory and `-DOPENSDR_SANITIZER=thread` or
`-DOPENSDR_SANITIZER=address,undefined` for sanitizer checks on a supported host.
Kotlin playback tests run with `./gradlew --no-daemon --no-parallel :opensdrcore:testDebugUnitTest`.

AM carrier selection runs bounded spectral checks before the narrow IQ filter.
`am_carrier_selection_test` covers adjacent-carrier rejection, lock/reacquisition,
deep modulation and USB block boundaries at all three supported input rates.
It complements the existing narrowband-noise and squelch regression tests.

Carrier selection uses the expected RF offsets supplied by the controller through
`NativeAmConfig.carrierTargets` and the live `tuneHz` call. The same calculation
covers startup, retuning, and receiver frequency correction. An airband channel
designator has a nominal RF carrier and a literal-frequency alternative; ordinary
Host tuning uses one literal target. These offsets are relative to the actual
hardware center, including its zero-IF avoidance offset and Hz correction.

Candidates within 1500 Hz of either target take priority over unrelated peaks;
the nominal candidate gets a 6 dB preference. The full +/-6500 Hz search remains
available when neither target has a valid carrier. An existing lock is not stolen
by a louder adjacent channel. A better target challenger must exceed the incumbent
by at least 3 dB in raw power and 6 dB in preference-weighted power, and persist
for five FFT observations (about 200 ms). A valid target can also replace an
unrelated fallback lock. Between fallback candidates, switching needs 12 dB and
eight observations (about 320 ms). Loss of the incumbent retains the faster
two-observation acquisition. All decisions reuse the existing 25 FFTs/s, with no
additional per-sample DSP or denoising.

`am_carrier_context_test` exercises target priority, confirmation/hysteresis,
separate short bursts, wide-search fallback and target changes. Pipeline tests
also check recovered PCM, stronger adjacent stations and settings/retune updates.
This selection policy does not change the receiver's DC removal: a real carrier
landing exactly at hardware zero IF can still be attenuated by that separate path.

`am_speech_filter_test` covers the default-on 250-2500 Hz AM output filter,
its steeper Butterworth response (sixth-order high-pass, eighth-order low-pass),
smooth bypass, preserved FIFO/carrier/squelch state and complete bypass for other
modulations. The controller persists `amSpeechFilterEnabled`
independently of profile defaults; native processing is in `am_speech_filter.cpp`.

`Low CPU Clean Voice` currently enables `pipeline.simpleAmEnabled`: envelope AM
with essential channel filtering, one base AGC and the optional speech filter.
Extra voice shaping, noise processing, post leveler and boost are bypassed, not
deleted. Set this JSON flag to `false` and reapply the profile defaults to restore
the previous path. FM/SSB, carrier selection, FIFO and resampling are unchanged.

## Third-Party Sources

`opensdrcore` builds against bundled sources from:

- `third_party/libusb`
- `third_party/rtl-sdr-android`

See [NOTICE.md](NOTICE.md) for the repository-level pointer to upstream licensing files.

## When To Update This Module

Update `opensdrcore` when you change:

- DSP behavior
- USB/session handling
- AM profile format or defaults
- host-side runtime metrics
- native bridge lifecycle

If the client-facing transport changes, update `radioapi` and `rtlsdrhost` as well.
