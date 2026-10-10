#include "am_pipeline.h"
#include "am_demod.h"

#include <algorithm>
#include <cmath>

namespace {
// Fixed coefficients are kept here instead of in profile JSON when they protect
// pipeline stability or CPU cost. User-facing audio taste parameters stay in
// profiles and are normalized in update_config().
constexpr float kPi = 3.14159265358979323846f;
constexpr float kMinAudioCutoffHz = 1800.0f;
constexpr float kMaxAudioCutoffHz = 3600.0f;
constexpr float kChannelMinCutoffHz = 3200.0f;
constexpr float kChannelMaxCutoffHz = 9000.0f;
constexpr float kRfLevelReference = 90.0f;
constexpr float kAudioLevelReference = 16000.0f;
constexpr float kAutoSquelchReference = 180.0f;
constexpr float kManualCarrierReference = 56.0f;
// A symmetric low-pass measures average carrier amplitude without following
// AM speech peaks. Its time constant also makes isolated RF impulses too short
// to satisfy the manual/custom open debounce.
constexpr float kManualSquelchLevelAlpha = 0.0025f;
constexpr int kManualSquelchMinimumOpenDebounceMs = 100;
constexpr float kSquelchActivityWeight = 0.60f;
constexpr float kQualitySquelchWeight = 0.45f;
constexpr float kSquelchNoiseFloorRiseAlpha = 0.0008f;
constexpr float kSquelchNoiseFloorFallAlpha = 0.020f;
constexpr float kManualSquelchMinReleaseAlpha = 0.020f;
constexpr int kDeadCarrierCloseMs = 900;
constexpr int kSpeechFriendlyOpenDebounceMs = 60;
constexpr int kSpeechFriendlyCloseHangMs = 500;
constexpr int kCarrierDropGraceMs = 260;
constexpr float kCarrierPresentClosedGateFloor = 0.34f;
constexpr float kCarrierDropGraceGateFloor = 0.22f;
constexpr float kSoftSquelchCarrierPresentGateFloor = 0.26f;
constexpr float kSoftSquelchCarrierDropGraceGateFloor = 0.18f;
constexpr float kSoftSquelchClosedGateFloor = 0.0f;
constexpr float kCarrierPresentSpeechGainFloor = 0.97f;
constexpr float kCarrierPresentDenoiseFloor = 0.94f;
constexpr int kRetuneGuardMs = 650;
constexpr float kRetuneGuardOpenBonus = 18.0f;
constexpr float kRetuneGuardQualityBonus = 10.0f;
constexpr float kRetuneStrongSignalOpenBonus = 12.0f;
constexpr float kRetuneStrongSignalQualityFloor = 58.0f;
constexpr int kSquelchModeAuto = 0;
constexpr int kSquelchModeManual = 1;
constexpr int kSquelchModeCustom = 2;
constexpr float kDcBlockPole = 0.995f;
constexpr float kAgcAttackAlpha = 0.004f;
constexpr float kAgcReleaseAlpha = 0.0005f;
constexpr float kAgcFloor = 120.0f;
constexpr float kAgcMinGain = 0.25f;
constexpr float kAgcMaxGain = 6.0f;
constexpr float kAgcGainAttackAlpha = 0.020f;
constexpr float kAgcGainReleaseAlpha = 0.0020f;
constexpr float kCarrierRecoveryAlpha = 0.0025f;
constexpr float kCarrierFloor = 18.0f;
constexpr float kCarrierTrackMinMagnitude = 10.0f;
constexpr float kWeakCarrierMinMagnitude = 0.5f;
constexpr float kWeakCarrierMinCoherenceSquared = 0.90f;
constexpr int kCarrierTrackUpdateStride = 4;
constexpr float kCarrierTrackMinCoherenceSquared = 0.040f;
constexpr float kCarrierTrackConfidenceAttackAlpha = 0.18f;
constexpr float kCarrierTrackConfidenceReleaseAlpha = 0.04f;
constexpr float kCarrierTrackMinimumConfidence = 0.24f;
// Legacy phase steering retained for SSB/NFM; AM uses spectral line selection.
constexpr float kEnvelopeCarrierTrackAttackAlpha = 0.012f;
constexpr float kEnvelopeCarrierTrackReleaseAlpha = 0.0010f;
constexpr float kEnvelopeCarrierTrackDiscriminatorAlpha = 0.025f;
constexpr float kEnvelopeCarrierTrackAcquireAttackAlpha = 0.045f;
constexpr float kEnvelopeCarrierTrackAcquireWindowHz = 1800.0f;
// A second, much slower carrier reference keeps envelope AM centered without
// letting short-term speech/noise structure yank the tracking frequency around.
constexpr float kEnvelopeCarrierReferenceAttackAlpha = 0.00030f;
constexpr float kEnvelopeCarrierReferenceBootstrapAlpha = 0.00150f;
constexpr float kEnvelopeCarrierReferenceBlendWindowHz = 1200.0f;
constexpr float kEnvelopeCarrierReferenceBootstrapWindowHz = 2600.0f;
constexpr float kEnvelopeCarrierReferenceStablePresenceMargin = 4.0f;
constexpr float kEnvelopeCarrierReferenceSpeechBlendFloor = 0.72f;
// Covers both an 8.33 designator's nominal carrier and transmitters operating
// 5 kHz away (for example channel 128.805 with RF on 128.800 or 128.805).
// AM uses this actual selection limit, without subtracting the filter margin.
constexpr float kAmCarrierSearchHalfBandwidthHz = 6500.0f;
constexpr float kPhaseCarrierSearchHalfBandwidthHz = 6000.0f;
constexpr float kCarrierTrackPullInMarginHz = 200.0f;
constexpr float kCarrierTrackAudioGuardHz = 2200.0f;
constexpr float kFineCarrierTrackAttackAlpha = 0.015f;
constexpr float kFineCarrierTrackReleaseAlpha = 0.003f;
constexpr float kFineCarrierTrackDiscriminatorAlpha = 0.060f;
constexpr float kFineCarrierTrackMaxHz = 350.0f;
constexpr float kModulationAttackAlpha = 0.040f;
constexpr float kModulationReleaseAlpha = 0.003f;
constexpr float kCarrierPauseCarrierThreshold = 24.0f;
constexpr float kCarrierPresenceThreshold = 20.0f;
constexpr float kCarrierPauseModulationThreshold = 75.0f;
constexpr float kSpeechTailModulationFloor = 12.0f;
constexpr float kSpeechTailActivityFloor = 6.0f;
constexpr float kSpeechTailQualityFloor = 8.0f;
constexpr float kClipGuardAttackAlpha = 0.035f;
constexpr float kClipGuardReleaseAlpha = 0.015f;
constexpr float kClipGuardHeadroom = 0.94f;
constexpr float kOutputFadeAttackAlpha = 0.0045f;
constexpr float kOutputFadeReleaseAlpha = 0.0075f;
constexpr float kNoiseBlankerEnvelopeAttackAlpha = 0.25f;
constexpr float kNoiseBlankerEnvelopeReleaseAlpha = 0.0025f;
constexpr float kNoiseBlankerThresholdScale = 11.0f;
constexpr float kNoiseBlankerFloor = 16.0f;
constexpr float kNoiseBlankerBlend = 0.25f;
constexpr float kDenoiseNoiseRiseAlpha = 0.030f;
constexpr float kDenoiseNoiseFallAlpha = 0.0012f;
constexpr float kDenoiseSignalAttackAlpha = 0.055f;
constexpr float kDenoiseSignalReleaseAlpha = 0.0030f;
constexpr float kDenoiseSuppressionFloor = 0.72f;
constexpr float kDenoiseOpenBonus = 1.18f;
constexpr float kDenoiseGainAttackAlpha = 0.20f;
constexpr float kDenoiseGainReleaseAlpha = 0.010f;
constexpr float kDenoiseLimiterAttackAlpha = 0.22f;
constexpr float kDenoiseLimiterReleaseAlpha = 0.008f;
constexpr float kAdaptiveNotchPowerAlpha = 0.004f;
constexpr float kAdaptiveNotchCorrAlpha = 0.012f;
constexpr float kAdaptiveNotchFreqAlpha = 0.10f;
constexpr float kAdaptiveNotchConfidenceAttackAlpha = 0.030f;
constexpr float kAdaptiveNotchConfidenceReleaseAlpha = 0.006f;
constexpr int kAudioFifoMs = 250;
constexpr int kStage1Decimation = 5;

float soft_clip_sample(float sample, float threshold, float drive) {
    const float magnitude = std::fabs(sample);
    if (magnitude <= threshold) {
        return sample;
    }
    const float excess = magnitude - threshold;
    const float safe_drive = std::max(drive, 1.0f);
    const float compressed = threshold + safe_drive * std::tanh(excess / safe_drive);
    return std::copysign(compressed, sample);
}
}  // namespace

AmPipeline::AmPipeline(const AmPipelineConfig& config) {
    update_config(config);
    reset();
}

