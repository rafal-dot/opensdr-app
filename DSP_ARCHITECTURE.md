# DSP Architecture

This document describes the native receiver pipeline used by OpenSDR Radio Host.

The implementation is intentionally small and low-CPU oriented. It is not a general-purpose SDR framework. The host optimizes for local RTL-SDR reception, stable audio playback, and simple control from an Android client.

## Runtime Path

```text
RTL-SDR USB bulk read
-> native stream worker
-> AmPipeline
-> native PCM ring buffer
-> Kotlin PCM reader
-> lightweight Kotlin resampler/AudioTrack writer
-> local speaker/headphones
```

Important files:

- `opensdrcore/src/main/cpp/opensdr_rtlsdr.cpp`
- `opensdrcore/src/main/cpp/am_pipeline.h`
- `opensdrcore/src/main/cpp/am_pipeline.cpp`
- `opensdrcore/src/main/cpp/am_carrier_tracker.h` / `am_carrier_tracker.cpp`
- `opensdrcore/src/main/java/org/opensdr/sdrcore/DirectRtlSdrNativeBridge.kt`
- `opensdrcore/src/main/java/org/opensdr/sdrcore/DirectRtlSdrController.kt`

## Threading Model

The native side has one stream worker thread while audio is active.

The worker:

1. Reads IQ blocks from RTL-SDR through librtlsdr.
2. Processes IQ through `AmPipeline`.
3. Writes PCM samples into a native ring buffer.
4. Updates level, squelch, drift, and spectrum metrics.

Kotlin reads PCM blocks from the native ring buffer and writes them to `AudioTrack`. The native ring buffer deliberately decouples USB/DSP cadence from Android audio scheduling jitter.

## Lock Ownership

Native code separates control, PCM, and DSP synchronization:

- `g_mutex`: device/session lifecycle and librtlsdr configuration.
- `g_stream_mutex`: stream worker state, PCM ring buffer, live metrics, and condition-variable signaling.
- Each `AmPipeline` has a mutex held once per IQ block, also used by configuration, reset, and retune calls. FIR histories cannot be rebuilt concurrently with processing. The DSP lock is released before the worker publishes PCM under `g_stream_mutex`.
- Error text has its own mutex, so reporting a failed USB read does not race with JNI status queries.
- `SpectrumHistory` has a separate short-held mutex. A reader copies completed frames under this lock, then encodes its IPC reply after releasing it. History readers never acquire the PCM or DSP locks.

Do not hold `g_mutex` while blocking on PCM reads. Do not call back into Android from the native stream worker.

USB broadcasts, notification actions, and IPC commands are serialized on the host service's controller handler. Shutdown is queued on that same handler rather than freeing the pipeline from Android's main thread during a command.

AudioTrack write errors (including a zero-progress blocking write) end the writer loop. The writer publishes a track-specific failure and asks the host to enqueue a controller refresh, even without a visible client. That serialized refresh disconnects the failed session and reports the error, so Power is released and a single click can start again. Failures from an already replaced track are ignored. Telemetry reads a timestamped playback-buffer snapshot instead of taking the blocking writer lock. The host UI allows only one outstanding status poll, shared with its Refresh button; command replies cannot acknowledge an unrelated poll. Direct SDK users should likewise dispatch `requestControllerRefresh` onto their controller thread, or keep calling `refreshState()`.

The build compiles upstream librtlsdr through `rtl_usb_transport.c`. This small adapter gives otherwise unlimited synchronous bulk reads a 200 ms timeout without editing the external checkout or changing asynchronous transfers. Partial timed-out reads are processed, including unmatched I/Q bytes. Device removal ends the worker immediately; repeated empty/failed reads end it after ten attempts, with a short interruptible backoff. Native metrics report whether the worker is still running so playback can stop instead of repeating stale PCM.

## Pipeline Stages

The AM path in `AmPipeline` performs the following high-level stages:

