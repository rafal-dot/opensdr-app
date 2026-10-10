package org.opensdr.amhost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_AUTO
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_MANUAL
import org.opensdr.sdrcore.RTL_SQUELCH_MODE_CUSTOM
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrequencyControls(
    frequencyText: String,
    frequencyHistory: List<FrequencyHistoryEntry>,
    stepEnabled: Boolean,
    onFrequencyTextChange: (String) -> Unit,
    onHistorySelected: (FrequencyHistoryEntry) -> Unit,
    onStepDown: () -> Unit,
    onStepUp: () -> Unit,
) {
    var frequencyHistoryExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onStepDown,
            enabled = stepEnabled,
        ) {
            Text("-")
        }
        Box(
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = frequencyText,
                onValueChange = onFrequencyTextChange,
                label = { Text("Frequency [MHz]") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    if (frequencyHistory.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                frequencyHistoryExpanded = !frequencyHistoryExpanded
                            },
                        ) {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = frequencyHistoryExpanded)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = frequencyHistoryExpanded,
                onDismissRequest = { frequencyHistoryExpanded = false },
                modifier = Modifier.heightIn(max = 280.dp),
            ) {
                frequencyHistory.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${formatFrequencyHzAsMhzText(entry.frequencyHz)} | ${entry.modulation.label} | SQ ${entry.squelchLevel}%",
                            )
                        },
                        onClick = {
                            frequencyHistoryExpanded = false
                            onHistorySelected(entry)
                        },
                    )
                }
            }
        }
        Button(
            onClick = onStepUp,
            enabled = stepEnabled,
        ) {
            Text("+")
        }
    }
}

@Composable
internal fun ReceiverActionRow(
    powerButtonLabel: String,
    powerButtonEnabled: Boolean,
    tuneEnabled: Boolean,
    onPowerClick: () -> Unit,
    onTuneClick: () -> Unit,
    onRefreshClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onPowerClick,
            enabled = powerButtonEnabled,
            modifier = Modifier.weight(1.3f),
        ) {
            Text(powerButtonLabel)
        }
        Button(
            onClick = onTuneClick,
            enabled = tuneEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text("Tune")
        }
        Button(
            onClick = onRefreshClick,
            modifier = Modifier.weight(1f),
        ) {
            Text("Refresh")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModulationSelector(
    selected: ModulationOption,
    enabled: Boolean,
    onSelected: (ModulationOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Modulation") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
            enabled = enabled,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modulationOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TuningStepSelector(
    selectedStep: TuningStep,
    enabled: Boolean,
    onStepSelected: (TuningStep) -> Unit,
) {
    var stepExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = stepExpanded,
        onExpandedChange = { if (enabled) stepExpanded = !stepExpanded },
    ) {
        OutlinedTextField(
            value = selectedStep.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Step") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stepExpanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
            enabled = enabled,
        )
        DropdownMenu(
            expanded = stepExpanded,
            onDismissRequest = { stepExpanded = false },
        ) {
            tuningSteps.forEach { step ->
                DropdownMenuItem(
                    text = { Text(step.label) },
                    onClick = {
                        stepExpanded = false
                        onStepSelected(step)
                    },
                )
            }
        }
    }
}

@Composable
internal fun CompactManualSquelchControl(
    squelchLevel: Float,
    currentMode: Int,
    currentThresholdPercent: Int,
    enabled: Boolean,
    onSquelchLevelChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "SQ ${squelchLevel.roundToInt()}% | active ${currentThresholdPercent}% | ${formatHostSquelchMode(currentMode, squelchLevel.roundToInt())}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onSquelchLevelChange((squelchLevel - 1f).coerceAtLeast(0f)) },
                enabled = enabled,
            ) {
                Text("-")
            }
            Slider(
                value = squelchLevel,
                onValueChange = onSquelchLevelChange,
                valueRange = 0f..100f,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onSquelchLevelChange((squelchLevel + 1f).coerceAtMost(100f)) },
                enabled = enabled,
            ) {
                Text("+")
            }
        }
    }
}

@Composable
internal fun RadioSettingsCard(
    selectedModulation: ModulationOption,
    selectedStep: TuningStep,
    stepEnabled: Boolean,
    settingsEnabled: Boolean,
    waterfallDisplaySettings: WaterfallDisplaySettings,
    onModulationSelected: (ModulationOption) -> Unit,
    onStepSelected: (TuningStep) -> Unit,
    onWaterfallDisplaySettingsChange: (WaterfallDisplaySettings) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Radio settings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${selectedModulation.label} | step ${selectedStep.label} | " +
                            "spectrum + waterfall ${waterfallDisplaySettings.floorDbfs.roundToInt()} dBFS / " +
                            "${waterfallDisplaySettings.contrastPercent.roundToInt()}% contrast",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        ModulationSelector(
                            selected = selectedModulation,
                            enabled = settingsEnabled,
                            onSelected = onModulationSelected,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        TuningStepSelector(
                            selectedStep = selectedStep,
                            enabled = stepEnabled,
                            onStepSelected = onStepSelected,
                        )
                    }
                }
                WaterfallContrastControl(
                    settings = waterfallDisplaySettings,
                    onSettingsChange = onWaterfallDisplaySettingsChange,
                )
            }
        }
    }
}

@Composable
internal fun WaterfallContrastControl(
    settings: WaterfallDisplaySettings,
    onSettingsChange: (WaterfallDisplaySettings) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Spectrum + waterfall floor sensitivity " +
                "${settings.floorSensitivityPercent.roundToInt()}% " +
                "(${settings.floorDbfs.roundToInt()} dBFS)",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = settings.floorSensitivityPercent,
            onValueChange = { onSettingsChange(settings.withFloorSensitivity(it)) },
            valueRange = MIN_WATERFALL_FLOOR_SENSITIVITY_PERCENT..
                MAX_WATERFALL_FLOOR_SENSITIVITY_PERCENT,
        )
        Text(
            "Spectrum + waterfall contrast ${settings.contrastPercent.roundToInt()}% " +
                "(range ${settings.rangeDb.roundToInt()} dB)",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = settings.contrastPercent,
            onValueChange = { onSettingsChange(settings.withContrast(it)) },
            valueRange = MIN_WATERFALL_CONTRAST_PERCENT..MAX_WATERFALL_CONTRAST_PERCENT,
        )
    }
}

internal fun formatHostSquelchMode(mode: Int, level: Int): String = when {
    level <= 0 -> "off"
    mode == RTL_SQUELCH_MODE_MANUAL -> "manual"
    mode == RTL_SQUELCH_MODE_CUSTOM -> "custom"
    mode == RTL_SQUELCH_MODE_AUTO -> "auto"
    else -> mode.toString()
}
