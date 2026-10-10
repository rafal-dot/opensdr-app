package org.opensdr.amhost

import org.opensdr.radioapi.AmHostStatus
import org.opensdr.radioapi.HostReceiverSettings

/** Check on the serialized service thread as well as the UI, covering queued client writes. */
internal fun expertSettingsConflict(
    current: AmHostStatus,
    expected: HostReceiverSettings?,
    expectedProfileId: String?,
): Boolean = expected != null &&
    (current.toReceiverSettings() != expected.copy(
        amSpeechFilterEnabled = expected.amSpeechFilterEnabled ?: current.amSpeechFilterEnabled,
    ) || current.selectedProfileId != expectedProfileId)
