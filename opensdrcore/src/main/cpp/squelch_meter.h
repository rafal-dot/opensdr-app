#pragma once

#include <algorithm>
#include <array>
#include <cstdint>

// Fixed-cost sustained peak meter: qualify each integer threshold separately.
// No history allocations or quadratic rescans on the USB/PCM critical path.
class SquelchMeter {
public:
    void configure(int qualification_ms, int hold_ms, int observation_slack_ms = 25) {
        qualification_ms = std::max(100, qualification_ms);
        hold_ms = std::max(0, hold_ms);
        observation_slack_ms = std::max(0, observation_slack_ms);
        if (qualification_ms_ != qualification_ms || hold_ms_ != hold_ms ||
            observation_slack_ms_ != observation_slack_ms) reset();
        qualification_ms_ = qualification_ms;
        hold_ms_ = hold_ms;
        observation_slack_ms_ = observation_slack_ms;
    }

    void reset() {
        levels_ = {};
        has_observation_ = false;
    }

    void observe(int percent, int64_t now_ms) {
        if (has_observation_ && (now_ms < last_observed_ms_ ||
            now_ms - last_observed_ms_ > qualification_ms_ + hold_ms_ + observation_slack_ms_)) reset();
        last_observed_ms_ = now_ms;
        has_observation_ = true;
        percent = std::clamp(percent, 0, 100);
        for (int level = 1; level <= 100; ++level) {
            auto& state = levels_[level];
            if (level > percent) {
                state.active = false;
                continue;
            }
            if (!state.active) {
                state.active = true;
                state.started_ms = now_ms;
            }
            if (now_ms - state.started_ms >= qualification_ms_) {
                state.qualified = true;
                state.qualified_ms = now_ms;
            }
        }
    }

    int percent(int64_t now_ms) const {
        for (int level = 100; level > 0; --level) {
            const auto& state = levels_[level];
            if (state.qualified && now_ms - state.qualified_ms <= hold_ms_ + observation_slack_ms_) return level;
        }
        return 0;
    }

private:
    struct Level {
        int64_t started_ms = 0;
        int64_t qualified_ms = 0;
        bool active = false;
        bool qualified = false;
    };
    std::array<Level, 101> levels_{};
    int qualification_ms_ = 120;
    int hold_ms_ = 700;
    int observation_slack_ms_ = 25;
    int64_t last_observed_ms_ = 0;
    bool has_observation_ = false;
};
