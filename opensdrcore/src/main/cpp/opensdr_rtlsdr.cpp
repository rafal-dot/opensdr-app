#include <jni.h>
#ifdef OPENSDR_DIAGNOSTIC_CAPTURE
#include "diagnostic_capture.h"
#include <cstdio>
#include <cstring>
#endif
#include <rtl-sdr.h>

#include "am_pipeline.h"
#include "squelch_meter.h"
#include "usb_stream_read.h"
#include "spectrum_history.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <complex>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
    // Native backend state is process-global because the Android host exposes a
    // single RTL-SDR receiver service. Kotlin owns lifecycle policy; this file
    // owns the librtlsdr session, stream worker, native PCM ring, and JNI data
    // marshaling.
    std::mutex g_mutex;
    rtlsdr_dev_t* g_dev = nullptr;
    bool g_session_configured = false;
    int g_vendor_id = 0;
    int g_product_id = 0;
    uint32_t g_sample_rate_hz = 0;
    uint32_t g_frequency_hz = 0;
    int g_ppm_correction = 0;
    uint32_t g_tuner_bandwidth_hz = 0;
    bool g_bias_tee_enabled = false;
    bool g_offset_tuning_enabled = false;
    std::string g_last_error;
    std::mutex g_error_mutex;

    // Stream state is protected separately from device/session configuration so
    // PCM readers do not block on slow USB configuration calls.
    std::mutex g_stream_mutex;
    std::condition_variable g_stream_cv;
    std::thread g_stream_thread;
    bool g_stream_running = false;
    bool g_stream_stop_requested = false;
    AmPipeline* g_stream_pipeline = nullptr;
    std::vector<int16_t> g_stream_pcm_ring;
    std::size_t g_stream_pcm_read_index = 0;
    std::size_t g_stream_pcm_write_index = 0;
    std::size_t g_stream_pcm_size = 0;
    int g_stream_iq_read_size = 0;
    uint64_t g_stream_total_produced_samples = 0;
    uint64_t g_stream_total_consumed_samples = 0;
    uint64_t g_stream_total_usb_bytes = 0;
    uint64_t g_stream_total_usb_blocks = 0;
    uint64_t g_stream_total_usb_failures = 0;
    uint64_t g_stream_total_dropped_samples = 0;
    int g_stream_rf_level_percent = 0;
    int g_stream_audio_level_percent = 0;
    int g_stream_clip_percent = 0;
    int g_stream_iq_clip_percent = 0;
    int g_stream_estimated_carrier_offset_hz = 0;
    int g_stream_squelch_percent = 0;
    int g_stream_squelch_noise_floor_percent = 0;
    int g_stream_squelch_threshold_percent = 0;
    int g_stream_squelch_effective_threshold_percent = 0;
    bool g_stream_squelch_percent_uses_peak_meter = true;
    bool g_stream_squelch_open = true;
    std::vector<jint> g_stream_spectrum_bins;
    int g_stream_squelch_open_debounce_ms = 120;
    int g_stream_squelch_close_hang_ms = 700;

    SquelchMeter g_stream_squelch_meter;
    SpectrumHistory g_spectrum_history;
    int g_stream_output_rate_hz = 48000;

    constexpr std::size_t kSpectrumFftSize = 256;
    constexpr int kSpectrumBinCount = static_cast<int>(kSpectrumFftSize);
    constexpr int kSpectrumUpdateStride = 10;
    constexpr double kSpectrumMinDbfs = -100.0;
    constexpr double kSpectrumMaxDbfs = 0.0;

    void stop_stream_worker();

    int64_t stream_time_ms() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    void clear_stream_pcm_ring_locked() {
        g_stream_pcm_read_index = 0;
        g_stream_pcm_write_index = 0;
        g_stream_pcm_size = 0;
        g_stream_cv.notify_all();
    }

    void set_error(const std::string& value) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_error = value;
    }

    const char* tuner_name(enum rtlsdr_tuner tuner) {
        switch (tuner) {
            case RTLSDR_TUNER_E4000: return "E4000";
            case RTLSDR_TUNER_FC0012: return "FC0012";
            case RTLSDR_TUNER_FC0013: return "FC0013";
            case RTLSDR_TUNER_FC2580: return "FC2580";
            case RTLSDR_TUNER_R820T: return "R820T";
            case RTLSDR_TUNER_R828D: return "R828D";
            case RTLSDR_TUNER_UNKNOWN:
            default: return "UNKNOWN";
        }
    }

    bool apply_device_settings_locked(int ppm_correction, bool bias_tee_enabled, uint32_t tuner_bandwidth_hz, int gain_mode, int tuner_gain, bool agc_mode) {
        int result = rtlsdr_set_freq_correction(g_dev, ppm_correction);
        if (result < 0) {
            if (result == -2) {
                g_ppm_correction = 0;
            } else {
                set_error("Native settings failed: rtlsdr_set_freq_correction returned " + std::to_string(result));
                return false;
            }
        } else {
            g_ppm_correction = ppm_correction;
        }

        result = rtlsdr_set_bias_tee(g_dev, bias_tee_enabled ? 1 : 0);
        if (result < 0) {
            if (result == -2) {
                g_bias_tee_enabled = false;
            } else {
                set_error("Native settings failed: rtlsdr_set_bias_tee returned " + std::to_string(result));
                return false;
            }
        } else {
            g_bias_tee_enabled = bias_tee_enabled;
        }

        result = rtlsdr_set_tuner_bandwidth(g_dev, tuner_bandwidth_hz);
        if (result < 0) {
            if (result == -2) {
                g_tuner_bandwidth_hz = 0;
            } else {
                set_error("Native settings failed: rtlsdr_set_tuner_bandwidth returned " + std::to_string(result));
                return false;
            }
        } else {
            g_tuner_bandwidth_hz = tuner_bandwidth_hz;
        }

        result = rtlsdr_set_tuner_gain_mode(g_dev, gain_mode);
        if (result < 0) {
            set_error("Native settings failed: rtlsdr_set_tuner_gain_mode returned " + std::to_string(result));
            return false;
        }

        if (gain_mode != 0) {
            result = rtlsdr_set_tuner_gain(g_dev, tuner_gain);
            if (result < 0) {
                set_error("Native settings failed: rtlsdr_set_tuner_gain returned " + std::to_string(result));
                return false;
            }
        }

        result = rtlsdr_set_agc_mode(g_dev, agc_mode ? 1 : 0);
        if (result < 0) {
            set_error("Native settings failed: rtlsdr_set_agc_mode returned " + std::to_string(result));
            return false;
        }

        return true;
    }

    void close_locked() {
        stop_stream_worker();
        if (g_dev != nullptr) {
            rtlsdr_close(g_dev);
            g_dev = nullptr;
        }
        g_session_configured = false;
        g_sample_rate_hz = 0;
        g_frequency_hz = 0;
        g_ppm_correction = 0;
        g_tuner_bandwidth_hz = 0;
        g_bias_tee_enabled = false;
        g_offset_tuning_enabled = false;
    }

    AmPipelineConfig make_pipeline_config(
            jint input_sample_rate_hz,
            jint output_sample_rate_hz,
            jint channel_bandwidth_hz,
            jint squelch_mode,
            jint squelch_level,
            jboolean hard_squelch_mute_enabled,
            jint audio_boost_percent,
            jboolean voice_focus_enabled,
            jboolean post_leveler_enabled,
            jboolean noise_blanker_enabled,
            jint denoise_mode,
            jint demod_mode,
            jint notch_mode,
            jint sideband_mode,
            jboolean advanced_sync_enabled,
            jboolean quality_squelch_enabled,
            jint speech_enhancer_mode,
            jboolean am_speech_filter_enabled,
            jboolean simple_am_enabled,
            jfloat dc_alpha,
            jint stage1_fir_taps,
            jint stage2_fir_taps,
            jfloat channel_guard_hz,
            jfloat channel_cutoff_ratio,
            jfloat voice_audio_cutoff_hz,
            jfloat natural_audio_cutoff_hz,
            jfloat voice_audio_ratio,
            jfloat natural_audio_ratio,
            jfloat audio_high_pass_hz,
            jfloat squelch_attack_alpha,
            jfloat squelch_release_alpha,
            jfloat squelch_hysteresis_percent,
            jint squelch_open_debounce_ms,
            jint squelch_close_hang_ms,
            jfloat output_gain,
            jfloat target_audio_level,
            jfloat soft_clip_threshold,
            jfloat soft_clip_drive,
            jfloat post_leveler_target,
            jfloat post_leveler_max_gain,
            jfloat post_leveler_attack_alpha,
            jfloat post_leveler_release_alpha,
            jboolean carrier_targets_enabled,
            jfloat primary_carrier_offset_hz,
            jfloat alternate_carrier_offset_hz) {
        // Keep this field mapping synchronized with NativeAmConfig in Kotlin.
        // JNI does not protect us from semantically swapped numeric arguments.
        AmPipelineConfig config;
        config.input_sample_rate_hz = input_sample_rate_hz;
        config.output_sample_rate_hz = output_sample_rate_hz;
        config.channel_bandwidth_hz = channel_bandwidth_hz;
        config.squelch_mode = squelch_mode;
        config.squelch_level = squelch_level;
        config.hard_squelch_mute_enabled = hard_squelch_mute_enabled == JNI_TRUE;
        config.audio_boost_percent = audio_boost_percent;
        config.voice_focus_enabled = voice_focus_enabled == JNI_TRUE;
        config.post_leveler_enabled = post_leveler_enabled == JNI_TRUE;
        config.noise_blanker_enabled = noise_blanker_enabled == JNI_TRUE;
        config.denoise_mode = denoise_mode;
        config.demod_mode = demod_mode;
        config.notch_mode = notch_mode;
        config.sideband_mode = sideband_mode;
        config.advanced_sync_enabled = advanced_sync_enabled == JNI_TRUE;
        config.quality_squelch_enabled = quality_squelch_enabled == JNI_TRUE;
        config.speech_enhancer_mode = speech_enhancer_mode;
        config.am_speech_filter_enabled = am_speech_filter_enabled == JNI_TRUE;
        config.simple_am_enabled = simple_am_enabled == JNI_TRUE;
        config.dc_alpha = dc_alpha;
        config.stage1_fir_taps = stage1_fir_taps;
        config.stage2_fir_taps = stage2_fir_taps;
        config.channel_guard_hz = channel_guard_hz;
        config.channel_cutoff_ratio = channel_cutoff_ratio;
        config.voice_audio_cutoff_hz = voice_audio_cutoff_hz;
        config.natural_audio_cutoff_hz = natural_audio_cutoff_hz;
        config.voice_audio_ratio = voice_audio_ratio;
        config.natural_audio_ratio = natural_audio_ratio;
        config.audio_high_pass_hz = audio_high_pass_hz;
        config.squelch_attack_alpha = squelch_attack_alpha;
        config.squelch_release_alpha = squelch_release_alpha;
        config.squelch_hysteresis_percent = squelch_hysteresis_percent;
        config.squelch_open_debounce_ms = squelch_open_debounce_ms;
        config.squelch_close_hang_ms = squelch_close_hang_ms;
        config.output_gain = output_gain;
        config.target_audio_level = target_audio_level;
        config.soft_clip_threshold = soft_clip_threshold;
        config.soft_clip_drive = soft_clip_drive;
        config.post_leveler_target = post_leveler_target;
        config.post_leveler_max_gain = post_leveler_max_gain;
        config.post_leveler_attack_alpha = post_leveler_attack_alpha;
        config.post_leveler_release_alpha = post_leveler_release_alpha;
        config.carrier_targets = {carrier_targets_enabled == JNI_TRUE,
                                  primary_carrier_offset_hz, alternate_carrier_offset_hz};
        return config;
    }

    void reset_stream_state_locked() {
        g_stream_pipeline = nullptr;
        g_stream_pcm_ring.clear();
        g_stream_pcm_read_index = 0;
        g_stream_pcm_write_index = 0;
        g_stream_pcm_size = 0;
        g_stream_iq_read_size = 0;
        g_stream_total_produced_samples = 0;
        g_stream_total_consumed_samples = 0;
        g_stream_total_usb_bytes = 0;
        g_stream_total_usb_blocks = 0;
        g_stream_total_usb_failures = 0;
        g_stream_total_dropped_samples = 0;
        g_stream_rf_level_percent = 0;
        g_stream_audio_level_percent = 0;
        g_stream_clip_percent = 0;
        g_stream_iq_clip_percent = 0;
        g_stream_estimated_carrier_offset_hz = 0;
        g_stream_squelch_percent = 0;
        g_stream_squelch_meter.reset();
        g_stream_squelch_noise_floor_percent = 0;
        g_stream_squelch_threshold_percent = 0;
        g_stream_squelch_effective_threshold_percent = 0;
        g_stream_squelch_percent_uses_peak_meter = true;
        g_stream_squelch_open = true;
        g_stream_spectrum_bins.assign(kSpectrumBinCount, 0);
    }

    struct SpectrumFftPlan {
        std::array<float, kSpectrumFftSize> window{};
        std::array<std::complex<float>, kSpectrumFftSize / 2> twiddles{};
        float window_sum = 0.0f;

        SpectrumFftPlan() {
            constexpr float kPi = 3.14159265358979323846f;
            for (std::size_t index = 0; index < kSpectrumFftSize; ++index) {
                const float phase = (2.0f * kPi * static_cast<float>(index)) /
                    static_cast<float>(kSpectrumFftSize - 1);
                window[index] = 0.5f - 0.5f * std::cos(phase);
                window_sum += window[index];
            }
            for (std::size_t index = 0; index < twiddles.size(); ++index) {
                const float phase = (-2.0f * kPi * static_cast<float>(index)) /
                    static_cast<float>(kSpectrumFftSize);
                twiddles[index] = std::complex<float>(std::cos(phase), std::sin(phase));
            }
        }
    };

    const SpectrumFftPlan& spectrum_fft_plan() {
        static const SpectrumFftPlan plan;
        return plan;
    }

    void spectrum_fft_in_place(
            std::array<std::complex<float>, kSpectrumFftSize>& samples,
            const SpectrumFftPlan& plan) {
        static_assert((kSpectrumFftSize & (kSpectrumFftSize - 1)) == 0, "FFT size must be a power of two");

        for (std::size_t index = 1, reversed = 0; index < kSpectrumFftSize; ++index) {
            std::size_t bit = kSpectrumFftSize >> 1;
            while ((reversed & bit) != 0) {
                reversed ^= bit;
                bit >>= 1;
            }
            reversed ^= bit;
            if (index < reversed) {
                std::swap(samples[index], samples[reversed]);
            }
        }

        for (std::size_t length = 2; length <= kSpectrumFftSize; length <<= 1) {
            const std::size_t half_length = length >> 1;
            const std::size_t twiddle_step = kSpectrumFftSize / length;
            for (std::size_t block = 0; block < kSpectrumFftSize; block += length) {
                for (std::size_t offset = 0; offset < half_length; ++offset) {
                    const auto even = samples[block + offset];
                    const auto odd = samples[block + offset + half_length] *
                        plan.twiddles[offset * twiddle_step];
                    samples[block + offset] = even + odd;
                    samples[block + offset + half_length] = even - odd;
                }
            }
        }
    }

    bool compute_stream_spectrum(
            const uint8_t* iq_bytes,
            std::size_t iq_byte_count,
            std::array<std::complex<float>, kSpectrumFftSize>& fft_samples,
            std::array<jint, kSpectrumFftSize>& bins) {
        if (iq_bytes == nullptr || iq_byte_count / 2 < kSpectrumFftSize) {
            bins.fill(0);
            return false;
        }

        const auto& plan = spectrum_fft_plan();
        const std::size_t complex_sample_count = iq_byte_count / 2;
        const std::size_t sample_offset = (complex_sample_count - kSpectrumFftSize) / 2;
        for (std::size_t index = 0; index < kSpectrumFftSize; ++index) {
            const std::size_t byte_index = (sample_offset + index) * 2;
            const float i = (static_cast<float>(iq_bytes[byte_index]) - 127.5f) / 128.0f;
            const float q = (static_cast<float>(iq_bytes[byte_index + 1]) - 127.5f) / 128.0f;
            fft_samples[index] = std::complex<float>(i, q) * plan.window[index];
        }
        spectrum_fft_in_place(fft_samples, plan);

        // Keep a fixed dBFS reference so display code can apply stable manual scaling.
        const double db_span = kSpectrumMaxDbfs - kSpectrumMinDbfs;
        for (std::size_t display_bin = 0; display_bin < kSpectrumFftSize; ++display_bin) {
            const std::size_t fft_bin = (display_bin + (kSpectrumFftSize / 2)) % kSpectrumFftSize;
            const double magnitude = std::abs(fft_samples[fft_bin]) / plan.window_sum;
            const double dbfs = 20.0 * std::log10(std::max(magnitude, 1e-12));
            const double level = ((dbfs - kSpectrumMinDbfs) / db_span) * 100.0;
            bins[display_bin] = static_cast<jint>(
                std::lround(std::max(0.0, std::min(100.0, level)))
            );
        }

        return true;
    }

    void push_stream_pcm_locked(const int16_t* samples, std::size_t sample_count) {
        if (samples == nullptr || sample_count == 0 || g_stream_pcm_ring.empty()) {
            return;
        }
        for (std::size_t index = 0; index < sample_count; ++index) {
            if (g_stream_pcm_size == g_stream_pcm_ring.size()) {
                g_stream_pcm_read_index = (g_stream_pcm_read_index + 1) % g_stream_pcm_ring.size();
                g_stream_pcm_size -= 1;
                g_stream_total_dropped_samples += 1;
            }
            g_stream_pcm_ring[g_stream_pcm_write_index] = samples[index];
            g_stream_pcm_write_index = (g_stream_pcm_write_index + 1) % g_stream_pcm_ring.size();
            g_stream_pcm_size += 1;
        }
    }

    void stop_stream_worker() {
        g_spectrum_history.suspend();
        {
            std::lock_guard<std::mutex> lock(g_stream_mutex);
            g_stream_stop_requested = true;
            g_stream_cv.notify_all();
        }
        if (g_stream_thread.joinable()) {
            g_stream_thread.join();
        }
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        g_stream_running = false;
        g_stream_stop_requested = false;
        reset_stream_state_locked();
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeIsReady(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeVersionLabel(
        JNIEnv* env,
        jobject /*thiz*/) {
    return env->NewStringUTF("opensdr_rtlsdr librtlsdr bridge v10");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeLastError(
        JNIEnv* env,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeOpen(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint device_fd,
        jint vendor_id,
        jint product_id) {
    std::lock_guard<std::mutex> lock(g_mutex);
    close_locked();

    if (device_fd < 0) {
        set_error("Native open failed: invalid USB file descriptor");
        return JNI_FALSE;
    }

    rtlsdr_dev_t* dev = nullptr;
    const int result = rtlsdr_open_file_descriptor(&dev, device_fd);
    if (result < 0 || dev == nullptr) {
        set_error("Native open failed: rtlsdr_open_file_descriptor returned " + std::to_string(result));
        return JNI_FALSE;
    }

    g_dev = dev;
    g_vendor_id = vendor_id;
    g_product_id = product_id;
    const auto tuner = rtlsdr_get_tuner_type(g_dev);
    set_error(
        "Opened RTL-SDR " + std::to_string(vendor_id) + ":" + std::to_string(product_id) +
        " via librtlsdr, tuner=" + tuner_name(tuner)
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeConfigureSession(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong center_frequency_hz,
        jint sample_rate_hz,
        jint gain_mode,
        jint tuner_gain,
        jboolean agc_mode,
        jboolean offset_tuning,
        jint ppm_correction,
        jboolean bias_tee_enabled,
        jint tuner_bandwidth_hz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_dev == nullptr) {
        set_error("Native session config failed: transport is not open");
        return JNI_FALSE;
    }
    if (center_frequency_hz <= 0 || sample_rate_hz <= 0) {
        set_error("Native session config failed: invalid RF parameters");
        return JNI_FALSE;
    }
    int result = 0;

    const bool requested_offset_tuning = offset_tuning == JNI_TRUE;
    result = rtlsdr_set_offset_tuning(g_dev, requested_offset_tuning ? 1 : 0);
    if (result < 0) {
        if (result == -2) {
            g_offset_tuning_enabled = false;
        } else {
            set_error("Native session config failed: rtlsdr_set_offset_tuning returned " + std::to_string(result));
            return JNI_FALSE;
        }
    } else {
        g_offset_tuning_enabled = requested_offset_tuning;
    }

    result = rtlsdr_set_sample_rate(g_dev, static_cast<uint32_t>(sample_rate_hz));
    if (result < 0) {
        set_error("Native session config failed: rtlsdr_set_sample_rate returned " + std::to_string(result));
        return JNI_FALSE;
    }

    if (!apply_device_settings_locked(ppm_correction, bias_tee_enabled == JNI_TRUE, static_cast<uint32_t>(tuner_bandwidth_hz), gain_mode, tuner_gain, agc_mode == JNI_TRUE)) {
        return JNI_FALSE;
    }

    result = rtlsdr_set_center_freq(g_dev, static_cast<uint32_t>(center_frequency_hz));
    if (result < 0) {
        set_error("Native session config failed: rtlsdr_set_center_freq returned " + std::to_string(result));
        return JNI_FALSE;
    }
    result = rtlsdr_reset_buffer(g_dev);
    if (result < 0) {
        set_error("Native session config failed: rtlsdr_reset_buffer returned " + std::to_string(result));
        return JNI_FALSE;
    }

    g_session_configured = true;
    g_sample_rate_hz = static_cast<uint32_t>(sample_rate_hz);
    g_frequency_hz = static_cast<uint32_t>(center_frequency_hz);
    set_error(
        "Direct session configured at " + std::to_string(g_frequency_hz) +
        " Hz @ " + std::to_string(g_sample_rate_hz) +
        " sps, offset " + (g_offset_tuning_enabled ? std::string("on") : std::string("off")) +
        ", gain " + std::to_string(gain_mode != 0 ? tuner_gain : 0) +
        ", ppm " + std::to_string(g_ppm_correction) +
        ", bias " + (g_bias_tee_enabled ? std::string("on") : std::string("off")) +
        ", bw " + std::to_string(g_tuner_bandwidth_hz)
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeApplySettings(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint ppm_correction,
        jboolean bias_tee_enabled,
        jint tuner_bandwidth_hz,
        jint gain_mode,
        jint tuner_gain,
        jboolean agc_mode) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_dev == nullptr) {
        set_error("Native settings failed: device is not open");
        return JNI_FALSE;
    }

    if (!apply_device_settings_locked(ppm_correction, bias_tee_enabled == JNI_TRUE, static_cast<uint32_t>(tuner_bandwidth_hz), gain_mode, tuner_gain, agc_mode == JNI_TRUE)) {
        return JNI_FALSE;
    }

    if (g_session_configured) {
        const int reset_result = rtlsdr_reset_buffer(g_dev);
        if (reset_result < 0) {
            set_error("Native settings failed: rtlsdr_reset_buffer returned " + std::to_string(reset_result));
            return JNI_FALSE;
        }
    }

    set_error(
        "Native settings applied: ppm " + std::to_string(g_ppm_correction) +
        ", bias " + (g_bias_tee_enabled ? std::string("on") : std::string("off")) +
        ", bw " + std::to_string(g_tuner_bandwidth_hz) +
        ", gain " + std::to_string(gain_mode != 0 ? tuner_gain : 0)
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeClose(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    close_locked();
    set_error("Native device closed");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeTuneHz(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong frequency_hz,
        jboolean carrier_targets_enabled,
        jfloat primary_carrier_offset_hz,
        jfloat alternate_carrier_offset_hz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_dev == nullptr) {
        set_error("Native tune failed: device is not open");
        return JNI_FALSE;
    }
    if (!g_session_configured) {
        set_error("Native tune failed: direct session is not configured");
        return JNI_FALSE;
    }
    if (frequency_hz <= 0 || !std::isfinite(primary_carrier_offset_hz) ||
        !std::isfinite(alternate_carrier_offset_hz)) {
        set_error("Native tune failed: invalid frequency");
        return JNI_FALSE;
    }

    g_spectrum_history.suspend();
    const int result = rtlsdr_set_center_freq(g_dev, static_cast<uint32_t>(frequency_hz));
    if (result < 0) {
        g_spectrum_history.resume(g_frequency_hz, g_sample_rate_hz, stream_time_ms());
        set_error("Native tune failed: rtlsdr_set_center_freq returned " + std::to_string(result));
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> stream_lock(g_stream_mutex);
        if (g_stream_pipeline != nullptr) {
            g_stream_pipeline->note_retune({carrier_targets_enabled == JNI_TRUE,
                primary_carrier_offset_hz, alternate_carrier_offset_hz});
        }
        g_stream_pcm_read_index = 0;
        g_stream_pcm_write_index = 0;
        g_stream_pcm_size = 0;
        g_stream_rf_level_percent = 0;
        g_stream_audio_level_percent = 0;
        g_stream_clip_percent = 0;
        g_stream_iq_clip_percent = 0;
        g_stream_squelch_percent = 0;
        g_stream_squelch_meter.reset();
        g_stream_squelch_noise_floor_percent = 0;
        g_stream_squelch_threshold_percent = 0;
        g_stream_squelch_effective_threshold_percent = 0;
        g_stream_squelch_percent_uses_peak_meter = true;
        g_stream_squelch_open = false;
    }

    g_frequency_hz = static_cast<uint32_t>(frequency_hz);
    g_spectrum_history.resume(g_frequency_hz, g_sample_rate_hz, stream_time_ms());
    set_error("Native tune applied: " + std::to_string(g_frequency_hz) + " Hz");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeCreateAmPipeline(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint input_sample_rate_hz,
        jint output_sample_rate_hz,
        jint channel_bandwidth_hz,
        jint squelch_mode,
        jint squelch_level,
        jboolean hard_squelch_mute_enabled,
        jint audio_boost_percent,
        jboolean voice_focus_enabled,
        jboolean post_leveler_enabled,
        jboolean noise_blanker_enabled,
        jint denoise_mode,
        jint demod_mode,
        jint notch_mode,
        jint sideband_mode,
        jboolean advanced_sync_enabled,
        jboolean quality_squelch_enabled,
        jint speech_enhancer_mode,
        jboolean am_speech_filter_enabled,
        jboolean simple_am_enabled,
        jfloat dc_alpha,
        jint stage1_fir_taps,
        jint stage2_fir_taps,
        jfloat channel_guard_hz,
        jfloat channel_cutoff_ratio,
        jfloat voice_audio_cutoff_hz,
        jfloat natural_audio_cutoff_hz,
        jfloat voice_audio_ratio,
        jfloat natural_audio_ratio,
        jfloat audio_high_pass_hz,
        jfloat squelch_attack_alpha,
        jfloat squelch_release_alpha,
        jfloat squelch_hysteresis_percent,
        jint squelch_open_debounce_ms,
        jint squelch_close_hang_ms,
        jfloat output_gain,
        jfloat target_audio_level,
        jfloat soft_clip_threshold,
        jfloat soft_clip_drive,
        jfloat post_leveler_target,
        jfloat post_leveler_max_gain,
        jfloat post_leveler_attack_alpha,
        jfloat post_leveler_release_alpha,
        jboolean carrier_targets_enabled,
        jfloat primary_carrier_offset_hz,
        jfloat alternate_carrier_offset_hz) {
    const AmPipelineConfig config = make_pipeline_config(
        input_sample_rate_hz,
        output_sample_rate_hz,
        channel_bandwidth_hz,
        squelch_mode,
        squelch_level,
        hard_squelch_mute_enabled,
        audio_boost_percent,
        voice_focus_enabled,
        post_leveler_enabled,
        noise_blanker_enabled,
        denoise_mode,
        demod_mode,
        notch_mode,
        sideband_mode,
        advanced_sync_enabled,
        quality_squelch_enabled,
        speech_enhancer_mode,
        am_speech_filter_enabled,
        simple_am_enabled,
        dc_alpha,
        stage1_fir_taps,
        stage2_fir_taps,
        channel_guard_hz,
        channel_cutoff_ratio,
        voice_audio_cutoff_hz,
        natural_audio_cutoff_hz,
        voice_audio_ratio,
        natural_audio_ratio,
        audio_high_pass_hz,
        squelch_attack_alpha,
        squelch_release_alpha,
        squelch_hysteresis_percent,
        squelch_open_debounce_ms,
        squelch_close_hang_ms,
        output_gain,
        target_audio_level,
        soft_clip_threshold,
        soft_clip_drive,
        post_leveler_target,
        post_leveler_max_gain,
        post_leveler_attack_alpha,
        post_leveler_release_alpha,
        carrier_targets_enabled,
        primary_carrier_offset_hz,
        alternate_carrier_offset_hz);
    auto pipeline = std::make_unique<AmPipeline>(config);
    if (!pipeline->update_config(config)) {
        set_error("Native AM pipeline creation failed: invalid config");
        return 0;
    }
    pipeline->reset();
    {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        g_stream_squelch_open_debounce_ms = config.squelch_open_debounce_ms;
        g_stream_output_rate_hz = config.output_sample_rate_hz;
        g_stream_squelch_close_hang_ms = config.squelch_close_hang_ms;
    }
    set_error("Native AM pipeline created");
    return reinterpret_cast<jlong>(pipeline.release());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeUpdateAmPipeline(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint input_sample_rate_hz,
        jint output_sample_rate_hz,
        jint channel_bandwidth_hz,
        jint squelch_mode,
        jint squelch_level,
        jboolean hard_squelch_mute_enabled,
        jint audio_boost_percent,
        jboolean voice_focus_enabled,
        jboolean post_leveler_enabled,
        jboolean noise_blanker_enabled,
        jint denoise_mode,
        jint demod_mode,
        jint notch_mode,
        jint sideband_mode,
        jboolean advanced_sync_enabled,
        jboolean quality_squelch_enabled,
        jint speech_enhancer_mode,
        jboolean am_speech_filter_enabled,
        jboolean simple_am_enabled,
        jfloat dc_alpha,
        jint stage1_fir_taps,
        jint stage2_fir_taps,
        jfloat channel_guard_hz,
        jfloat channel_cutoff_ratio,
        jfloat voice_audio_cutoff_hz,
        jfloat natural_audio_cutoff_hz,
        jfloat voice_audio_ratio,
        jfloat natural_audio_ratio,
        jfloat audio_high_pass_hz,
        jfloat squelch_attack_alpha,
        jfloat squelch_release_alpha,
        jfloat squelch_hysteresis_percent,
        jint squelch_open_debounce_ms,
        jint squelch_close_hang_ms,
        jfloat output_gain,
        jfloat target_audio_level,
        jfloat soft_clip_threshold,
        jfloat soft_clip_drive,
        jfloat post_leveler_target,
        jfloat post_leveler_max_gain,
        jfloat post_leveler_attack_alpha,
        jfloat post_leveler_release_alpha,
        jboolean carrier_targets_enabled,
        jfloat primary_carrier_offset_hz,
        jfloat alternate_carrier_offset_hz) {
    auto* pipeline = reinterpret_cast<AmPipeline*>(handle);
    if (pipeline == nullptr) {
        set_error("Native AM pipeline update failed: invalid handle");
        return JNI_FALSE;
    }
    const AmPipelineConfig config = make_pipeline_config(
            input_sample_rate_hz,
            output_sample_rate_hz,
            channel_bandwidth_hz,
            squelch_mode,
            squelch_level,
            hard_squelch_mute_enabled,
            audio_boost_percent,
            voice_focus_enabled,
            post_leveler_enabled,
            noise_blanker_enabled,
            denoise_mode,
            demod_mode,
            notch_mode,
            sideband_mode,
            advanced_sync_enabled,
            quality_squelch_enabled,
            speech_enhancer_mode,
            am_speech_filter_enabled,
            simple_am_enabled,
            dc_alpha,
            stage1_fir_taps,
            stage2_fir_taps,
            channel_guard_hz,
            channel_cutoff_ratio,
            voice_audio_cutoff_hz,
            natural_audio_cutoff_hz,
            voice_audio_ratio,
            natural_audio_ratio,
            audio_high_pass_hz,
            squelch_attack_alpha,
            squelch_release_alpha,
            squelch_hysteresis_percent,
            squelch_open_debounce_ms,
            squelch_close_hang_ms,
            output_gain,
            target_audio_level,
            soft_clip_threshold,
            soft_clip_drive,
            post_leveler_target,
            post_leveler_max_gain,
            post_leveler_attack_alpha,
            post_leveler_release_alpha,
            carrier_targets_enabled,
            primary_carrier_offset_hz,
            alternate_carrier_offset_hz);
    const bool ok = pipeline->update_config(config);
    if (ok) {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        g_stream_squelch_open_debounce_ms = config.squelch_open_debounce_ms;
        g_stream_output_rate_hz = config.output_sample_rate_hz;
        g_stream_squelch_close_hang_ms = config.squelch_close_hang_ms;
        g_stream_squelch_meter.configure(config.squelch_open_debounce_ms, config.squelch_close_hang_ms);
    }
    set_error(ok ? "Native AM pipeline updated" : "Native AM pipeline update failed: invalid config");
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeResetAmPipeline(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle) {
    auto* pipeline = reinterpret_cast<AmPipeline*>(handle);
    if (pipeline == nullptr) {
        set_error("Native AM pipeline reset failed: invalid handle");
        return JNI_FALSE;
    }
    pipeline->reset();
    set_error("Native AM pipeline reset");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeStartAmStream(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint iq_read_size,
        jint pcm_ring_capacity) {
    std::lock_guard<std::mutex> device_lock(g_mutex);
    auto* pipeline = reinterpret_cast<AmPipeline*>(handle);
    if (pipeline == nullptr || iq_read_size <= 0 || pcm_ring_capacity <= 0) {
        set_error("Native AM stream start failed: invalid arguments");
        return JNI_FALSE;
    }
    {
        if (g_dev == nullptr || !g_session_configured) {
            set_error("Native AM stream start failed: session is not ready");
            return JNI_FALSE;
        }
    }

    stop_stream_worker();

    {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        reset_stream_state_locked();
        g_stream_squelch_meter.configure(g_stream_squelch_open_debounce_ms, g_stream_squelch_close_hang_ms);
        g_stream_pipeline = pipeline;
        g_stream_iq_read_size = iq_read_size;
        g_stream_pcm_ring.assign(static_cast<std::size_t>(pcm_ring_capacity), 0);
        g_stream_running = true;
        g_stream_stop_requested = false;
    }

    g_spectrum_history.resume(g_frequency_hz, g_sample_rate_hz, stream_time_ms());
    g_stream_thread = std::thread([pipeline]() {
        std::vector<uint8_t> iq_buffer(static_cast<std::size_t>(g_stream_iq_read_size));
        std::vector<int16_t> pcm_buffer(static_cast<std::size_t>(g_stream_iq_read_size));
        std::array<std::complex<float>, kSpectrumFftSize> spectrum_fft_samples{};
        std::array<jint, kSpectrumFftSize> latest_spectrum_bins{};
        int spectrum_stride_counter = 0;
        UsbStreamReadPolicy read_policy;
#ifdef OPENSDR_DIAGNOSTIC_CAPTURE
        uint64_t next_capture_metrics_ns = 0;
#endif

        while (true) {
            {
                std::lock_guard<std::mutex> lock(g_stream_mutex);
                if (g_stream_stop_requested) {
                    break;
                }
            }

            int bytes_read = 0;
            const auto spectrum_context = g_spectrum_history.context();
            const int read_result = rtlsdr_read_sync(g_dev, iq_buffer.data(), g_stream_iq_read_size, &bytes_read);
            const auto spectrum_time_ms = stream_time_ms();
            const auto action = read_policy.received(read_result, bytes_read);
            if (action != UsbReadAction::process) {
#ifdef OPENSDR_DIAGNOSTIC_CAPTURE
                const char* event = "{\"type\":\"usb_read_failure\"}";
                diagnostic_capture().offer(DiagnosticCapture::event, event, std::strlen(event), diagnostic_time_ns());
#endif
                std::unique_lock<std::mutex> lock(g_stream_mutex);
                if (g_stream_stop_requested) {
                    break;
                }
                g_stream_total_usb_failures += 1;
                if (action == UsbReadAction::stop) {
                    set_error("USB stream stopped after failed reads: " + std::to_string(read_result));
                    break;
                }
                // Back off even when an error returns immediately; wake early
                // for Stop instead of spinning on a failed endpoint.
                g_stream_cv.wait_for(lock, std::chrono::milliseconds(10), [] { return g_stream_stop_requested; });
                continue;
            }

#ifdef OPENSDR_DIAGNOSTIC_CAPTURE
            diagnostic_capture().offer(DiagnosticCapture::iq, iq_buffer.data(), bytes_read, diagnostic_time_ns());
#endif
            const auto result = pipeline->process(
                reinterpret_cast<const int8_t*>(iq_buffer.data()),
                static_cast<std::size_t>(bytes_read),
                pcm_buffer.data(),
                pcm_buffer.size()
            );
#ifdef OPENSDR_DIAGNOSTIC_CAPTURE
            diagnostic_capture().produced(result.pcm_samples_written);
            const auto capture_now = diagnostic_time_ns();
            if (diagnostic_capture().status() == DiagnosticCapture::active && capture_now >= next_capture_metrics_ns) {
                char metrics[384];
                const int count = std::snprintf(metrics, sizeof(metrics),
                    "{\"type\":\"dsp\",\"carrier_offset_hz\":%d,\"rf_percent\":%d,\"audio_percent\":%d,"
                    "\"iq_clip_percent\":%d,\"clip_percent\":%d,\"squelch_percent\":%d,\"squelch_open\":%s}",
                    result.estimated_carrier_offset_hz, result.rf_level_percent, result.audio_level_percent,
                    result.iq_clip_percent, result.clip_percent, result.squelch_percent, result.squelch_open ? "true" : "false");
                if (count > 0 && count < static_cast<int>(sizeof(metrics)))
                    diagnostic_capture().offer(DiagnosticCapture::event, metrics, count, capture_now);
                next_capture_metrics_ns = capture_now + 100000000;
            }
#endif
            bool spectrum_updated = false;
            spectrum_stride_counter += 1;
            if (spectrum_stride_counter >= kSpectrumUpdateStride) {
                spectrum_stride_counter = 0;
                spectrum_updated = compute_stream_spectrum(
                    iq_buffer.data(),
                    static_cast<std::size_t>(bytes_read),
                    spectrum_fft_samples,
                    latest_spectrum_bins
                );
            }

            std::lock_guard<std::mutex> lock(g_stream_mutex);
            g_stream_total_usb_bytes += static_cast<uint64_t>(bytes_read);
            g_stream_total_usb_blocks += 1;
            g_stream_rf_level_percent = result.rf_level_percent;
            g_stream_audio_level_percent = result.audio_level_percent;
            g_stream_clip_percent = result.clip_percent;
            g_stream_iq_clip_percent = result.iq_clip_percent;
            g_stream_estimated_carrier_offset_hz = result.estimated_carrier_offset_hz;
            g_stream_squelch_percent = result.squelch_percent;
            if (result.squelch_percent_uses_peak_meter) {
                if (!g_stream_squelch_percent_uses_peak_meter) g_stream_squelch_meter.reset();
                g_stream_squelch_meter.observe(result.squelch_percent, stream_time_ms());
            }
            g_stream_squelch_noise_floor_percent = result.squelch_noise_floor_percent;
            g_stream_squelch_threshold_percent = result.squelch_threshold_percent;
            g_stream_squelch_effective_threshold_percent = result.squelch_effective_threshold_percent;
            g_stream_squelch_percent_uses_peak_meter = result.squelch_percent_uses_peak_meter;
            g_stream_squelch_open = result.squelch_open;
            if (spectrum_updated) {
                std::copy(latest_spectrum_bins.begin(), latest_spectrum_bins.end(), g_stream_spectrum_bins.begin());
                const auto delay_ms = static_cast<uint32_t>(std::min<uint64_t>(1000,
                    g_stream_pcm_size * 1000ULL / std::max(1, g_stream_output_rate_hz)));
                g_spectrum_history.append(spectrum_context, spectrum_time_ms, delay_ms, latest_spectrum_bins);
            }
            if (result.pcm_samples_written > 0) {
                push_stream_pcm_locked(pcm_buffer.data(), static_cast<std::size_t>(result.pcm_samples_written));
                g_stream_total_produced_samples += static_cast<uint64_t>(result.pcm_samples_written);
                g_stream_cv.notify_all();
            }
        }

        std::lock_guard<std::mutex> lock(g_stream_mutex);
        g_stream_running = false;
#ifdef OPENSDR_DIAGNOSTIC_CAPTURE
        const char* event = "{\"type\":\"stream_stopped\"}";
        diagnostic_capture().offer(DiagnosticCapture::event, event, std::strlen(event), diagnostic_time_ns());
        diagnostic_capture().stop();
#endif
        g_stream_cv.notify_all();
    });

    set_error("Native AM stream started");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeStopAmStream(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> device_lock(g_mutex);
    stop_stream_worker();
    set_error("Native AM stream stopped");
}

extern "C"
JNIEXPORT void JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeClearAmStreamBuffer(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_stream_mutex);
    clear_stream_pcm_ring_locked();
    set_error("Native AM stream buffer cleared");
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeReadAmStreamPcm(
        JNIEnv* env,
        jobject /*thiz*/,
        jshortArray pcm_out,
        jint requested_samples,
        jint timeout_ms) {
    if (pcm_out == nullptr || requested_samples <= 0) {
        return 0;
    }

    const jsize pcm_length = env->GetArrayLength(pcm_out);
    const std::size_t target_samples = std::min<std::size_t>(static_cast<std::size_t>(requested_samples), static_cast<std::size_t>(pcm_length));
    if (target_samples == 0) {
        return 0;
    }

    jboolean pcm_is_copy = JNI_FALSE;
    auto* pcm_bytes = env->GetShortArrayElements(pcm_out, &pcm_is_copy);
    if (pcm_bytes == nullptr) {
        return 0;
    }

    std::unique_lock<std::mutex> lock(g_stream_mutex);
    if (g_stream_pcm_size < target_samples && g_stream_running && !g_stream_stop_requested) {
        g_stream_cv.wait_for(lock, std::chrono::milliseconds(timeout_ms), [target_samples]() {
            return g_stream_pcm_size >= target_samples || !g_stream_running || g_stream_stop_requested;
        });
    }

    const std::size_t samples_to_copy = std::min(target_samples, g_stream_pcm_size);
    for (std::size_t index = 0; index < samples_to_copy; ++index) {
        pcm_bytes[index] = g_stream_pcm_ring[g_stream_pcm_read_index];
        g_stream_pcm_read_index = (g_stream_pcm_read_index + 1) % g_stream_pcm_ring.size();
    }
    g_stream_pcm_size -= samples_to_copy;
    g_stream_total_consumed_samples += static_cast<uint64_t>(samples_to_copy);
    lock.unlock();

    env->ReleaseShortArrayElements(pcm_out, pcm_bytes, 0);
    return static_cast<jint>(samples_to_copy);
}

extern "C"
JNIEXPORT jlongArray JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeAmStreamMetrics(
        JNIEnv* env,
        jobject /*thiz*/) {
    jlongArray result_array = env->NewLongArray(18);
    if (result_array == nullptr) {
        return nullptr;
    }

    jlong values[18] = {};
    {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        values[0] = static_cast<jlong>(g_stream_pcm_size);
        values[1] = static_cast<jlong>(g_stream_total_produced_samples);
        values[2] = static_cast<jlong>(g_stream_total_consumed_samples);
        values[3] = static_cast<jlong>(g_stream_total_usb_bytes);
        values[4] = static_cast<jlong>(g_stream_total_usb_blocks);
        values[5] = static_cast<jlong>(g_stream_total_usb_failures);
        values[6] = static_cast<jlong>(g_stream_total_dropped_samples);
        values[7] = static_cast<jlong>(g_stream_rf_level_percent);
        values[8] = static_cast<jlong>(g_stream_audio_level_percent);
        values[9] = static_cast<jlong>(g_stream_clip_percent);
        values[10] = static_cast<jlong>(g_stream_estimated_carrier_offset_hz);
        values[11] = static_cast<jlong>(
            g_stream_squelch_percent_uses_peak_meter
                ? g_stream_squelch_meter.percent(stream_time_ms())
                : g_stream_squelch_percent);
        values[12] = static_cast<jlong>(g_stream_squelch_noise_floor_percent);
        values[13] = static_cast<jlong>(g_stream_squelch_threshold_percent);
        values[14] = static_cast<jlong>(g_stream_squelch_effective_threshold_percent);
        values[15] = static_cast<jlong>(g_stream_squelch_open ? 1 : 0);
        values[16] = static_cast<jlong>(g_stream_iq_clip_percent);
        values[17] = g_stream_running ? 1 : 0;
    }

    env->SetLongArrayRegion(result_array, 0, 18, values);
    return result_array;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeAmStreamSpectrum(
        JNIEnv* env,
        jobject /*thiz*/) {
    std::vector<jint> spectrum_bins;
    {
        std::lock_guard<std::mutex> lock(g_stream_mutex);
        spectrum_bins = g_stream_spectrum_bins;
    }
    jintArray result_array = env->NewIntArray(static_cast<jsize>(spectrum_bins.size()));
    if (result_array == nullptr) {
        return nullptr;
    }
    if (!spectrum_bins.empty()) {
        env->SetIntArrayRegion(result_array, 0, static_cast<jsize>(spectrum_bins.size()), spectrum_bins.data());
    }
    return result_array;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeSpectrumHistory(
        JNIEnv* env, jobject /*thiz*/, jlong after_sequence) {
    const auto frames = g_spectrum_history.since(static_cast<uint64_t>(std::max<jlong>(0, after_sequence)), stream_time_ms());
    const auto bytes = encode_spectrum_history(frames);
    auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result != nullptr) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                                                reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_org_opensdr_sdrcore_DirectRtlSdrNativeBridge_nativeDestroyAmPipeline(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle) {
    std::lock_guard<std::mutex> device_lock(g_mutex);
    auto* pipeline = reinterpret_cast<AmPipeline*>(handle);
    bool active;
    {
        std::lock_guard<std::mutex> stream_lock(g_stream_mutex);
        active = g_stream_pipeline == pipeline;
    }
    if (active) stop_stream_worker();
    delete pipeline;
    set_error("Native AM pipeline destroyed");
}