bool AmPipeline::update_config(const AmPipelineConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    // Normalize every runtime/profile value before it reaches the hot path.
    // When the filter topology is unchanged, preserve tracking state so profile
    // or squelch tweaks do not cause audible dropouts.
    if (config.input_sample_rate_hz <= 0 || config.output_sample_rate_hz <= 0) {
        return false;
    }
    if (config.input_sample_rate_hz % config.output_sample_rate_hz != 0) {
        return false;
    }

    AmPipelineConfig normalized = config;
    if (!std::isfinite(normalized.carrier_targets.primary_hz) ||
        !std::isfinite(normalized.carrier_targets.alternate_hz)) return false;
    normalized.demod_mode = std::clamp(normalized.demod_mode, 1, 5);
    normalized.simple_am_enabled = normalized.simple_am_enabled && normalized.demod_mode <= 2;
    if (normalized.simple_am_enabled) {
        // Mirror the profile's effective-settings policy at the native boundary.
        // Keep legacy algorithms and their tuning available by disabling this flag.
        normalized.demod_mode = 1;
        normalized.audio_boost_percent = 100;
        normalized.voice_focus_enabled = false;
        normalized.post_leveler_enabled = false;
        normalized.noise_blanker_enabled = false;
        normalized.denoise_mode = 0;
        normalized.notch_mode = 0;
        normalized.sideband_mode = 0;
        normalized.advanced_sync_enabled = false;
        normalized.quality_squelch_enabled = false;
        normalized.speech_enhancer_mode = 0;
        normalized.output_gain = 1.0f;
    }
    normalized.channel_bandwidth_hz = normalized.demod_mode == 5
        ? 200000
        : std::clamp(normalized.channel_bandwidth_hz, 6000, 20000);
    normalized.squelch_mode = std::clamp(normalized.squelch_mode, kSquelchModeAuto, kSquelchModeCustom);
    normalized.squelch_level = std::clamp(normalized.squelch_level, 0, 100);
    normalized.audio_boost_percent = std::clamp(normalized.audio_boost_percent, 100, 320);
    normalized.denoise_mode = std::clamp(normalized.denoise_mode, 0, 3);
    normalized.notch_mode = std::clamp(normalized.notch_mode, 0, 1);
    normalized.sideband_mode = std::clamp(normalized.sideband_mode, 0, 2);
    normalized.speech_enhancer_mode = std::clamp(normalized.speech_enhancer_mode, 0, 2);
    normalized.dc_alpha = std::clamp(normalized.dc_alpha, 0.0001f, 0.05f);
    normalized.stage1_fir_taps = std::clamp(normalized.stage1_fir_taps, 17, 257);
    normalized.stage2_fir_taps = std::clamp(normalized.stage2_fir_taps, 17, 257);
    normalized.channel_guard_hz = std::clamp(normalized.channel_guard_hz, 100.0f, 2000.0f);
    normalized.channel_cutoff_ratio = std::clamp(normalized.channel_cutoff_ratio, 0.20f, 0.48f);
    normalized.voice_audio_cutoff_hz = std::clamp(normalized.voice_audio_cutoff_hz, 1500.0f, 3200.0f);
    normalized.natural_audio_cutoff_hz = std::clamp(normalized.natural_audio_cutoff_hz, 1800.0f, 3600.0f);
    normalized.voice_audio_ratio = std::clamp(normalized.voice_audio_ratio, 0.20f, 0.45f);
    normalized.natural_audio_ratio = std::clamp(normalized.natural_audio_ratio, 0.25f, 0.48f);
    normalized.audio_high_pass_hz = std::clamp(normalized.audio_high_pass_hz, 80.0f, 800.0f);
    normalized.squelch_attack_alpha = std::clamp(normalized.squelch_attack_alpha, 0.005f, 0.30f);
    normalized.squelch_release_alpha = std::clamp(normalized.squelch_release_alpha, 0.0005f, 0.05f);
    normalized.squelch_hysteresis_percent = std::clamp(normalized.squelch_hysteresis_percent, 1.0f, 20.0f);
    normalized.squelch_open_debounce_ms = std::clamp(normalized.squelch_open_debounce_ms, 0, 500);
    normalized.squelch_close_hang_ms = std::clamp(normalized.squelch_close_hang_ms, 0, 1000);
    if (normalized.squelch_mode != kSquelchModeAuto && normalized.squelch_level > 0) {
        normalized.squelch_open_debounce_ms = std::max(
            normalized.squelch_open_debounce_ms,
            kManualSquelchMinimumOpenDebounceMs);
    }
    normalized.output_gain = std::clamp(normalized.output_gain, 0.25f, 4.0f);
    normalized.target_audio_level = std::clamp(normalized.target_audio_level, 1000.0f, 20000.0f);
    normalized.soft_clip_threshold = std::clamp(normalized.soft_clip_threshold, 12000.0f, 32000.0f);
    normalized.soft_clip_drive = std::clamp(normalized.soft_clip_drive, 1000.0f, 12000.0f);
    normalized.post_leveler_target = std::clamp(normalized.post_leveler_target, 1000.0f, 20000.0f);
    normalized.post_leveler_max_gain = std::clamp(normalized.post_leveler_max_gain, 1.0f, 4.0f);
    normalized.post_leveler_attack_alpha = std::clamp(normalized.post_leveler_attack_alpha, 0.001f, 0.10f);
    normalized.post_leveler_release_alpha = std::clamp(normalized.post_leveler_release_alpha, 0.0001f, 0.05f);

    const int next_decimation_factor = normalized.input_sample_rate_hz / normalized.output_sample_rate_hz;
    if (next_decimation_factor <= 0 || next_decimation_factor % kStage1Decimation != 0) {
        return false;
    }

    const bool speech_friendly_squelch =
        normalized.squelch_mode == kSquelchModeAuto &&
            normalized.squelch_level > 0;
    if (speech_friendly_squelch) {
        normalized.squelch_open_debounce_ms = std::min(normalized.squelch_open_debounce_ms, kSpeechFriendlyOpenDebounceMs);
        normalized.squelch_close_hang_ms = std::max(normalized.squelch_close_hang_ms, kSpeechFriendlyCloseHangMs);
    }

    // The output speech filter crossfades independently; its toggle must not
    // reset carrier acquisition, AGC, squelch or queued PCM.
    const bool can_preserve_runtime_state =
        !stage1_taps_.empty() && !stage2_taps_.empty() && !audio_fifo_.empty() &&
        config_.input_sample_rate_hz == normalized.input_sample_rate_hz &&
        config_.output_sample_rate_hz == normalized.output_sample_rate_hz &&
        config_.channel_bandwidth_hz == normalized.channel_bandwidth_hz &&
        config_.hard_squelch_mute_enabled == normalized.hard_squelch_mute_enabled &&
        config_.audio_boost_percent == normalized.audio_boost_percent &&
        config_.voice_focus_enabled == normalized.voice_focus_enabled &&
        config_.post_leveler_enabled == normalized.post_leveler_enabled &&
        config_.noise_blanker_enabled == normalized.noise_blanker_enabled &&
        config_.denoise_mode == normalized.denoise_mode &&
        config_.demod_mode == normalized.demod_mode &&
        config_.simple_am_enabled == normalized.simple_am_enabled &&
        config_.carrier_targets == normalized.carrier_targets &&
        config_.notch_mode == normalized.notch_mode &&
        config_.sideband_mode == normalized.sideband_mode &&
        config_.advanced_sync_enabled == normalized.advanced_sync_enabled &&
        config_.quality_squelch_enabled == normalized.quality_squelch_enabled &&
        config_.speech_enhancer_mode == normalized.speech_enhancer_mode &&
        config_.dc_alpha == normalized.dc_alpha &&
        config_.stage1_fir_taps == normalized.stage1_fir_taps &&
        config_.stage2_fir_taps == normalized.stage2_fir_taps &&
        config_.channel_guard_hz == normalized.channel_guard_hz &&
        config_.channel_cutoff_ratio == normalized.channel_cutoff_ratio &&
        config_.voice_audio_cutoff_hz == normalized.voice_audio_cutoff_hz &&
        config_.natural_audio_cutoff_hz == normalized.natural_audio_cutoff_hz &&
        config_.voice_audio_ratio == normalized.voice_audio_ratio &&
        config_.natural_audio_ratio == normalized.natural_audio_ratio &&
        config_.audio_high_pass_hz == normalized.audio_high_pass_hz &&
        config_.output_gain == normalized.output_gain &&
        config_.target_audio_level == normalized.target_audio_level &&
        config_.soft_clip_threshold == normalized.soft_clip_threshold &&
        config_.soft_clip_drive == normalized.soft_clip_drive &&
        config_.post_leveler_target == normalized.post_leveler_target &&
        config_.post_leveler_max_gain == normalized.post_leveler_max_gain &&
        config_.post_leveler_attack_alpha == normalized.post_leveler_attack_alpha &&
        config_.post_leveler_release_alpha == normalized.post_leveler_release_alpha;

    if ((config_.squelch_mode == kSquelchModeAuto) != (normalized.squelch_mode == kSquelchModeAuto)) {
        manual_squelch_meter_.reset();
        manual_qualified_percent_ = 0;
    }
    config_ = normalized;
    manual_squelch_meter_.configure(config_.squelch_open_debounce_ms, config_.squelch_close_hang_ms, 0);
    decimation_factor_ = next_decimation_factor;
    // WFM must be discriminated at 240 ksps, BEFORE conversion to 48 kHz PCM.
    stage1_decimation_factor_ = config_.demod_mode == 5
        ? next_decimation_factor / kStage1Decimation
        : kStage1Decimation;
    stage2_decimation_factor_ = decimation_factor_ / stage1_decimation_factor_;
    stage1_sample_rate_hz_ = config_.input_sample_rate_hz;
    stage2_sample_rate_hz_ = config_.input_sample_rate_hz / stage1_decimation_factor_;
    carrier_drop_grace_samples_ = std::max(
        1,
        static_cast<int>((static_cast<long long>(config_.output_sample_rate_hz) * kCarrierDropGraceMs) / 1000LL));
    if (stage2_decimation_factor_ <= 0) {
        return false;
    }

    if (can_preserve_runtime_state) {
        squelch_open_debounce_samples_ = static_cast<int>(
            (static_cast<long long>(config_.output_sample_rate_hz) * static_cast<long long>(config_.squelch_open_debounce_ms)) / 1000LL);
        squelch_close_hang_samples_ = static_cast<int>(
            (static_cast<long long>(config_.output_sample_rate_hz) * static_cast<long long>(config_.squelch_close_hang_ms)) / 1000LL);
        return true;
    }

    weak_carrier_coherence_.configure(static_cast<float>(stage2_sample_rate_hz_) / kCarrierTrackUpdateStride);
    am_carrier_tracker_.configure(stage2_sample_rate_hz_, kAmCarrierSearchHalfBandwidthHz, config_.carrier_targets);
    redesign_filters();
    const std::size_t fifo_capacity = std::max<std::size_t>(
        static_cast<std::size_t>(config_.output_sample_rate_hz * kAudioFifoMs / 1000),
        2048);
    audio_fifo_.assign(fifo_capacity, 0);
    reset_locked();
    return true;
}

void AmPipeline::redesign_filters() {
    const float audio_ratio = config_.voice_focus_enabled ? config_.voice_audio_ratio : config_.natural_audio_ratio;
    const float max_audio_cutoff = config_.voice_focus_enabled ? config_.voice_audio_cutoff_hz : config_.natural_audio_cutoff_hz;
    const float target_audio_cutoff_hz = config_.simple_am_enabled ? 2500.0f
        : std::min(static_cast<float>(config_.channel_bandwidth_hz) * audio_ratio, max_audio_cutoff);
    const float half_channel_hz = static_cast<float>(config_.channel_bandwidth_hz) * 0.5f;
    // Widen AM acquisition only; the centered narrow IQ and audio filters below
    // keep their existing passbands, as do the SSB/NFM acquisition filters.
    const bool am = config_.demod_mode == 1 || config_.demod_mode == 2;
    const float acquisition_half_width_hz = am
        ? kAmCarrierSearchHalfBandwidthHz + kCarrierTrackPullInMarginHz
        : kPhaseCarrierSearchHalfBandwidthHz;
    const float carrier_window_hz =
        std::max(half_channel_hz, acquisition_half_width_hz) +
        std::min(target_audio_cutoff_hz, kCarrierTrackAudioGuardHz);
    const float requested_channel_cutoff_hz = std::max(
        std::max(target_audio_cutoff_hz + config_.channel_guard_hz, carrier_window_hz),
        kChannelMinCutoffHz);
    const float channel_cutoff_hz = std::min(
        std::max(requested_channel_cutoff_hz, static_cast<float>(config_.channel_bandwidth_hz) * config_.channel_cutoff_ratio),
        std::min(kChannelMaxCutoffHz, static_cast<float>(config_.output_sample_rate_hz) * 0.45f));
    const bool wfm = config_.demod_mode == 5;
    // Before centering the carrier, preserve its whole voice passband AND the
    // spectral context used to reject neighboring AM sidebands. A Blackman FIR's
    // cutoff is already -6 dB, so add a transition margin of 2 * Fs / (N - 1)
    // beyond that context. Keep the tap count/decimation cost and FM/SSB paths unchanged.
    const int stage1_tap_count = config_.stage1_fir_taps | 1;
    const float acquisition_cutoff_hz = std::min(
        am_carrier_tracker_.context_half_width_hz() +
            2.0f * stage1_sample_rate_hz_ / (stage1_tap_count - 1),
        stage2_sample_rate_hz_ * .45f);
    const float stage1_cutoff_hz = wfm ? 95000.0f : (am ? acquisition_cutoff_hz : channel_cutoff_hz);
    design_fir(&stage1_taps_, stage1_sample_rate_hz_, stage1_cutoff_hz, config_.stage1_fir_taps);
    design_fir(&stage2_taps_, stage2_sample_rate_hz_, wfm ? 16000.0f : channel_cutoff_hz, config_.stage2_fir_taps);
    const int narrowband_tap_count = (config_.simple_am_enabled || config_.voice_focus_enabled) ? 81 : 57;
    // Keep RF noise out of the nonlinear envelope detector, with enough margin
    // not to cut the wanted 2500 Hz sidebands. This is still a channel filter.
    const float narrowband_cutoff_hz = config_.simple_am_enabled
        ? std::min({half_channel_hz, 3250.0f, config_.output_sample_rate_hz * 0.45f})
        : config_.voice_focus_enabled
        ? std::clamp(target_audio_cutoff_hz + 150.0f, 2100.0f, 2800.0f)
        : std::clamp(target_audio_cutoff_hz + 350.0f, 2400.0f, 3400.0f);
    if (wfm) {
        narrowband_taps_.clear();
    } else {
        design_fir(&narrowband_taps_, config_.output_sample_rate_hz, narrowband_cutoff_hz, narrowband_tap_count);
    }
    configure_ssb_pipeline(&ssb_pipeline_, config_.voice_focus_enabled);

    stage1_i_history_.assign(stage1_taps_.size(), 0.0f);
    stage1_q_history_.assign(stage1_taps_.size(), 0.0f);
    stage1_write_index_ = 0;

    stage2_i_history_.assign(stage2_taps_.size(), 0.0f);
    stage2_q_history_.assign(stage2_taps_.size(), 0.0f);
    stage2_write_index_ = 0;

    narrowband_i_history_.assign(narrowband_taps_.size(), 0.0f);
    narrowband_q_history_.assign(narrowband_taps_.size(), 0.0f);
    narrowband_write_index_ = 0;
}

