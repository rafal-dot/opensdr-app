#include "am_test_config.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
constexpr double pi = 3.14159265358979323846;

// A deterministic narrow noise band, not a second AM carrier. Adjacent-sample
// phase coherence alone cannot distinguish this interference from a carrier.
class NeighborNoise {
public:
    NeighborNoise(double width_hz, int sample_rate)
        : alpha_(1 - std::exp(-2 * pi * width_hz / sample_rate)),
          scale_(std::sqrt(3 * (2 - alpha_) / alpha_)) {}

    void next() {
        i += (white_noise() * scale_ - i) * alpha_;
        q += (white_noise() * scale_ - q) * alpha_;
    }
    double i = 0, q = 0;

private:
    double white_noise() {
        random_ = random_ * 1664525 + 1013904223;
        return int(random_ >> 16) / 32768.0 - 1;
    }
    unsigned random_ = 12345;
    double alpha_, scale_;
};

double receive_with_neighbor_noise(int sample_rate, double width_hz, double carrier_offset_hz, double neighbor_offset_hz,
                                  bool simple_am) {
    auto config = clean_voice();
    config.simple_am_enabled = simple_am;
    config.input_sample_rate_hz = sample_rate;
    AmPipeline pipeline(config);
    NeighborNoise noise(width_hz, sample_rate);
    std::vector<int8_t> iq(4096);
    std::vector<int16_t> pcm(4096);
    double real = 0, imaginary = 0, power = 0;
    int output_index = 0, measured = 0;
    for (int start = 0; start < sample_rate * 2; start += 2048) {
        for (int n = 0; n < 2048; ++n) {
            const double t = (start + n) / double(sample_rate);
            const double magnitude = 2 * (1 + .5 * std::cos(2 * pi * 1000 * t));
            const double phase = 2 * pi * carrier_offset_hz * t;
            const double neighbor_phase = 2 * pi * neighbor_offset_hz * t;
            noise.next();
            const double i = magnitude * std::cos(phase) +
                2 * (noise.i * std::cos(neighbor_phase) - noise.q * std::sin(neighbor_phase));
            const double q = magnitude * std::sin(phase) +
                2 * (noise.i * std::sin(neighbor_phase) + noise.q * std::cos(neighbor_phase));
            iq[n * 2] = static_cast<int8_t>(std::clamp(std::lround(128 + i), 0L, 255L));
            iq[n * 2 + 1] = static_cast<int8_t>(std::clamp(std::lround(128 + q), 0L, 255L));
        }
        const auto result = pipeline.process(iq.data(), iq.size(), pcm.data(), pcm.size());
        if (start >= sample_rate && std::abs(result.estimated_carrier_offset_hz - carrier_offset_hz) > 150) {
            std::cerr << "Noise displaced the wanted carrier\n";
            std::exit(1);
        }
        for (int n = 0; n < result.pcm_samples_written; ++n, ++output_index) {
            if (output_index < 48000) continue;
            const double phase = 2 * pi * 1000 * output_index / 48000;
            real += pcm[n] * std::cos(phase);
            imaginary += pcm[n] * std::sin(phase);
            power += double(pcm[n]) * pcm[n];
            ++measured;
        }
    }
    if (measured == 0) return -100;
    const double tone = 2 * std::hypot(real, imaginary) / measured;
    const double tone_power = tone * tone / 2;
    return 10 * std::log10(tone_power / std::max(1.0, power / measured - tone_power));
}
}

int main() {
    for (int sample_rate : {240000, 480000, 960000}) {
        for (double sign : {-1., 1.}) {
            for (double width_hz : {100., 300.}) {
                const double legacy = receive_with_neighbor_noise(sample_rate, width_hz, sign * -500, sign * 5000, false);
                const double simple = receive_with_neighbor_noise(sample_rate, width_hz, sign * -500, sign * 5000, true);
                std::cout << "Rate=" << sample_rate << " neighbor width=" << width_hz
                          << " sign=" << sign << " legacy SINAD=" << legacy
                          << " simple SINAD=" << simple << " dB\n";
                // The wider noise band passes more energy through the unchanged
                // RF filter, particularly at 960 ksps; guard against loss of the
                // carrier, not that pre-existing difference in filter response.
                const double minimum_sinad = width_hz == 100 ? 12 : 8;
                if (legacy < minimum_sinad) {
                    std::cerr << "Weak AM must not be replaced by nearby narrowband noise\n";
                    return 1;
                }
                // A 1 kHz tone benefits from the legacy narrow cascade; speech
                // needs the upper band too. Bound the noise cost of removing that
                // cascade, while independently requiring correct carrier lock.
                if (simple < legacy - 3.5) {
                    std::cerr << "Simple AM exceeds the 3.5 dB wider-band noise budget\n";
                    return 1;
                }
            }
        }
    }
    return 0;
}
