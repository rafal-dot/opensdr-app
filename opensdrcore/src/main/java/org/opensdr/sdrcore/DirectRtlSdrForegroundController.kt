package org.opensdr.sdrcore

interface DirectRtlSdrForegroundController {
    fun start(context: android.content.Context, frequencyText: String?)

    fun stop(context: android.content.Context)
}

object NoOpDirectRtlSdrForegroundController : DirectRtlSdrForegroundController {
    override fun start(context: android.content.Context, frequencyText: String?) = Unit

    override fun stop(context: android.content.Context) = Unit
}
