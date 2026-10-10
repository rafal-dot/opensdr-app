#pragma once

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

#include "carrier_coherence.h"
#include "am_carrier_tracker.h"
#include "am_speech_filter.h"
#include "squelch_meter.h"
#include "nfm_pipeline.h"
#include "ssb_pipeline.h"
#include "wfm_pipeline.h"

// Low-CPU demodulation pipeline shared by the OpenSDR Radio Host service and
// clients using the native bridge. The pipeline is intentionally stateful: it
// keeps FIR histories, carrier tracking, squelch smoothing, AGC, and audio FIFO
// state between USB reads to avoid per-block reinitialization artifacts.
struct AmPipelineConfig {
    // Keep this structure synchronized with NativeAmConfig in the Kotlin bridge.
    // Numeric fields use Hz, percent, or single-pole filter alpha values.
    int input_sample_rate_hz = 240000;
    int output_sample_rate_hz = 48000;
    int channel_bandwidth_hz = 12000;
    int squelch_mode = 0;
    int squelch_level = 0;
    bool hard_squelch_mute_enabled = true;
    int audio_boost_percent = 100;
    bool voice_focus_enabled = true;
    bool post_leveler_enabled = true;
    bool noise_blanker_enabled = true;
    int denoise_mode = 0;
    int demod_mode = 1;
    int notch_mode = 0;
    int sideband_mode = 0;
    bool advanced_sync_enabled = false;
    bool quality_squelch_enabled = false;
    int speech_enhancer_mode = 0;
    bool am_speech_filter_enabled = true;
    // Reversible profile bypass; legacy AM and every FM/SSB path remain available.
    bool simple_am_enabled = false;
    AmCarrierTargets carrier_targets;
    float dc_alpha = 0.0015f;
    int stage1_fir_taps = 81;
    int stage2_fir_taps = 129;
    float channel_guard_hz = 900.0f;
    float channel_cutoff_ratio = 0.40f;
    float voice_audio_cutoff_hz = 2400.0f;
    float natural_audio_cutoff_hz = 2800.0f;
    float voice_audio_ratio = 0.30f;
    float natural_audio_ratio = 0.36f;
    float audio_high_pass_hz = 300.0f;
    float squelch_attack_alpha = 0.05f;
    float squelch_release_alpha = 0.002f;
    float squelch_hysteresis_percent = 6.0f;
    int squelch_open_debounce_ms = 120;
    int squelch_close_hang_ms = 200;
    float output_gain = 1.0f;
    float target_audio_level = 7000.0f;
    float soft_clip_threshold = 30000.0f;
    float soft_clip_drive = 8000.0f;
    float post_leveler_target = 9000.0f;
    float post_leveler_max_gain = 1.8f;
    float post_leveler_attack_alpha = 0.010f;
    float post_leveler_release_alpha = 0.001f;
};

struct AmPipelineProcessResult {
    // Metrics are intentionally decision-compatible with the current squelch
    // path, so the app's meters explain what the native DSP is actually doing.
    int pcm_samples_written = 0;
    int rf_level_percent = 0;
    int audio_level_percent = 0;
    int clip_percent = 0;
    int iq_clip_percent = 0;
    int estimated_carrier_offset_hz = 0;
    int squelch_percent = 0;
    int squelch_noise_floor_percent = 0;
    int squelch_threshold_percent = 0;
    int squelch_effective_threshold_percent = 0;
    bool squelch_percent_uses_peak_meter = true;
    bool squelch_open = true;
};

class AmPipeline {
public:
    explicit AmPipeline(const AmPipelineConfig& config);

