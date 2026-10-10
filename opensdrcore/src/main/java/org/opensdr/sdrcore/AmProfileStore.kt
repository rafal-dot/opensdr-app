package org.opensdr.sdrcore

import android.content.Context
import android.util.Log
import org.json.JSONObject

data class DirectRtlAmProfileOption(
    val id: String,
    val label: String,
    val description: String,
)

/**
 * Low-level tuning knobs for the native AM pipeline.
 *
 * These values are loaded from profile JSON and validated before reaching JNI.
 * They intentionally mirror the native `AmPipelineConfig` fields that are safe
 * to expose as profile-level tuning, while keeping USB/session settings outside
 * the profile system.
 */
data class NativeAmTuning(
    val simpleAmEnabled: Boolean = false,
    val dcAlpha: Float = 0.0015f,
    val stage1FirTaps: Int = 81,
    val stage2FirTaps: Int = 129,
    val channelGuardHz: Float = 900.0f,
    val channelCutoffRatio: Float = 0.40f,
    val voiceAudioCutoffHz: Float = 2400.0f,
    val naturalAudioCutoffHz: Float = 2800.0f,
    val voiceAudioRatio: Float = 0.30f,
    val naturalAudioRatio: Float = 0.36f,
    val audioHighPassHz: Float = 300.0f,
    val squelchAttackAlpha: Float = 0.05f,
    val squelchReleaseAlpha: Float = 0.002f,
    val squelchHysteresisPercent: Float = 6.0f,
    val squelchOpenDebounceMs: Int = 120,
    val squelchCloseHangMs: Int = 700,
    val outputGain: Float = 1.0f,
    val targetAudioLevel: Float = 7000.0f,
    val softClipThreshold: Float = 30000.0f,
    val softClipDrive: Float = 8000.0f,
    val postLevelerTarget: Float = 9000.0f,
    val postLevelerMaxGain: Float = 1.8f,
    val postLevelerAttackAlpha: Float = 0.010f,
    val postLevelerReleaseAlpha: Float = 0.001f,
)

/** Receiver defaults that may be applied when a profile is selected. */
data class DirectRtlAmProfileDefaults(
    val preferredSampleRateHz: Int? = null,
    val amBandwidthHz: Int? = null,
    val audioBoostPercent: Int? = null,
    val voiceFocusEnabled: Boolean? = null,
    val postLevelerEnabled: Boolean? = null,
    val demodMode: Int? = null,
    val noiseBlankerEnabled: Boolean? = null,
    val denoiseMode: Int? = null,
    val notchMode: Int? = null,
    val sidebandMode: Int? = null,
    val advancedSyncEnabled: Boolean? = null,
    val qualitySquelchEnabled: Boolean? = null,
    val speechEnhancerMode: Int? = null,
    val frequencyCorrectionHz: Int? = null,
    val squelchOpenDebounceMs: Int? = null,
    val squelchCloseHangMs: Int? = null,
    val hardSquelchMuteEnabled: Boolean? = null,
)

