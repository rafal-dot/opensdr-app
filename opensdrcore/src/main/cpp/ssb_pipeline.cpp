#include "ssb_pipeline.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kEnvelopeMagnitudeScale = 120.0f;

void design_hilbert_fir(std::vector<float>* taps, int tap_count) {
    if (tap_count % 2 == 0) {
        tap_count += 1;
    }
    taps->assign(static_cast<std::size_t>(tap_count), 0.0f);
    const int center = tap_count / 2;

    for (int n = 0; n < tap_count; ++n) {
        const int m = n - center;
        float tap = 0.0f;
        if (m != 0 && (m % 2 != 0)) {
            tap = 2.0f / (kPi * static_cast<float>(m));
        }
        const float phase = (2.0f * kPi * static_cast<float>(n)) / static_cast<float>(tap_count - 1);
        const float window = 0.42f - 0.50f * std::cos(phase) + 0.08f * std::cos(2.0f * phase);
        (*taps)[static_cast<std::size_t>(n)] = tap * window;
    }
}
}

void configure_ssb_pipeline(SsbPipelineState* state, bool voice_focus_enabled) {
    design_hilbert_fir(&state->hilbert_taps, voice_focus_enabled ? 63 : 47);
    state->i_history.assign(state->hilbert_taps.size(), 0.0f);
    state->q_history.assign(state->hilbert_taps.size(), 0.0f);
    state->write_index = 0;
}

void reset_ssb_pipeline(SsbPipelineState* state) {
    std::fill(state->i_history.begin(), state->i_history.end(), 0.0f);
    std::fill(state->q_history.begin(), state->q_history.end(), 0.0f);
    state->write_index = 0;
}

float demodulate_ssb(
    SsbPipelineState* state,
    int sideband_mode,
    float filtered_i,
    float filtered_q,
    float* carrier_presence_level) {
    if (state->hilbert_taps.empty()) {
        return filtered_i * kEnvelopeMagnitudeScale;
    }
    state->i_history[state->write_index] = filtered_i;
    state->q_history[state->write_index] = filtered_q;
    state->write_index = (state->write_index + 1) % state->hilbert_taps.size();

    const int center = static_cast<int>(state->hilbert_taps.size() / 2);
    const std::size_t delayed_index =
        (state->write_index + state->hilbert_taps.size() - 1 - static_cast<std::size_t>(center)) % state->hilbert_taps.size();
    const float delayed_i = state->i_history[delayed_index];

    float hilbert_q = 0.0f;
    std::size_t history_index = state->write_index;
    for (std::size_t tap_index = 0; tap_index < state->hilbert_taps.size(); ++tap_index) {
        history_index = (history_index == 0) ? state->hilbert_taps.size() - 1 : history_index - 1;
        hilbert_q += state->q_history[history_index] * state->hilbert_taps[tap_index];
    }

    const float product = sideband_mode == 1
        ? (delayed_i + hilbert_q)
        : (delayed_i - hilbert_q);
    *carrier_presence_level += (std::fabs(product) - *carrier_presence_level) * 0.010f;
    *carrier_presence_level = std::max(*carrier_presence_level, 1.0f);
    return product * kEnvelopeMagnitudeScale;
}
