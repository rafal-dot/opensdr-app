package org.opensdr.sdrcore

/**
 * Complete native DSP configuration passed across the JNI boundary.
 *
 * Keep this model aligned with `AmPipelineConfig` in `am_pipeline.h` and with
 * the argument order used by `nativeCreateAmPipeline` / `nativeUpdateAmPipeline`.
 * The explicit field list is verbose, but it avoids opaque maps on the hot path
 * and keeps profile parsing separated from native execution.
 */
data class NativeAmConfig(
    val inputSampleRateHz: Int,
    val outputSampleRateHz: Int,
    val channelBandwidthHz: Int,
    val squelchMode: Int,
    val squelchLevel: Int,
    val hardSquelchMuteEnabled: Boolean,
    val audioBoostPercent: Int,
    val voiceFocusEnabled: Boolean,
    val postLevelerEnabled: Boolean,
    val noiseBlankerEnabled: Boolean,
    val denoiseMode: Int,
    val demodMode: Int,
    val notchMode: Int,
    val sidebandMode: Int,
    val advancedSyncEnabled: Boolean,
    val qualitySquelchEnabled: Boolean,
    val speechEnhancerMode: Int,
    val amSpeechFilterEnabled: Boolean,
    val tuning: NativeAmTuning = NativeAmTuning(),
    val carrierTargets: AmCarrierTargets? = null,
)

/**
 * Snapshot of native stream counters and receiver metrics.
 *
 * Native code returns this as a compact primitive array for JNI efficiency.
 * The Kotlin bridge maps array positions into named fields here so callers do
 * not depend on raw index semantics.
 */
data class NativeAmStreamMetrics(
    val bufferedSamples: Int,
    val totalProducedSamples: Long,
    val totalConsumedSamples: Long,
    val totalUsbBytes: Long,
    val totalUsbBlocks: Long,
    val totalUsbFailures: Long,
    val totalDroppedSamples: Long,
    val rfLevelPercent: Int,
    val audioLevelPercent: Int,
    val clipPercent: Int,
    val iqClipPercent: Int,
    val estimatedCarrierOffsetHz: Int,
    val squelchPercent: Int,
    val squelchNoiseFloorPercent: Int,
    val squelchThresholdPercent: Int,
    val squelchEffectiveThresholdPercent: Int,
    val squelchOpen: Boolean,
    val running: Boolean = false,
)

/**
 * Thin Kotlin facade over `libopensdr_rtlsdr`.
 *
 * This object intentionally owns only JNI loading and typed argument mapping.
 * USB policy, session lifecycle, user-visible state, and AudioTrack ownership
 * stay in [DirectRtlSdrController]. Native state is process-global, so callers
 * must treat this bridge as a single active receiver backend.
 */
object DirectRtlSdrNativeBridge {
    private val loadSucceeded: Boolean by lazy {
        runCatching {
            System.loadLibrary("opensdr_rtlsdr")
            true
        }.getOrElse { false }
    }

    val isAvailable: Boolean
        get() = loadSucceeded && nativeIsReady()

    val versionLabel: String
        get() = if (!loadSucceeded) {
            "native library not loaded"
        } else {
            nativeVersionLabel()
        }

    val lastError: String?
        get() = if (!loadSucceeded) {
            "native library not loaded"
        } else {
            nativeLastError()?.takeIf { it.isNotBlank() }
        }

    fun open(deviceFd: Int, vendorId: Int, productId: Int): Boolean {
        return loadSucceeded && nativeOpen(deviceFd, vendorId, productId)
    }

    fun configureSession(
        centerFrequencyHz: Long,
        sampleRateHz: Int,
        gainMode: Int,
        tunerGain: Int,
        agcMode: Boolean,
        offsetTuning: Boolean,
        ppmCorrection: Int,
        biasTeeEnabled: Boolean,
        tunerBandwidthHz: Int,
    ): Boolean {
        return loadSucceeded && nativeConfigureSession(
            centerFrequencyHz,
            sampleRateHz,
            gainMode,
            tunerGain,
            agcMode,
            offsetTuning,
            ppmCorrection,
            biasTeeEnabled,
            tunerBandwidthHz,
        )
    }

    fun applySettings(
        ppmCorrection: Int,
        biasTeeEnabled: Boolean,
        tunerBandwidthHz: Int,
        gainMode: Int,
        tunerGain: Int,
        agcMode: Boolean,
    ): Boolean {
        return loadSucceeded && nativeApplySettings(
            ppmCorrection,
            biasTeeEnabled,
            tunerBandwidthHz,
            gainMode,
            tunerGain,
            agcMode,
        )
    }

