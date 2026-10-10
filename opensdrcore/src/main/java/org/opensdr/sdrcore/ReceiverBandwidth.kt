package org.opensdr.sdrcore

/** WFM uses a separate RF path; switching back must retain the user's AM bandwidth. */
fun receiverRfBandwidthHz(demodMode: Int, amBandwidthHz: Int): Int =
    if (demodMode == DEMOD_MODE_WFM) 200_000 else amBandwidthHz