    bool update_config(const AmPipelineConfig& config);
    void reset();
    void note_retune(AmCarrierTargets targets = {});
    AmPipelineProcessResult process(const int8_t* iq_bytes, std::size_t iq_byte_count, int16_t* pcm_out, std::size_t pcm_capacity);

private:
    void reset_locked();
    void redesign_filters();
    void design_fir(std::vector<float>* taps, int sample_rate_hz, float cutoff_hz, int tap_count) const;
    float low_pass_alpha(float cutoff_hz, float min_cutoff_hz, float max_cutoff_hz, float max_alpha = 0.45f) const;
    float high_pass_alpha(float cutoff_hz) const;
    float current_auto_squelch_percent() const;
    float current_manual_squelch_percent() const;
    float current_meter_percent() const;
    float current_squelch_noise_floor_percent() const;
    void push_iq_stage1(float i, float q);
    void push_iq_stage2(float i, float q);
    void fir_decimate(const std::vector<float>& taps, const std::vector<float>& history_i, const std::vector<float>& history_q, std::size_t write_index, float* out_i, float* out_q) const;
    float dc_block(float sample);
    float audio_high_pass(float sample, float cutoff_hz);
    float audio_low_pass(float sample, float alpha);
    float demodulate_envelope(float filtered_i, float filtered_q, float magnitude);
    float demodulate_synchronous(float filtered_i, float filtered_q, float magnitude);
    float demodulate_ssb(float filtered_i, float filtered_q);
    float demodulate_nfm(float filtered_i, float filtered_q);
    float demodulate_wfm(float filtered_i, float filtered_q);
    void update_carrier_tracking(float i, float q);
    void apply_sideband_focus(float* i, float* q);
    float apply_auto_notch(float sample);
    float apply_speech_enhancer(float sample, bool carrier_present);
    void apply_noise_blanker(float* i, float* q, float magnitude);
    float apply_denoise(float sample, bool carrier_present);
    void push_audio_sample(float sample);
    std::size_t pop_audio_samples(int16_t* pcm_out, std::size_t pcm_capacity);

    // One lock per IQ block, never per sample. Configuration and retunes can
    // only touch FIR histories and tracking state between complete blocks.
    std::mutex mutex_;
    AmPipelineConfig config_;

    // Decimation topology. For the default 240 ksps to 48 ksps path the first
    // FIR stage removes out-of-channel energy before the final audio-rate stage.
    int decimation_factor_ = 20;
    int stage1_decimation_factor_ = 4;
    int stage2_decimation_factor_ = 5;
    int stage1_sample_rate_hz_ = 240000;
    int stage2_sample_rate_hz_ = 48000;

    // FIR taps and circular histories for complex IQ filtering.
    std::vector<float> stage1_taps_;
    std::vector<float> stage1_i_history_;
    std::vector<float> stage1_q_history_;
    std::size_t stage1_write_index_ = 0;

    std::vector<float> stage2_taps_;
    std::vector<float> stage2_i_history_;
    std::vector<float> stage2_q_history_;
    std::size_t stage2_write_index_ = 0;

    std::vector<float> narrowband_taps_;
    std::vector<float> narrowband_i_history_;
    std::vector<float> narrowband_q_history_;
    std::size_t narrowband_write_index_ = 0;

    SsbPipelineState ssb_pipeline_;
    AmSpeechFilter am_speech_filter_;

    // Output FIFO decouples bursty USB processing from the smaller PCM reads
    // made by AudioTrack and keeps short scheduler jitter out of audible audio.
    std::vector<int16_t> audio_fifo_;
    std::size_t audio_fifo_read_index_ = 0;
    std::size_t audio_fifo_write_index_ = 0;
    std::size_t audio_fifo_size_ = 0;
    uint8_t pending_iq_byte_ = 0;
    bool has_pending_iq_byte_ = false;

    // Carrier, DC, and RF level tracking. These values are intentionally slow
    // moving so envelope AM does not chase speech formants or noise bursts.
    float i_dc_ = 0.0f;
    float q_dc_ = 0.0f;
    float rf_level_tracker_ = 0.0f;
    float manual_squelch_level_ = 0.0f;
    SquelchMeter manual_squelch_meter_;
    int manual_meter_samples_remaining_ = 0;
    int64_t manual_meter_time_ms_ = 0;
    int manual_qualified_percent_ = 0;
    float carrier_level_ = 18.0f;
    float carrier_presence_level_ = 18.0f;
    float carrier_recovery_i_lp_ = 1.0f;
    float carrier_recovery_q_lp_ = 0.0f;
    float sync_lock_quality_ = 0.0f;
    float sync_phase_error_ = 0.0f;
    float stage1_carrier_phase_rad_ = 0.0f;
    float stage1_carrier_freq_rad_per_sample_ = 0.0f;
    float effective_stage1_carrier_freq_rad_per_sample_ = 0.0f;
    float envelope_carrier_reference_rad_per_sample_ = 0.0f;
    float stage1_prev_i_ = 0.0f;
    float stage1_prev_q_ = 0.0f;
    bool has_stage1_prev_ = false;
    bool has_envelope_carrier_reference_ = false;
    float carrier_track_disc_i_lp_ = 1.0f;
    float carrier_track_disc_q_lp_ = 0.0f;
    float carrier_track_target_rad_per_sample_ = 0.0f;
    float carrier_track_confidence_ = 0.0f;
    int carrier_track_update_counter_ = 0;
    CarrierCoherence weak_carrier_coherence_;
    AmCarrierTracker am_carrier_tracker_;
    float fine_carrier_freq_rad_per_sample_ = 0.0f;
    float fine_prev_i_ = 0.0f;
    float fine_prev_q_ = 0.0f;
    bool has_fine_prev_ = false;
    NfmPipelineState nfm_pipeline_;
    WfmPipelineState wfm_pipeline_;
    float fine_track_disc_i_lp_ = 1.0f;
    float fine_track_disc_q_lp_ = 0.0f;
    float raw_prev_i_ = 0.0f;
    float raw_prev_q_ = 0.0f;
    bool has_raw_prev_ = false;