    fun close() {
        if (loadSucceeded) nativeClose()
    }

    fun tuneHz(frequencyHz: Long, carrierTargets: AmCarrierTargets? = null): Boolean {
        return loadSucceeded && nativeTuneHz(
            frequencyHz,
            carrierTargets != null,
            carrierTargets?.primaryOffsetHz ?: 0f,
            carrierTargets?.alternateOffsetHz ?: 0f,
        )
    }

    fun createAmPipeline(config: NativeAmConfig): Long {
        if (!loadSucceeded) return 0L
        // Keep this argument order exactly synchronized with the native JNI
        // declaration. A wrong position still type-checks but changes DSP behavior.
        return nativeCreateAmPipeline(
            config.inputSampleRateHz,
            config.outputSampleRateHz,
            config.channelBandwidthHz,
            config.squelchMode,
            config.squelchLevel,
            config.hardSquelchMuteEnabled,
            config.audioBoostPercent,
            config.voiceFocusEnabled,
            config.postLevelerEnabled,
            config.noiseBlankerEnabled,
            config.denoiseMode,
            config.demodMode,
            config.notchMode,
            config.sidebandMode,
            config.advancedSyncEnabled,
            config.qualitySquelchEnabled,
            config.speechEnhancerMode,
            config.amSpeechFilterEnabled,
            config.tuning.simpleAmEnabled,
            config.tuning.dcAlpha,
            config.tuning.stage1FirTaps,
            config.tuning.stage2FirTaps,
            config.tuning.channelGuardHz,
            config.tuning.channelCutoffRatio,
            config.tuning.voiceAudioCutoffHz,
            config.tuning.naturalAudioCutoffHz,
            config.tuning.voiceAudioRatio,
            config.tuning.naturalAudioRatio,
            config.tuning.audioHighPassHz,
            config.tuning.squelchAttackAlpha,
            config.tuning.squelchReleaseAlpha,
            config.tuning.squelchHysteresisPercent,
            config.tuning.squelchOpenDebounceMs,
            config.tuning.squelchCloseHangMs,
            config.tuning.outputGain,
            config.tuning.targetAudioLevel,
            config.tuning.softClipThreshold,
            config.tuning.softClipDrive,
            config.tuning.postLevelerTarget,
            config.tuning.postLevelerMaxGain,
            config.tuning.postLevelerAttackAlpha,
            config.tuning.postLevelerReleaseAlpha,
            config.carrierTargets != null,
            config.carrierTargets?.primaryOffsetHz ?: 0f,
            config.carrierTargets?.alternateOffsetHz ?: 0f,
        )
    }

    fun updateAmPipeline(handle: Long, config: NativeAmConfig): Boolean {
        if (!loadSucceeded || handle == 0L) return false
        // Updating preserves native filter/state where possible; the native
        // pipeline decides when a full reset is required.
        return nativeUpdateAmPipeline(
            handle,
            config.inputSampleRateHz,
            config.outputSampleRateHz,
            config.channelBandwidthHz,
            config.squelchMode,
            config.squelchLevel,
            config.hardSquelchMuteEnabled,
            config.audioBoostPercent,
            config.voiceFocusEnabled,
            config.postLevelerEnabled,
            config.noiseBlankerEnabled,
            config.denoiseMode,
            config.demodMode,
            config.notchMode,
            config.sidebandMode,
            config.advancedSyncEnabled,
            config.qualitySquelchEnabled,
            config.speechEnhancerMode,
            config.amSpeechFilterEnabled,
            config.tuning.simpleAmEnabled,
            config.tuning.dcAlpha,
            config.tuning.stage1FirTaps,
            config.tuning.stage2FirTaps,
            config.tuning.channelGuardHz,
            config.tuning.channelCutoffRatio,
            config.tuning.voiceAudioCutoffHz,
            config.tuning.naturalAudioCutoffHz,
            config.tuning.voiceAudioRatio,
            config.tuning.naturalAudioRatio,
            config.tuning.audioHighPassHz,
            config.tuning.squelchAttackAlpha,
            config.tuning.squelchReleaseAlpha,
            config.tuning.squelchHysteresisPercent,
            config.tuning.squelchOpenDebounceMs,
            config.tuning.squelchCloseHangMs,
            config.tuning.outputGain,
            config.tuning.targetAudioLevel,
            config.tuning.softClipThreshold,
            config.tuning.softClipDrive,
            config.tuning.postLevelerTarget,
            config.tuning.postLevelerMaxGain,
            config.tuning.postLevelerAttackAlpha,
            config.tuning.postLevelerReleaseAlpha,
            config.carrierTargets != null,
            config.carrierTargets?.primaryOffsetHz ?: 0f,
            config.carrierTargets?.alternateOffsetHz ?: 0f,
        )
    }