void AmPipeline::design_fir(std::vector<float>* taps, int sample_rate_hz, float cutoff_hz, int tap_count) const {
    if (tap_count % 2 == 0) {
        tap_count += 1;
    }
    taps->assign(static_cast<std::size_t>(tap_count), 0.0f);

    const float nyquist_hz = static_cast<float>(sample_rate_hz) * 0.5f;
    const float normalized_cutoff = std::clamp(cutoff_hz / static_cast<float>(sample_rate_hz), 0.001f, (nyquist_hz * 0.95f) / static_cast<float>(sample_rate_hz));
    const int center = tap_count / 2;

    float sum = 0.0f;
    for (int n = 0; n < tap_count; ++n) {
        const int m = n - center;
        const float sinc = (m == 0)
            ? 2.0f * normalized_cutoff
            : std::sin(2.0f * kPi * normalized_cutoff * static_cast<float>(m)) / (kPi * static_cast<float>(m));
        const float phase = (2.0f * kPi * static_cast<float>(n)) / static_cast<float>(tap_count - 1);
        const float window = 0.42f - 0.50f * std::cos(phase) + 0.08f * std::cos(2.0f * phase);
        const float tap = sinc * window;
        (*taps)[static_cast<std::size_t>(n)] = tap;
        sum += tap;
    }

    if (sum != 0.0f) {
        for (float& tap : *taps) {
            tap /= sum;
        }
    }
}

void AmPipeline::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    reset_locked();
}

void AmPipeline::reset_locked() {
    am_speech_filter_.configure(config_.output_sample_rate_hz);
    am_speech_filter_.reset(config_.am_speech_filter_enabled);
    i_dc_ = 0.0f;
    q_dc_ = 0.0f;
    rf_level_tracker_ = 0.0f;
    manual_squelch_level_ = 0.0f;
    manual_squelch_meter_.reset();
    manual_meter_samples_remaining_ = 0;
    manual_meter_time_ms_ = 0;
    manual_qualified_percent_ = 0;
    carrier_level_ = kCarrierFloor;
    carrier_presence_level_ = kCarrierFloor;
    carrier_recovery_i_lp_ = 1.0f;
    carrier_recovery_q_lp_ = 0.0f;
    sync_lock_quality_ = 0.0f;
    sync_phase_error_ = 0.0f;
    stage1_carrier_phase_rad_ = 0.0f;
    stage1_carrier_freq_rad_per_sample_ = 0.0f;
    effective_stage1_carrier_freq_rad_per_sample_ = 0.0f;
    envelope_carrier_reference_rad_per_sample_ = 0.0f;
    stage1_prev_i_ = 0.0f;
    stage1_prev_q_ = 0.0f;
    has_stage1_prev_ = false;
    has_envelope_carrier_reference_ = false;
    carrier_track_disc_i_lp_ = 1.0f;
    carrier_track_disc_q_lp_ = 0.0f;
    carrier_track_target_rad_per_sample_ = 0.0f;
    carrier_track_confidence_ = 0.0f;
    carrier_track_update_counter_ = 0;
    weak_carrier_coherence_.reset();
    am_carrier_tracker_.reset();
    fine_carrier_freq_rad_per_sample_ = 0.0f;
    fine_prev_i_ = 0.0f;
    fine_prev_q_ = 0.0f;
    has_fine_prev_ = false;
    fine_track_disc_i_lp_ = 1.0f;
    fine_track_disc_q_lp_ = 0.0f;
    raw_prev_i_ = 0.0f;
    raw_prev_q_ = 0.0f;
    has_raw_prev_ = false;
    iq_impulse_envelope_ = 0.0f;
    squelch_level_tracker_ = 0.0f;
    squelch_noise_floor_ = 0.0f;
    detector_prev_input_ = 0.0f;
    detector_prev_output_ = 0.0f;
    sideband_focus_phase_rad_ = 0.0f;
    sideband_focus_i_lp_ = 0.0f;
    sideband_focus_q_lp_ = 0.0f;
    audio_hpf_prev_input_ = 0.0f;
    audio_hpf_prev_output_ = 0.0f;
    audio_lp1_ = 0.0f;
    audio_lp2_ = 0.0f;
    speech_low_lp_ = 0.0f;
    speech_mid_lp_ = 0.0f;
    speech_presence_lp_ = 0.0f;
    speech_harsh_lp_ = 0.0f;
    speech_last_enhanced_ = 0.0f;
    speech_enhancer_gain_ = 1.0f;
    speech_activity_env_ = 0.0f;
    speech_consonant_env_ = 0.0f;
    speech_limiter_env_ = 0.0f;
    speech_limiter_gain_ = 1.0f;
    prev_audio_sample_ = 0.0f;
    notch_prev_input_1_ = 0.0f;
    notch_prev_input_2_ = 0.0f;
    notch_prev_output_1_ = 0.0f;
    notch_prev_output_2_ = 0.0f;
    notch_power_ = 0.0f;
    notch_corr_ = 0.0f;
    notch_mix_i_ = 1.0f;
    notch_mix_q_ = 0.0f;
    notch_frequency_hz_ = 0.0f;
    notch_confidence_ = 0.0f;
    audio_activity_envelope_ = 0.0f;
    squelch_quality_envelope_ = 0.0f;
    denoise_noise_floor_ = 0.0f;
    denoise_signal_envelope_ = 0.0f;
    denoise_gain_ = 1.0f;
    denoise_peak_envelope_ = 0.0f;
    denoise_limiter_gain_ = 1.0f;
    modulation_envelope_ = 0.0f;
    agc_envelope_ = kAgcFloor;
    agc_gain_ = 1.0f;
    gate_gain_ = 1.0f;
    output_fade_gain_ = 1.0f;
    carrier_pause_gate_gain_ = 1.0f;
    clip_guard_gain_ = 1.0f;
    post_level_envelope_ = 0.0f;
    post_level_gain_ = 1.0f;
    stage1_decimation_counter_ = 0;
    stage2_decimation_counter_ = 0;
    squelch_open_ = config_.squelch_mode == kSquelchModeAuto || config_.squelch_level <= 0;
    squelch_open_debounce_samples_ = static_cast<int>(
        (static_cast<long long>(config_.output_sample_rate_hz) * static_cast<long long>(config_.squelch_open_debounce_ms)) / 1000LL);
    squelch_close_hang_samples_ = static_cast<int>(
        (static_cast<long long>(config_.output_sample_rate_hz) * static_cast<long long>(config_.squelch_close_hang_ms)) / 1000LL);
    carrier_drop_grace_samples_ = std::max(
        1,
        static_cast<int>((static_cast<long long>(config_.output_sample_rate_hz) * kCarrierDropGraceMs) / 1000LL));
    carrier_drop_grace_samples_remaining_ = 0;
    dead_carrier_close_samples_ = static_cast<int>(
        (static_cast<long long>(config_.output_sample_rate_hz) * static_cast<long long>(kDeadCarrierCloseMs)) / 1000LL);
    squelch_open_candidate_count_ = 0;
    squelch_close_candidate_count_ = 0;
    dead_carrier_candidate_count_ = 0;
    retune_guard_samples_remaining_ = 0;
    pending_iq_byte_ = 0;
    has_pending_iq_byte_ = false;

    std::fill(stage1_i_history_.begin(), stage1_i_history_.end(), 0.0f);
    std::fill(stage1_q_history_.begin(), stage1_q_history_.end(), 0.0f);
    stage1_write_index_ = 0;
    std::fill(stage2_i_history_.begin(), stage2_i_history_.end(), 0.0f);
    std::fill(stage2_q_history_.begin(), stage2_q_history_.end(), 0.0f);
    stage2_write_index_ = 0;
    std::fill(narrowband_i_history_.begin(), narrowband_i_history_.end(), 0.0f);
    std::fill(narrowband_q_history_.begin(), narrowband_q_history_.end(), 0.0f);
    narrowband_write_index_ = 0;
    reset_ssb_pipeline(&ssb_pipeline_);
    reset_nfm_pipeline(&nfm_pipeline_);
    reset_wfm_pipeline(&wfm_pipeline_);

    std::fill(audio_fifo_.begin(), audio_fifo_.end(), 0);
    audio_fifo_read_index_ = 0;
    audio_fifo_write_index_ = 0;
    audio_fifo_size_ = 0;

    stats_rf_accumulator_ = 0.0f;
    stats_audio_accumulator_ = 0.0f;
    stats_sample_count_ = 0;
    stats_audio_count_ = 0;
    stats_clip_count_ = 0;
    stats_iq_count_ = 0;
    stats_iq_clip_count_ = 0;

    rf_level_percent_ = 0;
    audio_level_percent_ = 0;
    clip_percent_ = 0;
    iq_clip_percent_ = 0;
}

void AmPipeline::note_retune(AmCarrierTargets targets) {
    std::lock_guard<std::mutex> lock(mutex_);
    config_.carrier_targets = targets;
    am_carrier_tracker_.set_targets(targets);
    manual_squelch_meter_.reset();
    manual_meter_samples_remaining_ = 0;
    manual_meter_time_ms_ = 0;
    manual_qualified_percent_ = 0;
    squelch_open_ = false;
    squelch_open_candidate_count_ = 0;
    squelch_close_candidate_count_ = 0;
    carrier_drop_grace_samples_remaining_ = 0;
    dead_carrier_candidate_count_ = 0;
    has_pending_iq_byte_ = false;
    stage1_carrier_phase_rad_ = 0.0f;
    stage1_carrier_freq_rad_per_sample_ = 0.0f;
    carrier_track_target_rad_per_sample_ = 0.0f;
    carrier_track_confidence_ = 0.0f;
    carrier_track_update_counter_ = 0;
    weak_carrier_coherence_.reset();
    am_carrier_tracker_.reset();
    carrier_track_disc_i_lp_ = 1.0f;
    carrier_track_disc_q_lp_ = 0.0f;
    has_stage1_prev_ = false;
    envelope_carrier_reference_rad_per_sample_ = 0.0f;
    effective_stage1_carrier_freq_rad_per_sample_ = 0.0f;
    has_envelope_carrier_reference_ = false;
    retune_guard_samples_remaining_ = std::max(
        1,
        static_cast<int>((static_cast<long long>(config_.output_sample_rate_hz) * kRetuneGuardMs) / 1000LL));
}

float AmPipeline::low_pass_alpha(float cutoff_hz, float min_cutoff_hz, float max_cutoff_hz, float max_alpha) const {
    const float clamped = std::clamp(cutoff_hz, min_cutoff_hz, max_cutoff_hz);
    const float alpha = 1.0f - std::exp((-2.0f * kPi * clamped) / static_cast<float>(config_.output_sample_rate_hz));
    return std::clamp(alpha, 0.01f, max_alpha);
}

float AmPipeline::high_pass_alpha(float cutoff_hz) const {
    const float rc = 1.0f / (2.0f * kPi * std::max(cutoff_hz, 1.0f));
    const float dt = 1.0f / static_cast<float>(config_.output_sample_rate_hz);
    return std::clamp(rc / (rc + dt), 0.5f, 0.999f);
}

float AmPipeline::current_auto_squelch_percent() const {
    const float squelch_excess = std::max(0.0f, squelch_level_tracker_ - squelch_noise_floor_);
    return std::clamp((squelch_excess / kAutoSquelchReference) * 100.0f, 0.0f, 100.0f);
}

float AmPipeline::current_manual_squelch_percent() const {
    return std::clamp((manual_squelch_level_ / kManualCarrierReference) * 100.0f, 0.0f, 100.0f);
}

float AmPipeline::current_meter_percent() const {
    return current_manual_squelch_percent();
}

float AmPipeline::current_squelch_noise_floor_percent() const {
    return std::clamp((squelch_noise_floor_ / kAutoSquelchReference) * 100.0f, 0.0f, 100.0f);
}

