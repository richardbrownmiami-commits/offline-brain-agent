package com.cue.offlinebrain.notifications

/** Thread-safe in-process gate for notification-listener repair attempts. */
class RepairCooldown(private val cooldownMillis: Long = 60_000L) {
    private var lastAllowedAt: Long? = null

    @Synchronized
    fun tryAcquire(nowMillis: Long): Boolean {
        val previous = lastAllowedAt
        if (previous != null && nowMillis - previous < cooldownMillis) return false
        lastAllowedAt = nowMillis
        return true
    }
}