1. Convert unsigned RTL-SDR IQ bytes into centered float IQ samples.
2. Apply DC blocking.
3. Apply the wide acquisition/anti-alias FIR and first decimation, preserving off-center carriers and their sidebands.
4. Select a carrier within +/-6500 Hz, using the same FFT's wider context to reject recognizable AM sideband peaks.
5. Translate that carrier to zero, then apply the second FIR/decimation and narrow, centered IQ channel filter.
6. Estimate carrier levels and demodulate AM envelope or synchronous AM.
7. Apply DC blocking, squelch and base audio AGC. Legacy profiles can also enable audio shaping and enhancements; simple AM bypasses them.
8. Apply the optional AM output speech filter (250-2500 Hz).
9. Apply final overload protection and convert to `int16` PCM.

SSB/NFM share the decimation infrastructure but retain their own acquisition widths, phase steering and demodulation. They do not use the AM spectral selector or output speech filter.

WFM has a separate path: wide RF filtering to 240 ksps, instantaneous-frequency demodulation, 75 us deemphasis, then audio filtering and decimation to 48 kHz mono PCM. It bypasses AM carrier steering and the narrow AM IQ filter. The host requests 200 kHz RF bandwidth in WFM and restores the stored AM bandwidth on return to AM; it does not overwrite the user's AM preference. This additional work runs only in WFM.

## AM Output Speech Filter

The default Clean Voice profile now enables the reversible `simpleAmEnabled`
path: IQ/DC and anti-alias filtering, carrier selection and centered channel
filtering, envelope AM, DC blocking, squelch, one existing base AGC, the optional
250-2500 Hz filter, and final overload protection. Its centered IQ low-pass uses
up to 3250 Hz cutoff, rather than the old 2300 Hz voice-dependent cutoff. Extra
audio HP/LP shaping, blanker, denoise, notch, sideband focus, speech enhancer,
post leveler, boost and the dynamic clip guard are bypassed, not removed.
PCM buffering/resampling are unchanged; carrier acquisition is described below. See
`AM_PROFILES.md` for effective settings and rollback instructions.

`am_speech_filter.h/.cpp` adds a fixed 250-2500 Hz band-pass to envelope and
synchronous AM, enabled by default and persisted independently of profile JSON.
It uses a sixth-order Butterworth high-pass at 250 Hz and an eighth-order
Butterworth low-pass at 2500 Hz (seven biquads total). The cutoffs remain -3 dB;
the stronger edges do not stack extra attenuation inside the speech passband.
The lower and upper asymptotic roll-offs are 36 and 48 dB/octave respectively.
2500-3000 Hz is a smooth transition band, not a hard gate at 3000 Hz. At 48 kHz
PCM, attenuation is approximately 48 dB at 100 Hz, 13 dB at 3000 Hz, 34 dB at
4000 Hz and 50 dB at 5000 Hz. Coefficients are calculated only during
configuration; sample processing is allocation-free, without FFT or adaptive
noise detection. This adds three biquads compared with the original filter.

The filter is after the existing AGC/leveler and before the final soft clipper.
Carrier acquisition, squelch detectors, RF bandwidth and gain-control detectors
are independent of this output-filter toggle. Legacy profiles retain their
additional shaping; simple AM bypasses it. Neither path guarantees a flat total
receiver response. The output filter cannot recover speech lost before this stage or repair
RF overload. SSB/NFM/WFM bypass it completely.

A 10 ms crossfade handles live enable/disable. Filter-only changes preserve
carrier lock and queued PCM; the audible change follows the existing playback
latency instead of flushing audio. Retune/reset clears filter history. In Host,
use Expert receiver settings / Edit active settings to change the filter; clients
can include it in partial listening controls. Older clients omit it and preserve
the Host choice. See `radioapi/README.md` for the additive Bundle transport.

`am_speech_filter_test` checks frequency response, exact bypass, live crossfade,
impulse decay, AM integration, unchanged carrier/squelch metrics and queued PCM,
and bit-identical SSB/NFM/WFM output with the switch on/off.

