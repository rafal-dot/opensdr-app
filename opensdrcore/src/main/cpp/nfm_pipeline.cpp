#include "nfm_pipeline.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kNfmDiscriminatorAlpha = 0.22f;
}

void reset_nfm_pipeline(NfmPipelineState* state) {
    state->prev_i = 0.0f;
    state->prev_q = 0.0f;
    state->has_prev = false;
    state->disc_i_lp = 1.0f;
    state->disc_q_lp = 0.0f;
}

float demodulate_nfm(
    NfmPipelineState* state,
    float filtered_i,
    float filtered_q,
    float* carrier_presence_level) {
    const float magnitude = std::sqrt(filtered_i * filtered_i + filtered_q * filtered_q);
    *carrier_presence_level += (magnitude - *carrier_presence_level) * 0.020f;
    *carrier_presence_level = std::max(*carrier_presence_level, 1.0f);
    if (!state->has_prev) {
        state->prev_i = filtered_i;
        state->prev_q = filtered_q;
        state->has_prev = true;
        return 0.0f;
    }

    const float dot = state->prev_i * filtered_i + state->prev_q * filtered_q;
    const float cross = state->prev_i * filtered_q - state->prev_q * filtered_i;
    const float norm = std::max(
        std::sqrt(state->prev_i * state->prev_i + state->prev_q * state->prev_q) * magnitude,
        1e-6f);
    state->disc_i_lp += ((dot / norm) - state->disc_i_lp) * kNfmDiscriminatorAlpha;
    state->disc_q_lp += ((cross / norm) - state->disc_q_lp) * kNfmDiscriminatorAlpha;
    state->prev_i = filtered_i;
    state->prev_q = filtered_q;
    return std::atan2(state->disc_q_lp, state->disc_i_lp) * 14000.0f;
}
