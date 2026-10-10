# AM Profile Format

OpenSDR Radio Host ships AM/DSP profiles as JSON assets in:

```text
opensdrcore/src/main/assets/am_profiles/
```

Profiles are loaded by:

```text
opensdrcore/src/main/java/org/opensdr/sdrcore/AmProfileStore.kt
```

The profile system keeps low-CPU DSP tuning outside Kotlin and native source code. A profile can change receiver defaults and selected native pipeline parameters.

## Startup And Selection

`Low CPU Clean Voice` is the bundled default. Its receiver defaults are 240 ksps,
10 kHz AM bandwidth and the reversible `pipeline.simpleAmEnabled` bypass enabled.
In AM the effective settings are envelope detection, full sidebands, 100% audio
boost and extra audio/DSP processing disabled. The original JSON defaults and
legacy algorithms remain available for rollback (see below).

A fresh Host uses the selected profile's JSON defaults for missing preferences;
saved receiver settings (including modulation) still take precedence on later starts,
except for the processing options intentionally bypassed by simple AM.
Applying a profile with defaults enabled restores its processing defaults.
Ordinary squelch and calibration edits do not reapply the profile or overwrite
explicit Host experiments in profiles without the simple-AM bypass.

The separate **AM speech filter (250-2500 Hz)** defaults to enabled. It is a saved
listening preference, not a JSON profile default, so applying or switching profiles
does not change it. It adds output filtering after profile processing and is
bypassed in SSB/NFM/WFM. Do not confuse it with `speechEnhancerMode`.

Profile selection preserves tuned frequency, manual/custom squelch level, PPM,
Bias-T and tuner gain. The two Low CPU profiles explicitly restore squelch timing
(120 ms opening, 700 ms hang, subject to mode-specific native limits), soft Auto mute
and zero extra Hz offset unless the client explicitly requests calibration
preservation (`KEY_PRESERVE_FREQUENCY_CORRECTION` during profile selection).
Manual/custom still hard-mute in native DSP. Per-frequency
squelch history stays in the client. JSON contains all processing defaults; there
is no additional client-side Low CPU mask.

Profile selection and receiver updates are serialized. Clients editing PPM,
Bias-T, gain and squelch can use partial-control command 11. Clients editing Hz
correction instead of gain should use command 12, which leaves Host gain alone.
Both commands support the optional speech filter and preserve other DSP settings.
Require the corresponding advertised capability before sending a command, so a
stale client snapshot cannot restore old Host DSP parameters. Clients should verify
the selected profile in the returned Host status rather than assume initialization
succeeded.

## Reversible Simple AM

`pipeline.simpleAmEnabled` defaults to `false` when absent. It is currently `true`
only in `low_cpu_clean_voice.json`, and applies only to AM. It forces envelope
demodulation, full sidebands, unity output gain and 100% audio boost; Voice focus,
post leveler, blanker, denoise, notch, advanced sync, quality squelch and speech
enhancer are bypassed. The extra audio HP/LP stages and dynamic clip guard are
also bypassed. Essential IQ/DC/anti-alias/channel filtering, the existing base
AGC, squelch, final overload soft clipping and the optional 250-2500 Hz output
filter remain. The centered IQ filter has a 3250 Hz cutoff (or half the requested
channel width if smaller), leaving room for the 2500 Hz voice sidebands without
admitting unnecessary RF noise before the envelope detector.

The controller applies one effective-settings policy on preference restore,
profile selection and every receiver update. Status and JNI therefore report
the same active values; saved or client-provided enhancements cannot silently
reactivate them. The native boundary enforces the same bypass. Tuner gain,
calibration, selected frequency, squelch settings and the independent speech-filter
switch remain user-controlled. SSB/NFM/WFM ignore this bypass entirely.

To restore the exact previous Clean Voice processing, set `simpleAmEnabled` to
`false` in its JSON, rebuild/reinstall the Host, and apply that profile's defaults.
Its original 130% boost, Voice focus, post leveler and legacy tuning values are
deliberately retained in JSON. Other existing profiles also retain their original
paths. No algorithms have been deleted. This is a diagnostic baseline, not a
claim that carrier selection or real-world audio quality is already fixed.

## Basic Shape

The following example illustrates the schema; see the bundled JSON for complete
profile-specific pipeline values.

