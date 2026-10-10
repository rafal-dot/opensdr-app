package org.opensdr.amhost

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.opensdr.radioapi.AmHostStatus
import org.opensdr.radioapi.HostReceiverSettings
import org.opensdr.radioapi.ReceiverSettingRules

/** An explicit draft, never a preference replay or an effect triggered by opening the host. */
internal class HostExpertDraft(val baseline: HostReceiverSettings, val profileId: String? = null) {
    var settings by mutableStateOf(baseline)
    fun canApply(current: HostReceiverSettings): Boolean = current == baseline && settings != baseline
}

@Composable
internal fun ExpertSettingsCard(
    status: AmHostStatus,
    enabled: Boolean,
    onSelectProfile: (String) -> Unit,
    onApplySettings: (HostReceiverSettings, HostReceiverSettings, String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<HostExpertDraft?>(null) }
    var selectedProfile by remember(status.selectedProfileId) { mutableStateOf(status.selectedProfileId.orEmpty()) }
    val current = status.toReceiverSettings()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Expert receiver settings", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = {
                    expanded = !expanded
                    draft = null
                }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) {
                Text("Viewing does not change playback. Applying a profile or edits updates the active receiver for all connected apps.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Other connected apps may also update receiver settings.",
                    style = MaterialTheme.typography.bodySmall)
                HostExpertChoice(
                    "Demodulation profile", selectedProfile,
                    status.availableAmProfiles.map { it.id to it.label },
                    enabled && draft == null,
                ) { selectedProfile = it }
                TextButton(
                    enabled = enabled && draft == null && status.availableAmProfiles.any { it.id == selectedProfile },
                    onClick = { onSelectProfile(selectedProfile) },
                ) { Text("Apply profile defaults") }
                status.availableAmProfiles.firstOrNull { it.id == status.selectedProfileId }
                    ?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                status.receiverSettingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                val editing = draft
                if (editing == null) {
                    Text("BW ${current.amBandwidthHz} Hz | audio ${current.audioBoostPercent}% | " +
                        "PPM ${current.ppmCorrection} | offset ${current.frequencyCorrectionHz} Hz")
                    TextButton(enabled = enabled, onClick = { draft = HostExpertDraft(current, status.selectedProfileId) }) {
                        Text("Edit active settings")
                    }
                } else {
                    val conflict = expertSettingsConflict(status, editing.baseline, editing.profileId)
                    if (conflict) {
                        Text("Receiver settings changed elsewhere. Reload before applying edits.",
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { draft = HostExpertDraft(current, status.selectedProfileId) }) { Text("Reload active settings") }
                    }
                    ExpertReceiverFields(editing.settings) { editing.settings = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = enabled && !conflict && editing.canApply(current), onClick = {
                            onApplySettings(editing.settings, editing.baseline, editing.profileId)
                            draft = null
                        }) { Text("Apply edits") }
                        TextButton(onClick = { draft = null }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpertReceiverFields(s: HostReceiverSettings, change: (HostReceiverSettings) -> Unit) {
    ExpertTunerFields(s, change)
    HorizontalDivider()
    ExpertAudioFields(s, change)
    HorizontalDivider()
    ExpertSquelchFields(s, change)
}

@Composable
private fun ExpertTunerFields(s: HostReceiverSettings, change: (HostReceiverSettings) -> Unit) {
    Text("Receiver", style = MaterialTheme.typography.titleSmall)
    HostExpertInteger("Frequency correction [Hz]", s.frequencyCorrectionHz,
        ReceiverSettingRules.MIN_CORRECTION_HZ..ReceiverSettingRules.MAX_CORRECTION_HZ) {
        change(s.copy(frequencyCorrectionHz = it))
    }
    HostExpertInteger("PPM correction", s.ppmCorrection, ReceiverSettingRules.MIN_PPM..ReceiverSettingRules.MAX_PPM) {
        change(s.copy(ppmCorrection = it))
    }
    HostExpertToggle("Bias-T (powered antenna only)", s.biasTeeEnabled) { change(s.copy(biasTeeEnabled = it)) }
    HostExpertChoice("Gain", s.gainMode.toString(), listOf("0" to "Auto", "1" to "Manual")) {
        change(s.copy(gainMode = it.toInt()))
    }
    if (s.gainMode == 1) {
        HostExpertInteger("Tuner gain [tenths of dB, rounded to supported gain]", s.tunerGain, 0..496) {
            change(s.copy(tunerGain = it))
        }
    }
    HostExpertChoice("IQ sample rate", s.preferredSampleRateHz.toString(),
        listOf("240000" to "240 ksps (Low CPU)", "480000" to "480 ksps", "960000" to "960 ksps")) {
        change(s.copy(preferredSampleRateHz = it.toInt()))
    }
    Text("Sample-rate changes take effect on the next connection.", style = MaterialTheme.typography.bodySmall)
    AmBandwidthControl(s.amBandwidthHz.toString(), { change(s.copy(amBandwidthHz = it.toInt())) })
    Text("Bandwidth above is for AM. FM and SSB use mode-specific RF bandwidths.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ExpertAudioFields(s: HostReceiverSettings, change: (HostReceiverSettings) -> Unit) {
    Text("Audio / DSP", style = MaterialTheme.typography.titleSmall)
    ModulationSelector(modulationOptionFor(s.demodMode, s.sidebandMode), true) {
        change(s.copy(demodMode = it.demodMode, sidebandMode = it.sidebandMode))
    }
    HostExpertChoice("AM sideband", s.sidebandMode.toString(),
        listOf("0" to "Full AM", "1" to "Lower", "2" to "Upper"), s.demodMode <= 2) {
        change(s.copy(sidebandMode = it.toInt()))
    }
    HostExpertInteger("Audio boost [%]", s.audioBoostPercent, 100..320) { change(s.copy(audioBoostPercent = it)) }
    HostExpertToggle("Voice focus", s.voiceFocusEnabled) { change(s.copy(voiceFocusEnabled = it)) }
    HostExpertToggle("AM speech filter (250-2500 Hz)", s.amSpeechFilterEnabled ?: true) {
        change(s.copy(amSpeechFilterEnabled = it))
    }
    Text("AM only. Independent of the audio profile; FM and SSB are unchanged.", style = MaterialTheme.typography.bodySmall)
    HostExpertToggle("Post leveler", s.postLevelerEnabled) { change(s.copy(postLevelerEnabled = it)) }
    Text("Optional DSP can increase CPU usage and change speech quality.", style = MaterialTheme.typography.bodySmall)
    HostExpertToggle("Noise blanker", s.noiseBlankerEnabled) { change(s.copy(noiseBlankerEnabled = it)) }
    HostExpertChoice("Denoise", s.denoiseMode.toString(),
        listOf("0" to "Off", "1" to "Mild", "2" to "Strong", "3" to "Auto")) {
        change(s.copy(denoiseMode = it.toInt()))
    }
    HostExpertChoice("Notch", s.notchMode.toString(), listOf("0" to "Off", "1" to "Auto")) {
        change(s.copy(notchMode = it.toInt()))
    }
    HostExpertChoice("Speech enhancer", s.speechEnhancerMode.toString(),
        listOf("0" to "Off", "1" to "Mild", "2" to "Strong")) {
        change(s.copy(speechEnhancerMode = it.toInt()))
    }
    HostExpertToggle("Advanced sync (AM Sync only)", s.advancedSyncEnabled, s.demodMode == 2) {
        change(s.copy(advancedSyncEnabled = it))
    }
}

@Composable
private fun ExpertSquelchFields(s: HostReceiverSettings, change: (HostReceiverSettings) -> Unit) {
    Text("Squelch timing", style = MaterialTheme.typography.titleSmall)
    HostExpertInteger("Open delay [ms]", s.squelchOpenDebounceMs,
        ReceiverSettingRules.openDelayRange(s.squelchMode, s.squelchLevel)) { change(s.copy(squelchOpenDebounceMs = it)) }
    HostExpertInteger("Close hang [ms]", s.squelchCloseHangMs,
        ReceiverSettingRules.closeHangRange(s.squelchMode, s.squelchLevel)) { change(s.copy(squelchCloseHangMs = it)) }
    HostExpertToggle("Hard mute (Auto only)", s.hardSquelchMuteEnabled, s.squelchMode == 0) {
        change(s.copy(hardSquelchMuteEnabled = it))
    }
    HostExpertToggle("Quality squelch (Auto only)", s.qualitySquelchEnabled, s.squelchMode == 0) {
        change(s.copy(qualitySquelchEnabled = it))
    }
    Text("Manual/custom always mute below their threshold. Native safety limits remain active.",
        style = MaterialTheme.typography.bodySmall)
}
