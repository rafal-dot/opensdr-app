#include "am_pipeline.h"
#include "am_test_config.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>

namespace {
constexpr double pi = 3.14159265358979323846;

void check(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

struct Reception {
    double tone = 0;
    double rms = 0;
    int offset = 0;
    int clips = 0;
    std::vector<int16_t> pcm;
};

Reception receive(AmPipeline& pipeline, int input_rate, double amplitude, double offset,
        bool fm = false, bool impulses = false, double noise = 0, double tone_hz = 1000) {
    std::vector<int8_t> iq(4096);
    std::vector<int16_t> pcm(4096);
    Reception result;
    unsigned rng = 12345;
    int output_index = 0;
    double real = 0, imaginary = 0, power = 0;
    int measured = 0;
    for (int start = 0; start < input_rate * 3 / 2; start += 2048) {
        for (int n = 0; n < 2048; ++n) {
            const double t = (start + n) / double(input_rate);
            const double tone_phase = 2 * pi * tone_hz * t;
            const double magnitude = amplitude * (fm ? 1 : 1 + .5 * std::cos(tone_phase));
            const double phase = 2 * pi * offset * t + (fm ? 75000 / tone_hz * std::sin(tone_phase) : 0);
            for (int axis = 0; axis < 2; ++axis) {
                rng = rng * 1664525 + 1013904223;
                const double white_noise = (int(rng >> 16) / 32768.0 - 1) * noise;
                double value = magnitude * (axis == 0 ? std::cos(phase) : std::sin(phase)) + white_noise;
                if (impulses && (start + n) % 20000 == 10000) value = axis == 0 ? 127 : -128;
                iq[n * 2 + axis] = static_cast<int8_t>(std::clamp(std::lround(128 + value), 0L, 255L));
            }
        }
        const auto metrics = pipeline.process(iq.data(), iq.size(), pcm.data(), pcm.size());
        result.offset = metrics.estimated_carrier_offset_hz;
        for (int n = 0; n < metrics.pcm_samples_written; ++n, ++output_index) {
            result.pcm.push_back(pcm[n]);
            if (output_index < 48000) continue;
            const double phase = 2 * pi * tone_hz * output_index / 48000;
            real += pcm[n] * std::cos(phase);
            imaginary += pcm[n] * std::sin(phase);
            power += double(pcm[n]) * pcm[n];
            result.clips += std::abs(int(pcm[n])) >= 32767;
            ++measured;
        }
    }
    check(measured > 0, "Pipeline must produce PCM");
    result.tone = 2 * std::hypot(real, imaginary) / measured;
    result.rms = std::sqrt(power / measured);
    return result;
}

Reception receive(const AmPipelineConfig& config, double amplitude, double offset,
        bool impulses = false, double noise = 0) {
    AmPipeline pipeline(config);
    return receive(pipeline, config.input_sample_rate_hz, amplitude, offset, config.demod_mode == 5, impulses, noise);
}

void test_am(bool simple_am) {
    auto c = clean_voice();
    c.simple_am_enabled = simple_am;
    const auto reference = receive(c, 5, -500);
    for (double offset : {-5500., -3000., 2000., 5000.}) {
        const auto weak = receive(c, 5, offset, false, 2);
        check(std::abs(weak.offset - offset) < 150, "Weak coherent AM carrier must be tracked");
        check(weak.tone > reference.tone * .65, "Carrier search must not discard weak off-center speech");
    }
    for (double offset : {-5000., 5000.}) {
        const auto noisy = receive(c, 3, offset, false, 5);
        const double tone_power = noisy.tone * noisy.tone / 2;
        const double sinad_db = 10 * std::log10(tone_power / std::max(1., noisy.rms * noisy.rms - tone_power));
        std::cout << "Simple=" << simple_am << " weak AM offset=" << offset
                  << " tracked=" << noisy.offset << " SINAD=" << sinad_db << '\n';
        check(std::abs(noisy.offset - offset) < 150, "Noise must not displace the wanted carrier");
        // Simple AM deliberately retains more upper-band audio/noise. Keep the
        // old regression limit for legacy AM; allow 1 dB for its wider replacement.
        check(sinad_db > (simple_am ? 8 : 9), "Weak AM must remain audible in broadband noise");
    }
    const auto strong = receive(c, 40, -3000);
    const auto stronger = receive(c, 75, -3000);
    check(strong.clips == 0 && stronger.clips == 0, "Strong non-clipped IQ must not clip PCM");
    check(std::abs(strong.tone / stronger.tone - 1) < .1, "AGC must normalize strong stations");
    AmPipeline alternating(c);
    receive(alternating, 240000, 40, -3000);
    const auto next_station = receive(alternating, 240000, 5, 2000);
    check(std::abs(next_station.offset - 2000) < 150, "Weak station must replace the previous strong carrier reference");
    for (int rate : {240000, 480000, 960000}) {
        auto config = c;
        config.input_sample_rate_hz = rate;
        AmPipeline retuned(config);
        receive(retuned, rate, 5, -5500, false, false, 2);
        retuned.note_retune();
        const auto next = receive(retuned, rate, 5, 5000, false, false, 2);
        check(std::abs(next.offset - 5000) < 150, "Weak carrier qualification must recover after retune at every rate");
    }
}

void test_carrier_coherence_reset() {
    CarrierCoherence coherence;
    for (int rate : {12000, 24000, 48000}) {
        coherence.configure(rate);
        check(!coherence.coherent(), "New rate must discard old carrier confidence");
        for (int n = 0; n < rate / 5; ++n) {
            const double t = n / double(rate);
            const double phase = 2 * pi * -5500 * t;
            const float amplitude = 2 * (1 + .9 * std::cos(2 * pi * 1370 * t));
            coherence.observe(amplitude * std::cos(phase), amplitude * std::sin(phase), amplitude);
            if (n < rate / 50) check(!coherence.coherent(), "A brief coherent burst must not qualify immediately");
        }
        check(coherence.coherent(), "Long-lag qualification must retain a deeply modulated carrier");
        coherence.reset();
        check(!coherence.coherent(), "Retune must clear weak-carrier qualification");
    }
}

void test_modes_and_initialization() {
    AmPipeline defaults(AmPipelineConfig{});
    check(receive(defaults, 240000, 40, -500).tone > 1000, "Default constructor must initialize filters");
    for (int rate : {240000, 480000, 960000}) {
        auto c = clean_voice();
        c.input_sample_rate_hz = rate;
        AmPipeline pipeline(c);
        c.demod_mode = 5;
        check(pipeline.update_config(c), "Switch to WFM");
        const auto fm = receive(pipeline, rate, 40, -500, true);
        check(fm.tone > 1000, "WFM with 75 kHz deviation must be audible");
        check(fm.tone * fm.tone / (2 * fm.rms * fm.rms) > .95, "WFM must retain a clean modulation tone");
        if (rate == 240000) {
            const auto wide_audio = receive(pipeline, rate, 40, -500, true, false, 0, 10000);
            check(wide_audio.tone > 500, "WFM must retain audio above the AM voice passband");
        }
        c.demod_mode = 1;
        check(pipeline.update_config(c), "Switch back to AM");
        const auto am = receive(pipeline, rate, 40, -3000);
        check(std::abs(am.offset + 3000) < 150 && am.tone > 1000, "Restore AM filtering/tracking after WFM");
    }
}

void test_blanker() {
    auto c = clean_voice();
    c.simple_am_enabled = false; // Legacy processing stays available for comparison.
    const auto bypass = receive(c, 40, -500, true);
    const auto clean = receive(c, 40, -500);
    c.noise_blanker_enabled = true;
    const auto blanked = receive(c, 40, -500, true);
    const auto enabled_clean = receive(c, 40, -500);
    check(bypass.pcm != blanked.pcm, "Enabled blanker must affect isolated IQ impulses");
    check(clean.pcm == enabled_clean.pcm, "Blanker must leave continuous AM modulation unchanged");
    check(blanked.tone > bypass.tone * .95, "Blanker must not gate speech");
}

void test_simple_am_bypass_and_rollback() {
    auto c = clean_voice();
    const auto baseline = receive(c, 40, -3000, true, 2);
    auto overridden = c;
    overridden.demod_mode = 2;
    overridden.audio_boost_percent = 320;
    overridden.output_gain = 4;
    overridden.voice_focus_enabled = true;
    overridden.post_leveler_enabled = true;
    overridden.noise_blanker_enabled = true;
    overridden.denoise_mode = 3;
    overridden.notch_mode = 1;
    overridden.sideband_mode = 2;
    overridden.advanced_sync_enabled = true;
    overridden.quality_squelch_enabled = true;
    overridden.speech_enhancer_mode = 2;
    check(baseline.pcm == receive(overridden, 40, -3000, true, 2).pcm,
          "Simple AM must ignore every saved enhancement override, including sync demodulation");
    overridden.audio_high_pass_hz = 800;
    overridden.natural_audio_cutoff_hz = 1800;
    overridden.natural_audio_ratio = .25;
    check(baseline.pcm == receive(overridden, 40, -3000, true, 2).pcm,
          "Legacy voice high/low-pass tuning must not shape simple AM");

    for (int mode : {3, 4, 5}) {
        c.demod_mode = mode;
        const auto simple_flag_on = receive(c, 40, -500);
        c.simple_am_enabled = false;
        check(simple_flag_on.pcm == receive(c, 40, -500).pcm,
              "Simple AM profile switch must leave FM/SSB bit-identical");
        c.simple_am_enabled = true;
    }

    c = clean_voice();
    AmPipeline switching(c);
    receive(switching, 240000, 40, -3000);
    c.simple_am_enabled = false;
    check(switching.update_config(c), "Legacy AM must be selectable without removing code");
    const auto legacy = receive(c, 40, -3000);
    check(legacy.pcm == receive(switching, 240000, 40, -3000).pcm,
          "Switching back must restore the legacy signal path");
    c.simple_am_enabled = true;
    check(switching.update_config(c), "Simple AM must be selectable again");
    check(receive(c, 40, -3000).pcm == receive(switching, 240000, 40, -3000).pcm,
          "Returning to simple AM must initialize its filter histories correctly");
}

void test_simple_am_voice_passband() {
    const auto config = clean_voice();
    for (int rate : {240000, 480000, 960000}) {
        auto c = config;
        c.input_sample_rate_hz = rate;
        AmPipeline mid(c), upper(c), edge(c);
        // Low modulation amplitude keeps gain at its ceiling, so AGC cannot
        // hide excessive attenuation by normalizing each tone independently.
        const double reference = receive(mid, rate, 5, -3000).tone;
        const double high = receive(upper, rate, 5, -3000, false, false, 0, 2200).tone;
        const double boundary = receive(edge, rate, 5, -3000, false, false, 0, 2500).tone;
        std::cout << "Voice rate=" << rate << " 2200/1000=" << high / reference
                  << " 2500/1000=" << boundary / reference << '\n';
        check(high / reference > .78, "Upper voice band must not be cut by stacked voice filters");
        // The output filter contributes -3 dB; allow another 2 dB for the IQ
        // filters/DC rejection, especially when one sideband lies near zero IF.
        check(boundary / reference > std::pow(10., -5. / 20) && boundary / reference < .85,
              "Total 2500 Hz attenuation must stay within 5 dB, not the legacy stacked roll-off");
    }
}

void test_off_center_upper_voice_passband() {
    for (int rate : {240000, 480000, 960000}) {
        auto config = clean_voice();
        config.input_sample_rate_hz = rate;
        for (double tone : {2200., 2500.}) {
            AmPipeline reference_pipeline(config);
            const double reference = receive(reference_pipeline, rate, 5, -3000, false, false, 0, tone).tone;
            for (double offset : {-6490., -5000., 5000., 6490.}) {
                AmPipeline pipeline(config);
                const auto actual = receive(pipeline, rate, 5, offset, false, false, 0, tone);
                const double relative_db = 20 * std::log10(actual.tone / reference);
                std::cout << "Upper voice rate=" << rate << " offset=" << offset
                          << " tone=" << tone << " relative dB=" << relative_db << '\n';
                check(std::abs(actual.offset - offset) < 50,
                      "Upper-band AM test must track the actual carrier");
                // The same low input amplitude holds AGC at its gain ceiling.
                check(std::abs(relative_db) < 1,
                      "Acquisition filtering must not attenuate off-center upper speech by more than 1 dB");
            }
        }
    }
}

void test_concurrent_updates() {
    auto config = clean_voice();
    AmPipeline pipeline(config);
    std::atomic<bool> ready{false};
    std::thread worker([&] {
        std::vector<int8_t> iq(4096, -100);
        std::vector<int16_t> pcm(4096);
        ready = true;
        for (int n = 0; n < 300; ++n)
            pipeline.process(iq.data(), iq.size(), pcm.data(), pcm.size());
    });
    while (!ready.load()) std::this_thread::yield();
    for (int n = 0; n < 300; ++n) {
        config.channel_bandwidth_hz = n % 2 ? 10000 : 12000;
        check(pipeline.update_config(config), "Valid concurrent update");
        pipeline.note_retune();
        if (n % 5 == 0) pipeline.reset();
    }
    worker.join();
}

void test_iq_fragmentation() {
    const auto config = clean_voice();
    std::vector<int8_t> iq(20001);
    unsigned rng = 12345;
    for (auto& value : iq) {
        rng = rng * 1664525 + 1013904223;
        value = static_cast<int8_t>(rng >> 24);
    }
    auto decode = [&](int chunk_size) {
        AmPipeline pipeline(config);
        std::vector<int16_t> output, block(4096);
        for (std::size_t start = 0; start < iq.size(); start += chunk_size) {
            const auto count = std::min<std::size_t>(chunk_size, iq.size() - start);
            const auto result = pipeline.process(iq.data() + start, count, block.data(), block.size());
            output.insert(output.end(), block.begin(), block.begin() + result.pcm_samples_written);
        }
        return output;
    };
    check(decode(4096) == decode(4095), "Partial USB transfers must retain I/Q pairing and PCM continuity");
}
}

int main() {
    test_off_center_upper_voice_passband();
    test_am(false);
    test_am(true);
    test_carrier_coherence_reset();
    test_modes_and_initialization();
    test_blanker();
    test_simple_am_bypass_and_rollback();
    test_simple_am_voice_passband();
    test_concurrent_updates();
    test_iq_fragmentation();
    std::cout << "AM/WFM, initialization, blanker, and concurrent DSP tests passed\n";
}
