// SPDX-FileCopyrightText: 2026 Rafal Czeczotka
// SPDX-License-Identifier: MIT

package org.opensdr.radioapi

/**
 * Stable Android IPC contract between client applications and OpenSDR Radio Host.
 *
 * This object defines:
 * - how a client binds to the host service
 * - message ids sent over Messenger IPC
 * - Bundle keys used by those messages
 *
 * Keep this additive when possible so older clients and hosts do not break
 * unnecessarily during iteration.
 */
object RtlSdrIpcContract {
    // The installed application ID is independent of the implementation's namespace.
    const val SERVICE_PACKAGE = "dev.rafaldot.opensdr"
    const val SERVICE_CLASS_NAME = "org.opensdr.amhost.AmHostService"
    const val MAIN_ACTIVITY_CLASS_NAME = "org.opensdr.amhost.MainActivity"
    const val SERVICE_ACTION = "org.opensdr.radioapi.action.BIND_AM_HOST"
    const val BIND_PERMISSION = "org.opensdr.radioapi.permission.BIND_AM_HOST"

    /** Request the latest full [AmHostStatus] snapshot. */
    const val MSG_GET_STATUS = 1
    /** Ask the host to trigger Android USB permission flow for the selected RTL-SDR device. */
    const val MSG_REQUEST_USB_PERMISSION = 2
    /** Open/configure the SDR transport session. */
    const val MSG_CONNECT = 3
    /** Stop audio if needed and release the SDR session. */
    const val MSG_DISCONNECT = 4
    /** Start receiver streaming and playback. */
    const val MSG_START_AUDIO = 5
    /** Stop receiver streaming and playback while keeping the session available. */
    const val MSG_STOP_AUDIO = 6
    /** Retune the current session to [KEY_FREQUENCY_HZ]. */
    const val MSG_TUNE = 7
    /** Push a [HostReceiverSettings] payload under [KEY_SETTINGS]. */
    const val MSG_UPDATE_SETTINGS = 8
    /** Select an AM profile by [KEY_PROFILE_ID]. */
    const val MSG_SELECT_AM_PROFILE = 9
    /** Clear host-side trace diagnostics. */
    const val MSG_CLEAR_TRACE_LOG = 10
    /**
     * Partial [HostReceiverSettings] update: only PPM, Bias-T, gain mode/value, squelch mode/level
     * and the optional AM speech filter. Use putHostReceiverSettings/readHostReceiverSettings
     * to include additive audio options without changing the Parcel wire layout.
     * Other fields are ignored. Merge occurs on the host controller thread, retaining its DSP state.
     * Requires a host implementing this command; clients must not fall back to a full settings write.
     */
    const val MSG_UPDATE_LISTENING_CONTROLS = 11
    /**
     * Partial controls: frequency correction (Hz), PPM, Bias-T, squelch and optional speech filter.
     * Gain and expert DSP settings are retained by the host. Payload uses [KEY_SETTINGS].
     * Check [KEY_CALIBRATED_LISTENING_CONTROLS_SUPPORTED]; never fall back to a full write.
     */
    const val MSG_UPDATE_CALIBRATED_LISTENING_CONTROLS = 12
    /** Host-to-client status response; use putHostStatus/readHostStatus for optional audio fields. */
    const val MSG_STATUS = 100

    const val KEY_STATUS = "status"
    /** Additive response capability; absent on hosts predating partial controls. */
    const val KEY_LISTENING_CONTROLS_SUPPORTED = "listening_controls_supported"
    /** Also guarantees support for calibration-preserving profile selection. */
    const val KEY_CALIBRATED_LISTENING_CONTROLS_SUPPORTED = "calibrated_listening_controls_supported"
    /** MainActivity extra: show diagnostics without applying settings or starting playback. */
    const val KEY_SHOW_DIAGNOSTICS = "show_diagnostics"
    const val KEY_FREQUENCY_HZ = "frequency_hz"
    const val KEY_AIRBAND_CHANNEL_DESIGNATOR = "airband_channel_designator"
    const val KEY_SETTINGS = "settings"
    /** Optional compare-before-write guard for full expert edits on the host controller thread. */
    const val KEY_EXPECTED_SETTINGS = "expected_settings"
    const val KEY_EXPECTED_PROFILE_ID = "expected_profile_id"
    const val KEY_PROFILE_ID = "profile_id"
    const val KEY_APPLY_PROFILE_DEFAULTS = "apply_profile_defaults"
    /** Optional profile-selection flag. False/absent retains the original profile default behavior. */
    const val KEY_PRESERVE_FREQUENCY_CORRECTION = "preserve_frequency_correction"
}