```json
{
  "id": "low_cpu_clean_voice",
  "label": "Low CPU Clean Voice",
  "description": "Low-cost AM voice profile for normal airband listening.",
  "default": true,
  "defaults": {
    "preferredSampleRateHz": 240000,
    "amBandwidthHz": 10000,
    "audioBoostPercent": 130,
    "voiceFocusEnabled": true,
    "postLevelerEnabled": true,
    "demodMode": 1,
    "noiseBlankerEnabled": false,
    "denoiseMode": 0,
    "notchMode": 0,
    "sidebandMode": 0,
    "advancedSyncEnabled": false,
    "qualitySquelchEnabled": false,
    "speechEnhancerMode": 0
  },
  "pipeline": {
    "simpleAmEnabled": true,
    "dcAlpha": 0.0015,
    "stage1FirTaps": 81,
    "stage2FirTaps": 129,
    "channelGuardHz": 900.0,
    "channelCutoffRatio": 0.40,
    "voiceAudioCutoffHz": 2400.0,
    "naturalAudioCutoffHz": 2800.0,
    "voiceAudioRatio": 0.30,
    "naturalAudioRatio": 0.36,
    "audioHighPassHz": 300.0,
    "squelchAttackAlpha": 0.05,
    "squelchReleaseAlpha": 0.002,
    "squelchHysteresisPercent": 6.0,
    "squelchOpenDebounceMs": 120,
    "squelchCloseHangMs": 700,
    "outputGain": 1.0,
    "targetAudioLevel": 7000.0,
    "softClipThreshold": 30000.0,
    "softClipDrive": 8000.0,
    "postLevelerTarget": 9000.0,
    "postLevelerMaxGain": 1.8,
    "postLevelerAttackAlpha": 0.010,
    "postLevelerReleaseAlpha": 0.001
  }
}
```

## Required Fields

- `id`: stable profile identifier.
- `label`: user-facing name.

If `id` is missing, the loader uses the file name without `.json`.
If `label` is missing, the loader derives it from `id`.

## Optional Fields

- `description`: user-facing explanation.
- `default`: marks the startup profile. If more than one profile is marked default, the first loaded profile wins.
- `defaults`: receiver settings applied when the profile is selected with defaults enabled.
- `pipeline`: native DSP tuning values.

## Receiver Defaults

Supported `defaults` fields:

- `frequencyCorrectionHz`: extra tuner offset, range `-250000..250000`.
- `squelchOpenDebounceMs`: range `0..500`, further limited by squelch mode.
- `squelchCloseHangMs`: range `0..1000`, further limited by squelch mode.
- `hardSquelchMuteEnabled`: boolean; manual/custom always hard-mute.
- `preferredSampleRateHz`: allowed values `240000`, `480000`, `960000`.
- `amBandwidthHz`: range `6000..20000`.
- `audioBoostPercent`: range `100..320`.
- `voiceFocusEnabled`: boolean.
- `postLevelerEnabled`: boolean.
- `demodMode`: range `1..5`.
- `noiseBlankerEnabled`: boolean.
- `denoiseMode`: `0` off, `1` mild, `2` strong, `3` auto.
- `notchMode`: `0` off, `1` auto.
- `sidebandMode`: `0` full, `1` lower, `2` upper.
- `advancedSyncEnabled`: boolean.
- `qualitySquelchEnabled`: boolean.
- `speechEnhancerMode`: `0` off, `1` mild, `2` strong.

Omitted fields preserve the current receiver value. Bundled profiles explicitly
specify all of these audio defaults to avoid inheriting another profile's DSP options.

Demodulation mode constants:

- `1`: AM envelope.
- `2`: AM sync.
- `3`: SSB.
- `4`: NFM.
- `5`: WFM.

## Pipeline Fields

The loader validates every numeric pipeline value. Invalid profiles are skipped and a warning is logged.

Ranges:

- `simpleAmEnabled`: boolean, default `false`; reversible AM-only processing bypass
- `dcAlpha`: `0.0001..0.05`
- `stage1FirTaps`: `17..257`
- `stage2FirTaps`: `17..257`
- `channelGuardHz`: `100.0..2000.0`
- `channelCutoffRatio`: `0.20..0.48`
- `voiceAudioCutoffHz`: `1500.0..3200.0`
- `naturalAudioCutoffHz`: `1800.0..3600.0`
- `voiceAudioRatio`: `0.20..0.45`
- `naturalAudioRatio`: `0.25..0.48`
- `audioHighPassHz`: `80.0..800.0`
- `squelchAttackAlpha`: `0.005..0.30`
- `squelchReleaseAlpha`: `0.0005..0.05`
- `squelchHysteresisPercent`: `1.0..20.0`
- `squelchOpenDebounceMs`: `0..500`
- `squelchCloseHangMs`: `0..1000`
- `outputGain`: `0.25..4.0`
- `targetAudioLevel`: `1000.0..20000.0`
- `softClipThreshold`: `12000.0..32000.0`
- `softClipDrive`: `1000.0..12000.0`
- `postLevelerTarget`: `1000.0..20000.0`
- `postLevelerMaxGain`: `1.0..4.0`
- `postLevelerAttackAlpha`: `0.001..0.10`
- `postLevelerReleaseAlpha`: `0.0001..0.05`

## Practical Guidance

- Keep FIR tap counts modest. Larger filters increase CPU.
- Prefer tuning profile JSON before changing native constants.
- Avoid aggressive denoise or leveler settings if speech continuity matters more than quiet background.
- Test weak and strong stations separately; settings that help weak signals can over-compress strong ones.
- Do not use profile defaults to hide unsupported hardware behavior. Device/session failures should be handled in `DirectRtlSdrController`.
