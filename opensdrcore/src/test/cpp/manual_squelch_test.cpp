#include "am_pipeline.h"
#include "am_test_config.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
void check(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

AmPipelineConfig configuration(int mode, int rate) {
    auto config = clean_voice();
    config.squelch_mode = mode;
    config.squelch_level = 30;
    config.squelch_open_debounce_ms = 120;
    config.squelch_close_hang_ms = 700;
    config.input_sample_rate_hz = rate;
    return config;
}

struct Receiver {
    AmPipelineConfig config;
    AmPipeline pipeline;
    int64_t sample_index = 0;
    std::vector<int8_t> iq;
    std::vector<int16_t> pcm;

    Receiver(int mode, int rate) : config(configuration(mode, rate)), pipeline(config),
        iq(rate / 125 * 2), pcm(rate / 125) {}

    int time_ms() const { return int(sample_index * 1000 / config.input_sample_rate_hz); }

    template<typename Amplitude> AmPipelineProcessResult block(Amplitude amplitude) {
        constexpr double pi = 3.14159265358979323846;
        for (size_t n = 0; n < iq.size() / 2; ++n, ++sample_index) {
            const double t = double(sample_index) / config.input_sample_rate_hz;
            const double magnitude = amplitude(time_ms()) * (1 + .3 * std::cos(2 * pi * 1000 * t));
            const double phase = 2 * pi * -3000 * t;
            for (int axis = 0; axis < 2; ++axis) {
                const double value = magnitude * (axis ? std::sin(phase) : std::cos(phase));
                iq[2 * n + axis] = static_cast<int8_t>(std::clamp(std::lround(128 + value), 0L, 255L));
            }
        }
        const auto result = pipeline.process(iq.data(), iq.size(), pcm.data(), pcm.size());
        check(!result.squelch_percent_uses_peak_meter, "Manual/custom must not be qualified again by USB telemetry");
        check(result.squelch_open == (config.squelch_level == 0 || result.squelch_percent >= config.squelch_level),
            "Exported meter and native audio gate must make the same decision");
        return result;
    }

    AmPipelineProcessResult constant(double amplitude, int blocks) {
        AmPipelineProcessResult result;
        for (int n = 0; n < blocks; ++n) result = block([=](int) { return amplitude; });
        return result;
    }

    void threshold(int level) {
        config.squelch_level = level;
        check(pipeline.update_config(config), "Threshold change must be accepted");
    }
};

void test_impulses_during_close(int mode, int rate) {
    Receiver receiver(mode, rate);
    bool heard_station = false;
    while (receiver.time_ms() < 3000) {
        const auto result = receiver.block([](int ms) {
            return ms < 500 || (ms - 500) % 120 < 70 ? 40.0 : 8.0;
        });
        if (receiver.time_ms() > 300 && receiver.time_ms() < 500) heard_station |= result.squelch_open;
        if (receiver.time_ms() > 1500) check(!result.squelch_open, "Repeated short impulses must not extend hang time");
    }
    check(heard_station, "A sustained station must be audible");
}

void test_threshold_edits_and_retune(int mode) {
    Receiver receiver(mode, 240000);
    check(receiver.constant(40, 80).squelch_open, "Acquire a station");
    receiver.threshold(29);
    check(receiver.constant(40, 1).squelch_open, "Small threshold edit must not mute a qualified station");
    receiver.threshold(90);
    check(!receiver.constant(40, 1).squelch_open, "Raising threshold above the meter closes audio");
    receiver.threshold(30);
    check(receiver.constant(40, 1).squelch_open, "Lowering threshold reuses qualified history");
    receiver.pipeline.note_retune();
    check(!receiver.constant(0, 1).squelch_open, "Retune clears the old carrier qualification");
    receiver.constant(0, 150);
    for (int n = 0; n < 9; ++n) check(!receiver.constant(40, 1).squelch_open, "A short burst cannot open squelch");
    check(!receiver.constant(0, 100).squelch_open, "An isolated burst remains rejected");
    receiver.threshold(0);
    check(receiver.constant(0, 1).squelch_open, "SQ off always permits audio");
}

void test_fragment_without_pcm(int mode) {
    Receiver receiver(mode, 240000);
    check(receiver.constant(40, 80).squelch_open, "Acquire before fragmented USB read");
    for (size_t bytes : {size_t(0), size_t(1)}) {
        const auto result = receiver.pipeline.process(receiver.iq.data(), bytes, receiver.pcm.data(), receiver.pcm.size());
        check(result.squelch_open && result.squelch_percent >= receiver.config.squelch_level,
            "A USB fragment producing no PCM must retain the qualified meter");
    }
}
}

int main() {
    for (int mode : {1, 2}) {
        for (int rate : {240000, 480000, 960000}) test_impulses_during_close(mode, rate);
        test_threshold_edits_and_retune(mode);
        test_fragment_without_pcm(mode);
    }
    std::cout << "Manual/custom gate, telemetry, impulses and retune tests passed\n";
}
