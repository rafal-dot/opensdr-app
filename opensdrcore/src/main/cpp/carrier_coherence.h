#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

// Qualify weak carriers over a longer lag: band-limited noise can look almost
// perfectly coherent between adjacent IQ samples. This is only a validity
// check, not a frequency estimator (a long-lag phase would be ambiguous).
class CarrierCoherence {
public:
    void configure(float observations_per_second) {
        history_.resize(std::max(1, static_cast<int>(std::lround(observations_per_second * kLagSeconds))));
        alpha_ = 1.0f - std::exp(-1.0f / (observations_per_second * kAveragingSeconds));
        reset();
    }

    void reset() {
        next_ = 0;
        count_ = 0;
        correlation_i_ = 0;
        correlation_q_ = 0;
    }

    // Called once per tracking update, with preallocated history and no sqrt/FFT.
    void observe(float i, float q, float magnitude) {
        if (history_.empty()) return;
        if (count_ == history_.size()) {
            const auto& previous = history_[next_];
            const float inverse_magnitude = 1.0f / std::max(magnitude * previous.magnitude, 1e-6f);
            const float dot = (previous.i * i + previous.q * q) * inverse_magnitude;
            const float cross = (previous.i * q - previous.q * i) * inverse_magnitude;
            correlation_i_ += (dot - correlation_i_) * alpha_;
            correlation_q_ += (cross - correlation_q_) * alpha_;
        } else {
            ++count_;
        }
        history_[next_] = {i, q, magnitude};
        if (++next_ == history_.size()) next_ = 0;
    }

    bool coherent() const {
        return correlation_i_ * correlation_i_ + correlation_q_ * correlation_q_ >= kMinimumCoherenceSquared;
    }

private:
    static constexpr float kLagSeconds = .001f;
    static constexpr float kAveragingSeconds = .020f;
    static constexpr float kMinimumCoherenceSquared = .65f;
    struct Sample { float i = 0, q = 0, magnitude = 0; };
    std::vector<Sample> history_;
    std::size_t next_ = 0, count_ = 0;
    float alpha_ = 0;
    float correlation_i_ = 0, correlation_q_ = 0;
};
