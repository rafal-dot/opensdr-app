package org.opensdr.amhost

import android.content.SharedPreferences
import org.opensdr.sdrcore.DEMOD_MODE_AM_ENVELOPE
import kotlin.math.roundToInt

private const val PREVIOUS_WATERFALL_CONTRAST_DB_KEY = "waterfall_contrast_db"

internal fun SharedPreferences.loadHostFrequencyText(): String {
    val frequencyHz = getLong(PREF_FREQUENCY_HZ, DEFAULT_FREQUENCY_HZ)
        .coerceAtLeast(1L)
    return formatFrequencyHzAsMhzText(frequencyHz)
}

internal fun SharedPreferences.persistHostFrequencyTextIfValid(frequencyText: String) {
    val frequencyHz = parseFrequencyMhzToHz(frequencyText)
    if (frequencyHz <= 0L) return
    persistHostFrequencyHz(frequencyHz)
}

internal fun SharedPreferences.persistHostFrequencyHz(frequencyHz: Long) {
    if (frequencyHz <= 0L) return
    edit()
        .putLong(PREF_FREQUENCY_HZ, frequencyHz)
        .apply()
}

internal fun SharedPreferences.loadHostFrequencyHistory(): List<FrequencyHistoryEntry> {
    return getString(PREF_FREQUENCY_HISTORY_HZ, "")
        .orEmpty()
        .lineSequence()
        .mapNotNull(::parseFrequencyHistoryEntry)
        .distinctBy { it.frequencyHz }
        .take(MAX_FREQUENCY_HISTORY_SIZE)
        .toList()
}

internal fun SharedPreferences.recordHostFrequencyHistory(
    frequencyHz: Long,
    modulation: ModulationOption,
    squelchLevel: Int,
): List<FrequencyHistoryEntry> {
    if (frequencyHz <= 0L) return loadHostFrequencyHistory()
    val entry = FrequencyHistoryEntry(
        frequencyHz = frequencyHz,
        modulation = modulation,
        squelchLevel = squelchLevel.coerceIn(0, 100),
    )
    val updatedHistory = (listOf(entry) + loadHostFrequencyHistory())
        .distinctBy { it.frequencyHz }
        .take(MAX_FREQUENCY_HISTORY_SIZE)
    edit()
        .putString(PREF_FREQUENCY_HISTORY_HZ, updatedHistory.joinToString(separator = "\n", transform = ::formatFrequencyHistoryEntry))
        .apply()
    return updatedHistory
}

private fun parseFrequencyHistoryEntry(rawLine: String): FrequencyHistoryEntry? {
    val parts = rawLine.split('|')
    val frequencyHz = parts.firstOrNull()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val demodMode = parts.getOrNull(1)?.toIntOrNull() ?: DEMOD_MODE_AM_ENVELOPE
    val sidebandMode = parts.getOrNull(2)?.toIntOrNull() ?: 0
    val squelchLevel = parts.getOrNull(3)?.toIntOrNull() ?: DEFAULT_MANUAL_SQUELCH_LEVEL
    return FrequencyHistoryEntry(
        frequencyHz = frequencyHz,
        modulation = modulationOptionFor(demodMode, sidebandMode),
        squelchLevel = squelchLevel.coerceIn(0, 100),
    )
}

private fun formatFrequencyHistoryEntry(entry: FrequencyHistoryEntry): String {
    return "${entry.frequencyHz}|${entry.modulation.demodMode}|${entry.modulation.sidebandMode}|${entry.squelchLevel}"
}

internal fun SharedPreferences.loadHostTuningStep(): TuningStep {
    val persistedStepHz = getLong(PREF_TUNING_STEP_HZ, DEFAULT_TUNING_STEP_HZ)
    return tuningSteps.firstOrNull { it.stepHz == persistedStepHz } ?: defaultTuningStep
}

internal fun SharedPreferences.persistHostTuningStep(step: TuningStep) {
    edit()
        .putLong(PREF_TUNING_STEP_HZ, step.stepHz)
        .apply()
}

internal fun SharedPreferences.loadHostModulationOption(): ModulationOption {
    val demodMode = getInt(PREF_MODULATION_DEMOD_MODE, DEMOD_MODE_AM_ENVELOPE)
    val sidebandMode = getInt(PREF_MODULATION_SIDEBAND_MODE, 0)
    return modulationOptionFor(demodMode, sidebandMode)
}

internal fun SharedPreferences.loadHostModulationOptionOrNull(): ModulationOption? {
    if (!contains(PREF_MODULATION_DEMOD_MODE) || !contains(PREF_MODULATION_SIDEBAND_MODE)) {
        return null
    }
    return loadHostModulationOption()
}

internal fun SharedPreferences.persistHostModulationOption(option: ModulationOption) {
    edit()
        .putInt(PREF_MODULATION_DEMOD_MODE, option.demodMode)
        .putInt(PREF_MODULATION_SIDEBAND_MODE, option.sidebandMode)
        .apply()
}

internal fun SharedPreferences.loadHostManualSquelchLevel(): Float {
    return getInt(PREF_MANUAL_SQUELCH_LEVEL, DEFAULT_MANUAL_SQUELCH_LEVEL)
        .coerceIn(0, 100)
        .toFloat()
}

internal fun SharedPreferences.persistHostManualSquelchLevel(squelchLevel: Float) {
    edit()
        .putInt(PREF_MANUAL_SQUELCH_LEVEL, squelchLevel.roundToInt().coerceIn(0, 100))
        .apply()
}

internal fun SharedPreferences.loadHostWaterfallDisplaySettings(): WaterfallDisplaySettings {
    val floorDbfs = getFloat(PREF_WATERFALL_FLOOR_DBFS, DEFAULT_WATERFALL_FLOOR_DBFS)
    if (!contains(PREF_WATERFALL_CONTRAST_PERCENT) && contains(PREVIOUS_WATERFALL_CONTRAST_DB_KEY)) {
        return WaterfallDisplaySettings.fromRangeDb(
            floorDbfs = floorDbfs,
            rangeDb = getFloat(PREVIOUS_WATERFALL_CONTRAST_DB_KEY, 60f),
        )
    }
    return WaterfallDisplaySettings.normalized(
        floorDbfs = floorDbfs,
        contrastPercent = getFloat(
            PREF_WATERFALL_CONTRAST_PERCENT,
            DEFAULT_WATERFALL_CONTRAST_PERCENT,
        ),
    )
}

internal fun SharedPreferences.persistHostWaterfallDisplaySettings(settings: WaterfallDisplaySettings) {
    val normalized = WaterfallDisplaySettings.normalized(
        floorDbfs = settings.floorDbfs,
        contrastPercent = settings.contrastPercent,
    )
    edit()
        .putFloat(PREF_WATERFALL_FLOOR_DBFS, normalized.floorDbfs)
        .putFloat(PREF_WATERFALL_CONTRAST_PERCENT, normalized.contrastPercent)
        .remove(PREVIOUS_WATERFALL_CONTRAST_DB_KEY)
        .apply()
}