    fun resetAmPipeline(handle: Long): Boolean {
        return loadSucceeded && handle != 0L && nativeResetAmPipeline(handle)
    }

    fun startAmStream(handle: Long, iqReadSize: Int, pcmRingCapacity: Int): Boolean {
        return loadSucceeded && handle != 0L && nativeStartAmStream(handle, iqReadSize, pcmRingCapacity)
    }

    fun stopAmStream() {
        if (loadSucceeded) nativeStopAmStream()
    }

    fun clearAmStreamBuffer() {
        if (loadSucceeded) nativeClearAmStreamBuffer()
    }

    fun readAmStreamPcm(pcmOut: ShortArray, requestedSamples: Int, timeoutMs: Int): Int {
        if (!loadSucceeded) return 0
        return nativeReadAmStreamPcm(pcmOut, requestedSamples, timeoutMs)
    }

    fun amStreamMetrics(): NativeAmStreamMetrics {
        if (!loadSucceeded) {
            return NativeAmStreamMetrics(
                bufferedSamples = 0,
                totalProducedSamples = 0L,
                totalConsumedSamples = 0L,
                totalUsbBytes = 0L,
                totalUsbBlocks = 0L,
                totalUsbFailures = 0L,
                totalDroppedSamples = 0L,
                rfLevelPercent = 0,
                audioLevelPercent = 0,
                clipPercent = 0,
                iqClipPercent = 0,
                estimatedCarrierOffsetHz = 0,
                squelchPercent = 0,
                squelchNoiseFloorPercent = 0,
                squelchThresholdPercent = 0,
                squelchEffectiveThresholdPercent = 0,
                squelchOpen = true,
            )
        }
        val raw = nativeAmStreamMetrics()
        return NativeAmStreamMetrics(
            bufferedSamples = raw.getOrElse(0) { 0L }.toInt(),
            totalProducedSamples = raw.getOrElse(1) { 0L },
            totalConsumedSamples = raw.getOrElse(2) { 0L },
            totalUsbBytes = raw.getOrElse(3) { 0L },
            totalUsbBlocks = raw.getOrElse(4) { 0L },
            totalUsbFailures = raw.getOrElse(5) { 0L },
            totalDroppedSamples = raw.getOrElse(6) { 0L },
            rfLevelPercent = raw.getOrElse(7) { 0L }.toInt(),
            audioLevelPercent = raw.getOrElse(8) { 0L }.toInt(),
            clipPercent = raw.getOrElse(9) { 0L }.toInt(),
            estimatedCarrierOffsetHz = raw.getOrElse(10) { 0L }.toInt(),
            squelchPercent = raw.getOrElse(11) { 0L }.toInt(),
            squelchNoiseFloorPercent = raw.getOrElse(12) { 0L }.toInt(),
            squelchThresholdPercent = raw.getOrElse(13) { 0L }.toInt(),
            squelchEffectiveThresholdPercent = raw.getOrElse(14) { 0L }.toInt(),
            squelchOpen = raw.getOrElse(15) { 1L } != 0L,
            iqClipPercent = raw.getOrElse(16) { 0L }.toInt(),
            running = raw.getOrElse(17) { 0L } != 0L,
        )
    }

    fun amStreamSpectrum(): IntArray {
        if (!loadSucceeded) return IntArray(0)
        return nativeAmStreamSpectrum()
    }

    /** Bounded, incremental FFT history. Reading never consumes rows for another viewer. */
    fun spectrumHistory(afterSequence: Long = 0): ByteArray {
        if (!loadSucceeded) return byteArrayOf()
        return nativeSpectrumHistory(afterSequence.coerceAtLeast(0))
    }

    fun destroyAmPipeline(handle: Long) {
        if (loadSucceeded && handle != 0L) nativeDestroyAmPipeline(handle)
    }