The simple-AM regressions also check ignored enhancement overrides, preserved
upper speech band at all input rates, switching back to the legacy path, and
bit-identical FM/SSB with the simple-AM flag on or off. These are synthetic
regressions; comparison against another receiver still needs a listening test,
preferably with identical recorded IQ.

Removing the old voice cascade intentionally admits more upper-band noise as
well as speech. The legacy noise regressions retain their original SINAD limits;
simple AM is checked separately for correct carrier lock and a bounded noise
cost (up to 3.5 dB in the synthetic adjacent-noise comparison). This is not a
claim of improved SNR. End-to-end simple-AM tests also require less than 5 dB
attenuation at 2500 Hz relative to 1 kHz across all supported IQ input rates.

## Carrier Tracking

AM envelope and synchronous modes select a persistent spectral line in `AmCarrierTracker` before centering the narrow IQ filter. A phase discriminator on the entire acquisition band can estimate a frequency between two stations; narrowing around that estimate can remove the wanted carrier and sidebands without reporting IQ or PCM clipping.

The selector analyzes a Hann-windowed block of about 21 ms, at most 25 times per second. At 240/480/960 ksps RF input, the first decimator supplies 48/96/192 ksps IQ; FFT sizes are 1024/2048/4096 respectively, keeping approximately 47 Hz bin spacing without alias-prone subsampling. Window coefficients, FFT roots, bit-reversal indices and scratch storage are preallocated during configuration. There are no allocations or trigonometric calls in the per-sample selector path.

Local maxima must exceed an absolute floor and the median spectral noise floor, with enough energy concentrated near the peak. Two consistent analysis frames confirm acquisition. Subsequent smoothing follows only that line, not an average of unrelated peaks. An already qualified carrier is retained even when a neighbor gets louder. A disappeared carrier can be replaced by a newly confirmed peak; when no candidate exists the last frequency is held rather than sweeping through noise. Confidence expires after three missing frames. Synchronous AM retains its separate fine phase tracking after the centered IQ filter.

AM search spans +/-6.5 kHz about the hardware center, covering both nominal and literal airband channel frequencies and oscillator error. This is the actual peak-selection limit, not a nominal width reduced by a guard margin.

The first FIR must not be a narrow audio-channel filter: filtering before translation could attenuate one speech sideband of an off-center carrier irreversibly. It preserves context out to +/-13.5 kHz, with a Blackman transition margin of `2 * input_rate / (tap_count - 1)` beyond that context, capped at 45% of the first decimator's output rate. With the default 97 taps at 240 ksps, its cutoff is 18.5 kHz. The former 8.9 kHz cutoff already attenuated the 9 kHz upper sideband of a +6.5 kHz carrier by about 6.3 dB. Tap counts and decimation factors are unchanged. The second FIR and narrow IQ filter still operate AFTER carrier translation and keep their previous cutoffs, as do the output audio filters. SSB/NFM/WFM filtering is unchanged.

The same FFT supplies the wider context; only peaks inside +/-6.5 kHz can become tuning candidates. A candidate is rejected as a recognizable AM sideband when a stronger line has a matching mirror peak on its other side, with compatible frequency, power and phase symmetry. The sideband separation is limited to 3.5 kHz. The incumbent is rechecked too, so an already selected sideband is not retained indefinitely. A nearby stronger line alone is not a veto. Phase rotations and peak storage are precomputed/preallocated; FFT sizes and cadence do not increase. Confirmation, smoothing and hold rules remain unchanged.

This is a conservative sideband test, not general station identification: noise, fading, interference or missing mirror peaks can make the relationship unrecognizable. It does not suppress RF overload or distinguish every combination of independent carriers.

