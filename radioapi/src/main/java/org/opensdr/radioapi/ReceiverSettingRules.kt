// SPDX-FileCopyrightText: 2026 Rafal Czeczotka
// SPDX-License-Identifier: MIT

package org.opensdr.radioapi

/** Public control limits. Native DSP also validates callers that bypass the IPC API. */
object ReceiverSettingRules {
    const val MIN_PPM = -200
    const val MAX_PPM = 200
    const val MIN_CORRECTION_HZ = -250_000
    const val MAX_CORRECTION_HZ = 250_000

    fun openDelayRange(squelchMode: Int, level: Int): IntRange = when {
        level <= 0 -> 0..500
        squelchMode == 0 -> 0..60
        else -> 100..500
    }

    fun closeHangRange(squelchMode: Int, level: Int): IntRange =
        if (squelchMode == 0 && level > 0) 500..1000 else 0..1000
}