void AmPipeline::push_iq_stage1(float i, float q) {
    if (stage1_taps_.empty()) {
        return;
    }
    stage1_i_history_[stage1_write_index_] = i;
    stage1_q_history_[stage1_write_index_] = q;
    stage1_write_index_ = (stage1_write_index_ + 1) % stage1_taps_.size();
}

void AmPipeline::push_iq_stage2(float i, float q) {
    if (stage2_taps_.empty()) {
        return;
    }
    stage2_i_history_[stage2_write_index_] = i;
    stage2_q_history_[stage2_write_index_] = q;
    stage2_write_index_ = (stage2_write_index_ + 1) % stage2_taps_.size();
}

void AmPipeline::fir_decimate(const std::vector<float>& taps, const std::vector<float>& history_i, const std::vector<float>& history_q, std::size_t write_index, float* out_i, float* out_q) const {
    float acc_i = 0.0f;
    float acc_q = 0.0f;
    std::size_t history_index = write_index;

    for (std::size_t tap_index = 0; tap_index < taps.size(); ++tap_index) {
        history_index = (history_index == 0) ? taps.size() - 1 : history_index - 1;
        const float tap = taps[tap_index];
        acc_i += history_i[history_index] * tap;
        acc_q += history_q[history_index] * tap;
    }

    *out_i = acc_i;
    *out_q = acc_q;
}

float AmPipeline::dc_block(float sample) {
    const float output = sample - detector_prev_input_ + kDcBlockPole * detector_prev_output_;
    detector_prev_input_ = sample;
    detector_prev_output_ = output;
    return output;
}

float AmPipeline::audio_high_pass(float sample, float cutoff_hz) {
    const float alpha = high_pass_alpha(cutoff_hz);
    const float output = alpha * (audio_hpf_prev_output_ + sample - audio_hpf_prev_input_);
    audio_hpf_prev_input_ = sample;
    audio_hpf_prev_output_ = output;
    return output;
}

float AmPipeline::audio_low_pass(float sample, float alpha) {
    audio_lp1_ += (sample - audio_lp1_) * alpha;
    audio_lp2_ += (audio_lp1_ - audio_lp2_) * alpha;
    return audio_lp2_;
}

float AmPipeline::demodulate_envelope(float /*filtered_i*/, float /*filtered_q*/, float magnitude) {
    return demodulate_am_envelope(magnitude, &carrier_level_, &carrier_presence_level_);
}

float AmPipeline::demodulate_synchronous(float filtered_i, float filtered_q, float magnitude) {
    return demodulate_am_synchronous(
        config_.advanced_sync_enabled,
        filtered_i,
        filtered_q,
        magnitude,
        &carrier_level_,
        &carrier_presence_level_,
        &carrier_recovery_i_lp_,
        &carrier_recovery_q_lp_,
        &sync_lock_quality_,
        &sync_phase_error_);
}

float AmPipeline::demodulate_ssb(float filtered_i, float filtered_q) {
    return ::demodulate_ssb(&ssb_pipeline_, config_.sideband_mode, filtered_i, filtered_q, &carrier_presence_level_);
}

float AmPipeline::demodulate_nfm(float filtered_i, float filtered_q) {
    return ::demodulate_nfm(&nfm_pipeline_, filtered_i, filtered_q, &carrier_presence_level_);
}

float AmPipeline::demodulate_wfm(float filtered_i, float filtered_q) {
    return ::demodulate_wfm(&wfm_pipeline_, stage2_sample_rate_hz_, filtered_i, filtered_q, &carrier_presence_level_);
}

void AmPipeline::apply_sideband_focus(float* i, float* q) {
    if (i == nullptr || q == nullptr || config_.sideband_mode == 0) {
        return;
    }
    const float shift_hz = config_.voice_focus_enabled ? 1300.0f : 1500.0f;
    const float direction = config_.sideband_mode == 2 ? -1.0f : 1.0f;
    const float phase_step = (2.0f * kPi * shift_hz * direction) / static_cast<float>(config_.output_sample_rate_hz);
    sideband_focus_phase_rad_ += phase_step;
    if (sideband_focus_phase_rad_ > kPi) {
        sideband_focus_phase_rad_ -= 2.0f * kPi;
    } else if (sideband_focus_phase_rad_ < -kPi) {
        sideband_focus_phase_rad_ += 2.0f * kPi;
    }
    const float cos_phase = std::cos(sideband_focus_phase_rad_);
    const float sin_phase = std::sin(sideband_focus_phase_rad_);
    const float rotated_i = *i * cos_phase - *q * sin_phase;
    const float rotated_q = *i * sin_phase + *q * cos_phase;
    const float sideband_alpha = config_.voice_focus_enabled ? 0.18f : 0.14f;
    sideband_focus_i_lp_ += (rotated_i - sideband_focus_i_lp_) * sideband_alpha;
    sideband_focus_q_lp_ += (rotated_q - sideband_focus_q_lp_) * sideband_alpha;
    *i = sideband_focus_i_lp_;
    *q = sideband_focus_q_lp_;
}

float AmPipeline::apply_auto_notch(float sample) {
    if (config_.notch_mode <= 0) {
        notch_prev_input_2_ = notch_prev_input_1_;
        notch_prev_input_1_ = sample;
        notch_prev_output_2_ = notch_prev_output_1_;
        notch_prev_output_1_ = sample;
        notch_power_ += (0.0f - notch_power_) * 0.02f;
        notch_corr_ += (0.0f - notch_corr_) * 0.02f;
        notch_frequency_hz_ += (0.0f - notch_frequency_hz_) * 0.02f;
        notch_confidence_ += (0.0f - notch_confidence_) * 0.02f;
        notch_mix_i_ += (1.0f - notch_mix_i_) * 0.02f;
        notch_mix_q_ += (0.0f - notch_mix_q_) * 0.02f;
        return sample;
    }

    const float power = sample * sample;
    notch_power_ += (power - notch_power_) * kAdaptiveNotchPowerAlpha;
    notch_corr_ += ((sample * prev_audio_sample_) - notch_corr_) * kAdaptiveNotchCorrAlpha;

    float estimated_hz = 0.0f;
    if (notch_power_ > 1.0f) {
        const float normalized_corr = std::clamp(notch_corr_ / notch_power_, -0.995f, 0.995f);
        estimated_hz = std::acos(normalized_corr) * static_cast<float>(config_.output_sample_rate_hz) / (2.0f * kPi);
    }
    const bool frequency_valid = estimated_hz >= 350.0f && estimated_hz <= 3200.0f;
    const float tone_ratio = notch_power_ > 1.0f ? std::fabs(notch_corr_) / notch_power_ : 0.0f;
    const bool tone_candidate = frequency_valid &&
        tone_ratio >= 0.90f &&
        notch_power_ >= std::max(1600.0f, denoise_noise_floor_ * denoise_noise_floor_ * 1.4f);
    const float target_confidence = tone_candidate ? 1.0f : 0.0f;
    notch_confidence_ += (target_confidence - notch_confidence_) * (
        target_confidence > notch_confidence_
            ? kAdaptiveNotchConfidenceAttackAlpha
            : kAdaptiveNotchConfidenceReleaseAlpha);
    if (frequency_valid) {
        notch_frequency_hz_ += (estimated_hz - notch_frequency_hz_) * kAdaptiveNotchFreqAlpha;
    } else {
        notch_frequency_hz_ += (0.0f - notch_frequency_hz_) * 0.01f;
    }

    if (notch_confidence_ < 0.20f || notch_frequency_hz_ < 1.0f) {
        notch_prev_input_2_ = notch_prev_input_1_;
        notch_prev_input_1_ = sample;
        notch_prev_output_2_ = notch_prev_output_1_;
        notch_prev_output_1_ = sample;
        return sample;
    }

    const float omega = 2.0f * kPi * notch_frequency_hz_ / static_cast<float>(config_.output_sample_rate_hz);
    const float cos_omega = std::cos(omega);
    const float radius = 0.975f;
    const float b0 = 1.0f;
    const float b1 = -2.0f * cos_omega;
    const float b2 = 1.0f;
    const float a1 = -2.0f * radius * cos_omega;
    const float a2 = radius * radius;
    const float filtered = b0 * sample +
        b1 * notch_prev_input_1_ +
        b2 * notch_prev_input_2_ -
        a1 * notch_prev_output_1_ -
        a2 * notch_prev_output_2_;
    notch_prev_input_2_ = notch_prev_input_1_;
    notch_prev_input_1_ = sample;
    notch_prev_output_2_ = notch_prev_output_1_;
    notch_prev_output_1_ = filtered;

    return sample + (filtered - sample) * std::clamp(notch_confidence_, 0.0f, 1.0f);
}

float AmPipeline::apply_speech_enhancer(float sample, bool carrier_present) {
    if (config_.speech_enhancer_mode <= 0) {
        speech_low_lp_ += (sample - speech_low_lp_) * 0.030f;
        speech_mid_lp_ += (sample - speech_mid_lp_) * 0.080f;
        speech_presence_lp_ += (sample - speech_presence_lp_) * 0.180f;
        speech_harsh_lp_ += (sample - speech_harsh_lp_) * 0.320f;
        speech_last_enhanced_ = sample;
        speech_enhancer_gain_ += (1.0f - speech_enhancer_gain_) * 0.03f;
        speech_activity_env_ += (0.0f - speech_activity_env_) * 0.03f;
        speech_consonant_env_ += (0.0f - speech_consonant_env_) * 0.03f;
        speech_limiter_env_ += (0.0f - speech_limiter_env_) * 0.03f;
        speech_limiter_gain_ += (1.0f - speech_limiter_gain_) * 0.03f;
        return sample;
    }

    const bool strong_mode = config_.speech_enhancer_mode >= 2;
    const float signal_quality = std::clamp(
        ((carrier_presence_level_ - kCarrierPresenceThreshold) / 28.0f) * 0.65f +
            std::clamp(1.0f - (denoise_noise_floor_ / std::max(carrier_presence_level_, 1.0f)), 0.0f, 1.0f) * 0.35f,
        0.0f,
        1.0f);
    const float adaptive_drive = strong_mode
        ? (1.05f - signal_quality * 0.35f)
        : (1.00f - signal_quality * 0.25f);
    const float low_alpha = strong_mode ? 0.035f : 0.025f;
    const float mid_alpha = strong_mode ? 0.095f : 0.070f;
    const float presence_alpha = strong_mode ? 0.22f : 0.16f;
    const float harsh_alpha = strong_mode ? 0.38f : 0.28f;
    speech_low_lp_ += (sample - speech_low_lp_) * low_alpha;
    speech_mid_lp_ += (sample - speech_mid_lp_) * mid_alpha;
    speech_presence_lp_ += (sample - speech_presence_lp_) * presence_alpha;
    speech_harsh_lp_ += (sample - speech_harsh_lp_) * harsh_alpha;

    const float body_band = speech_mid_lp_ - speech_low_lp_;
    const float presence_band = speech_presence_lp_ - speech_mid_lp_;
    const float harsh_band = speech_harsh_lp_ - speech_presence_lp_;
    const float transient = sample - speech_last_enhanced_;
    speech_last_enhanced_ = sample;
    const float activity_target = std::fabs(body_band) + std::fabs(presence_band) * 0.8f;
    speech_activity_env_ += (activity_target - speech_activity_env_) * (activity_target > speech_activity_env_ ? 0.08f : 0.02f);
    const float consonant_target = std::fabs(transient) + std::fabs(presence_band) * 0.35f;
    speech_consonant_env_ += (consonant_target - speech_consonant_env_) * (
        consonant_target > speech_consonant_env_ ? 0.12f : 0.03f);

    const float body_weight = (strong_mode ? 0.14f : 0.08f) * adaptive_drive;
    const float presence_weight = (strong_mode ? 0.34f : 0.20f) * adaptive_drive;
    const float transient_weight = (strong_mode ? 0.15f : 0.08f) * adaptive_drive;
    const float anti_harsh_weight = (strong_mode ? 0.18f : 0.10f) * (0.85f + (1.0f - signal_quality) * 0.25f);
    const float expander_floor = strong_mode ? 0.84f : 0.92f;
    const float activity_gate = std::clamp(
        (modulation_envelope_ - (strong_mode ? 10.0f : 14.0f)) / (strong_mode ? 40.0f : 48.0f),
        0.0f,
        1.0f);
    const float consonant_gate = std::clamp(
        (std::fabs(presence_band) - (strong_mode ? 18.0f : 24.0f)) / (strong_mode ? 160.0f : 190.0f),
        0.0f,
        1.0f) * activity_gate * std::clamp(speech_consonant_env_ / (strong_mode ? 220.0f : 260.0f), 0.0f, 1.0f);
    const float consonant_protection = std::clamp(
        std::fabs(presence_band) / (strong_mode ? 240.0f : 300.0f) +
            std::fabs(transient) / (strong_mode ? 520.0f : 680.0f),
        0.0f,
        1.0f) * activity_gate;
    const float body_boost = body_band * body_weight * activity_gate;
    const float presence_boost = presence_band * presence_weight * activity_gate;
    const float transient_boost = transient * transient_weight * std::max(consonant_gate, consonant_protection * 0.75f);
    const float harsh_control = harsh_band * anti_harsh_weight * activity_gate;
    const float speech_target_gain = carrier_present
        ? (squelch_open_ ? 1.0f : kCarrierPresentSpeechGainFloor)
        : expander_floor;
    const float speech_gain_alpha = speech_target_gain < speech_enhancer_gain_ ? 0.06f : 0.02f;
    speech_enhancer_gain_ += (speech_target_gain - speech_enhancer_gain_) * speech_gain_alpha;

    const float enhanced = (sample + body_boost + presence_boost + transient_boost - harsh_control) * speech_enhancer_gain_;
    const float enhanced_abs = std::fabs(enhanced);
    speech_limiter_env_ += (enhanced_abs - speech_limiter_env_) * (enhanced_abs > speech_limiter_env_ ? 0.12f : 0.01f);
    const float limiter_threshold = strong_mode ? 11800.0f : 13200.0f;
    const float limiter_target = speech_limiter_env_ > limiter_threshold
        ? std::clamp(limiter_threshold / std::max(speech_limiter_env_, 1.0f), strong_mode ? 0.78f : 0.88f, 1.0f)
        : 1.0f;
    speech_limiter_gain_ += (limiter_target - speech_limiter_gain_) * (
        limiter_target < speech_limiter_gain_ ? 0.16f : 0.02f);
    return std::clamp(enhanced * speech_limiter_gain_, -32767.0f, 32767.0f);
}

