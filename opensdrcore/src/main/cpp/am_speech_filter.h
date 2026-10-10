#pragma once

#include <array>

// Output-only speech band, no allocations or per-sample trig.
// Butterworth edges: sixth-order HP at 250 Hz, eighth-order LP at 2500 Hz.
// Both cutoffs remain -3 dB; the upper transition band extends to 3000 Hz.
class AmSpeechFilter {
public:
    void configure(int sample_rate_hz);
    void reset(bool enabled);
    float process(float sample, bool enabled);

private:
    struct Biquad {
        float b0 = 0, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        float z1 = 0, z2 = 0;
        void design(float cutoff_hz, float q, int sample_rate_hz, bool high_pass);
        float process(float sample);
    };
    static constexpr int kHighPassStages = 3;
    static constexpr int kLowPassStages = 4;
    std::array<Biquad, kHighPassStages + kLowPassStages> stages_{};
    float wet_ = 0;
    float ramp_step_ = 1;
};
