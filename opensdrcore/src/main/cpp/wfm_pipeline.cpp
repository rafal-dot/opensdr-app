#include "wfm_pipeline.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kWfmDeemphasisUs = 75.0f;
constexpr float kPi = 3.14159265358979323846f;
}

void reset_wfm_pipeline(WfmPipelineState* state) {
    state->prev_i = 0.0f;
    state->prev_q = 0.0f;
    state->has_prev = false;
    state->deemphasis = 0.0f;
}

float demodulate_wfm(
    WfmPipelineState* state,
    int iq_sample_rate_hz,
    float filtered_i,
    float filtered_q,
    float* carrier_presence_level) {
    const float magnitude = std::sqrt(filtered_i * filtered_i + filtered_q * filtered_q);
    *carrier_presence_level += (magnitude - *carrier_presence_level) * 0.015f;
    *carrier_presence_level = std::max(*carrier_presence_level, 1.0f);
    if (!state->has_prev) {
        state->prev_i = filtered_i;
        state->prev_q = filtered_q;
        state->has_prev = true;
        return 0.0f;
    }

    const float dot = state->prev_i * filtered_i + state->prev_q * filtered_q;
    const float cross = state->prev_i * filtered_q - state->prev_q * filtered_i;
    state->prev_i = filtered_i;
    state->prev_q = filtered_q;

    // Decode instantaneous frequency before audio low-pass/decimation. Averaging
    // the complex discriminator first distorts large broadcast FM deviations.
    const float rate = std::max(static_cast<float>(iq_sample_rate_hz), 1.0f);
    const float discriminated = std::atan2(cross, dot) * rate / (2.0f * kPi) * (9000.0f / 75000.0f);
    const float dt = 1.0f / rate;
    const float tau = kWfmDeemphasisUs * 1.0e-6f;
    const float alpha = dt / (tau + dt);
    state->deemphasis += (discriminated - state->deemphasis) * alpha;
    return state->deemphasis;
}
