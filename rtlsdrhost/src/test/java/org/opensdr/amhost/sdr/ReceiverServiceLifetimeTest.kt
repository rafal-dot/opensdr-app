package org.opensdr.amhost.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceiverServiceLifetimeTest {
    private val events = mutableListOf<String>()
    private val lifetime = ReceiverServiceLifetime(
        startService = { events += "start owner" },
        showForeground = { events += "show $it" },
        releaseForeground = { events += "release" },
        stopService = { events += "stop owner $it" },
    )

    @Test
    fun playbackStartsItsOwnServiceBeforeShowingNotification() {
        lifetime.start("128.805")
        lifetime.onStartCommand(1)

        assertEquals(listOf("start owner", "show 128.805"), events)
    }

    @Test
    fun notificationUpdateDoesNotRestartService() {
        lifetime.start("128.805")
        lifetime.start("118.305")

        assertEquals(listOf("start owner", "show 128.805", "show 118.305"), events)
    }

    @Test
    fun stopReleasesForegroundAndStartedLifetime() {
        lifetime.start("128.805")
        lifetime.stop()

        assertEquals(listOf("start owner", "show 128.805", "release", "stop owner null"), events)
    }

    @Test
    fun delayedStartCommandDoesNotRestartStoppedAudio() {
        lifetime.start("128.805")
        lifetime.stop()
        events.clear()
        lifetime.onStartCommand(1)

        assertEquals(listOf("stop owner 1"), events)
    }

    @Test
    fun oldStartCommandCannotStopNewPlayback() {
        lifetime.start("128.805")
        lifetime.stop()
        lifetime.start("118.305")
        events.clear()
        lifetime.onStartCommand(1)
        lifetime.onStartCommand(2)

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun startWithoutPlaybackDoesNotLeaveIdleServiceOrWakeLock() {
        lifetime.onStartCommand(5)

        assertEquals(listOf("stop owner 5"), events)
    }

    @Test
    fun foregroundFailureRollsBackStartedLifetime() {
        val failingLifetime = ReceiverServiceLifetime(
            startService = { events += "start owner" },
            showForeground = { throw IllegalStateException("foreground rejected") },
            releaseForeground = { events += "release" },
            stopService = { events += "stop owner $it" },
        )

        assertThrows(IllegalStateException::class.java) { failingLifetime.start("128.805") }
        assertEquals(listOf("start owner", "release", "stop owner null"), events)
    }

    @Test
    fun destroyedServiceRejectsQueuedPlaybackStart() {
        lifetime.start("128.805")
        events.clear()
        lifetime.destroy()

        assertThrows(IllegalStateException::class.java) { lifetime.start("118.305") }
        assertEquals(listOf("release"), events)
    }
}
