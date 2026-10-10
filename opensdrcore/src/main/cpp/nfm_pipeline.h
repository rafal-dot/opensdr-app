#pragma once

struct NfmPipelineState {
    float prev_i = 0.0f;
    float prev_q = 0.0f;
    bool has_prev = false;
    float disc_i_lp = 1.0f;
    float disc_q_lp = 0.0f;
};

void reset_nfm_pipeline(NfmPipelineState* state);
float demodulate_nfm(
    NfmPipelineState* state,
    float filtered_i,
    float filtered_q,
    float* carrier_presence_level);
