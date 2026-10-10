#pragma once

#include "am_pipeline.h"

// Mirrors low_cpu_clean_voice.json; only squelch is disabled for audio measurements.
inline AmPipelineConfig clean_voice() {
    AmPipelineConfig c;
    c.simple_am_enabled = true;
    c.channel_bandwidth_hz = 10000;
    c.squelch_mode = 1;
    c.squelch_level = 0;
    c.noise_blanker_enabled = false;
    c.audio_boost_percent = 130;
    c.stage1_fir_taps = 97;
    c.stage2_fir_taps = 145;
    c.channel_guard_hz = 750;
    c.channel_cutoff_ratio = .38f;
    c.voice_audio_cutoff_hz = 2150;
    c.natural_audio_cutoff_hz = 2450;
    c.voice_audio_ratio = .26f;
    c.natural_audio_ratio = .33f;
    c.audio_high_pass_hz = 360;
    c.post_leveler_target = 8400;
    c.post_leveler_max_gain = 1.45f;
    return c;
}