For channel 128.805 MHz with zero frequency/PPM correction, the midpoint between 128.800 and 128.805 MHz plus the 500 Hz zero-IF avoidance offset gives a hardware center of 128.803 MHz. AM acquisition therefore covers 128.796500-128.809500 MHz. The selector has no station identity information: at initial acquisition it chooses the strongest qualified line inside that search window. An initially stronger in-window interferer or a carrier indistinguishable from a narrow spur remains a limitation. Raw IQ or contemporaneous spectrum/offset diagnostics are needed to establish whether a particular real-world fault is carrier selection, overload, or transmitter distortion.

`am_carrier_selection_test` verifies envelope/synchronous AM reception near both expanded edges at 240/480/960 ksps input, interpolated peaks just inside/outside +/-6500 Hz, rejection of noise/short bursts and retention of a confirmed carrier against a louder neighbor. It also checks strongly modulated outside neighbors and anti-alias rejection. `am_carrier_context_test` checks mirrored sidebands, fractional FFT bins, preservation of a real edge carrier beside a stronger neighbor, phase discrimination, lock revalidation and reset. `am_pipeline_test` requires 2200/2500 Hz speech-tone amplitudes near both search edges to stay within 1 dB of the same signal at -3 kHz, at every supported input rate.

Reset, retune and filter-topology changes discard frame/lock history; squelch-only updates preserve it. `note_retune()` also maintains the existing squelch reacquisition guard. SSB/NFM retain their previous phase steering and long-lag coherence validation; WFM bypasses carrier steering. Audio filters, level control and PCM buffering are independent of this AM selection change.

## Squelch Metrics

The pipeline reports several squelch-related values:

- `squelchPercent`: decision-compatible signal level for manual/custom squelch.
- `squelchNoiseFloorPercent`: adaptive noise-floor estimate.
- `squelchThresholdPercent`: configured threshold.
- `squelchEffectiveThresholdPercent`: threshold after mode-specific adjustments.
- `squelchOpen`: current gate decision.

Manual/custom squelch is intended to behave as a direct threshold. Auto squelch may use softer gate behavior and longer hang times to preserve speech tails.

Manual/custom uses one sustained-peak meter for both the audio gate and exported level. Its 100 integer thresholds are updated every 1 ms of PCM sample time using preallocated state, independent of USB block sizes and wall-clock scheduling. A threshold requires at least 100 ms of continuous qualification; isolated impulses cannot renew its hold deadline. Editing the threshold reuses qualified history without resetting the audio pipeline. Retune, reset, and switching to/from auto clear this history. The USB telemetry layer does not filter this already-qualified value again.

Auto retains a separate wall-clock sustained peak for display and its existing adaptive gate. Its stale display peaks expire when USB data stops; ending the USB stream stops audio in either mode.

## PCM Continuity

`StreamingPcmResampler` keeps fractional input position and interpolation lookahead between output blocks. At equal input/output rates, samples pass through unchanged, without duplicated block endpoints. A partial PCM read is not stretched to a full block. The resampler performs linear interpolation with preallocated storage; the existing producer-rate estimator and buffer feedback still control its consumption rate.

## Spectrum And Waterfall History

`spectrum_history.h/.cpp` owns a process-local ring of completed 256-bin FFT
frames in the receiver core, independent of Activity lifetime and UI polling.
It reuses the existing stream FFT: no additional FFT, IQ recording, or disk
access is needed. Each stored row includes a monotonic timestamp, sequence,
tuning epoch, actual IQ center, sample rate, and estimated native PCM queue
delay. Levels use one byte per bin. The ring holds at most 256 frames, accepting
at most one per 100 ms time bucket, and returns only the latest 22 seconds
(20 seconds visible plus an audio-delay margin). Native storage is about 80 KiB.

The stream worker continues collecting while audio is running even with no
visible Host screen. A private, same-UID Messenger request retrieves frames
after the viewer's last sequence; a recreated or reconnected viewer requests
the retained snapshot. History is not attached to public status replies and
does not change the `radioapi` contract. Polling and rendering stop while the
viewer lifecycle is below STARTED. Requests allow one outstanding reply with a
timeout; duplicate or late responses do not duplicate rows.

