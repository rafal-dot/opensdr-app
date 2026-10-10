package org.opensdr.amhost

import org.junit.Assert.*
import org.junit.Test

class HostStatusPollGateTest {
    @Test fun slowRepliesCoalesceRefreshAndPeriodicPolls() {
        val gate = HostStatusPollGate()
        val first = gate.begin()!!
        repeat(1000) { assertNull(gate.begin()) }
        assertFalse(gate.complete(0)) // A Tune/Stop response is not a poll acknowledgement.
        assertNull(gate.begin())
        assertTrue(gate.complete(first))
        assertNotNull(gate.begin())
    }

    @Test fun staleReplyAfterReconnectCannotReleaseNewRequest() {
        val gate = HostStatusPollGate()
        val old = gate.begin()!!
        gate.reset()
        val current = gate.begin()!!
        assertFalse(gate.complete(old))
        assertNull(gate.begin())
        assertTrue(gate.complete(current))
    }

    @Test fun sendFailureCanReleasePendingRequest() {
        val gate = HostStatusPollGate()
        assertTrue(gate.complete(gate.begin()!!))
        assertNotNull(gate.begin())
    }
}
