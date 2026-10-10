#pragma once

struct WfmPipelineState {
    float prev_i = 0.0f;
    float prev_q = 0.0f;
    bool has_prev = false;
    float deemphasis = 0.0f;
};

void reset_wfm_pipeline(WfmPipelineState* state);
float demodulate_wfm(
    WfmPipelineState* state,
    int iq_sample_rate_hz,
    float filtered_i,
    float filtered_q,
    float* carrier_presence_level);
