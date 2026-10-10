package org.opensdr.sdrcore

import org.junit.Assert.*
import org.junit.Test

class ReceiverConfigurationApplicationTest {
    @Test fun partialFailureForcesFullRetryOfTheSameSettings() {
        val application = ReceiverConfigurationApplication<String>()
        application.confirm("old")
        assertEquals("USB error", application.apply("new") { previous ->
            assertEquals("old", previous)
            "USB error"
        })
        assertFalse(application.isApplied("new"))
        assertNull(application.apply("new") { previous ->
            assertNull(previous)
            null
        })
        assertTrue(application.isApplied("new"))
    }

    @Test fun reconnectAndExceptionsInvalidateConfirmedState() {
        val application = ReceiverConfigurationApplication<String>()
        application.confirm("old")
        runCatching { application.apply("new") { error("Native failure") } }
        assertFalse(application.isApplied("old"))
        assertNotNull(application.error)
        application.invalidate()
        assertNull(application.error)
        assertNull(application.apply("new") { previous ->
            assertNull(previous)
            null
        })
    }
}
