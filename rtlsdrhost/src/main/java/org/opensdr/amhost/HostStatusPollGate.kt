package org.opensdr.amhost

/** Main-thread gate shared by periodic polling and the Refresh button. */
internal class HostStatusPollGate {
    private var nextId = 1
    private var pendingId: Int? = null

    fun begin(): Int? {
        if (pendingId != null) return null
        val id = nextId
        nextId = if (id == Int.MAX_VALUE) 1 else id + 1
        pendingId = id
        return id
    }

    fun complete(id: Int): Boolean {
        if (pendingId != id) return false
        pendingId = null
        return true
    }

    fun reset() {
        pendingId = null
    }
}
