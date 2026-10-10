#pragma once

float demodulate_am_envelope(
    float magnitude,
    float* carrier_level,
    float* carrier_presence_level);

float demodulate_am_synchronous(
    bool advanced_sync_enabled,
    float filtered_i,
    float filtered_q,
    float magnitude,
    float* carrier_level,
    float* carrier_presence_level,
    float* carrier_recovery_i_lp,
    float* carrier_recovery_q_lp,
    float* sync_lock_quality,
    float* sync_phase_error);
