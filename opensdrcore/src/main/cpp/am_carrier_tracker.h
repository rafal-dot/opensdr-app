#pragma once

#include <complex>
#include <cstddef>
#include <vector>

// Offsets from the actual hardware center, not the displayed channel name.
// Disabled for callers that have not supplied tuning context.
struct AmCarrierTargets {
    bool enabled = false;
    float primary_hz = 0;
    float alternate_hz = 0;

    bool operator==(const AmCarrierTargets& other) const {
        return enabled == other.enabled && primary_hz == other.primary_hz &&
            alternate_hz == other.alternate_hz;
    }
};

// Select a persistent spectral line BEFORE narrowing AM IQ. A phase discriminator
// on the whole search band can instead point between two unrelated carriers.
class AmCarrierTracker {
public:
    void configure(int sample_rate_hz, float search_half_width_hz, AmCarrierTargets targets = {});
    void set_targets(AmCarrierTargets targets);
    void reset();
    void observe(float i, float q);
    float frequency_hz() const { return frequency_hz_; }
    bool locked() const { return locked_; }
    float context_half_width_hz() const { return context_half_width_hz_; }

private:
    struct Peak {
        float frequency_hz = 0;
        float power = 0;
        std::complex<float> phase{0, 0};
    };

    void analyze();
    void transform();
    float power_at(int bin) const;
    Peak peak_at(int bin, float minimum_power) const;
    bool is_modulation_sideband(const Peak& candidate) const;
    float target_weight(float frequency_hz) const;
    bool better_candidate(const Peak& candidate, const Peak& current) const;
    bool can_replace(const Peak& candidate, const Peak& incumbent) const;
    void select(Peak candidate, Peak incumbent);

    // Allocated/precomputed only on configuration. No allocations or trig in observe().
    std::vector<std::complex<float>> spectrum_;
    std::vector<std::complex<float>> roots_;
    std::vector<std::size_t> reversed_;
    std::vector<float> window_;
    std::vector<float> powers_;
    std::vector<float> noise_scratch_;
    std::vector<Peak> peaks_;
    std::vector<std::complex<float>> window_center_rotations_;
    std::size_t frame_position_ = 0;
    std::size_t frame_period_ = 0;
    float bin_hz_ = 0;
    float search_half_width_hz_ = 0;
    float context_half_width_hz_ = 0;
    float minimum_line_power_ = 0;
    int search_bins_ = 0;
    int context_bins_ = 0;
    AmCarrierTargets targets_;
    bool locked_ = false;
    float frequency_hz_ = 0;
    float pending_frequency_hz_ = 0;
    int pending_frames_ = 0;
    int missing_frames_ = 0;
};