void AmPipeline::apply_noise_blanker(float* i, float* q, float magnitude) {
    if (i == nullptr || q == nullptr || !config_.noise_blanker_enabled) {
        return;
    }
    if (!has_raw_prev_) {
        raw_prev_i_ = *i;
        raw_prev_q_ = *q;
        has_raw_prev_ = true;
        return;
    }

    const float delta_i = *i - raw_prev_i_;
    const float delta_q = *q - raw_prev_q_;
    const float impulse = std::sqrt(delta_i * delta_i + delta_q * delta_q);
    const float impulse_alpha = impulse > iq_impulse_envelope_
        ? kNoiseBlankerEnvelopeAttackAlpha
        : kNoiseBlankerEnvelopeReleaseAlpha;
    const float threshold = std::max(
        kNoiseBlankerFloor + magnitude * 0.55f,
        iq_impulse_envelope_ * kNoiseBlankerThresholdScale);
    const bool strong_impulse = impulse > threshold && impulse > magnitude * 1.15f;
    // Compare against the preceding baseline; including this impulse first
    // makes the threshold exceed the impulse itself and disables the blanker.
    iq_impulse_envelope_ += (impulse - iq_impulse_envelope_) * impulse_alpha;
    raw_prev_i_ = *i;
    raw_prev_q_ = *q;
    if (strong_impulse) {
        *i -= delta_i * kNoiseBlankerBlend;
        *q -= delta_q * kNoiseBlankerBlend;
    }
}

float AmPipeline::apply_denoise(float sample, bool carrier_present) {
    if (config_.denoise_mode <= 0) {
        denoise_gain_ += (1.0f - denoise_gain_) * 0.02f;
        return sample;
    }

    const bool auto_mode = config_.denoise_mode >= 3;
    const bool strong_mode = config_.denoise_mode >= 2 && !auto_mode;
    const float denoise_aggression = auto_mode
        ? std::clamp((carrier_presence_level_ - kCarrierPresenceThreshold) / 36.0f, 0.0f, 0.75f)
        : (strong_mode ? 0.70f : 0.0f);

    const float abs_sample = std::fabs(sample);
    const bool likely_noise = !carrier_present ||
        !squelch_open_ ||
        (modulation_envelope_ < (12.0f + denoise_aggression * 6.0f) &&
            abs_sample < std::max(denoise_signal_envelope_ * (0.46f + denoise_aggression * 0.10f), 68.0f + denoise_aggression * 24.0f));
    const float noise_alpha = likely_noise
        ? (abs_sample > denoise_noise_floor_ ? kDenoiseNoiseRiseAlpha : kDenoiseNoiseFallAlpha)
        : kDenoiseNoiseFallAlpha;
    denoise_noise_floor_ += (abs_sample - denoise_noise_floor_) * noise_alpha;

    const float signal_alpha = abs_sample > denoise_signal_envelope_
        ? kDenoiseSignalAttackAlpha
        : kDenoiseSignalReleaseAlpha;
    denoise_signal_envelope_ += (abs_sample - denoise_signal_envelope_) * signal_alpha;

    const float safe_noise = std::max(denoise_noise_floor_, 1.0f);
    const float speech_guard = std::max(48.0f + denoise_aggression * 16.0f, modulation_envelope_ * (0.36f - denoise_aggression * 0.06f));
    const float excess = std::max(0.0f, abs_sample - safe_noise * (1.05f + denoise_aggression * 0.07f));
    const float preserve = std::clamp(excess / (safe_noise * (1.35f + denoise_aggression * 0.45f) + speech_guard), 0.0f, 1.0f);
    const float suppression_floor = 0.78f - denoise_aggression * (0.78f - kDenoiseSuppressionFloor);
    float target_gain = suppression_floor + (1.0f - suppression_floor) * preserve;
    if (carrier_present && squelch_open_ && modulation_envelope_ > (55.0f + denoise_aggression * 30.0f)) {
        target_gain = std::max(target_gain, 0.97f - denoise_aggression * 0.05f);
    }
    if (carrier_present) {
        target_gain = std::max(target_gain, kCarrierPresentDenoiseFloor - denoise_aggression * 0.05f);
    }
    if (auto_mode && carrier_presence_level_ < (kCarrierPresenceThreshold + 8.0f)) {
        target_gain = std::max(target_gain, 0.88f);
    }
    const float gain_alpha = target_gain < denoise_gain_ ? kDenoiseGainAttackAlpha : kDenoiseGainReleaseAlpha;
    denoise_gain_ += (target_gain - denoise_gain_) * gain_alpha;
    return sample * denoise_gain_;
}

void AmPipeline::push_audio_sample(float sample) {
    if (audio_fifo_.empty()) {
        return;
    }

    const float clamped = std::clamp(sample, -32767.0f, 32767.0f);
    audio_fifo_[audio_fifo_write_index_] = static_cast<int16_t>(std::lround(clamped));
    audio_fifo_write_index_ = (audio_fifo_write_index_ + 1) % audio_fifo_.size();

    if (audio_fifo_size_ == audio_fifo_.size()) {
        audio_fifo_read_index_ = (audio_fifo_read_index_ + 1) % audio_fifo_.size();
    } else {
        audio_fifo_size_ += 1;
    }
}

std::size_t AmPipeline::pop_audio_samples(int16_t* pcm_out, std::size_t pcm_capacity) {
    if (pcm_out == nullptr || pcm_capacity == 0 || audio_fifo_size_ == 0) {
        return 0;
    }

    const std::size_t output_count = std::min(audio_fifo_size_, pcm_capacity);
    for (std::size_t index = 0; index < output_count; ++index) {
        pcm_out[index] = audio_fifo_[audio_fifo_read_index_];
        audio_fifo_read_index_ = (audio_fifo_read_index_ + 1) % audio_fifo_.size();
    }
    audio_fifo_size_ -= output_count;
    return output_count;
}

