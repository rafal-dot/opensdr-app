package org.opensdr.amhost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private const val AM_BANDWIDTH_UI_MIN_HZ = 7_000
private const val AM_BANDWIDTH_UI_MAX_HZ = 20_000
private const val AM_BANDWIDTH_UI_STEP_HZ = 100

internal fun normalizeAmBandwidthHzForUi(valueHz: Int): Int {
    val clampedValueHz = valueHz.coerceIn(AM_BANDWIDTH_UI_MIN_HZ, AM_BANDWIDTH_UI_MAX_HZ)
    val offsetHz = clampedValueHz - AM_BANDWIDTH_UI_MIN_HZ
    val roundedOffsetHz = ((offsetHz + (AM_BANDWIDTH_UI_STEP_HZ / 2)) / AM_BANDWIDTH_UI_STEP_HZ) * AM_BANDWIDTH_UI_STEP_HZ
    return (AM_BANDWIDTH_UI_MIN_HZ + roundedOffsetHz).coerceIn(AM_BANDWIDTH_UI_MIN_HZ, AM_BANDWIDTH_UI_MAX_HZ)
}

internal fun parseAmBandwidthHzForUi(value: String): Int =
    normalizeAmBandwidthHzForUi(value.toIntOrNull() ?: 12_000)

internal fun adjustAmBandwidthHzForUi(
    currentValue: String,
    deltaSteps: Int,
): String =
    normalizeAmBandwidthHzForUi(
        parseAmBandwidthHzForUi(currentValue) + (deltaSteps * AM_BANDWIDTH_UI_STEP_HZ),
    ).toString()

private fun formatAmBandwidthUiValue(valueHz: Int): String =
    String.format(Locale.US, "%.1f kHz", valueHz / 1_000.0)

@Composable
internal fun AmBandwidthControl(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bandwidthHz = parseAmBandwidthHzForUi(value)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "AM bandwidth",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatAmBandwidthUiValue(value.toIntOrNull() ?: bandwidthHz),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onValueChange(adjustAmBandwidthHzForUi(value, -1)) },
                enabled = bandwidthHz > AM_BANDWIDTH_UI_MIN_HZ,
                modifier = Modifier.width(52.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text("-")
            }
            Slider(
                value = bandwidthHz.toFloat(),
                onValueChange = { sliderValue ->
                    onValueChange(
                        normalizeAmBandwidthHzForUi(sliderValue.roundToInt()).toString(),
                    )
                },
                valueRange = AM_BANDWIDTH_UI_MIN_HZ.toFloat()..AM_BANDWIDTH_UI_MAX_HZ.toFloat(),
                steps = ((AM_BANDWIDTH_UI_MAX_HZ - AM_BANDWIDTH_UI_MIN_HZ) / AM_BANDWIDTH_UI_STEP_HZ) - 1,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { onValueChange(adjustAmBandwidthHzForUi(value, 1)) },
                enabled = bandwidthHz < AM_BANDWIDTH_UI_MAX_HZ,
                modifier = Modifier.width(52.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text("+")
            }
        }
        Text(
            text = "Range ${formatAmBandwidthUiValue(AM_BANDWIDTH_UI_MIN_HZ)} - ${formatAmBandwidthUiValue(AM_BANDWIDTH_UI_MAX_HZ)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


