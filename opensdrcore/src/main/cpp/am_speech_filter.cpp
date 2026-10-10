#include "am_speech_filter.h"

#include <algorithm>
#include <cmath>

void AmSpeechFilter::Biquad::design(float cutoff_hz, float q, int sample_rate_hz, bool high_pass) {
    const double omega = 2.0 * 3.14159265358979323846 * cutoff_hz / sample_rate_hz;
    const double c = std::cos(omega);
    const double alpha = std::sin(omega) / (2.0 * q);
    const double a0 = 1.0 + alpha;
    b0 = static_cast<float>((high_pass ? 1.0 + c : 1.0 - c) / (2.0 * a0));
    b1 = (high_pass ? -2.0f : 2.0f) * b0;
    b2 = b0;
    a1 = static_cast<float>(-2.0 * c / a0);
    a2 = static_cast<float>((1.0 - alpha) / a0);
}

float AmSpeechFilter::Biquad::process(float sample) {
    const float result = b0 * sample + z1;
    z1 = b1 * sample - a1 * result + z2;
    z2 = b2 * sample - a2 * result;
    // Avoid slow subnormal arithmetic during long squelched/silent periods.
    if (std::fabs(z1) < 1e-20f) z1 = 0;
    if (std::fabs(z2) < 1e-20f) z2 = 0;
    return result;
}

void AmSpeechFilter::configure(int sample_rate_hz) {
    const int rate = std::max(sample_rate_hz, 8000);
    // Design each complete Butterworth edge, rather than stacking copies of the
    // old filter (which would also attenuate wanted speech at the band edges).
    const auto design_edge = [&](int first, int count, float cutoff, bool high_pass) {
        for (int i = 0; i < count; ++i) {
            const double angle = 3.14159265358979323846 * (2 * i + 1) / (4 * count);
            const float q = static_cast<float>(1.0 / (2.0 * std::cos(angle)));
            stages_[first + i].design(cutoff, q, rate, high_pass);
        }
    };
    design_edge(0, kHighPassStages, 250.0f, true);
    design_edge(kHighPassStages, kLowPassStages, 2500.0f, false);
    ramp_step_ = 1.0f / (rate * 0.010f);
}

void AmSpeechFilter::reset(bool enabled) {
    for (auto& stage : stages_) stage.z1 = stage.z2 = 0;
    wet_ = enabled ? 1.0f : 0.0f;
}

float AmSpeechFilter::process(float sample, bool enabled) {
    if (!enabled && wet_ == 0.0f) return sample;
    float filtered = sample;
    for (auto& stage : stages_) filtered = stage.process(filtered);
    wet_ = enabled ? std::min(1.0f, wet_ + ramp_step_) : std::max(0.0f, wet_ - ramp_step_);
    const float result = sample + wet_ * (filtered - sample);
    if (!enabled && wet_ == 0.0f) reset(false);
    return result;
}