/** Fully parsed AM profile: user-facing option, optional defaults, and DSP tuning. */
data class DirectRtlAmProfile(
    val option: DirectRtlAmProfileOption,
    val isDefault: Boolean,
    val defaults: DirectRtlAmProfileDefaults = DirectRtlAmProfileDefaults(),
    val tuning: NativeAmTuning = NativeAmTuning(),
) {
    fun applyDefaults(
        base: DirectRtlReceiverSettings,
        preserveFrequencyCorrection: Boolean = false,
    ): DirectRtlReceiverSettings {
        return base.copy(
            frequencyCorrectionHz = if (preserveFrequencyCorrection) base.frequencyCorrectionHz
                else defaults.frequencyCorrectionHz ?: base.frequencyCorrectionHz,
            squelchOpenDebounceMs = defaults.squelchOpenDebounceMs ?: base.squelchOpenDebounceMs,
            squelchCloseHangMs = defaults.squelchCloseHangMs ?: base.squelchCloseHangMs,
            hardSquelchMuteEnabled = defaults.hardSquelchMuteEnabled ?: base.hardSquelchMuteEnabled,
            preferredSampleRateHz = defaults.preferredSampleRateHz ?: base.preferredSampleRateHz,
            amBandwidthHz = defaults.amBandwidthHz ?: base.amBandwidthHz,
            audioBoostPercent = defaults.audioBoostPercent ?: base.audioBoostPercent,
            voiceFocusEnabled = defaults.voiceFocusEnabled ?: base.voiceFocusEnabled,
            postLevelerEnabled = defaults.postLevelerEnabled ?: base.postLevelerEnabled,
            demodMode = defaults.demodMode ?: base.demodMode,
            noiseBlankerEnabled = defaults.noiseBlankerEnabled ?: base.noiseBlankerEnabled,
            denoiseMode = defaults.denoiseMode ?: base.denoiseMode,
            notchMode = defaults.notchMode ?: base.notchMode,
            sidebandMode = defaults.sidebandMode ?: base.sidebandMode,
            advancedSyncEnabled = defaults.advancedSyncEnabled ?: base.advancedSyncEnabled,
            qualitySquelchEnabled = defaults.qualitySquelchEnabled ?: base.qualitySquelchEnabled,
            speechEnhancerMode = defaults.speechEnhancerMode ?: base.speechEnhancerMode,
        ).let(::effectiveSettings)
    }

    /** One policy for restored settings, IPC edits, visible status and native configuration. */
    fun effectiveSettings(base: DirectRtlReceiverSettings): DirectRtlReceiverSettings {
        if (!tuning.simpleAmEnabled ||
            base.demodMode !in setOf(DEMOD_MODE_AM_ENVELOPE, DEMOD_MODE_AM_SYNC)
        ) return base
        return base.copy(
            demodMode = DEMOD_MODE_AM_ENVELOPE,
            audioBoostPercent = 100,
            voiceFocusEnabled = false,
            postLevelerEnabled = false,
            noiseBlankerEnabled = false,
            denoiseMode = DENOISE_MODE_OFF,
            notchMode = NOTCH_MODE_OFF,
            sidebandMode = AM_SIDEBAND_MODE_FULL,
            advancedSyncEnabled = false,
            qualitySquelchEnabled = false,
            speechEnhancerMode = SPEECH_ENHANCER_MODE_OFF,
        )
    }
}

/**
 * Loads and validates AM profile JSON assets.
 *
 * Invalid or duplicated profiles are skipped instead of crashing the host. This
 * is deliberate: profiles are configuration data, and a bad optional profile
 * should not prevent the SDR service from starting with the built-in fallback.
 */
class AmProfileStore(context: Context) {
    private val profiles: List<DirectRtlAmProfile> = loadProfiles(context).ifEmpty { listOf(fallbackProfile()) }
    val defaultProfile: DirectRtlAmProfile = profiles.firstOrNull { it.isDefault } ?: profiles.first()
    val options: List<DirectRtlAmProfileOption> = profiles.map { it.option }

    fun profileOrDefault(profileId: String?): DirectRtlAmProfile {
        return profiles.firstOrNull { it.option.id == profileId } ?: defaultProfile
    }

    private fun loadProfiles(context: Context): List<DirectRtlAmProfile> {
        val assetNames = context.assets.list(PROFILE_DIR)
            ?.filter { it.endsWith(".json", ignoreCase = true) }
            ?.sorted()
            .orEmpty()
        val seenProfileIds = mutableSetOf<String>()
        return assetNames.mapNotNull { assetName ->
            val profile = runCatching {
                context.assets.open("$PROFILE_DIR/$assetName").bufferedReader().use { reader ->
                    parseProfile(JSONObject(reader.readText()), assetName)
                }
            }.onFailure { error ->
                Log.w(TAG, "Skipping AM profile asset $assetName: ${error.message}", error)
            }.getOrNull() ?: return@mapNotNull null
            when {
                !validateProfile(profile, assetName) -> null
                !seenProfileIds.add(profile.option.id) -> {
                    Log.w(TAG, "Skipping duplicated AM profile id '${profile.option.id}' from $assetName")
                    null
                }
                else -> profile
            }
        }
    }

