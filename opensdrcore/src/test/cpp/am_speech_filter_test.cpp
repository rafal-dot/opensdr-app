#include "am_speech_filter.h"
#include "am_test_config.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
constexpr double pi = 3.14159265358979323846;

void check(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

double response(int rate, double frequency) {
    AmSpeechFilter filter;
    filter.configure(rate);
    filter.reset(true);
    double power = 0;
    for (int n = 0; n < rate * 2; ++n) {
        const float input = 1000 * std::sin(2 * pi * frequency * n / rate);
        const float output = filter.process(input, true);
        check(std::isfinite(output), "Filter must stay finite");
        if (n >= rate) power += output * output;
    }
    return std::sqrt(2 * power / rate) / 1000;
}

void test_frequency_response() {
    for (int rate : {8000, 44100, 48000, 96000}) {
        check(response(rate, 100) < .0045, "100 Hz rumble must be attenuated by at least 47 dB");
        check(response(rate, 150) < .05, "150 Hz must be attenuated by at least 26 dB");
        check(response(rate, 200) < .26, "Low-frequency transition must reach about -12 dB at 200 Hz");
        check(response(rate, 300) > .94, "Stronger rejection must not narrow the lower speech band");
        for (int frequency : {500, 1000, 2000}) {
            const auto gain = response(rate, frequency);
            check(gain > .98 && gain < 1.001, "Speech-band gain must remain flat without boosting");
        }
        for (int edge : {250, 2500}) {
            const auto gain = response(rate, edge);
            check(gain > .69 && gain < .72, "Band edges must be approximately -3 dB");
        }
        const auto transition = response(rate, 2750);
        const auto stop_band = response(rate, 3000);
        check(transition < .48 && stop_band < transition && stop_band < .24,
              "2500-3000 Hz transition must reach at least 12 dB attenuation at 3000 Hz");
        check(response(rate, 3500) < .07, "Above 3000 Hz the rejection must quickly become stronger");
        if (rate > 10000) {
            check(response(rate, 4000) < .027, "4 kHz hiss must be attenuated by at least 31 dB");
            check(response(rate, 5000) < .004, "5 kHz hiss must be attenuated by at least 48 dB");
        }
    }
}

void test_switching_and_decay() {
    for (int rate : {8000, 44100, 48000, 96000}) {
        AmSpeechFilter filter;
        filter.configure(rate);
        filter.reset(false);
        check(filter.process(1234, false) == 1234, "Disabled filter must be an exact bypass");
        check(filter.process(1000, true) > 980, "Enabling must crossfade, not abruptly replace audio");
        for (int n = 0; n < rate; ++n) filter.process(1000, true);
        check(std::abs(filter.process(1000, false)) < 20, "Disabling must also crossfade");
        for (int n = 0; n < rate / 20; ++n) filter.process(1000, false);
        check(filter.process(-4321, false) == -4321, "Bypass must become exact after the fade");
        filter.reset(true);
        for (int n = 0; n < rate; ++n) filter.process(n == 0 ? 20000 : 0, true);
        check(std::abs(filter.process(0, true)) < 1e-10f, "Impulse must decay to silence");
    }
}

std::vector<int8_t> iq_block(int start, int count, int rate, double tone) {
    std::vector<int8_t> iq(count * 2);
    for (int n = 0; n < count; ++n) {
        const double t = (start + n) / double(rate);
        const double magnitude = 25 * (1 + .5 * std::cos(2 * pi * tone * t));
        const double phase = 2 * pi * -2500 * t;
        iq[n * 2] = static_cast<int8_t>(std::lround(128 + magnitude * std::cos(phase)));
        iq[n * 2 + 1] = static_cast<int8_t>(std::lround(128 + magnitude * std::sin(phase)));
    }
    return iq;
}

struct ToneMeasurement {
    double real = 0, imaginary = 0;

    void add(int16_t sample, double phase) {
        real += sample * std::cos(phase);
        imaginary += sample * std::sin(phase);
    }

    double power() const { return real * real + imaginary * imaginary; }
};

void test_real_pipeline_tone(int mode, double tone) {
    auto config = clean_voice();
    config.demod_mode = mode;
    if (mode == 2) config.simple_am_enabled = false; // Exercise the retained synchronous path too.
    config.input_sample_rate_hz = mode == 5 ? 960000 : 240000;
    config.am_speech_filter_enabled = false;
    AmPipeline dry(config);
    config.am_speech_filter_enabled = true;
    AmPipeline wet(config);
    ToneMeasurement dry_tone, wet_tone;
    int output_index = 0;
    std::vector<int16_t> a(2048), b(2048);
    for (int start = 0; start < config.input_sample_rate_hz; start += 2048) {
        const auto iq = iq_block(start, 2048, config.input_sample_rate_hz, tone);
        const auto ra = dry.process(iq.data(), iq.size(), a.data(), a.size());
        const auto rb = wet.process(iq.data(), iq.size(), b.data(), b.size());
        check(ra.pcm_samples_written == rb.pcm_samples_written, "Filter must not change PCM timing/count");
        check(ra.estimated_carrier_offset_hz == rb.estimated_carrier_offset_hz &&
              ra.squelch_percent == rb.squelch_percent && ra.squelch_open == rb.squelch_open,
              "Filter must not alter carrier or squelch decisions");
        for (int n = 0; n < ra.pcm_samples_written; ++n, ++output_index) {
            if (mode >= 3) check(a[n] == b[n], "SSB, NFM and WFM must be bit-identical with either filter setting");
            // Measure complete tone cycles after acquisition, not total RMS:
            // 8-bit IQ quantization also produces unrelated in-band noise.
            if (output_index >= config.output_sample_rate_hz / 2 &&
                output_index < config.output_sample_rate_hz * 3 / 4) {
                const double phase = 2 * pi * tone * output_index / config.output_sample_rate_hz;
                dry_tone.add(a[n], phase);
                wet_tone.add(b[n], phase);
            }
        }
    }
    if (mode >= 3) return;
    check(dry_tone.power() > 1e6, "AM test must contain a demodulated tone");
    const double power_ratio = wet_tone.power() / dry_tone.power();
    std::cout << "Speech filter mode=" << mode << " tone=" << tone
              << " power ratio=" << power_ratio << '\n';
    if (tone == 100) check(power_ratio < .00004, "Real AM paths must strongly attenuate low-frequency audio");
    if (tone == 1000) check(power_ratio > .97 && power_ratio < 1.03,
                          "Real AM paths must preserve speech-band level");
    if (tone == 3000) check(power_ratio < .08, "Real AM paths must attenuate the upper transition band");
    if (tone == 4000) check(power_ratio < .001, "Real AM paths must strongly attenuate high-frequency audio");
}

void test_real_pipeline_and_other_modes() {
    for (int mode : {1, 2}) {
        for (int tone : {100, 1000, 3000}) test_real_pipeline_tone(mode, tone);
    }
    // Legacy voice shaping already removes the 4 kHz test tone before this
    // filter; use simple AM to verify stop-band rejection in the real pipeline.
    test_real_pipeline_tone(1, 4000);
    for (int mode : {3, 4, 5}) test_real_pipeline_tone(mode, 1000);
}

void test_toggle_keeps_fifo_and_carrier() {
    auto config = clean_voice();
    config.am_speech_filter_enabled = false;
    AmPipeline changed(config), reference(config);
    std::vector<int16_t> a(2048), b(2048);
    for (int start = 0; start < 240000; start += 2048) {
        const auto iq = iq_block(start, 2048, 240000, 1000);
        changed.process(iq.data(), iq.size(), a.data(), a.size());
        reference.process(iq.data(), iq.size(), b.data(), b.size());
    }
    const auto iq = iq_block(240000, 2048, 240000, 1000);
    const auto before = changed.process(iq.data(), iq.size(), a.data(), 1);
    reference.process(iq.data(), iq.size(), b.data(), 1);
    config.am_speech_filter_enabled = true;
    check(changed.update_config(config), "Live filter update must succeed");
    const auto after = changed.process(nullptr, 0, a.data(), a.size());
    const auto expected = reference.process(nullptr, 0, b.data(), b.size());
    check(after.pcm_samples_written > 300 && after.pcm_samples_written == expected.pcm_samples_written,
          "Toggle must retain queued PCM rather than flush the FIFO");
    check(std::equal(a.begin(), a.begin() + after.pcm_samples_written, b.begin()), "Queued PCM must remain intact");
    check(after.estimated_carrier_offset_hz == before.estimated_carrier_offset_hz,
          "Toggle must retain the acquired carrier");
}
}

int main() {
    check(AmPipelineConfig().am_speech_filter_enabled, "Speech filter must be enabled by default");
    test_frequency_response();
    test_switching_and_decay();
    test_real_pipeline_and_other_modes();
    test_toggle_keeps_fifo_and_carrier();
    std::cout << "AM speech filter tests passed\n";
}
