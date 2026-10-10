// SPDX-FileCopyrightText: 2026 Rafal Czeczotka
// SPDX-License-Identifier: MIT

package org.opensdr.radioapi

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.IgnoredOnParcel

@Parcelize
/** Lightweight description of a built-in AM profile exposed by the host. */
data class HostAmProfileOption(
    val id: String,
    val label: String,
    val description: String,
) : Parcelable

@Parcelize
/**
 * Desired receiver configuration sent from client to host.
 *
 * This is an intent/config payload, not a guarantee that every field was
 * applied exactly as requested on the current device/backend.
 */
data class HostReceiverSettings(
    val frequencyCorrectionHz: Int = 0,
    val ppmCorrection: Int = 0,
    val biasTeeEnabled: Boolean = false,
    val preferredSampleRateHz: Int = 240_000,
    val amBandwidthHz: Int = 12_000,
    val squelchMode: Int = 0,
    val squelchLevel: Int = 0,
    val squelchOpenDebounceMs: Int = 120,
    val squelchCloseHangMs: Int = 700,
    val hardSquelchMuteEnabled: Boolean = true,
    val gainMode: Int = 0,
    val tunerGain: Int = 0,
    val audioBoostPercent: Int = 100,
    val voiceFocusEnabled: Boolean = true,
    val postLevelerEnabled: Boolean = true,
    val noiseBlankerEnabled: Boolean = false,
    val denoiseMode: Int = 0,
    val demodMode: Int = 1,
    val notchMode: Int = 0,
    val sidebandMode: Int = 0,
    val advancedSyncEnabled: Boolean = false,
    val qualitySquelchEnabled: Boolean = false,
    val speechEnhancerMode: Int = 0,
    // Transported as an optional Bundle key, preserving the existing Parcel layout.
    @IgnoredOnParcel val amSpeechFilterEnabled: Boolean? = null,
) : Parcelable

@Parcelize
/** USB device metadata visible to the host and surfaced to clients. */
data class HostDeviceInfo(
    val deviceName: String,
    val productName: String,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val interfaceCount: Int = 0,
    val hasPermission: Boolean,
) : Parcelable

@Parcelize
/**
 * Full host snapshot returned to clients.
 *
 * This object intentionally contains several categories of data:
 * - device/session state
 * - currently applied receiver settings
 * - live runtime metrics
 * - diagnostics text
 *
 * Clients should treat it as the latest observed host state rather than as a
 * narrow command result type.
 */
data class AmHostStatus(
    val supportedDevices: List<HostDeviceInfo> = emptyList(),
    val availableAmProfiles: List<HostAmProfileOption> = emptyList(),
    val selectedDeviceName: String? = null,
    val selectedDeviceLabel: String? = null,
    val usbPermissionGranted: Boolean = false,
    val nativeBridgeReady: Boolean = false,
    val connected: Boolean = false,
    val sessionConfigured: Boolean = false,
    val streaming: Boolean = false,
    val claimedInterfaceIndex: Int? = null,
    val bulkInEndpointAddress: Int? = null,
    val sampleRateHz: Int? = null,
    val tunedFrequencyHz: Long? = null,
    val tunedCenterFrequencyHz: Long? = null,
    val frequencyCorrectionHz: Int = 0,
    val ppmCorrection: Int = 0,
    val biasTeeEnabled: Boolean = false,
    val preferredSampleRateHz: Int = 240_000,
    val amBandwidthHz: Int = 12_000,
    val squelchMode: Int = 0,
    val squelchLevel: Int = 0,
    val squelchOpenDebounceMs: Int = 120,
    val squelchCloseHangMs: Int = 700,
    val hardSquelchMuteEnabled: Boolean = true,
    val gainMode: Int = 0,
    val tunerGain: Int = 0,
    val audioBoostPercent: Int = 100,
    val voiceFocusEnabled: Boolean = true,
    val postLevelerEnabled: Boolean = true,
    val noiseBlankerEnabled: Boolean = false,
    val denoiseMode: Int = 0,
    val demodMode: Int = 1,
    val notchMode: Int = 0,
    val sidebandMode: Int = 0,
    val advancedSyncEnabled: Boolean = false,
    val qualitySquelchEnabled: Boolean = false,
    val speechEnhancerMode: Int = 0,
    // Transported as an optional Bundle key, preserving the existing Parcel layout.
    @IgnoredOnParcel val amSpeechFilterEnabled: Boolean? = null,
    val selectedProfileId: String? = null,
    val selectedProfileLabel: String? = null,
    val receiverSettingsError: String? = null,
    val rfLevelPercent: Int = 0,
    val audioLevelPercent: Int = 0,
    val clipPercent: Int = 0,
    val iqClipPercent: Int = 0,
    val estimatedCarrierOffsetHz: Int = 0,
    val squelchPercent: Int = 0,
    val squelchNoiseFloorPercent: Int = 0,
    val squelchThresholdPercent: Int = 0,
    val squelchEffectiveThresholdPercent: Int = 0,
    val squelchOpen: Boolean = true,
    val audioBufferSamples: Int = 0,
    val pcmProducedSamplesPerSecond: Int = 0,
    val pcmConsumedSamplesPerSecond: Int = 0,
    val pcmDriftSamplesPerSecond: Int = 0,
    val driftSnapshotCounter: Int = 0,
    val driftSnapshotText: String = "",
    /** Fixed FFT levels: 0 represents -100 dBFS and 100 represents 0 dBFS. */
    val spectrumBins: IntArray = intArrayOf(),
    val traceLogText: String = "",
    val statusText: String = "",
) : Parcelable