void AmPipeline::update_carrier_tracking(float i, float q) {
    if (config_.demod_mode == 1 || config_.demod_mode == 2) {
        am_carrier_tracker_.observe(i, q);
        effective_stage1_carrier_freq_rad_per_sample_ =
            2.0f * kPi * am_carrier_tracker_.frequency_hz() / stage2_sample_rate_hz_;
        return;
    }
    // Preserve the existing SSB/NFM steering independently of AM acquisition.
    const float stage1_magnitude = std::sqrt(i * i + q * q);
    const float tracking_rate_hz = static_cast<float>(stage2_sample_rate_hz_);
    const float max_track_hz = std::max(
        0.0f,
        std::min(
            kPhaseCarrierSearchHalfBandwidthHz,
            static_cast<float>(stage2_sample_rate_hz_) * 0.5f - 200.0f) - kCarrierTrackPullInMarginHz);
    const float max_track_rad = (tracking_rate_hz > 0.0f)
        ? (2.0f * kPi * max_track_hz) / tracking_rate_hz
        : 0.0f;

    const float coarse_disc_alpha = kEnvelopeCarrierTrackDiscriminatorAlpha;
    const float coarse_release_alpha = kEnvelopeCarrierTrackReleaseAlpha;
    const float envelope_acquire_window_rad = (tracking_rate_hz > 0.0f)
        ? (2.0f * kPi * kEnvelopeCarrierTrackAcquireWindowHz) / tracking_rate_hz
        : 0.0f;
    bool weak_coherent_carrier = false;
    if (has_stage1_prev_) {
        const float dot = stage1_prev_i_ * i + stage1_prev_q_ * q;
        const float cross = stage1_prev_i_ * q - stage1_prev_q_ * i;
        const float prev_magnitude = std::sqrt(stage1_prev_i_ * stage1_prev_i_ + stage1_prev_q_ * stage1_prev_q_);
        const float magnitude_product = std::max(stage1_magnitude * prev_magnitude, 1e-6f);
        const float disc_i = dot / magnitude_product;
        const float disc_q = cross / magnitude_product;
        carrier_track_disc_i_lp_ += (disc_i - carrier_track_disc_i_lp_) * coarse_disc_alpha;
        carrier_track_disc_q_lp_ += (disc_q - carrier_track_disc_q_lp_) * coarse_disc_alpha;
        const float coherence_squared =
            carrier_track_disc_i_lp_ * carrier_track_disc_i_lp_ +
            carrier_track_disc_q_lp_ * carrier_track_disc_q_lp_;
        carrier_track_update_counter_ += 1;
        if (carrier_track_update_counter_ >= kCarrierTrackUpdateStride) {
            weak_carrier_coherence_.observe(i, q, stage1_magnitude);
        }
        // Require both short- and long-lag coherence before extending
        // acquisition below the established RF threshold. Otherwise a
        // nearby noise band can steer the narrow AM filter away from speech.
        weak_coherent_carrier = stage1_magnitude >= kWeakCarrierMinMagnitude &&
            coherence_squared >= kWeakCarrierMinCoherenceSquared &&
            weak_carrier_coherence_.coherent();
        const bool coherent_carrier =
            (stage1_magnitude >= kCarrierTrackMinMagnitude &&
                coherence_squared >= kCarrierTrackMinCoherenceSquared) ||
            weak_coherent_carrier;
        if (carrier_track_update_counter_ >= kCarrierTrackUpdateStride) {
            carrier_track_update_counter_ = 0;
            const float confidence_target = coherent_carrier ? 1.0f : 0.0f;
            const float confidence_alpha = coherent_carrier
                ? kCarrierTrackConfidenceAttackAlpha
                : kCarrierTrackConfidenceReleaseAlpha;
            carrier_track_confidence_ +=
                (confidence_target - carrier_track_confidence_) * confidence_alpha;
            if (coherent_carrier) {
                const float raw_freq = std::atan2(carrier_track_disc_q_lp_, carrier_track_disc_i_lp_);
                carrier_track_target_rad_per_sample_ =
                    std::clamp(raw_freq, -max_track_rad, max_track_rad);
            } else {
                carrier_track_target_rad_per_sample_ +=
                    (0.0f - carrier_track_target_rad_per_sample_) * coarse_release_alpha *
                    static_cast<float>(kCarrierTrackUpdateStride);
            }
        }
        const float target_freq = carrier_track_target_rad_per_sample_;
        const bool carrier_track_valid =
            coherent_carrier &&
            carrier_track_confidence_ >= kCarrierTrackMinimumConfidence;
        const bool envelope_fast_acquire =
            carrier_track_valid &&
                std::fabs(target_freq - stage1_carrier_freq_rad_per_sample_) >= envelope_acquire_window_rad;
        float alpha = carrier_track_valid
            ? (envelope_fast_acquire ? kEnvelopeCarrierTrackAcquireAttackAlpha : kEnvelopeCarrierTrackAttackAlpha)
            : coarse_release_alpha;
        if (!envelope_fast_acquire &&
            modulation_envelope_ > kCarrierPauseModulationThreshold) {
            alpha *= 0.35f;
        }
        stage1_carrier_freq_rad_per_sample_ += (target_freq - stage1_carrier_freq_rad_per_sample_) * alpha;
    } else {
        carrier_track_disc_i_lp_ += (1.0f - carrier_track_disc_i_lp_) * coarse_release_alpha;
        carrier_track_disc_q_lp_ += (0.0f - carrier_track_disc_q_lp_) * coarse_release_alpha;
        stage1_carrier_freq_rad_per_sample_ += (0.0f - stage1_carrier_freq_rad_per_sample_) * coarse_release_alpha;
        carrier_track_target_rad_per_sample_ +=
            (0.0f - carrier_track_target_rad_per_sample_) * coarse_release_alpha;
        carrier_track_confidence_ +=
            (0.0f - carrier_track_confidence_) * kCarrierTrackConfidenceReleaseAlpha;
    }

    float stage1_tracking_freq_rad_per_sample = stage1_carrier_freq_rad_per_sample_;
    {
        const float reference_blend_window_rad = (tracking_rate_hz > 0.0f)
            ? (2.0f * kPi * kEnvelopeCarrierReferenceBlendWindowHz) / tracking_rate_hz
            : 0.0f;
        const float reference_bootstrap_window_rad = (tracking_rate_hz > 0.0f)
            ? (2.0f * kPi * kEnvelopeCarrierReferenceBootstrapWindowHz) / tracking_rate_hz
            : 0.0f;
        const bool stable_envelope_carrier =
            carrier_track_confidence_ >= kCarrierTrackMinimumConfidence &&
            ((stage1_magnitude >= kCarrierTrackMinMagnitude &&
                carrier_presence_level_ >= (kCarrierPresenceThreshold + kEnvelopeCarrierReferenceStablePresenceMargin)) ||
                weak_coherent_carrier);
        if (stable_envelope_carrier) {
            if (!has_envelope_carrier_reference_) {
                envelope_carrier_reference_rad_per_sample_ = stage1_carrier_freq_rad_per_sample_;
                has_envelope_carrier_reference_ = true;
            } else {
                const float reference_delta =
                    stage1_carrier_freq_rad_per_sample_ - envelope_carrier_reference_rad_per_sample_;
                float reference_alpha = kEnvelopeCarrierReferenceAttackAlpha;
                if (std::fabs(reference_delta) >= reference_bootstrap_window_rad) {
                    reference_alpha = std::max(reference_alpha, kEnvelopeCarrierReferenceBootstrapAlpha);
                }
                envelope_carrier_reference_rad_per_sample_ += reference_delta * reference_alpha;
            }
        }
        if (has_envelope_carrier_reference_) {
            const float reference_delta = std::fabs(
                stage1_carrier_freq_rad_per_sample_ - envelope_carrier_reference_rad_per_sample_);
            float reference_blend = reference_blend_window_rad > 0.0f
                ? std::clamp(1.0f - (reference_delta / reference_blend_window_rad), 0.0f, 1.0f)
                : 0.0f;
            if (modulation_envelope_ > kCarrierPauseModulationThreshold) {
                reference_blend = std::max(reference_blend, kEnvelopeCarrierReferenceSpeechBlendFloor);
            }
            stage1_tracking_freq_rad_per_sample =
                stage1_carrier_freq_rad_per_sample_ * (1.0f - reference_blend) +
                envelope_carrier_reference_rad_per_sample_ * reference_blend;
        }
    }
    effective_stage1_carrier_freq_rad_per_sample_ = stage1_tracking_freq_rad_per_sample;

    stage1_prev_i_ = i;
    stage1_prev_q_ = q;
    has_stage1_prev_ = true;
}