    // Squelch and audio shaping state. Manual/custom squelch uses the same
    // smoothed value reported to the UI, while optional gates avoid hard cuts.
    float iq_impulse_envelope_ = 0.0f;
    float squelch_level_tracker_ = 0.0f;
    float squelch_noise_floor_ = 0.0f;
    float detector_prev_input_ = 0.0f;
    float detector_prev_output_ = 0.0f;
    float sideband_focus_phase_rad_ = 0.0f;
    float sideband_focus_i_lp_ = 0.0f;
    float sideband_focus_q_lp_ = 0.0f;
    float audio_hpf_prev_input_ = 0.0f;
    float audio_hpf_prev_output_ = 0.0f;
    float audio_lp1_ = 0.0f;
    float audio_lp2_ = 0.0f;
    float speech_low_lp_ = 0.0f;
    float speech_mid_lp_ = 0.0f;
    float speech_presence_lp_ = 0.0f;
    float speech_harsh_lp_ = 0.0f;
    float speech_last_enhanced_ = 0.0f;
    float speech_enhancer_gain_ = 1.0f;
    float speech_activity_env_ = 0.0f;
    float speech_consonant_env_ = 0.0f;
    float speech_limiter_env_ = 0.0f;
    float speech_limiter_gain_ = 1.0f;
    float prev_audio_sample_ = 0.0f;
    float notch_prev_input_1_ = 0.0f;
    float notch_prev_input_2_ = 0.0f;
    float notch_prev_output_1_ = 0.0f;
    float notch_prev_output_2_ = 0.0f;
    float notch_power_ = 0.0f;
    float notch_corr_ = 0.0f;
    float notch_mix_i_ = 1.0f;
    float notch_mix_q_ = 0.0f;
    float notch_frequency_hz_ = 0.0f;
    float notch_confidence_ = 0.0f;
    float audio_activity_envelope_ = 0.0f;
    float squelch_quality_envelope_ = 0.0f;
    float denoise_noise_floor_ = 0.0f;
    float denoise_signal_envelope_ = 0.0f;
    float denoise_gain_ = 1.0f;
    float denoise_peak_envelope_ = 0.0f;
    float denoise_limiter_gain_ = 1.0f;
    float modulation_envelope_ = 0.0f;
    float agc_envelope_ = 120.0f;
    float agc_gain_ = 1.0f;
    float gate_gain_ = 1.0f;
    float output_fade_gain_ = 1.0f;
    float carrier_pause_gate_gain_ = 1.0f;
    float clip_guard_gain_ = 1.0f;
    float post_level_envelope_ = 0.0f;
    float post_level_gain_ = 1.0f;
    int stage1_decimation_counter_ = 0;
    int stage2_decimation_counter_ = 0;

    // Per-status-window counters used for native metrics and debug traces.
    float stats_rf_accumulator_ = 0.0f;
    float stats_audio_accumulator_ = 0.0f;
    int stats_sample_count_ = 0;
    int stats_audio_count_ = 0;
    int stats_clip_count_ = 0;
    int stats_iq_count_ = 0;
    int stats_iq_clip_count_ = 0;

    int rf_level_percent_ = 0;
    int audio_level_percent_ = 0;
    int clip_percent_ = 0;
    int iq_clip_percent_ = 0;
    bool squelch_open_ = true;
    int squelch_open_debounce_samples_ = 0;
    int squelch_close_hang_samples_ = 0;
    int squelch_open_candidate_count_ = 0;
    int squelch_close_candidate_count_ = 0;
    int carrier_drop_grace_samples_ = 0;
    int carrier_drop_grace_samples_remaining_ = 0;
    int dead_carrier_close_samples_ = 0;
    int dead_carrier_candidate_count_ = 0;
    int retune_guard_samples_remaining_ = 0;
};
