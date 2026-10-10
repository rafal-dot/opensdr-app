// SPDX-FileCopyrightText: 2026 Rafal Czeczotka
// SPDX-License-Identifier: MIT

package org.opensdr.radioapi

import android.os.Build
import android.os.Bundle
import android.os.Parcelable

// Do not append fields to the positional Parcel wire format: installed clients
// and hosts can have different versions. Missing keys mean unsupported/no edit.
private fun speechFilterKey(payloadKey: String) = "$payloadKey.am_speech_filter_enabled"

fun Bundle.putHostReceiverSettings(key: String, settings: HostReceiverSettings) {
    putParcelable(key, settings)
    remove(speechFilterKey(key))
    settings.amSpeechFilterEnabled?.let { putBoolean(speechFilterKey(key), it) }
}

fun Bundle.readHostReceiverSettings(key: String): HostReceiverSettings? =
    readAudioParcelable<HostReceiverSettings>(key)?.copy(amSpeechFilterEnabled = readSpeechFilter(key))

fun Bundle.putHostStatus(status: AmHostStatus) {
    val key = RtlSdrIpcContract.KEY_STATUS
    putParcelable(key, status)
    remove(speechFilterKey(key))
    status.amSpeechFilterEnabled?.let { putBoolean(speechFilterKey(key), it) }
}

fun Bundle.readHostStatus(): AmHostStatus? {
    val key = RtlSdrIpcContract.KEY_STATUS
    return readAudioParcelable<AmHostStatus>(key)?.copy(amSpeechFilterEnabled = readSpeechFilter(key))
}

private fun Bundle.readSpeechFilter(key: String): Boolean? =
    speechFilterKey(key).let { if (containsKey(it)) getBoolean(it) else null }

private inline fun <reified T : Parcelable> Bundle.readAudioParcelable(key: String): T? {
    classLoader = T::class.java.classLoader
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
}