    private fun parseProfile(json: JSONObject, assetName: String): DirectRtlAmProfile {
        val defaultsJson = json.optJSONObject("defaults")
        val tuningJson = json.optJSONObject("pipeline")
        val id = json.optString("id").ifBlank { assetName.removeSuffix(".json") }
        val label = json.optString("label").ifBlank { id.replace('_', ' ') }
        val description = json.optString("description")
        return DirectRtlAmProfile(
            option = DirectRtlAmProfileOption(
                id = id,
                label = label,
                description = description,
            ),
            isDefault = json.optBoolean("default", false),
            defaults = DirectRtlAmProfileDefaults(
                frequencyCorrectionHz = defaultsJson?.optIntOrNull("frequencyCorrectionHz"),
                squelchOpenDebounceMs = defaultsJson?.optIntOrNull("squelchOpenDebounceMs"),
                squelchCloseHangMs = defaultsJson?.optIntOrNull("squelchCloseHangMs"),
                hardSquelchMuteEnabled = defaultsJson?.optBooleanOrNull("hardSquelchMuteEnabled"),
                preferredSampleRateHz = defaultsJson?.optIntOrNull("preferredSampleRateHz"),
                amBandwidthHz = defaultsJson?.optIntOrNull("amBandwidthHz"),
                audioBoostPercent = defaultsJson?.optIntOrNull("audioBoostPercent"),
                voiceFocusEnabled = defaultsJson?.optBooleanOrNull("voiceFocusEnabled"),
                postLevelerEnabled = defaultsJson?.optBooleanOrNull("postLevelerEnabled"),
                demodMode = defaultsJson?.optIntOrNull("demodMode"),
                noiseBlankerEnabled = defaultsJson?.optBooleanOrNull("noiseBlankerEnabled"),
                denoiseMode = defaultsJson?.optIntOrNull("denoiseMode"),
                notchMode = defaultsJson?.optIntOrNull("notchMode"),
                sidebandMode = defaultsJson?.optIntOrNull("sidebandMode"),
                advancedSyncEnabled = defaultsJson?.optBooleanOrNull("advancedSyncEnabled"),
                qualitySquelchEnabled = defaultsJson?.optBooleanOrNull("qualitySquelchEnabled"),
                speechEnhancerMode = defaultsJson?.optIntOrNull("speechEnhancerMode"),
            ),
            tuning = NativeAmTuning(
                simpleAmEnabled = tuningJson?.optBoolean("simpleAmEnabled", false) ?: false,
                dcAlpha = tuningJson?.optFloatOrDefault("dcAlpha", 0.0015f) ?: 0.0015f,
                stage1FirTaps = tuningJson?.optIntOrDefault("stage1FirTaps", 81) ?: 81,
                stage2FirTaps = tuningJson?.optIntOrDefault("stage2FirTaps", 129) ?: 129,
                channelGuardHz = tuningJson?.optFloatOrDefault("channelGuardHz", 900.0f) ?: 900.0f,
                channelCutoffRatio = tuningJson?.optFloatOrDefault("channelCutoffRatio", 0.40f) ?: 0.40f,
                voiceAudioCutoffHz = tuningJson?.optFloatOrDefault("voiceAudioCutoffHz", 2400.0f) ?: 2400.0f,
                naturalAudioCutoffHz = tuningJson?.optFloatOrDefault("naturalAudioCutoffHz", 2800.0f) ?: 2800.0f,
                voiceAudioRatio = tuningJson?.optFloatOrDefault("voiceAudioRatio", 0.30f) ?: 0.30f,
                naturalAudioRatio = tuningJson?.optFloatOrDefault("naturalAudioRatio", 0.36f) ?: 0.36f,
                audioHighPassHz = tuningJson?.optFloatOrDefault("audioHighPassHz", 300.0f) ?: 300.0f,
                squelchAttackAlpha = tuningJson?.optFloatOrDefault("squelchAttackAlpha", 0.05f) ?: 0.05f,
                squelchReleaseAlpha = tuningJson?.optFloatOrDefault("squelchReleaseAlpha", 0.002f) ?: 0.002f,
                squelchHysteresisPercent = tuningJson?.optFloatOrDefault("squelchHysteresisPercent", 6.0f) ?: 6.0f,
                squelchOpenDebounceMs = tuningJson?.optIntOrDefault("squelchOpenDebounceMs", 120) ?: 120,
                squelchCloseHangMs = tuningJson?.optIntOrDefault("squelchCloseHangMs", 700) ?: 700,
                outputGain = tuningJson?.optFloatOrDefault("outputGain", 1.0f) ?: 1.0f,
                targetAudioLevel = tuningJson?.optFloatOrDefault("targetAudioLevel", 7000.0f) ?: 7000.0f,
                softClipThreshold = tuningJson?.optFloatOrDefault("softClipThreshold", 30000.0f) ?: 30000.0f,
                softClipDrive = tuningJson?.optFloatOrDefault("softClipDrive", 8000.0f) ?: 8000.0f,
                postLevelerTarget = tuningJson?.optFloatOrDefault("postLevelerTarget", 9000.0f) ?: 9000.0f,
                postLevelerMaxGain = tuningJson?.optFloatOrDefault("postLevelerMaxGain", 1.8f) ?: 1.8f,
                postLevelerAttackAlpha = tuningJson?.optFloatOrDefault("postLevelerAttackAlpha", 0.010f) ?: 0.010f,
                postLevelerReleaseAlpha = tuningJson?.optFloatOrDefault("postLevelerReleaseAlpha", 0.001f) ?: 0.001f,
            ),
        )
    }

