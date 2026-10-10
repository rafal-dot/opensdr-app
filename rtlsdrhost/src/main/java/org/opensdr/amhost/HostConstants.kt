package org.opensdr.amhost

internal const val HOST_APP_DISPLAY_NAME = "OpenSDR Radio Host"
internal const val HOST_PREFS_NAME = "opensdr_radio_host_preferences"
internal const val PREF_WALKTHROUGH_SEEN = "walkthrough_seen_v1"
internal const val PREF_FREQUENCY_HZ = "frequency_hz"
internal const val PREF_FREQUENCY_HISTORY_HZ = "frequency_history_hz"
internal const val PREF_TUNING_STEP_HZ = "tuning_step_hz"
internal const val PREF_MODULATION_DEMOD_MODE = "modulation_demod_mode"
internal const val PREF_MODULATION_SIDEBAND_MODE = "modulation_sideband_mode"
internal const val PREF_WATERFALL_FLOOR_DBFS = "waterfall_floor_dbfs"
internal const val PREF_WATERFALL_CONTRAST_PERCENT = "waterfall_contrast_percent"
internal const val PREF_MANUAL_SQUELCH_LEVEL = "manual_squelch_level"
internal const val DEFAULT_MANUAL_SQUELCH_LEVEL = 20
internal const val DEFAULT_FREQUENCY_HZ = 118_000_000L
internal const val DEFAULT_TUNING_STEP_HZ = 8_333L
internal const val DEFAULT_WATERFALL_FLOOR_DBFS = -44f // 30% floor sensitivity.
internal const val DEFAULT_WATERFALL_CONTRAST_PERCENT = 80f
internal const val MAX_FREQUENCY_HISTORY_SIZE = 10
internal const val HOST_STATUS_POLL_IDLE_MS = 350L
internal const val HOST_STATUS_POLL_STREAMING_MS = 125L
internal const val HOST_SPECTRUM_AUDIO_OUTPUT_DELAY_MS = 220L
internal const val HOST_WATERFALL_ROW_MS = 125L
internal const val HOST_WATERFALL_MAX_GAP_MS = 500L
internal const val WATERFALL_ROW_COUNT = 160
internal const val WATERFALL_COLUMN_COUNT = 256