    private external fun nativeIsReady(): Boolean
    private external fun nativeVersionLabel(): String
    private external fun nativeLastError(): String?
    private external fun nativeOpen(deviceFd: Int, vendorId: Int, productId: Int): Boolean
    private external fun nativeConfigureSession(
        centerFrequencyHz: Long,
        sampleRateHz: Int,
        gainMode: Int,
        tunerGain: Int,
        agcMode: Boolean,
        offsetTuning: Boolean,
        ppmCorrection: Int,
        biasTeeEnabled: Boolean,
        tunerBandwidthHz: Int,
    ): Boolean
    private external fun nativeApplySettings(
        ppmCorrection: Int,
        biasTeeEnabled: Boolean,
        tunerBandwidthHz: Int,
        gainMode: Int,
        tunerGain: Int,
        agcMode: Boolean,
    ): Boolean
    private external fun nativeClose()
    private external fun nativeTuneHz(
        frequencyHz: Long,
        carrierTargetsEnabled: Boolean,
        primaryCarrierOffsetHz: Float,
        alternateCarrierOffsetHz: Float,
    ): Boolean
    private external fun nativeCreateAmPipeline(
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
        channelBandwidthHz: Int,
        squelchMode: Int,
        squelchLevel: Int,
        hardSquelchMuteEnabled: Boolean,
        audioBoostPercent: Int,
        voiceFocusEnabled: Boolean,
        postLevelerEnabled: Boolean,
        noiseBlankerEnabled: Boolean,
        denoiseMode: Int,
        demodMode: Int,
        notchMode: Int,
        sidebandMode: Int,
        advancedSyncEnabled: Boolean,
        qualitySquelchEnabled: Boolean,
        speechEnhancerMode: Int,
        amSpeechFilterEnabled: Boolean,
        simpleAmEnabled: Boolean,
        dcAlpha: Float,
        stage1FirTaps: Int,
        stage2FirTaps: Int,
        channelGuardHz: Float,
        channelCutoffRatio: Float,
        voiceAudioCutoffHz: Float,
        naturalAudioCutoffHz: Float,
        voiceAudioRatio: Float,
        naturalAudioRatio: Float,
        audioHighPassHz: Float,
        squelchAttackAlpha: Float,
        squelchReleaseAlpha: Float,
        squelchHysteresisPercent: Float,
        squelchOpenDebounceMs: Int,
        squelchCloseHangMs: Int,
        outputGain: Float,
        targetAudioLevel: Float,
        softClipThreshold: Float,
        softClipDrive: Float,
        postLevelerTarget: Float,
        postLevelerMaxGain: Float,
        postLevelerAttackAlpha: Float,
        postLevelerReleaseAlpha: Float,
        carrierTargetsEnabled: Boolean,
        primaryCarrierOffsetHz: Float,
        alternateCarrierOffsetHz: Float,
    ): Long
    private external fun nativeUpdateAmPipeline(
        handle: Long,
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
        channelBandwidthHz: Int,
        squelchMode: Int,
        squelchLevel: Int,
        hardSquelchMuteEnabled: Boolean,
        audioBoostPercent: Int,
        voiceFocusEnabled: Boolean,
        postLevelerEnabled: Boolean,
        noiseBlankerEnabled: Boolean,
        denoiseMode: Int,
        demodMode: Int,
        notchMode: Int,
        sidebandMode: Int,
        advancedSyncEnabled: Boolean,
        qualitySquelchEnabled: Boolean,
        speechEnhancerMode: Int,
        amSpeechFilterEnabled: Boolean,
        simpleAmEnabled: Boolean,
        dcAlpha: Float,
        stage1FirTaps: Int,
        stage2FirTaps: Int,
        channelGuardHz: Float,
        channelCutoffRatio: Float,
        voiceAudioCutoffHz: Float,
        naturalAudioCutoffHz: Float,
        voiceAudioRatio: Float,
        naturalAudioRatio: Float,
        audioHighPassHz: Float,
        squelchAttackAlpha: Float,
        squelchReleaseAlpha: Float,
        squelchHysteresisPercent: Float,
        squelchOpenDebounceMs: Int,
        squelchCloseHangMs: Int,
        outputGain: Float,
        targetAudioLevel: Float,
        softClipThreshold: Float,
        softClipDrive: Float,
        postLevelerTarget: Float,
        postLevelerMaxGain: Float,
        postLevelerAttackAlpha: Float,
        postLevelerReleaseAlpha: Float,
        carrierTargetsEnabled: Boolean,
        primaryCarrierOffsetHz: Float,
        alternateCarrierOffsetHz: Float,
    ): Boolean
    private external fun nativeResetAmPipeline(handle: Long): Boolean
    private external fun nativeStartAmStream(
        handle: Long,
        iqReadSize: Int,
        pcmRingCapacity: Int,
    ): Boolean
    private external fun nativeStopAmStream()
    private external fun nativeClearAmStreamBuffer()
    private external fun nativeReadAmStreamPcm(
        pcmOut: ShortArray,
        requestedSamples: Int,
        timeoutMs: Int,
    ): Int
    private external fun nativeAmStreamMetrics(): LongArray
    private external fun nativeAmStreamSpectrum(): IntArray
    private external fun nativeSpectrumHistory(afterSequence: Long): ByteArray
    private external fun nativeDestroyAmPipeline(handle: Long)
}