    private fun fallbackProfile(): DirectRtlAmProfile {
        return DirectRtlAmProfile(
            option = DirectRtlAmProfileOption(
                id = "default",
                label = "Default",
                description = "Built-in fallback profile",
            ),
            isDefault = true,
        )
    }

    private fun validateProfile(profile: DirectRtlAmProfile, assetName: String): Boolean {
        val defaults = profile.defaults
        val tuning = profile.tuning
        return validateIntRange(assetName, "frequencyCorrectionHz", defaults.frequencyCorrectionHz,
            org.opensdr.radioapi.ReceiverSettingRules.MIN_CORRECTION_HZ,
            org.opensdr.radioapi.ReceiverSettingRules.MAX_CORRECTION_HZ) &&
            validateIntRange(assetName, "squelchOpenDebounceMs", defaults.squelchOpenDebounceMs, 0, 500) &&
            validateIntRange(assetName, "squelchCloseHangMs", defaults.squelchCloseHangMs, 0, 1000) &&
            validateInt(assetName, "preferredSampleRateHz", defaults.preferredSampleRateHz, setOf(240_000, 480_000, 960_000)) &&
            validateIntRange(assetName, "amBandwidthHz", defaults.amBandwidthHz, 6_000, 20_000) &&
            validateIntRange(assetName, "audioBoostPercent", defaults.audioBoostPercent, 100, 320) &&
            validateIntRange(assetName, "demodMode", defaults.demodMode, DEMOD_MODE_AM_ENVELOPE, DEMOD_MODE_WFM) &&
            validateIntRange(assetName, "denoiseMode", defaults.denoiseMode, DENOISE_MODE_OFF, DENOISE_MODE_AUTO) &&
            validateIntRange(assetName, "notchMode", defaults.notchMode, NOTCH_MODE_OFF, NOTCH_MODE_AUTO) &&
            validateIntRange(assetName, "sidebandMode", defaults.sidebandMode, AM_SIDEBAND_MODE_FULL, AM_SIDEBAND_MODE_UPPER) &&
            validateIntRange(assetName, "speechEnhancerMode", defaults.speechEnhancerMode, SPEECH_ENHANCER_MODE_OFF, SPEECH_ENHANCER_MODE_STRONG) &&
            validateFloatRange(assetName, "dcAlpha", tuning.dcAlpha, 0.0001f, 0.05f) &&
            validateIntRange(assetName, "stage1FirTaps", tuning.stage1FirTaps, 17, 257) &&
            validateIntRange(assetName, "stage2FirTaps", tuning.stage2FirTaps, 17, 257) &&
            validateFloatRange(assetName, "channelGuardHz", tuning.channelGuardHz, 100.0f, 2000.0f) &&
            validateFloatRange(assetName, "channelCutoffRatio", tuning.channelCutoffRatio, 0.20f, 0.48f) &&
            validateFloatRange(assetName, "voiceAudioCutoffHz", tuning.voiceAudioCutoffHz, 1500.0f, 3200.0f) &&
            validateFloatRange(assetName, "naturalAudioCutoffHz", tuning.naturalAudioCutoffHz, 1800.0f, 3600.0f) &&
            validateFloatRange(assetName, "voiceAudioRatio", tuning.voiceAudioRatio, 0.20f, 0.45f) &&
            validateFloatRange(assetName, "naturalAudioRatio", tuning.naturalAudioRatio, 0.25f, 0.48f) &&
            validateFloatRange(assetName, "audioHighPassHz", tuning.audioHighPassHz, 80.0f, 800.0f) &&
            validateFloatRange(assetName, "squelchAttackAlpha", tuning.squelchAttackAlpha, 0.005f, 0.30f) &&
            validateFloatRange(assetName, "squelchReleaseAlpha", tuning.squelchReleaseAlpha, 0.0005f, 0.05f) &&
            validateFloatRange(assetName, "squelchHysteresisPercent", tuning.squelchHysteresisPercent, 1.0f, 20.0f) &&
            validateIntRange(assetName, "squelchOpenDebounceMs", tuning.squelchOpenDebounceMs, 0, 500) &&
            validateIntRange(assetName, "squelchCloseHangMs", tuning.squelchCloseHangMs, 0, 1000) &&
            validateFloatRange(assetName, "outputGain", tuning.outputGain, 0.25f, 4.0f) &&
            validateFloatRange(assetName, "targetAudioLevel", tuning.targetAudioLevel, 1000.0f, 20000.0f) &&
            validateFloatRange(assetName, "softClipThreshold", tuning.softClipThreshold, 12000.0f, 32000.0f) &&
            validateFloatRange(assetName, "softClipDrive", tuning.softClipDrive, 1000.0f, 12000.0f) &&
            validateFloatRange(assetName, "postLevelerTarget", tuning.postLevelerTarget, 1000.0f, 20000.0f) &&
            validateFloatRange(assetName, "postLevelerMaxGain", tuning.postLevelerMaxGain, 1.0f, 4.0f) &&
            validateFloatRange(assetName, "postLevelerAttackAlpha", tuning.postLevelerAttackAlpha, 0.001f, 0.10f) &&
            validateFloatRange(assetName, "postLevelerReleaseAlpha", tuning.postLevelerReleaseAlpha, 0.0001f, 0.05f)
    }