Retunes and stream restarts begin new epochs without clearing retained history.
Blocks overlapping a retune and the first 100 ms of settling are not stored.
Smoothing and FFT peak hold never mix epochs. Older waterfall sections carry
their own IQ center/span labels and separators; the live FFT axis uses the
metadata of the displayed frame, not a newer tuning status. Frequency labels
refer to the raw IQ center, including the zero-IF avoidance offset, rather than
the selected channel name. Stop leaves recent history to age out; process
termination discards it. This is not persistent recording across app/process
restarts.

`HostSpectrumFrameStore` rebuilds the viewer cache from timestamped native
frames. Presentation uses the captured native PCM queue delay plus the existing
220 ms playback allowance, capped at one second. This is an estimate, not
sample-accurate AudioTrack/DAC synchronization. Retunes discard only the old
unheard tail that would overlap the new band's presentation. The renderer uses
fixed 125 ms timeline rows, with fractional translation on each display frame,
rather than restarting a scroll animation on every IPC response. Gaps longer
than 500 ms display the empty-signal background instead of stale FFT samples.
Floor and contrast remain viewer controls and recolor the retained history.

`spectrum_history_test` checks bounded storage, retention, incremental and
non-destructive reads, retune guards, metadata encoding, and concurrent reads
and writes. Kotlin tests check payload validation, viewer reconstruction,
deduplication, audio delay, time-based scrolling, gaps, and frequency/rate
boundaries. Device testing should also cover rotation, background/foreground
transitions, tuning from an external client, and smooth scrolling at each
supported sample rate.

## Regression Tests

The native tests run without an Android device and use the real DSP implementation:

```sh
cmake -S opensdrcore/src/main/cpp -B /tmp/opensdr-dsp-tests -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build /tmp/opensdr-dsp-tests
ctest --test-dir /tmp/opensdr-dsp-tests --output-on-failure
```

For a separate ThreadSanitizer build on a supported host, add `-DOPENSDR_SANITIZER=thread` to the configure command and use a different build directory. `address,undefined` is also supported. Tests cover weak offset carriers, adjacent narrowband noise rejection, carrier qualification/reset at multiple sample rates, strong-signal AGC, AM/WFM transitions, default initialization, impulse blanking, concurrent updates, partial IQ reads, USB retry decisions, and sustained squelch peaks. These tests do not replace USB fault injection or listening tests on an Android device.

`am_carrier_selection_test` also checks simultaneous carriers 8.33 kHz apart in both AM modes and both directions, a louder arriving neighbor, weak station replacement, frequency error, search edges, retune/block boundaries, deep low-frequency modulation, noise-only input and short bursts. The original two-carrier regression displaced the estimate by about 3.34 kHz and reduced the wanted 1 kHz tone by about 29 dB without clipping; it now checks correct centering and preservation of that tone. This synthetic case is not proof of the cause of any individual recording.

Run Kotlin tests sequentially:

```sh
./gradlew --no-daemon --no-parallel :opensdrcore:testDebugUnitTest :rtlsdrhost:testDebugUnitTest
```

They also cover PCM continuity, fractional rates and rate changes, short input, failed/partial AudioTrack writes, nonblocking buffer telemetry, bounded status polling, and modulation-specific RF bandwidth selection. The native `manual_squelch_test` checks gate/meter agreement, repeated short impulses during hang time, live threshold changes, and retunes in both manual and custom modes at all three supported input rates.

## Profiles

AM profiles are JSON assets loaded by `AmProfileStore`. They tune defaults and selected DSP constants without recompiling native code.

See `AM_PROFILES.md`.

## Design Constraints

- Keep the hot path allocation-free or near allocation-free.
- Prefer simple IIR/FIR state; bound spectral acquisition work to short, infrequent, preallocated transforms.
- Avoid algorithms that require large buffers or high CPU on older Android devices.
- Keep settings updateable while streaming when the sample-rate/filter topology does not change.
- Treat Android audio scheduling spikes as normal; buffers and timeouts must tolerate them.