AmPipelineProcessResult AmPipeline::process(const int8_t* iq_bytes, std::size_t iq_byte_count, int16_t* pcm_out, std::size_t pcm_capacity) {
    std::lock_guard<std::mutex> lock(mutex_);
    AmPipelineProcessResult result;
    if (pcm_out == nullptr || stage1_taps_.empty() || stage2_taps_.empty()) {
        return result;
    }

    const bool ssb_mode = config_.demod_mode == 3;
    const bool nfm_mode = config_.demod_mode == 4;
    const bool wfm_mode = config_.demod_mode == 5;
    const float audio_ratio = ssb_mode
        ? 0.42f
        : (nfm_mode
            ? 0.34f
            : (wfm_mode ? 0.60f : (config_.voice_focus_enabled ? config_.voice_audio_ratio : config_.natural_audio_ratio)));
    const float max_audio_cutoff = ssb_mode
        ? 3400.0f
        : (nfm_mode
            ? 3200.0f
            : (wfm_mode ? 15000.0f : (config_.voice_focus_enabled ? config_.voice_audio_cutoff_hz : config_.natural_audio_cutoff_hz)));
    const float audio_cutoff_hz = std::min(static_cast<float>(config_.channel_bandwidth_hz) * audio_ratio, max_audio_cutoff);
    const float audio_low_pass_alpha = low_pass_alpha(
        audio_cutoff_hz,
        wfm_mode ? 4000.0f : kMinAudioCutoffHz,
        wfm_mode ? 16000.0f : kMaxAudioCutoffHz,
        wfm_mode ? 0.95f : 0.45f);
    const float audio_high_pass_hz = ssb_mode ? 180.0f : (nfm_mode ? 120.0f : (wfm_mode ? 30.0f : config_.audio_high_pass_hz));
    float block_squelch_percent_peak = 0.0f;
    auto process_audio_sample = [&](float sample) {
        sample = dc_block(sample);
        if (!config_.simple_am_enabled) {
            sample = audio_high_pass(sample, audio_high_pass_hz);
            sample = audio_low_pass(sample, audio_low_pass_alpha);
            sample = apply_auto_notch(sample);
        }

        const float modulation_metric = std::fabs(sample - prev_audio_sample_);
        const float modulation_alpha = modulation_metric > modulation_envelope_
            ? kModulationAttackAlpha
            : kModulationReleaseAlpha;
        modulation_envelope_ += (modulation_metric - modulation_envelope_) * modulation_alpha;
        prev_audio_sample_ = sample;

        const float activity_metric = std::fabs(sample);
        const float activity_alpha = activity_metric > audio_activity_envelope_
            ? 0.020f
            : 0.003f;
        audio_activity_envelope_ += (activity_metric - audio_activity_envelope_) * activity_alpha;
        const float squelch_quality_target = std::clamp(
            sync_lock_quality_ * 100.0f +
                std::max(0.0f, (carrier_presence_level_ - squelch_noise_floor_) * 0.8f) +
                std::max(0.0f, modulation_envelope_ - audio_activity_envelope_ * 0.35f),
            0.0f,
            100.0f);
        squelch_quality_envelope_ += (squelch_quality_target - squelch_quality_envelope_) * (
            squelch_quality_target > squelch_quality_envelope_ ? 0.030f : 0.008f);

        const bool auto_squelch = config_.squelch_mode == kSquelchModeAuto;
        if (!auto_squelch && --manual_meter_samples_remaining_ <= 0) {
            // A single sustained-peak decision drives both audio and telemetry.
            // Fixed-cost integer work at 1 kHz, never a per-sample history scan.
            manual_meter_samples_remaining_ = std::max(1, config_.output_sample_rate_hz / 1000);
            manual_squelch_meter_.observe(
                static_cast<int>(std::lround(current_manual_squelch_percent())), ++manual_meter_time_ms_);
            manual_qualified_percent_ = manual_squelch_meter_.percent(manual_meter_time_ms_);
        }
        const float manual_squelch_metric = manual_squelch_level_;
        const float auto_squelch_metric = std::max(
            std::max(
                modulation_envelope_,
                audio_activity_envelope_ * kSquelchActivityWeight),
            std::max(
                0.0f,
                config_.quality_squelch_enabled ? squelch_quality_envelope_ * kQualitySquelchWeight : 0.0f));
        const float squelch_metric = auto_squelch
            ? auto_squelch_metric
            : manual_squelch_metric;
        const float squelch_alpha = squelch_metric > squelch_level_tracker_
            ? config_.squelch_attack_alpha
            : (
                auto_squelch
                    ? config_.squelch_release_alpha
                    : std::max(config_.squelch_release_alpha, kManualSquelchMinReleaseAlpha)
            );
        squelch_level_tracker_ += (squelch_metric - squelch_level_tracker_) * squelch_alpha;

        const bool carrier_present = carrier_presence_level_ >= kCarrierPresenceThreshold;
        sample = apply_denoise(sample, carrier_present);
        sample = apply_speech_enhancer(sample, carrier_present);
        const bool manual_squelch_fully_open = !auto_squelch && config_.squelch_level <= 0;
        const float preview_open_threshold = manual_squelch_fully_open
            ? 0.0f
            : static_cast<float>(config_.squelch_level);
        if (auto_squelch) {
            const bool update_noise_floor = !carrier_present || !squelch_open_;
            if (update_noise_floor) {
                const float noise_floor_alpha = squelch_level_tracker_ < squelch_noise_floor_
                    ? kSquelchNoiseFloorFallAlpha
                    : kSquelchNoiseFloorRiseAlpha;
                squelch_noise_floor_ += (squelch_level_tracker_ - squelch_noise_floor_) * noise_floor_alpha;
            } else {
                const float held_open_noise_floor_alpha = kSquelchNoiseFloorRiseAlpha * 0.15f;
                squelch_noise_floor_ += (squelch_level_tracker_ - squelch_noise_floor_) * held_open_noise_floor_alpha;
            }
            squelch_noise_floor_ = std::clamp(squelch_noise_floor_, 0.0f, squelch_level_tracker_);
        } else {
            squelch_noise_floor_ = 0.0f;
        }

        const int effective_open_debounce_samples = squelch_open_debounce_samples_;
        const int effective_close_hang_samples = squelch_close_hang_samples_;
        const float squelch_percent = auto_squelch
            ? current_auto_squelch_percent()
            : static_cast<float>(manual_qualified_percent_);
        block_squelch_percent_peak = std::max(block_squelch_percent_peak, current_meter_percent());
        const float open_threshold = preview_open_threshold;
        const float close_threshold = auto_squelch
            ? std::max(0.0f, open_threshold - config_.squelch_hysteresis_percent)
            : open_threshold;
        const float quality_open_threshold = std::clamp(open_threshold * 0.55f + 10.0f, 8.0f, 75.0f);
        const bool retune_guard_active = auto_squelch && retune_guard_samples_remaining_ > 0;
        const float guarded_open_threshold = retune_guard_active
            ? std::min(100.0f, open_threshold + kRetuneGuardOpenBonus)
            : open_threshold;
        const float guarded_quality_open_threshold = retune_guard_active
            ? std::clamp(quality_open_threshold + kRetuneGuardQualityBonus, 8.0f, 90.0f)
            : quality_open_threshold;
        const bool quality_open_ok = !config_.quality_squelch_enabled || squelch_quality_envelope_ >= quality_open_threshold;
        const bool speech_tail_active =
            auto_squelch &&
                squelch_open_ &&
                (
                    modulation_envelope_ >= std::max(kSpeechTailModulationFloor, close_threshold * 0.32f) ||
                        audio_activity_envelope_ >= std::max(kSpeechTailActivityFloor, close_threshold * 0.18f) ||
                        squelch_quality_envelope_ >= std::max(kSpeechTailQualityFloor, quality_open_threshold * 0.28f)
                );
        const bool carrier_drop_grace_active =
            auto_squelch &&
                !carrier_present &&
                speech_tail_active &&
                carrier_drop_grace_samples_remaining_ > 0;
        if (auto_squelch && carrier_present) {
            carrier_drop_grace_samples_remaining_ = carrier_drop_grace_samples_;
        } else if (carrier_drop_grace_active) {
            carrier_drop_grace_samples_remaining_ -= 1;
        } else {
            carrier_drop_grace_samples_remaining_ = 0;
        }
        const bool effective_carrier_present = carrier_present || carrier_drop_grace_active;
        const bool continuity_hold_active = auto_squelch && effective_carrier_present && speech_tail_active;
        const bool dead_carrier_candidate =
            auto_squelch &&
                carrier_present &&
                modulation_envelope_ < std::max(12.0f, close_threshold * 0.36f) &&
                audio_activity_envelope_ < std::max(8.0f, close_threshold * 0.24f) &&
                squelch_percent < std::min(100.0f, close_threshold + 2.0f);
        if (dead_carrier_candidate) {
            dead_carrier_candidate_count_ += 1;
        } else {
            dead_carrier_candidate_count_ = 0;
        }
        const bool dead_carrier_timeout =
            dead_carrier_close_samples_ > 0 &&
                dead_carrier_candidate_count_ >= dead_carrier_close_samples_;
        const bool strong_signal_bypass = retune_guard_active &&
            carrier_present &&
            squelch_percent >= std::min(100.0f, open_threshold + kRetuneStrongSignalOpenBonus) &&
            squelch_quality_envelope_ >= std::max(guarded_quality_open_threshold, kRetuneStrongSignalQualityFloor);
        const bool open_candidate = auto_squelch
            ? (
                carrier_present &&
                    (
                        strong_signal_bypass ||
                            (
                                squelch_percent >= guarded_open_threshold &&
                                squelch_quality_envelope_ >= guarded_quality_open_threshold &&
                                    quality_open_ok
                            )
                    )
            )
            : (squelch_percent >= open_threshold);
        const bool close_candidate = auto_squelch
            ? (!effective_carrier_present || (squelch_percent < close_threshold && !continuity_hold_active) || dead_carrier_timeout)
            : (squelch_percent < close_threshold && !continuity_hold_active);
        if (!auto_squelch) {
            squelch_open_ = manual_squelch_fully_open || manual_qualified_percent_ >= config_.squelch_level;
            squelch_open_candidate_count_ = 0;
            squelch_close_candidate_count_ = 0;
        } else if (config_.squelch_level == 0) {
            squelch_open_ = true;
            squelch_open_candidate_count_ = 0;
            squelch_close_candidate_count_ = 0;
            dead_carrier_candidate_count_ = 0;
        } else if (squelch_open_) {
            squelch_open_candidate_count_ = 0;
            if (close_candidate) {
                if (effective_close_hang_samples <= 0) {
                    squelch_open_ = false;
                    squelch_close_candidate_count_ = 0;
                    dead_carrier_candidate_count_ = 0;
                } else {
                    squelch_close_candidate_count_ += 1;
                    if (squelch_close_candidate_count_ >= effective_close_hang_samples) {
                        squelch_open_ = false;
                        squelch_close_candidate_count_ = 0;
                        dead_carrier_candidate_count_ = 0;
                    }
                }
            } else {
                squelch_close_candidate_count_ = 0;
            }
        } else if (open_candidate) {
            squelch_close_candidate_count_ = 0;
            dead_carrier_candidate_count_ = 0;
            if (effective_open_debounce_samples <= 0) {
                squelch_open_ = true;
                squelch_open_candidate_count_ = 0;
            } else {
                squelch_open_candidate_count_ += 1;
                if (squelch_open_candidate_count_ >= effective_open_debounce_samples) {
                    squelch_open_ = true;
                    squelch_open_candidate_count_ = 0;
                }
            }
        } else {
            squelch_open_candidate_count_ = 0;
            squelch_close_candidate_count_ = 0;
            dead_carrier_candidate_count_ = 0;
        }
        if (retune_guard_samples_remaining_ > 0) {
            retune_guard_samples_remaining_ -= 1;
        }

        // Manual/custom squelch is expected to behave as a strict threshold.
        // Keep soft gate behavior only for auto squelch, where preserving speech tails matters more.
        const bool strict_manual_squelch = !auto_squelch && config_.squelch_level > 0;
        const bool hard_squelch_mute_enabled = strict_manual_squelch || config_.hard_squelch_mute_enabled;
        const bool soft_squelch_mode = !hard_squelch_mute_enabled && config_.squelch_level > 0;
        const bool speech_friendly_gate =
            auto_squelch &&
                config_.squelch_level > 0 &&
                (config_.voice_focus_enabled || config_.post_leveler_enabled || config_.speech_enhancer_mode > 0);
        const float hard_closed_gate_floor = (!squelch_open_ && speech_friendly_gate)
            ? (
                carrier_present
                    ? kCarrierPresentClosedGateFloor
                    : (carrier_drop_grace_active ? kCarrierDropGraceGateFloor : 0.0f)
            )
            : 0.0f;
        const float soft_closed_gate_floor =
            carrier_present
                ? kSoftSquelchCarrierPresentGateFloor
                : (carrier_drop_grace_active ? kSoftSquelchCarrierDropGraceGateFloor : kSoftSquelchClosedGateFloor);
        const float closed_gate_floor = soft_squelch_mode ? soft_closed_gate_floor : hard_closed_gate_floor;
        const float gate_target = squelch_open_ ? 1.0f : closed_gate_floor;
        const float gate_alpha = gate_target > gate_gain_
            ? 0.08f
            : (soft_squelch_mode ? 0.010f : (closed_gate_floor > 0.0f ? 0.008f : 0.08f));
        gate_gain_ += (gate_target - gate_gain_) * gate_alpha;
        const float fade_target = squelch_open_
            ? 1.0f
            : (soft_squelch_mode ? 1.0f : closed_gate_floor);
        const float fade_alpha = fade_target > output_fade_gain_
            ? kOutputFadeAttackAlpha
            : kOutputFadeReleaseAlpha;
        output_fade_gain_ += (fade_target - output_fade_gain_) * fade_alpha;
        sample *= gate_gain_ * output_fade_gain_;

        const bool low_modulation = carrier_present && modulation_envelope_ <= kCarrierPauseModulationThreshold;
        carrier_pause_gate_gain_ = 1.0f;

        const float abs_sample = std::fabs(sample);
        if (squelch_open_ || gate_gain_ > 0.2f) {
            float agc_alpha = abs_sample > agc_envelope_ ? kAgcAttackAlpha : kAgcReleaseAlpha;
            if (low_modulation && abs_sample <= agc_envelope_) {
                agc_alpha *= 0.15f;
            }
            agc_envelope_ += (abs_sample - agc_envelope_) * agc_alpha;
        } else {
            agc_envelope_ += (kAgcFloor - agc_envelope_) * 0.04f;
        }
        agc_envelope_ = std::max(agc_envelope_, kAgcFloor);

        const float target_gain = std::clamp(config_.target_audio_level / agc_envelope_, kAgcMinGain, kAgcMaxGain);
        const float gain_alpha = target_gain < agc_gain_ ? kAgcGainAttackAlpha : kAgcGainReleaseAlpha;
        agc_gain_ += (target_gain - agc_gain_) * gain_alpha;
        float processed = sample * agc_gain_ * config_.output_gain *
            (static_cast<float>(config_.audio_boost_percent) / 100.0f);

        if (!config_.simple_am_enabled) {
            const float clip_guard_threshold = config_.soft_clip_threshold * kClipGuardHeadroom;
            const float processed_abs = std::fabs(processed);
            const float clip_guard_target = processed_abs > clip_guard_threshold
                ? std::clamp(clip_guard_threshold / std::max(processed_abs, 1.0f), 0.78f, 1.0f)
                : 1.0f;
            clip_guard_gain_ += (clip_guard_target - clip_guard_gain_) * (
                clip_guard_target < clip_guard_gain_
                    ? (kClipGuardAttackAlpha * 0.55f)
                    : (kClipGuardReleaseAlpha * 0.60f));
            processed *= clip_guard_gain_;
        }

        if (config_.post_leveler_enabled) {
            const float level_abs = std::fabs(processed);
            const float env_alpha = level_abs > post_level_envelope_ ? config_.post_leveler_attack_alpha : config_.post_leveler_release_alpha;
            post_level_envelope_ += (level_abs - post_level_envelope_) * env_alpha;
            const float safe_env = std::max(post_level_envelope_, 1.0f);
            const bool denoise_active = config_.denoise_mode > 0;
            const bool strong_denoise = config_.denoise_mode >= 2;
            const float leveler_target_scale = denoise_active ? (strong_denoise ? 0.88f : 0.95f) : 1.0f;
            const float leveler_max_gain = denoise_active
                ? std::min(config_.post_leveler_max_gain, strong_denoise ? 1.45f : 1.60f)
                : config_.post_leveler_max_gain;
            const float leveler_target = std::clamp(
                (config_.post_leveler_target * leveler_target_scale) / safe_env,
                1.0f / leveler_max_gain,
                leveler_max_gain);
            const float configured_alpha = leveler_target < post_level_gain_ ? config_.post_leveler_attack_alpha : config_.post_leveler_release_alpha;
            const float leveler_alpha = std::clamp(configured_alpha * 0.35f, 0.0005f, 0.020f);
            post_level_gain_ += (leveler_target - post_level_gain_) * leveler_alpha;
            processed *= post_level_gain_;
        } else {
            post_level_envelope_ += (0.0f - post_level_envelope_) * 0.06f;
            post_level_gain_ += (1.0f - post_level_gain_) * 0.06f;
        }

        if (config_.denoise_mode > 0) {
            const bool strong_denoise = config_.denoise_mode >= 2;
            const float peak_abs = std::fabs(processed);
            const float peak_alpha = peak_abs > denoise_peak_envelope_ ? 0.030f : 0.004f;
            denoise_peak_envelope_ += (peak_abs - denoise_peak_envelope_) * peak_alpha;
            const float limiter_threshold = std::min(
                config_.soft_clip_threshold * (strong_denoise ? 0.78f : 0.86f),
                std::max(strong_denoise ? 9000.0f : 11000.0f, denoise_peak_envelope_ * (strong_denoise ? 1.20f : 1.32f)));
            const float limiter_target = peak_abs > limiter_threshold
                ? std::clamp(limiter_threshold / std::max(peak_abs, 1.0f), strong_denoise ? 0.72f : 0.84f, 1.0f)
                : 1.0f;
            const float limiter_alpha = limiter_target < denoise_limiter_gain_
                ? kDenoiseLimiterAttackAlpha
                : kDenoiseLimiterReleaseAlpha;
            denoise_limiter_gain_ += (limiter_target - denoise_limiter_gain_) * limiter_alpha;
            processed *= denoise_limiter_gain_;
        } else {
            denoise_peak_envelope_ += (0.0f - denoise_peak_envelope_) * 0.05f;
            denoise_limiter_gain_ += (1.0f - denoise_limiter_gain_) * 0.05f;
        }

        // Keep the existing level detectors unchanged: they must not compensate
        // for the noise this listening-only filter removes. FM/SSB bypass it.
        if (!ssb_mode && !nfm_mode && !wfm_mode) {
            processed = am_speech_filter_.process(processed, config_.am_speech_filter_enabled);
        }
        if (std::fabs(processed) >= config_.soft_clip_threshold) {
            stats_clip_count_ += 1;
        }
        const float output_sample = soft_clip_sample(processed, config_.soft_clip_threshold, config_.soft_clip_drive);

        push_audio_sample(output_sample);
        stats_audio_accumulator_ += output_sample * output_sample;
        stats_audio_count_ += 1;
    };

    auto process_stage2 = [&](float i, float q) {
        if (wfm_mode) {
            const float magnitude = std::sqrt(i * i + q * q);
            const float audio = demodulate_wfm(i, q);
            push_iq_stage2(audio, 0.0f);
            if (++stage2_decimation_counter_ != stage2_decimation_factor_) return;
            stage2_decimation_counter_ = 0;
            float filtered_audio = 0.0f;
            float unused_q = 0.0f;
            fir_decimate(stage2_taps_, stage2_i_history_, stage2_q_history_, stage2_write_index_, &filtered_audio, &unused_q);
            rf_level_tracker_ += (magnitude - rf_level_tracker_) * 0.01f;
            manual_squelch_level_ += (magnitude - manual_squelch_level_) * kManualSquelchLevelAlpha;
            stats_rf_accumulator_ += magnitude * magnitude;
            stats_sample_count_ += 1;
            process_audio_sample(filtered_audio);
            return;
        }
        float stage2_input_i = i;
        float stage2_input_q = q;
        update_carrier_tracking(i, q);
        const float tracking_rate_hz = static_cast<float>(stage2_sample_rate_hz_);

        stage1_carrier_phase_rad_ += effective_stage1_carrier_freq_rad_per_sample_ + fine_carrier_freq_rad_per_sample_;
        if (stage1_carrier_phase_rad_ > kPi) {
            stage1_carrier_phase_rad_ -= 2.0f * kPi;
        } else if (stage1_carrier_phase_rad_ < -kPi) {
            stage1_carrier_phase_rad_ += 2.0f * kPi;
        }

        const float cos_phase = std::cos(stage1_carrier_phase_rad_);
        const float sin_phase = std::sin(stage1_carrier_phase_rad_);
        stage2_input_i = i * cos_phase + q * sin_phase;
        stage2_input_q = q * cos_phase - i * sin_phase;

        push_iq_stage2(stage2_input_i, stage2_input_q);
        stage2_decimation_counter_ += 1;
        if (stage2_decimation_counter_ != stage2_decimation_factor_) {
            return;
        }
        stage2_decimation_counter_ = 0;

        float filtered_i = 0.0f;
        float filtered_q = 0.0f;
        fir_decimate(stage2_taps_, stage2_i_history_, stage2_q_history_, stage2_write_index_, &filtered_i, &filtered_q);
        if (!narrowband_taps_.empty()) {
            narrowband_i_history_[narrowband_write_index_] = filtered_i;
            narrowband_q_history_[narrowband_write_index_] = filtered_q;
            narrowband_write_index_ = (narrowband_write_index_ + 1) % narrowband_taps_.size();
            fir_decimate(
                narrowband_taps_,
                narrowband_i_history_,
                narrowband_q_history_,
                narrowband_write_index_,
                &filtered_i,
                &filtered_q);
        }
        if (config_.demod_mode != 3 && config_.sideband_mode != 0) {
            apply_sideband_focus(&filtered_i, &filtered_q);
        }

        const bool fine_tracking_enabled = config_.demod_mode == 2;
        if (fine_tracking_enabled) {
            const float fine_max_rad = (tracking_rate_hz > 0.0f)
                ? (2.0f * kPi * kFineCarrierTrackMaxHz) / tracking_rate_hz
                : 0.0f;
            if (has_fine_prev_) {
                const float dot = fine_prev_i_ * filtered_i + fine_prev_q_ * filtered_q;
                const float cross = fine_prev_i_ * filtered_q - fine_prev_q_ * filtered_i;
                const float prev_magnitude = std::sqrt(fine_prev_i_ * fine_prev_i_ + fine_prev_q_ * fine_prev_q_);
                const float magnitude = std::sqrt(filtered_i * filtered_i + filtered_q * filtered_q);
                const float magnitude_product = std::max(prev_magnitude * magnitude, 1e-6f);
                const float disc_i = dot / magnitude_product;
                const float disc_q = cross / magnitude_product;
                fine_track_disc_i_lp_ += (disc_i - fine_track_disc_i_lp_) * kFineCarrierTrackDiscriminatorAlpha;
                fine_track_disc_q_lp_ += (disc_q - fine_track_disc_q_lp_) * kFineCarrierTrackDiscriminatorAlpha;
                const float residual_freq = std::atan2(fine_track_disc_q_lp_, fine_track_disc_i_lp_);
                const float target_freq = std::clamp(residual_freq, -fine_max_rad, fine_max_rad);
                const float alpha = magnitude >= kCarrierTrackMinMagnitude
                    ? kFineCarrierTrackAttackAlpha
                    : kFineCarrierTrackReleaseAlpha;
                fine_carrier_freq_rad_per_sample_ += (target_freq - fine_carrier_freq_rad_per_sample_) * alpha;
            } else {
                fine_track_disc_i_lp_ += (1.0f - fine_track_disc_i_lp_) * kFineCarrierTrackReleaseAlpha;
                fine_track_disc_q_lp_ += (0.0f - fine_track_disc_q_lp_) * kFineCarrierTrackReleaseAlpha;
                fine_carrier_freq_rad_per_sample_ += (0.0f - fine_carrier_freq_rad_per_sample_) * kFineCarrierTrackReleaseAlpha;
            }
            fine_prev_i_ = filtered_i;
            fine_prev_q_ = filtered_q;
            has_fine_prev_ = true;
        } else {
            fine_track_disc_i_lp_ += (1.0f - fine_track_disc_i_lp_) * kFineCarrierTrackReleaseAlpha;
            fine_track_disc_q_lp_ += (0.0f - fine_track_disc_q_lp_) * kFineCarrierTrackReleaseAlpha;
            fine_carrier_freq_rad_per_sample_ += (0.0f - fine_carrier_freq_rad_per_sample_) * kFineCarrierTrackReleaseAlpha;
            fine_prev_i_ = 0.0f;
            fine_prev_q_ = 0.0f;
            has_fine_prev_ = false;
        }

        const float magnitude = std::sqrt(filtered_i * filtered_i + filtered_q * filtered_q);
        rf_level_tracker_ += (magnitude - rf_level_tracker_) * 0.01f;
        manual_squelch_level_ +=
            (magnitude - manual_squelch_level_) * kManualSquelchLevelAlpha;

        const float demodulated =
            config_.demod_mode == 4
                ? demodulate_nfm(filtered_i, filtered_q)
                : (config_.demod_mode == 3
                ? demodulate_ssb(filtered_i, filtered_q)
                : (config_.demod_mode == 2
                    ? demodulate_synchronous(filtered_i, filtered_q, magnitude)
                    : demodulate_envelope(filtered_i, filtered_q, magnitude)));
        stats_rf_accumulator_ += magnitude * magnitude;
        stats_sample_count_ += 1;
        process_audio_sample(demodulated);
    };

    auto process_iq_pair = [&](uint8_t raw_i_u8, uint8_t raw_q_u8) {
        stats_iq_count_ += 1;
        if (raw_i_u8 <= 2 || raw_i_u8 >= 253 || raw_q_u8 <= 2 || raw_q_u8 >= 253) {
            stats_iq_clip_count_ += 1;
        }
        const float raw_i = static_cast<float>(static_cast<int>(raw_i_u8) - 128);
        const float raw_q = static_cast<float>(static_cast<int>(raw_q_u8) - 128);
        i_dc_ += (raw_i - i_dc_) * config_.dc_alpha;
        q_dc_ += (raw_q - q_dc_) * config_.dc_alpha;
        float i = raw_i - i_dc_;
        float q = raw_q - q_dc_;
        apply_noise_blanker(&i, &q, std::sqrt(i * i + q * q));

        push_iq_stage1(i, q);
        stage1_decimation_counter_ += 1;
        if (stage1_decimation_counter_ != stage1_decimation_factor_) {
            return;
        }
        stage1_decimation_counter_ = 0;

        float stage1_i = 0.0f;
        float stage1_q = 0.0f;
        fir_decimate(stage1_taps_, stage1_i_history_, stage1_q_history_, stage1_write_index_, &stage1_i, &stage1_q);
        process_stage2(stage1_i, stage1_q);
    };

    if (iq_bytes != nullptr) {
        std::size_t index = 0;
        if (has_pending_iq_byte_ && iq_byte_count > 0) {
            process_iq_pair(pending_iq_byte_, static_cast<uint8_t>(iq_bytes[0]));
            has_pending_iq_byte_ = false;
            index = 1;
        }

        for (; index + 1 < iq_byte_count; index += 2) {
            process_iq_pair(static_cast<uint8_t>(iq_bytes[index]), static_cast<uint8_t>(iq_bytes[index + 1]));
        }

        if (index < iq_byte_count) {
            pending_iq_byte_ = static_cast<uint8_t>(iq_bytes[index]);
            has_pending_iq_byte_ = true;
        }
    }

    if (stats_sample_count_ > 0 && stats_audio_count_ > 0) {
        const float rf_rms = std::sqrt(stats_rf_accumulator_ / static_cast<float>(stats_sample_count_));
        const float audio_rms = std::sqrt(stats_audio_accumulator_ / static_cast<float>(stats_audio_count_));
        rf_level_percent_ = std::clamp(static_cast<int>(std::lround((rf_rms / kRfLevelReference) * 100.0f)), 0, 100);
        audio_level_percent_ = std::clamp(static_cast<int>(std::lround((audio_rms / kAudioLevelReference) * 100.0f)), 0, 100);
        clip_percent_ = std::clamp(static_cast<int>(std::lround((static_cast<float>(stats_clip_count_) * 100.0f) / static_cast<float>(stats_audio_count_))), 0, 100);
        if (stats_iq_count_ > 0) {
            iq_clip_percent_ = std::clamp(
                static_cast<int>(std::lround(
                    (static_cast<float>(stats_iq_clip_count_) * 100.0f) /
                    static_cast<float>(stats_iq_count_))),
                0,
                100);
        }
        stats_rf_accumulator_ = 0.0f;
        stats_audio_accumulator_ = 0.0f;
        stats_sample_count_ = 0;
        stats_audio_count_ = 0;
        stats_clip_count_ = 0;
        stats_iq_count_ = 0;
        stats_iq_clip_count_ = 0;
    }

    result.pcm_samples_written = static_cast<int>(pop_audio_samples(pcm_out, pcm_capacity));
    result.rf_level_percent = rf_level_percent_;
    result.audio_level_percent = audio_level_percent_;
    result.clip_percent = clip_percent_;
    result.iq_clip_percent = iq_clip_percent_;
    result.estimated_carrier_offset_hz = static_cast<int>(std::lround(
        ((effective_stage1_carrier_freq_rad_per_sample_ + fine_carrier_freq_rad_per_sample_) * static_cast<float>(stage2_sample_rate_hz_)) / (2.0f * kPi)));
    const bool auto_squelch = config_.squelch_mode == kSquelchModeAuto;
    const bool manual_squelch_fully_open = !auto_squelch && config_.squelch_level <= 0;
    const float current_squelch_noise_floor_percent_value = auto_squelch
        ? current_squelch_noise_floor_percent()
        : 0.0f;
    const float current_open_threshold = manual_squelch_fully_open
        ? 0.0f
        : static_cast<float>(config_.squelch_level);
    const float effective_auto_open_threshold = retune_guard_samples_remaining_ > 0
        ? std::min(100.0f, current_open_threshold + kRetuneGuardOpenBonus)
        : current_open_threshold;
    const float current_effective_threshold_percent_value = auto_squelch
        ? effective_auto_open_threshold
        : current_open_threshold;
    const float exported_squelch_percent = auto_squelch
        ? block_squelch_percent_peak
        : static_cast<float>(manual_qualified_percent_);
    result.squelch_percent = std::clamp(static_cast<int>(std::lround(exported_squelch_percent)), 0, 100);
    result.squelch_noise_floor_percent = std::clamp(static_cast<int>(std::lround(current_squelch_noise_floor_percent_value)), 0, 100);
    result.squelch_threshold_percent = std::clamp(static_cast<int>(std::lround(current_open_threshold)), 0, 100);
    result.squelch_effective_threshold_percent = std::clamp(
        static_cast<int>(std::lround(current_effective_threshold_percent_value)),
        0,
        100);
    result.squelch_percent_uses_peak_meter = auto_squelch;
    result.squelch_open = squelch_open_;
    return result;
}