    private fun validateInt(assetName: String, fieldName: String, value: Int?, allowedValues: Set<Int>): Boolean {
        if (value == null || value in allowedValues) return true
        Log.w(TAG, "Skipping AM profile asset $assetName: field '$fieldName' has unsupported value $value")
        return false
    }

    private fun validateIntRange(assetName: String, fieldName: String, value: Int?, minValue: Int, maxValue: Int): Boolean {
        if (value == null || value in minValue..maxValue) return true
        Log.w(TAG, "Skipping AM profile asset $assetName: field '$fieldName' is out of range ($value not in [$minValue, $maxValue])")
        return false
    }

    private fun validateFloatRange(assetName: String, fieldName: String, value: Float, minValue: Float, maxValue: Float): Boolean {
        if (value in minValue..maxValue) return true
        Log.w(TAG, "Skipping AM profile asset $assetName: field '$fieldName' is out of range ($value not in [$minValue, $maxValue])")
        return false
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optIntOrDefault(name: String, defaultValue: Int): Int {
        return if (has(name) && !isNull(name)) optInt(name) else defaultValue
    }

    private fun JSONObject.optFloatOrDefault(name: String, defaultValue: Float): Float {
        return if (has(name) && !isNull(name)) optDouble(name, defaultValue.toDouble()).toFloat() else defaultValue
    }

    private fun JSONObject.optBooleanOrNull(name: String): Boolean? {
        return if (has(name) && !isNull(name)) optBoolean(name) else null
    }

    private companion object {
        const val PROFILE_DIR = "am_profiles"
        const val TAG = "AmProfileStore"
    }
}
