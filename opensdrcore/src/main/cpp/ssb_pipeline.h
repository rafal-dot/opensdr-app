#pragma once

#include <cstddef>
#include <vector>

struct SsbPipelineState {
    std::vector<float> hilbert_taps;
    std::vector<float> i_history;
    std::vector<float> q_history;
    std::size_t write_index = 0;
};

void configure_ssb_pipeline(SsbPipelineState* state, bool voice_focus_enabled);
void reset_ssb_pipeline(SsbPipelineState* state);
float demodulate_ssb(
    SsbPipelineState* state,
    int sideband_mode,
    float filtered_i,
    float filtered_q,
    float* carrier_presence_level);
